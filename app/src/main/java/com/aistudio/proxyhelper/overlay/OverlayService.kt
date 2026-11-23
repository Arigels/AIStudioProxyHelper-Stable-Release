package com.aistudio.proxyhelper.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.aistudio.proxyhelper.MainActivity
import com.aistudio.proxyhelper.agent.AgentConfig
import com.aistudio.proxyhelper.agent.AgentEngine
import com.aistudio.proxyhelper.agent.AgentMode
import com.aistudio.proxyhelper.automation.AutomationController
import com.aistudio.proxyhelper.data.SettingsRepository
import com.aistudio.proxyhelper.net.LogCollector
import com.aistudio.proxyhelper.termux.TermuxBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** 悬浮窗展示的快照，由 App 主界面轮询时写入。 */
data class OverlaySnapshot(
    val online: Boolean = false,
    val accountsReady: Boolean = false,
    val port: Int = 8080,
    val stage: String = "准备中",
    val error: String? = null,
    val guidance: String = "打开 App 开始搭建",
    val aiHint: String? = null,
    val deploying: Boolean = false,
    val hasDeployed: Boolean = false,
    val termuxInstalled: Boolean = false,
    val a11yEnabled: Boolean = false
)

object OverlayState {
    @Volatile
    var deployStartedAt: Long = 0L

    @Volatile
    var snapshot: OverlaySnapshot = OverlaySnapshot()

    fun update(snapshot: OverlaySnapshot) {
        this.snapshot = snapshot
    }
}

/**
 * 悬浮窗：在 Termux / 酒馆上方显示当前状态与下一步提示。
 * 支持拖拽；点击「🩺 报错」自动调用 AI 分析后台日志与屏幕内容，放大显示并可上下滚动，点 ✕ 恢复原形。
 */
class OverlayService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val diagnosisScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var diagnoseJob: Job? = null
    private var windowManager: WindowManager? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var detailsScroll: ScrollView? = null
    private var closeButton: TextView? = null
    private var reportMode = false
    private var savedX = 0
    private var savedY = 0

    private var root: LinearLayout? = null
    private var dot: View? = null
    private var titleText: TextView? = null
    private var stageText: TextView? = null
    private var guideText: TextView? = null
    private var tutorialText: TextView? = null
    private var tutorialButton: TextView? = null
    private var tutorialAltButton: TextView? = null
    private var tutorialContinueRow: LinearLayout? = null
    private var tutorialSwitchRow: LinearLayout? = null
    private var tutorialBadge: TextView? = null
    private var tutorialNavRow: LinearLayout? = null
    private var tutorialPrev: TextView? = null
    private var tutorialNext: TextView? = null
    /** 回看定位：-1 表示跟随最新一步，其余为正在回看的 0–4 阶段 */
    private var cookieViewStage = -1
    private var tutorialEdgeSwitch: TextView? = null
    private var tutorialFirefoxSwitch: TextView? = null
    private var detailsBox: LinearLayout? = null
    private var aiText: TextView? = null
    private var elapsedText: TextView? = null
    private var modelTipText: LinearLayout? = null

    private var running = true
    private var expanded = false

    /** 部署完成待导入 Cookie 时，引导文字闪烁高亮用 */
    private var highlightPulse = false

    /** 剪贴板里已确认是 Cookie-Editor 导出的 JSON 时的字符数（读不到剪贴板时为 0） */
    private var cookieClipLength = 0

    /** 上一阶段用的浏览器名（Edge / 火狐），用于只显示对应那套点击路径 */
    private var tutorialBrowser: String? = null

    /** S1 主按钮打开失败时的红色提示行 */
    private var tutorialFailHint: String? = null

    /** S2 是否已弹出 VPN 确认卡 */
    private var vpnConfirmShown = false

    /** 部署步是否已弹出 VPN 确认卡 */
    private var deployVpnConfirmShown = false

    /** 刚点过「开始部署」的时刻：Activity 启动前 20 秒内按部署中显示，避免卡片闪回 */
    private var deployTapAt = 0L

    /** 酒馆向导是否已弹出完成确认卡 */
    private var tavernConfirmShown = false

    /** 打开 Termux 失败时保留红色提示，避免下一次重渲染把它冲掉 */
    private var tavernFailHint = false

    /** 浏览器包名缓存：检测成功才覆盖，检测失败不动旧值（只降级不归零） */
    private var edgePkgCache: String = ""
    private var firefoxPkgCache: String = ""

    /** online 变 false 的时刻；恢复 true 立即清零。心跳 6 秒，阈值取 15 秒≈丢两次以上 */
    private var offlineSince: Long = 0L

    /** 服务重建时刻：头 3 秒不判抢占，等第一次心跳结果 */
    private val rebuiltAt: Long = System.currentTimeMillis()

    private val offlineThresholdMs = 15_000L
    private val rebuildGraceMs = 3_000L

    private val guidePrefs by lazy {
        getSharedPreferences("aistudio_tutorial", MODE_PRIVATE)
    }

    /** 酒馆向导已完成：持久化，写死不再出现 */
    private var tavernDone: Boolean
        get() = guidePrefs.getBoolean("tavern_done", false)
        set(value) = guidePrefs.edit().putBoolean("tavern_done", value).apply()

    /** 拉模型提示已关闭：持久化，永久不再出现 */
    private var modelTipDismissed: Boolean
        get() = guidePrefs.getBoolean("model_tip_dismissed", false)
        set(value) = guidePrefs.edit().putBoolean("model_tip_dismissed", value).apply()

    /**
     * 五步引导到达的最远步骤：0 部署、1 浏览器、2 扩展、3 Cookie、4 粘贴、5 完成。
     * 与原 cookie_max_stage 并存，迁移一次后新逻辑只读写这个键。
     */
    private var guideStageMax: Int
        get() = guidePrefs.getInt("guide_stage_max", 0)
        set(value) = guidePrefs.edit()
            .putInt("guide_stage_max", maxOf(guidePrefs.getInt("guide_stage_max", 0), value))
            .apply()

    /** 扩展已确认安装（安卓读不到浏览器扩展列表，只能手动确认或剪贴板反推） */
    private var guideExtInstalled: Boolean
        get() = guidePrefs.getBoolean("guide_ext_installed", false)
        set(value) = guidePrefs.edit().putBoolean("guide_ext_installed", value).apply()

    /** 老用户从 cookie_max_stage(0–4) 映射到五步状态机；只迁移一次 */
    private fun migrateGuidePrefs() {
        if (guidePrefs.getBoolean("guide_migrated", false)) return
        val hasOld = guidePrefs.contains("cookie_max_stage")
        val old = guidePrefs.getInt("cookie_max_stage", 0)
        val mapped = when (old) {
            0 -> 0
            1 -> 2
            2 -> 3
            3 -> 3
            4 -> 4
            else -> 0
        }
        val done = guidePrefs.getBoolean("cookie_done", false)
        guidePrefs.edit()
            .putInt("guide_stage_max", if (done) 5 else if (hasOld) mapped else 0)
            .putBoolean("guide_ext_installed", hasOld && old >= 2)
            .putBoolean("guide_migrated", true)
            .apply()
    }

    /** Cookie 向导整体结束（导入成功即 true），置 true 后 A 阶段永久不再出现 */
    private var cookieDone: Boolean
        // 默认 false：全新安装要先看教程；老用户由 render() 里的一次性迁移按真实状态纠正
        get() = guidePrefs.getBoolean("cookie_done", false)
        set(value) = guidePrefs.edit().putBoolean("cookie_done", value).apply()

    /** 反代曾经连上过；一旦 true 永不回退，用于区分「从没启动」和「连上又断」 */
    private var proxyEverOnline: Boolean
        get() = guidePrefs.getBoolean("proxy_ever_online", false)
        set(value) = guidePrefs.edit().putBoolean("proxy_ever_online", value).apply()

    private val clipWatcher = ClipboardManager.OnPrimaryClipChangedListener {
        val text = runCatching { TermuxBridge.readFromClipboard(this) }.getOrNull().orEmpty()
        cookieClipLength = if (looksLikeCookieJson(text)) text.length else 0
        if (cookieClipLength > 0 && !cookieDone) {
            // 能导出 JSON 说明扩展已经装好；直接推进到粘贴步，不等用户手点
            guideExtInstalled = true
            guideStageMax = 4
        }
        handler.post { render() }
    }

    private var tickCount = 0

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            tickCount++
            highlightPulse = !highlightPulse
            // 每 4 拍（约 6 秒）重探一次浏览器，装完回来不用点「重新检测」
            if (tickCount % 4 == 0) refreshBrowserCache()
            render()
            handler.postDelayed(this, 1500L)
        }
    }

    /** 解锁时重探一次浏览器：装完 Edge/火狐回到桌面即能进 S1 */
    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_USER_PRESENT) {
                refreshBrowserCache()
                render()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundSafely()
        if (!Settings.canDrawOverlays(this)) {
            isRunning = false
            stopSelf()
            return
        }
        isRunning = true
        windowManager = getSystemService(WINDOW_SERVICE) as? WindowManager
        val legacy = getSharedPreferences("overlay_guide", MODE_PRIVATE)
        // 老版本把酒馆/提示状态存在 overlay_guide，首次运行搬到 aistudio_tutorial
        if (!guidePrefs.contains("tavern_done") && legacy.getBoolean("tavern_guide_done", false)) {
            tavernDone = true
        }
        if (!guidePrefs.contains("model_tip_dismissed") && legacy.getBoolean("model_tip_dismissed", false)) {
            modelTipDismissed = true
        }
        migrateGuidePrefs()
        loadBrowserCache()
        refreshBrowserCache()
        buildViews()
        runCatching {
            (getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager)
                ?.addPrimaryClipChangedListener(clipWatcher)
        }
        runCatching {
            registerReceiver(unlockReceiver, IntentFilter(Intent.ACTION_USER_PRESENT))
        }
        handler.post(ticker)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running = false
        isRunning = false
        runCatching {
            (getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager)
                ?.removePrimaryClipChangedListener(clipWatcher)
        }
        runCatching { unregisterReceiver(unlockReceiver) }
        handler.removeCallbacksAndMessages(null)
        diagnosisScope.cancel()
        root?.let { view -> runCatching { windowManager?.removeView(view) } }
        root = null
        super.onDestroy()
    }

    private fun buildViews() {
        if (root != null) return
        if (!Settings.canDrawOverlays(this)) return

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#EB111827"))
                setStroke(dp(1), Color.parseColor("#40FFFFFF"))
            }
        }

        // 标题行：状态点 + 标题 + ✕ 关闭报错放大视图按钮
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val dotView = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#94A3B8"))
            }
            layoutParams = LinearLayout.LayoutParams(dp(9), dp(9))
        }
        val title = TextView(this).apply {
            text = "反代助手"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(8), 0, 0, 0)
        }
        val closeBtn = TextView(this).apply {
            text = "✕"
            setTextColor(Color.parseColor("#FCA5A5"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(14), dp(2), dp(4), dp(2))
            visibility = View.GONE
            setOnClickListener { exitReportMode() }
        }
        this.closeButton = closeBtn
        titleRow.addView(dotView)
        titleRow.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        titleRow.addView(closeBtn)

        val stage = TextView(this).apply {
            setTextColor(Color.parseColor("#CBD5E1"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(6), 0, 0)
        }

        val guide = TextView(this).apply {
            setTextColor(Color.parseColor("#93C5FD"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(4), 0, 0)
            setOnClickListener { openApp() }
        }
        // 悬浮窗内嵌的 Cookie 教程：只在「已启动但还没导入账号」时出现，每次只显示当前一步
        val tutorial = TextView(this).apply {
            text = "教程加载中"
            setTextColor(Color.parseColor("#DBEAFE"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setLineSpacing(dp(2).toFloat(), 1.1f)
            setPadding(dp(8), dp(5), dp(8), dp(5))
            visibility = View.GONE
        }
        // 回看态顶部灰色小标：说明当前卡片是已走过的一步
        val badge = TextView(this).apply {
            setTextColor(Color.parseColor("#9AA0A6"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(8), dp(2), dp(8), 0)
            visibility = View.GONE
        }
        val navRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
            visibility = View.GONE
        }
        val navPrev = TextView(this).apply {
            text = "← 上一步"
            setTextColor(Color.parseColor("#8A8A8A"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            visibility = View.GONE
        }
        val navNext = TextView(this).apply {
            text = "下一步 →"
            setTextColor(Color.parseColor("#8A8A8A"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            visibility = View.GONE
        }
        navRow.addView(navPrev)
        navRow.addView(Space(this), LinearLayout.LayoutParams(0, 1, 1f))
        navRow.addView(navNext)
        val tutorialBtn = TextView(this).apply {
            text = "教程加载中"
            setTextColor(Color.parseColor("#86EFAC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(8), dp(6), dp(8), dp(2))
            visibility = View.GONE
        }
        val tutorialAltBtn = TextView(this).apply {
            setTextColor(Color.parseColor("#93C5FD"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(dp(8), dp(2), dp(8), dp(2))
            visibility = View.GONE
        }
        val switchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(2), dp(8), 0)
            visibility = View.GONE
        }
        val edgeSwitch = TextView(this).apply {
            text = "使用 Edge"
            setTextColor(Color.parseColor("#93C5FD"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, 0, dp(16), 0)
            setOnClickListener {
                tutorialBrowser = "Edge"
                tutorialFailHint = null
                guidePrefs.edit().putString("browser_choice", "Edge").apply()
                render()
            }
        }
        val firefoxSwitch = TextView(this).apply {
            text = "使用 火狐"
            setTextColor(Color.parseColor("#93C5FD"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, 0, 0, 0)
            setOnClickListener {
                tutorialBrowser = "火狐"
                tutorialFailHint = null
                guidePrefs.edit().putString("browser_choice", "火狐").apply()
                render()
            }
        }
        switchRow.addView(edgeSwitch)
        switchRow.addView(firefoxSwitch)
        val details = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val ai = TextView(this).apply {
            setTextColor(Color.parseColor("#FDE68A"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setLineSpacing(dp(2).toFloat(), 1.15f)
            setPadding(0, dp(6), 0, dp(4))
        }
        val elapsed = TextView(this).apply {
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, dp(4), 0, 0)
        }
        val modelTip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(4), dp(8), dp(2))
            visibility = View.GONE
        }
        val tipClose = TextView(this).apply {
            text = "✕"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setPadding(0, 0, dp(6), 0)
            setOnClickListener {
                modelTipDismissed = true
                render()
            }
        }
        val tipText = TextView(this).apply {
            text = "💡 反代页可点「拉取模型列表」确认模型可用"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        }
        modelTip.addView(tipClose)
        modelTip.addView(tipText)

        // 操作行：报错 + 打开 App，始终可见
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        val diagnoseText = TextView(this).apply {
            text = "🩺 报错"
            setTextColor(Color.parseColor("#FDE68A"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(12), dp(5), dp(12), dp(5))
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Color.parseColor("#33FFFFFF"))
            }
            setOnClickListener { runDiagnose() }
        }
        val openAppText = TextView(this).apply {
            text = "打开 App"
            setTextColor(Color.parseColor("#93C5FD"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(14), dp(5), dp(6), dp(5))
            setOnClickListener { openApp() }
        }
        actionRow.addView(diagnoseText)
        actionRow.addView(openAppText)

        details.addView(ai)
        details.addView(elapsed)

        // 报错结果放在可滚动区域里，文字多了显示上下滑块
        val scroll = ScrollView(this).apply {
            visibility = View.GONE
            isVerticalScrollBarEnabled = true
            isScrollbarFadingEnabled = false
            isFillViewport = false
            setPadding(0, dp(6), dp(4), 0)
            addView(
                details,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        detailsScroll = scroll

        panel.addView(titleRow)
        panel.addView(stage)
        panel.addView(guide)
        panel.addView(badge)
        panel.addView(tutorial)
        panel.addView(switchRow)
        panel.addView(tutorialBtn)
        panel.addView(tutorialAltBtn)
        panel.addView(navRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(30)
        ).apply { topMargin = dp(6) })
        panel.addView(modelTip)
        val continueRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        panel.addView(continueRow)
        panel.addView(actionRow)
        panel.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            dp(280),
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(72)
        }
        panelParams = params
        savedX = params.x
        savedY = params.y

        // 支持拖拽：按下记录起点，移动更新窗口位置；非报错放大模式下轻点可展开/收起
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        panel.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - downRawX).toInt()
                    val dy = (ev.rawY - downRawY).toInt()
                    if (kotlin.math.abs(dx) > 12 || kotlin.math.abs(dy) > 12) {
                        dragging = true
                    }
                    if (dragging) {
                        params.x = startX + dx
                        params.y = startY + dy
                        if (!reportMode) {
                            savedX = params.x
                            savedY = params.y
                        }
                        runCatching { windowManager?.updateViewLayout(panel, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging && !reportMode) {
                        expanded = !expanded
                        details.visibility = if (expanded) View.VISIBLE else View.GONE
                        scroll.visibility = if (expanded) View.VISIBLE else View.GONE
                        render()
                    }
                    true
                }
                else -> false
            }
        }

        runCatching {
            windowManager?.addView(panel, params)
            root = panel
            dot = dotView
            titleText = title
            stageText = stage
            guideText = guide
            tutorialText = tutorial
            tutorialButton = tutorialBtn
            tutorialAltButton = tutorialAltBtn
            tutorialContinueRow = continueRow
            tutorialSwitchRow = switchRow
            tutorialBadge = badge
            tutorialNavRow = navRow
            tutorialPrev = navPrev
            tutorialNext = navNext
            tutorialEdgeSwitch = edgeSwitch
            tutorialFirefoxSwitch = firefoxSwitch
            detailsBox = details
            aiText = ai
            elapsedText = elapsed
            modelTipText = modelTip
        }
    }

    private fun render() {
        val snapshot = OverlayState.snapshot
        val elapsedSec = if (OverlayState.deployStartedAt > 0L) {
            ((System.currentTimeMillis() - OverlayState.deployStartedAt) / 1000L).coerceAtLeast(0L)
        } else {
            0L
        }

        // 断线防抖：online 变 false 记时间戳，恢复 true 立即清零
        if (snapshot.online) {
            proxyEverOnline = true
            offlineSince = 0L
        } else if (offlineSince == 0L) {
            offlineSince = System.currentTimeMillis()
        }
        val now = System.currentTimeMillis()
        val takeover = proxyEverOnline && !snapshot.online &&
            offlineSince > 0L && now - offlineSince >= offlineThresholdMs &&
            now - rebuiltAt >= rebuildGraceMs

        // 老用户迁移：cookie_done 未写过时，等拿到真实服务状态再判定，避免升级被教程糊脸
        if (!guidePrefs.contains("cookie_done") && snapshot.online) {
            if (snapshot.accountsReady) {
                guideStageMax = 5
                cookieDone = true
            } else {
                cookieDone = false
            }
        }
        if (snapshot.accountsReady && !cookieDone) {
            guideStageMax = 5
            cookieDone = true
        }
        if (cookieDone) guideStageMax = 5

        val cookieMode = !cookieDone
        val tavernMode = cookieDone && !tavernDone
        val showTakeover = takeover && !reportMode
        val guideMode = (cookieMode || tavernMode || showTakeover) && !reportMode
        val needCookie = snapshot.online && !snapshot.accountsReady

        val color = when {
            snapshot.error != null -> "#EF4444"
            showTakeover -> "#EF4444"
            needCookie -> "#22C55E"
            snapshot.online -> "#22C55E"
            OverlayState.deployStartedAt > 0L -> "#F59E0B"
            else -> "#94A3B8"
        }
        (dot?.background as? GradientDrawable)?.setColor(Color.parseColor(color))

        titleText?.text = when {
            snapshot.error != null -> "反代助手 · 出错了"
            showTakeover -> "反代助手 · 反代断线"
            needCookie -> "反代助手 · 部署完成 🎉"
            snapshot.online -> "反代助手 · 运行中 :${snapshot.port}"
            OverlayState.deployStartedAt > 0L -> "反代助手 · 搭建中"
            else -> "反代助手"
        }

        val offlineSec = if (offlineSince > 0L) ((now - offlineSince) / 1000L).coerceAtLeast(0L) else 0L
        stageText?.text = when {
            showTakeover -> "已掉线 ${offlineSec} 秒 · 教程进度不会清零"
            snapshot.error != null -> "阶段：失败（${snapshot.error}）"
            needCookie -> "阶段：服务已启动，还差导入 Cookie"
            snapshot.online -> "阶段：服务已启动"
            else -> "阶段：${snapshot.stage}"
        }
        // 教程期不显示阶段行，避免和卡片抢位置
        stageText?.visibility = if (guideMode) View.GONE else View.VISIBLE

        if (!reportMode) {
            // 教程/断线卡期间只留一张卡；正常态才显示这一行引导
            guideText?.text = "👉 ${snapshot.guidance}"
            guideText?.setTextColor(Color.parseColor("#93C5FD"))
            guideText?.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            guideText?.setTypeface(null, Typeface.NORMAL)
            guideText?.setPadding(0, dp(4), 0, 0)
            guideText?.background = null

            aiText?.text = snapshot.aiHint?.let { "AI：$it" }
                ?: "AI：还没有结论，点「🩺 报错」让我自动分析"
        }

        elapsedText?.text = if (OverlayState.deployStartedAt > 0L) {
            "已用时 ${elapsedSec / 60} 分 ${elapsedSec % 60} 秒 · 拖动可移动"
        } else {
            "拖动可移动 · 点「🩺 报错」自动分析后台错误"
        }

        // 向导模式精简悬浮窗：隐藏 AI 行与已用时行；报错放大（reportMode）照常显示
        if (!reportMode) {
            val guideVisibility = if (guideMode) View.GONE else View.VISIBLE
            aiText?.visibility = guideVisibility
            elapsedText?.visibility = guideVisibility
            guideText?.visibility = guideVisibility
        }

        if (!expanded && !reportMode) {
            detailsBox?.visibility = View.GONE
            detailsScroll?.visibility = View.GONE
        }

        if (cookieDone) {
            cookieClipLength = 0
            tutorialBrowser = null
            vpnConfirmShown = false
            deployVpnConfirmShown = false
            tutorialFailHint = null
        }
        when {
            showTakeover -> {
                renderTutorial(false)
                renderTavernGuide(false)
                renderTakeoverCard(true)
            }
            cookieMode -> {
                renderTakeoverCard(false)
                renderTutorial(!reportMode)
            }
            tavernMode -> {
                renderTakeoverCard(false)
                renderTutorial(false)
                renderTavernGuide(!reportMode)
            }
            else -> {
                renderTutorial(false)
                renderTavernGuide(false)
                renderTakeoverCard(false)
            }
        }
        renderModelTip(snapshot.online && snapshot.accountsReady && tavernDone && !modelTipDismissed && !reportMode)
    }

    /** 一次性拉模型提示：✕ 可点，静默永久消失，不弹任何提示。 */
    private fun renderModelTip(show: Boolean) {
        val tip = modelTipText ?: return
        tip.visibility = if (show) View.VISIBLE else View.GONE
    }

    /** 悬浮窗教程：一次只显示当前一步，按检测到的浏览器给对应路径，控制在两三行内。 */
    private fun renderTutorial(show: Boolean) {
        val tut = tutorialText ?: return
        val btn = tutorialButton
        val altBtn = tutorialAltButton
        val switchRow = tutorialSwitchRow
        val continueRow = tutorialContinueRow
        if (!show) {
            cookieViewStage = -1
            tut.visibility = View.GONE
            tutorialBadge?.visibility = View.GONE
            tutorialNavRow?.visibility = View.GONE
            btn?.visibility = View.GONE
            altBtn?.visibility = View.GONE
            switchRow?.visibility = View.GONE
            continueRow?.visibility = View.GONE
            return
        }
        tut.visibility = View.VISIBLE

        val snapshot = OverlayState.snapshot
        val (edge, firefox) = browsersFromCache()
        val detected = edge ?: firefox
        // 已手动切换到某一类、但该类未安装时，回退到检测到的那一类
        val chosen = when {
            tutorialBrowser == "Edge" -> edge ?: detected
            tutorialBrowser == "火狐" -> firefox ?: detected
            // 记住用户上次手动选的浏览器；两个都装时不再无脑优先 Edge
            guidePrefs.getString("browser_choice", "") == "Edge" && edge != null -> edge
            guidePrefs.getString("browser_choice", "") == "火狐" && firefox != null -> firefox
            else -> detected
        }
        if (tutorialBrowser != null && chosen?.first != tutorialBrowser) tutorialBrowser = chosen?.first
        val browserLabel = chosen?.first
        val browserPkg = chosen?.second
        val bothInstalled = edge != null && firefox != null
        val deployedEver = snapshot.hasDeployed ||
            SettingsRepository(applicationContext).hasDeployed ||
            guideStageMax > 0
        val deployed = snapshot.online || deployedEver

        // 五步：0 部署、1 浏览器、2 扩展、3 Cookie、4 粘贴；没部署过就永远停在第 1 步
        val computed = when {
            cookieDone || snapshot.accountsReady -> 5
            cookieClipLength > 0 -> 4
            !deployed -> 0
            chosen != null && guideExtInstalled -> 3
            chosen != null -> 2
            else -> 1
        }
        // 浏览器被卸载：回到重新安装那一步，不能卡在扩展/ Cookie 卡
        if (chosen == null && guideStageMax >= 2) guideStageMax = 1
        if (computed > guideStageMax) guideStageMax = computed
        val stage = guideStageMax
        // 回看定位：只允许停在 0..stage-1，超出即跟随最新
        val view = if (cookieViewStage in 0 until stage) cookieViewStage else stage
        val readonly = view < stage
        cookieViewStage = if (readonly) view else -1
        if (view != 1) tutorialFailHint = null
        if (view != 3) vpnConfirmShown = false
        if (view != 0) deployVpnConfirmShown = false

        tut.setTypeface(null, Typeface.NORMAL)
        tut.background = tutorialPanelBackground()
        tut.setOnClickListener(null)
        continueRow?.visibility = View.GONE
        switchRow?.visibility =
            if (view == 2 && bothInstalled && !readonly) View.VISIBLE else View.GONE
        tutorialEdgeSwitch?.let {
            it.setTextColor(Color.parseColor(if (browserLabel == "Edge") "#FDE047" else "#93C5FD"))
            it.setTypeface(it.typeface, if (browserLabel == "Edge") Typeface.BOLD else Typeface.NORMAL)
        }
        tutorialFirefoxSwitch?.let {
            it.setTextColor(Color.parseColor(if (browserLabel == "火狐") "#FDE047" else "#93C5FD"))
            it.setTypeface(it.typeface, if (browserLabel == "火狐") Typeface.BOLD else Typeface.NORMAL)
        }

        tutorialBadge?.visibility = View.VISIBLE
        tutorialBadge?.text = if (readonly) {
            "已完成 · 第 ${view + 1} 步"
        } else {
            "第 ${view + 1}/5 步 · ${stageName(view)}"
        }
        tutorialPrev?.visibility = if (view > 0) View.VISIBLE else View.GONE
        tutorialNext?.visibility = if (view < stage) View.VISIBLE else View.GONE
        tutorialNavRow?.visibility = if (view > 0 || view < stage) View.VISIBLE else View.GONE
        tutorialPrev?.setOnClickListener { cookieViewStage = (view - 1).coerceAtLeast(0); render() }
        tutorialNext?.setOnClickListener {
            cookieViewStage = if (view + 1 >= stage) -1 else view + 1
            render()
        }
        btn?.isEnabled = !readonly
        btn?.alpha = if (readonly) 0.5f else 1f
        altBtn?.isEnabled = !readonly
        altBtn?.alpha = if (readonly) 0.5f else 1f
        tutorialEdgeSwitch?.isEnabled = !readonly
        tutorialFirefoxSwitch?.isEnabled = !readonly

        when (view) {
            0 -> renderDeployStage(tut, btn, altBtn, continueRow)
            1 -> renderBrowserStage(tut, btn, altBtn)
            2 -> renderExtensionStage(tut, btn, altBtn, continueRow, browserLabel, browserPkg)
            3 -> renderCookieStage(tut, btn, altBtn, continueRow, browserLabel, browserPkg)
            4 -> renderPasteStage(tut, btn, altBtn, continueRow, browserPkg)
            else -> renderTutorial(false)
        }
        if (readonly) {
            continueRow?.visibility = View.GONE
            switchRow?.visibility = View.GONE
        }
    }

    private fun stageName(stage: Int): String = when (stage) {
        0 -> "部署"
        1 -> "浏览器"
        2 -> "扩展"
        3 -> "Cookie"
        else -> "导入"
    }

    /** 断线卡：优先级高于教程卡，但只显示一块，不清空教程进度 */
    private fun renderTakeoverCard(show: Boolean) {
        val tut = tutorialText ?: return
        val btn = tutorialButton
        val altBtn = tutorialAltButton
        if (!show) {
            tut.visibility = View.GONE
            btn?.visibility = View.GONE
            altBtn?.visibility = View.GONE
            tutorialBadge?.visibility = View.GONE
            tutorialNavRow?.visibility = View.GONE
            tutorialContinueRow?.visibility = View.GONE
            tutorialSwitchRow?.visibility = View.GONE
            return
        }
        val sec = if (offlineSince > 0L) {
            ((System.currentTimeMillis() - offlineSince) / 1000L).coerceAtLeast(0L)
        } else {
            0L
        }
        tut.visibility = View.VISIBLE
        tut.text = "反代断线了（已掉线 $sec 秒）\n服务没在跑，教程进度不会清零"
        tut.setTextColor(Color.parseColor("#FCA5A5"))
        tut.background = tutorialPanelBackground()
        btn?.visibility = View.VISIBLE
        btn?.text = "启动反代"
        btn?.setOnClickListener { startServiceFromOverlay() }
        altBtn?.visibility = View.VISIBLE
        altBtn?.text = "诊断"
        altBtn?.setOnClickListener { runDiagnose() }
        tutorialBadge?.visibility = View.GONE
        tutorialNavRow?.visibility = View.GONE
        tutorialContinueRow?.visibility = View.GONE
        tutorialSwitchRow?.visibility = View.GONE
    }

    /** 第 1 步：半自动部署。Termux 没装先装；VPN 在卡内确认；部署中显示实时阶段。 */
    private fun renderDeployStage(
        tut: TextView,
        btn: TextView?,
        altBtn: TextView?,
        continueRow: LinearLayout?
    ) {
        continueRow?.visibility = View.GONE
        tut.setOnClickListener(null)
        tut.setTextColor(Color.parseColor("#DBEAFE"))
        val termuxNow = isPackageInstalled(TermuxBridge.TERMUX_PACKAGE)
        val snapshot = OverlayState.snapshot
        val deployedEver = snapshot.hasDeployed || SettingsRepository(applicationContext).hasDeployed
        val justTapped = deployTapAt > 0L && System.currentTimeMillis() - deployTapAt < 20_000L
        val deploying = snapshot.deploying || justTapped
        when {
            !termuxNow -> {
                tut.text = "还没装 Termux（必须 F-Droid / GitHub 的 arm64 版，商店旧版不行）"
                btn?.visibility = View.VISIBLE
                btn?.text = "去装 Termux"
                btn?.setOnClickListener { TermuxBridge.openUrl(this, TermuxBridge.URL_TERMUX_FDROID) }
                altBtn?.visibility = View.VISIBLE
                altBtn?.text = "我已装好，重新检测"
                altBtn?.setOnClickListener { render() }
            }
            deployVpnConfirmShown -> {
                tut.text = "⚠️ 先确认 VPN 已开（要能打开 Google），否则部署中途会失败"
                tut.setTextColor(Color.parseColor("#FDE68A"))
                btn?.visibility = View.VISIBLE
                btn?.text = "已开，开始部署"
                btn?.setOnClickListener {
                    deployVpnConfirmShown = false
                    deployTapAt = System.currentTimeMillis()
                    startDeployFromOverlay()
                    render()
                }
                altBtn?.visibility = View.VISIBLE
                altBtn?.text = "取消"
                altBtn?.setOnClickListener { deployVpnConfirmShown = false; render() }
            }
            snapshot.error != null && !snapshot.online -> {
                tut.text = "部署出错了：${snapshot.error.take(60)}"
                tut.setTextColor(Color.parseColor("#FCA5A5"))
                btn?.visibility = View.VISIBLE
                btn?.text = "重新部署"
                btn?.setOnClickListener { deployVpnConfirmShown = true; render() }
                altBtn?.visibility = View.VISIBLE
                altBtn?.text = "查看诊断"
                altBtn?.setOnClickListener { runDiagnose() }
            }
            deploying -> {
                tut.text = "正在部署：${snapshot.stage.ifBlank { "准备中" }}\n" +
                    "约 5~15 分钟；编译阶段长时间没输出是正常的，别杀 Termux"
                btn?.visibility = View.VISIBLE
                btn?.text = "打开 Termux 查看"
                btn?.setOnClickListener { TermuxBridge.openTermux(this) }
                altBtn?.visibility = View.VISIBLE
                altBtn?.text = "出错了？诊断"
                altBtn?.setOnClickListener { runDiagnose() }
            }
            deployedEver -> {
                tut.text = "部署跑完了，但反代没在跑"
                btn?.visibility = View.VISIBLE
                btn?.text = "启动反代"
                btn?.setOnClickListener { startServiceFromOverlay() }
                altBtn?.visibility = View.VISIBLE
                altBtn?.text = "诊断"
                altBtn?.setOnClickListener { runDiagnose() }
            }
            else -> {
                tut.text = "点下面按钮：自动复制部署命令 → 打开 Termux → 自动粘贴并回车\n" +
                    "没开无障碍也能部署，只是要你在 Termux 长按粘贴"
                btn?.visibility = View.VISIBLE
                btn?.text = "开始半自动部署"
                btn?.setOnClickListener { deployVpnConfirmShown = true; render() }
                altBtn?.visibility = View.VISIBLE
                altBtn?.text = "打开 Termux"
                altBtn?.setOnClickListener { TermuxBridge.openTermux(this) }
            }
        }
    }

    /** 第 2 步：装支持扩展的浏览器；检测到包名会自动跳到下一步。 */
    private fun renderBrowserStage(
        tut: TextView,
        btn: TextView?,
        altBtn: TextView?
    ) {
        tut.text = "手机自带浏览器、Chrome 不支持扩展，先装 Edge 或火狐"
        tut.setTextColor(Color.parseColor("#DBEAFE"))
        tut.setOnClickListener { refreshBrowserCache(); render() }
        btn?.visibility = View.VISIBLE
        btn?.text = "下载 Edge（官网）"
        btn?.setOnClickListener { TermuxBridge.openUrl(this, TermuxBridge.URL_EDGE_ANDROID) }
        altBtn?.visibility = View.VISIBLE
        altBtn?.text = "下载火狐（官网）"
        altBtn?.setOnClickListener { TermuxBridge.openUrl(this, TermuxBridge.URL_FIREFOX_ANDROID) }
    }

    /** 第 4 步：打开 AI Studio 登录并导出 Cookie；剪贴板命中 JSON 会自动跳到粘贴。 */
    private fun renderCookieStage(
        tut: TextView,
        btn: TextView?,
        altBtn: TextView?,
        continueRow: LinearLayout?,
        browserLabel: String?,
        browserPkg: String?
    ) {
        val label = browserLabel ?: "Edge"
        continueRow?.visibility = View.GONE
        if (vpnConfirmShown) {
            tut.text = "⚠️ 先确认 VPN 已开启\n需要能访问 Google，否则页面打不开"
            tut.setTextColor(Color.parseColor("#FDE68A"))
            btn?.visibility = View.VISIBLE
            btn?.text = "已开，继续"
            btn?.setOnClickListener {
                openAiStudio(browserPkg)
                vpnConfirmShown = false
                render()
            }
            altBtn?.visibility = View.VISIBLE
            altBtn?.text = "取消"
            altBtn?.setOnClickListener {
                vpnConfirmShown = false
                render()
            }
            return
        }
        val menuPath = if (label == "火狐") {
            "② 点右上「⋮」→ 扩展 → Cookie-Editor"
        } else {
            "② 点底部「≡」→ 扩展 → Cookie-Editor"
        }
        tut.text = "① 用 $label 打开 AI Studio 并登录\n$menuPath\n③ 点 Export → 选 Export as JSON（会自动复制）"
        tut.setTextColor(Color.parseColor("#DBEAFE"))
        btn?.visibility = View.VISIBLE
        btn?.text = if (label == "火狐") "用 火狐 打开 AI Studio" else "用 Edge 打开 AI Studio"
        btn?.setOnClickListener { vpnConfirmShown = true; render() }
        altBtn?.visibility = View.VISIBLE
        altBtn?.text = "复制 AI Studio 链接"
        altBtn?.setOnClickListener {
            TermuxBridge.copyToClipboard(this, "AI Studio 链接", TermuxBridge.URL_AISTUDIO)
        }
        renderTutorialActionLink(continueRow, "已导出，去粘贴 →") {
            guideStageMax = 4
            render()
        }
    }

    /** 酒馆向导：账号就绪后引导在 Termux 新会话启动酒馆；完成后内嵌确认卡，确定才持久化隐藏。 */
    private fun renderTavernGuide(show: Boolean) {
        val tut = tutorialText ?: return
        val btn = tutorialButton
        val altBtn = tutorialAltButton
        val continueRow = tutorialContinueRow
        val switchRow = tutorialSwitchRow
        if (!show) {
            tut.visibility = View.GONE
            btn?.visibility = View.GONE
            altBtn?.visibility = View.GONE
            continueRow?.visibility = View.GONE
            switchRow?.visibility = View.GONE
            tutorialBadge?.visibility = View.GONE
            tutorialNavRow?.visibility = View.GONE
            return
        }
        tut.visibility = View.VISIBLE
        tutorialBadge?.visibility = View.GONE
        tutorialNavRow?.visibility = View.GONE
        switchRow?.visibility = View.GONE
        continueRow?.visibility = View.GONE

        if (tavernConfirmShown) {
            tut.text = "点击完成后这段教程不再在悬浮窗显示，可在 App「解疑」页再次查看。"
            tut.setTextColor(Color.parseColor("#DBEAFE"))
            tut.background = tutorialPanelBackground()
            btn?.visibility = View.VISIBLE
            btn?.text = "确定"
            btn?.setOnClickListener {
                tavernDone = true
                tavernConfirmShown = false
                render()
            }
            altBtn?.visibility = View.VISIBLE
            altBtn?.text = "取消"
            altBtn?.setOnClickListener {
                tavernConfirmShown = false
                render()
            }
            return
        }

        val steps = "① 打开 Termux\n② 从屏幕左边缘向右滑，拉出白色小窗\n③ 点 NEW SESSION\n④ 在新窗口输入你的酒馆启动命令（示例：bash start.sh）"
        tut.text = if (tavernFailHint) "未能打开 Termux，请手动从桌面启动\n\n$steps" else steps
        tut.setTextColor(
            Color.parseColor(if (tavernFailHint) "#FCA5A5" else "#DBEAFE")
        )
        tut.background = tutorialPanelBackground()
        btn?.visibility = View.VISIBLE
        btn?.text = "打开 Termux"
        btn?.setOnClickListener {
            tavernFailHint = !TermuxBridge.openTermux(this)
            render()
        }
        altBtn?.visibility = View.VISIBLE
        altBtn?.text = "完成"
        altBtn?.setOnClickListener { tavernConfirmShown = true; render() }
    }

    /** 第 5 步：剪贴板命中 Cookie 后回账号页粘贴；导入成功由 accountsReady 自动完成。 */
    private fun renderPasteStage(
        tut: TextView,
        btn: TextView?,
        altBtn: TextView?,
        continueRow: LinearLayout?,
        browserPkg: String?
    ) {
        continueRow?.visibility = View.GONE
        val head = if (cookieClipLength > 0) {
            "✅ 已检测到 Cookie（${cookieClipLength} 字符）\n"
        } else {
            "还没检测到 Cookie，先回上一步导出\n"
        }
        tut.text = "${head}回 App →「账号」页 → 点「粘贴剪贴板」→ 导入\n导入成功后这个悬浮窗会自动消失"
        tut.setTextColor(Color.parseColor(if (highlightPulse) "#FDE047" else "#86EFAC"))
        tut.setTypeface(null, Typeface.BOLD)
        tut.background = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(Color.parseColor(if (highlightPulse) "#33FDE047" else "#2A22C55E"))
            setStroke(dp(1), Color.parseColor("#66FDE047"))
        }
        btn?.visibility = View.VISIBLE
        btn?.text = "📲 打开 App 账号页"
        btn?.setOnClickListener { openApp(tab = 1) }
        if (cookieClipLength > 0) {
            altBtn?.visibility = View.GONE
        } else {
            // 手动点了「已导出」但剪贴板读不到时，留一条重新导出的出口，避免卡死
            altBtn?.visibility = View.VISIBLE
            altBtn?.text = "重新导出 Cookie"
            altBtn?.setOnClickListener { openAiStudio(browserPkg) }
        }
    }

    /** 第 3 步：装 Cookie-Editor；安卓读不到扩展列表，只能「装好了」或剪贴板反推。 */
    private fun renderExtensionStage(
        tut: TextView,
        btn: TextView?,
        altBtn: TextView?,
        continueRow: LinearLayout?,
        browserLabel: String?,
        browserPkg: String?
    ) {
        val label = browserLabel ?: "Edge"
        tut.text = if (label == "火狐") {
            "① 打开火狐 → 右上「⋮」→ 扩展 → 浏览全部扩展\n② 搜 Cookie-Editor → 添加"
        } else {
            "① 打开 Edge → 底部「≡」→ 扩展\n② 搜 Cookie-Editor → 安装"
        }
        tut.setTextColor(
            Color.parseColor(if (tutorialFailHint != null) "#FCA5A5" else "#DBEAFE")
        )
        val addonUrl = browserAddonUrl(label)
        btn?.visibility = View.VISIBLE
        btn?.text = if (label == "火狐") "在 火狐 打开扩展安装页" else "在 Edge 打开扩展安装页"
        btn?.setOnClickListener {
            if (browserPkg.isNullOrBlank()) {
                TermuxBridge.openUrl(this, addonUrl)
            } else {
                val ok = TermuxBridge.openUrlInPackage(this, addonUrl, browserPkg)
                if (!ok) {
                    tutorialFailHint = label
                    render()
                }
            }
        }
        altBtn?.visibility = View.VISIBLE
        altBtn?.text = if (label == "火狐") "复制 火狐 CE 扩展链接" else "复制 Edge CE 扩展链接"
        altBtn?.setOnClickListener {
            TermuxBridge.copyToClipboard(this, "Cookie-Editor ${label}扩展链接", addonUrl)
        }
        val fail = tutorialFailHint
        if (fail != null) {
            tut.text = "${tut.text}\n未能用 $fail 打开，请点「复制链接」手动粘贴"
        }
        // 安卓版火狐 120 以下不支持扩展，AMO 只会给 .xpi 下载；版本号能读到就直接点明
        if (label == "火狐") {
            val ffMajor = firefoxMajorVersion(browserPkg)
            tut.text = if (ffMajor in 1..119) {
                "${tut.text}\n⚠️ 火狐 $ffMajor 低于 120，安卓版不支持扩展，只会给下载文件。请升级火狐，或改用 Edge。"
            } else {
                "${tut.text}\n页面只给「下载文件」？在火狐里点 ⋮ → 扩展 → 浏览全部扩展，搜 Cookie-Editor 安装。"
            }
        }
        tut.setOnClickListener(null)
        renderTutorialActionLink(continueRow, "装好了 →") {
            guideExtInstalled = true
            guideStageMax = 3
            tutorialFailHint = null
            render()
        }
    }

    /** 教程卡底部的手动推进链接（只给自动检测不到的那一步用）。 */
    private fun renderTutorialActionLink(host: LinearLayout?, text: String, action: () -> Unit) {
        host ?: return
        host.removeAllViews()
        val link = TextView(this).apply {
            this.text = text
            setTextColor(Color.parseColor("#93C5FD"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(dp(8), dp(2), dp(8), dp(2))
            setOnClickListener { action() }
        }
        host.addView(link)
        host.visibility = View.VISIBLE
    }

    private fun tutorialPanelBackground(): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(8).toFloat()
        setColor(Color.parseColor("#14FFFFFF"))
    }

    /** 同时返回两类浏览器，用于两个都装时的「使用 Edge / 使用 火狐」切换。 */
    private fun detectBrowsers(): Pair<Pair<String, String>?, Pair<String, String>?> {
        fun installed(pkg: String): Boolean = runCatching { packageManager.getPackageInfo(pkg, 0) }.isSuccess
        val edge = listOf(
            TermuxBridge.PACKAGE_EDGE, TermuxBridge.PACKAGE_EDGE_BETA, TermuxBridge.PACKAGE_EDGE_CANARY
        ).firstOrNull { installed(it) }?.let { "Edge" to it }
        val firefox = listOf(
            TermuxBridge.PACKAGE_FIREFOX, TermuxBridge.PACKAGE_FIREFOX_BETA, TermuxBridge.PACKAGE_FIREFOX_NIGHTLY
        ).firstOrNull { installed(it) }?.let { "火狐" to it }
        return edge to firefox
    }

    private fun browserAddonUrl(label: String?): String =
        if (label == "火狐") TermuxBridge.URL_COOKIE_EDITOR_FIREFOX_ANDROID else TermuxBridge.URL_COOKIE_EDITOR_EDGE

    /** 取火狐主版本号（如 130.0 / 132.0b9 → 130 / 132）；读不到返回 0。 */
    private fun firefoxMajorVersion(pkg: String?): Int {
        if (pkg.isNullOrBlank()) return 0
        val v = runCatching { packageManager.getPackageInfo(pkg, 0).versionName }.getOrNull().orEmpty()
        return v.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
    }

    /** 读回上次检测到的浏览器包名，服务重启后继续用，不重新探测。 */
    private fun loadBrowserCache() {
        edgePkgCache = guidePrefs.getString("browser_edge_pkg", "").orEmpty()
        firefoxPkgCache = guidePrefs.getString("browser_firefox_pkg", "").orEmpty()
    }

    /** 只在 onCreate / 解锁 / S0 点「重新检测」时调用；探测失败保留旧缓存，不清空。 */
    private fun refreshBrowserCache() {
        val found = detectBrowsers()
        found.first?.let {
            edgePkgCache = it.second
            guidePrefs.edit().putString("browser_edge_pkg", it.second).apply()
        }
        found.second?.let {
            firefoxPkgCache = it.second
            guidePrefs.edit().putString("browser_firefox_pkg", it.second).apply()
        }
    }

    /** 渲染只看缓存，避免每次重绘都探测导致退回 S0；但缓存里的包已卸载时必须作废，否则会一直给错浏览器。 */
    private fun browsersFromCache(): Pair<Pair<String, String>?, Pair<String, String>?> {
        if (edgePkgCache.isNotEmpty() && !isPackageInstalled(edgePkgCache)) {
            edgePkgCache = ""
            guidePrefs.edit().remove("browser_edge_pkg").apply()
        }
        if (firefoxPkgCache.isNotEmpty() && !isPackageInstalled(firefoxPkgCache)) {
            firefoxPkgCache = ""
            guidePrefs.edit().remove("browser_firefox_pkg").apply()
        }
        val edge = edgePkgCache.takeIf { it.isNotEmpty() }?.let { "Edge" to it }
        val firefox = firefoxPkgCache.takeIf { it.isNotEmpty() }?.let { "火狐" to it }
        return edge to firefox
    }

    private fun isPackageInstalled(pkg: String): Boolean =
        runCatching { packageManager.getPackageInfo(pkg, 0) }.isSuccess

    /** Cookie-Editor 导出的 JSON 是 name/value 数组，用这个特征判断剪贴板里是不是它。 */
    private fun looksLikeCookieJson(text: String): Boolean {
        val t = text.trimStart()
        if (!t.startsWith("[") && !t.startsWith("{")) return false
        return t.contains("\"name\"") && t.contains("\"value\"")
    }

    private fun openApp(tab: Int? = null) {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (tab != null) putExtra(MainActivity.EXTRA_TAB, tab)
        }
        runCatching { startActivity(intent) }
        handler.post { render() }
    }

    /** 悬浮窗直接拉起半自动部署：VPN 已在本卡确认，App 内不再重复弹确认框 */
    private fun startDeployFromOverlay() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(MainActivity.EXTRA_DEPLOY_NOW, true)
        }
        runCatching { startActivity(intent) }
    }

    /** 悬浮窗直接拉起反代服务（部署完成但服务停了时使用） */
    private fun startServiceFromOverlay() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(MainActivity.EXTRA_START_SERVICE, true)
        }
        runCatching { startActivity(intent) }
    }

    private fun openAiStudio(browserPkg: String?) {
        if (browserPkg.isNullOrBlank()) {
            TermuxBridge.openUrl(this, TermuxBridge.URL_AISTUDIO)
        } else {
            TermuxBridge.openUrlInPackage(this, TermuxBridge.URL_AISTUDIO, browserPkg)
        }
    }

    /** 抓日志 + 当前屏幕文本，严格按当前选中的 AI 书签（自定义 / 本地反代）单路径分析错误。 */
    private fun runDiagnose() {
        val prefs = SettingsRepository(applicationContext)
        val settings = prefs.load()
        val serviceOnline = OverlayState.snapshot.online
        // 严格隔离：只使用用户在「解疑」页选中的那一个书签，不串用另一个途径
        val useLocal = prefs.agentUseLocal

        if (useLocal) {
            if (!serviceOnline) {
                showReport("当前 AI 书签为「本地反代」，但反代服务未运行。\n\n请先到「反代」页启动服务，或到「解疑」页底部把 AI 书签切回「自定义」。")
                return
            }
            if (prefs.agentLocalModel.isBlank()) {
                showReport("当前「本地反代」还没有选择模型。\n\n请打开 App →「解疑」页底部 → 选中「本地反代」→ 点「连接并拉取模型」选一个模型并保存。")
                return
            }
        } else {
            if (prefs.agentApiKey.isBlank()) {
                showReport("当前 AI 书签为「自定义」，但还没有填写 API Key。\n\n请打开 App →「解疑」页底部配置 API Key，或切到「本地反代」书签。")
                return
            }
            if (prefs.agentModel.isBlank()) {
                showReport("当前「自定义」还没有选择模型。\n\n请打开 App →「解疑」页底部 → 点「连接并拉取模型」选一个模型并保存。")
                return
            }
        }

        val sourceLabel = if (useLocal) "本地反代 (${prefs.agentLocalModel})" else "自定义 (${prefs.agentModel})"
        showReport("正在读取后台日志和屏幕内容，调用 $sourceLabel 分析中，请稍等…")

        diagnoseJob?.cancel()
        diagnoseJob = diagnosisScope.launch {
            val config = AgentConfig(
                baseUrl = prefs.agentBaseUrl,
                apiKey = prefs.agentApiKey,
                model = prefs.agentModel,
                useLocalProxy = useLocal,
                localBaseUrl = "http://127.0.0.1:${settings.port}",
                localModel = prefs.agentLocalModel,
                localApiKey = prefs.agentLocalKey.ifBlank { "aistudio-api" }
            )
            val log = LogCollector.getLog(120)
            val screen = AutomationController.captureActiveWindowText()
            val snap = OverlayState.snapshot
            val payload = buildString {
                appendLine("【当前反代状态】在线=${snap.online}，端口=${settings.port}，阶段=${snap.stage}，已知报错=${snap.error ?: "无"}")
                appendLine("【最近命令日志】")
                appendLine(log.ifBlank { "（空）" })
                appendLine("【当前屏幕文本】")
                appendLine(screen.take(2000).ifBlank { "（未开启无障碍或读不到屏幕文本）" })
            }
            val answer = runCatching {
                AgentEngine().run(
                    config = config,
                    problem = payload,
                    mode = AgentMode.DIAGNOSE,
                    execute = { _, _ -> "" },
                    onLine = { }
                )
            }.getOrElse { "识别失败：${it.message}" }
            showReport(answer.trim())
            OverlayState.update(
                OverlayState.snapshot.copy(
                    aiHint = answer.lineSequence().firstOrNull()?.take(80)
                )
            )
        }
    }

    /** 放大悬浮窗（最大约分屏尺寸）并显示报错分析结果，字多可上下滑动，点 ✕ 恢复原形。 */
    private fun showReport(text: String) {
        val params = panelParams ?: return
        if (!reportMode) {
            savedX = params.x
            savedY = params.y
        }
        reportMode = true
        expanded = true
        detailsBox?.visibility = View.VISIBLE
        aiText?.text = "AI 报错分析（可上下滑动，点右上角 ✕ 恢复）：\n\n$text"
        aiText?.visibility = View.VISIBLE
        elapsedText?.visibility = View.VISIBLE
        closeButton?.visibility = View.VISIBLE

        detailsScroll?.let { sv ->
            sv.visibility = View.VISIBLE
            sv.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            sv.scrollTo(0, 0)
        }

        val dm = resources.displayMetrics
        params.width = (dm.widthPixels * 0.92f).toInt()
        params.height = (dm.heightPixels * 0.50f).toInt()
        params.x = (dm.widthPixels * 0.04f).toInt()
        params.y = dp(48)
        root?.let { view ->
            runCatching { windowManager?.updateViewLayout(view, params) }
        }
    }

    /** 点 ✕ 收起报错面板，悬浮窗恢复原来的小尺寸与位置。 */
    private fun exitReportMode() {
        reportMode = false
        expanded = false
        detailsBox?.visibility = View.GONE
        detailsScroll?.let { sv ->
            sv.visibility = View.GONE
            sv.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        closeButton?.visibility = View.GONE
        val params = panelParams ?: return
        params.width = dp(280)
        params.height = WindowManager.LayoutParams.WRAP_CONTENT
        params.x = savedX
        params.y = savedY
        root?.let { view ->
            runCatching { windowManager?.updateViewLayout(view, params) }
        }
    }

    private fun startForegroundSafely() {
        val channelId = "overlay_status"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager?.getNotificationChannel(channelId) == null) {
                manager?.createNotificationChannel(
                    NotificationChannel(
                        channelId,
                        "悬浮窗状态",
                        NotificationManager.IMPORTANCE_MIN
                    )
                )
            }
        }
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("反代助手悬浮窗")
            .setContentText("正在显示状态，点「🩺 报错」可调用 AI 分析错误")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(true)
            .build()
        startForeground(2001, notification)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            if (!Settings.canDrawOverlays(context)) return
            val intent = Intent(context, OverlayService::class.java)
            ContextCompat.startForegroundService(context, intent)
            isRunning = true
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
            isRunning = false
        }
    }
}
