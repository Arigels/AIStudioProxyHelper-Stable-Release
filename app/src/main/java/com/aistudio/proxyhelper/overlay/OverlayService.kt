package com.aistudio.proxyhelper.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
    val aiHint: String? = null
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

    /** 悬浮窗教程进度：0=还没在浏览器里打开，1=已在浏览器里取 Cookie，2=已复制好待导入 */
    private var tutorialStage = 0

    /** 上一阶段用的浏览器名（Edge / 火狐），用于只显示对应那套点击路径 */
    private var tutorialBrowser: String? = null

    /** S1 主按钮打开失败时的红色提示行 */
    private var tutorialFailHint: String? = null

    /** S2 是否已弹出 VPN 确认卡 */
    private var vpnConfirmShown = false

    /** 酒馆向导是否已弹出完成确认卡 */
    private var tavernConfirmShown = false

    /** 打开 Termux 失败时保留红色提示，避免下一次重渲染把它冲掉 */
    private var tavernFailHint = false

    /** 浏览器包名缓存：检测成功才覆盖，检测失败不动旧值（只降级不归零） */
    private var edgePkgCache: String = ""
    private var firefoxPkgCache: String = ""

    private val guidePrefs by lazy {
        getSharedPreferences("aistudio_tutorial", MODE_PRIVATE)
    }

    /** 酒馆向导是否已完成（持久化在第 7 步接入，先用内存值） */
    private var tavernDone = false

    /** 一次性拉模型提示是否已关闭（持久化在第 7 步接入，先用内存值） */
    private var modelTipDismissed = false

    private val clipWatcher = ClipboardManager.OnPrimaryClipChangedListener {
        val text = runCatching { TermuxBridge.readFromClipboard(this) }.getOrNull().orEmpty()
        cookieClipLength = if (looksLikeCookieJson(text)) text.length else 0
        if (cookieClipLength > 0 && OverlayState.snapshot.online && !OverlayState.snapshot.accountsReady) {
            tutorialStage = 4
        }
        handler.post { render() }
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            highlightPulse = !highlightPulse
            render()
            handler.postDelayed(this, 1500L)
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
        val prefs = getSharedPreferences("overlay_guide", MODE_PRIVATE)
        tavernDone = prefs.getBoolean("tavern_guide_done", false)
        modelTipDismissed = prefs.getBoolean("model_tip_dismissed", false)
        loadBrowserCache()
        refreshBrowserCache()
        buildViews()
        runCatching {
            (getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager)
                ?.addPrimaryClipChangedListener(clipWatcher)
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
            setOnClickListener { tutorialBrowser = "Edge"; tutorialFailHint = null; render() }
        }
        val firefoxSwitch = TextView(this).apply {
            text = "使用 火狐"
            setTextColor(Color.parseColor("#93C5FD"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, 0, 0, 0)
            setOnClickListener { tutorialBrowser = "火狐"; tutorialFailHint = null; render() }
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
                getSharedPreferences("overlay_guide", MODE_PRIVATE)
                    .edit().putBoolean("model_tip_dismissed", true).apply()
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
        panel.addView(tutorial)
        panel.addView(switchRow)
        panel.addView(tutorialBtn)
        panel.addView(tutorialAltBtn)
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

        // 部署完成但还没导入账号：进入「高亮提醒去获取 Cookie」模式
        val needCookie = snapshot.online && !snapshot.accountsReady
        // 账号已就绪但酒馆向导还没走完：进入酒馆引导模式
        val tavernMode = snapshot.online && snapshot.accountsReady && !tavernDone
        // 向导模式期间精简悬浮窗，隐藏 AI 行与已用时行（不影响报错放大视图）
        val guideMode = needCookie || tavernMode

        val color = when {
            snapshot.error != null -> "#EF4444"
            needCookie -> "#22C55E"
            snapshot.online -> "#22C55E"
            OverlayState.deployStartedAt > 0L -> "#F59E0B"
            else -> "#94A3B8"
        }
        (dot?.background as? GradientDrawable)?.setColor(Color.parseColor(color))

        titleText?.text = when {
            snapshot.error != null -> "反代助手 · 出错了"
            needCookie -> "反代助手 · 部署完成 🎉"
            snapshot.online -> "反代助手 · 运行中 :${snapshot.port}"
            OverlayState.deployStartedAt > 0L -> "反代助手 · 搭建中"
            else -> "反代助手"
        }

        stageText?.text = when {
            snapshot.error != null -> "阶段：失败（${snapshot.error}）"
            needCookie -> "阶段：服务已启动，还差导入 Cookie"
            snapshot.online -> "阶段：服务已启动"
            else -> "阶段：${snapshot.stage}"
        }

        if (!reportMode) {
            if (needCookie) {
                // 闪动高亮：文字颜色与底色在两种状态间切换，确保一眼看到
                guideText?.text = "👉 还差 1 步：拿 Cookie，照下面提示点就行"
                guideText?.setTextColor(
                    Color.parseColor(if (highlightPulse) "#FDE047" else "#86EFAC")
                )
                guideText?.setTextSize(
                    TypedValue.COMPLEX_UNIT_SP,
                    if (highlightPulse) 13f else 12.5f
                )
                guideText?.setTypeface(guideText?.typeface, Typeface.BOLD)
                guideText?.setPadding(dp(8), dp(6), dp(8), dp(6))
                guideText?.background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(Color.parseColor(if (highlightPulse) "#33FDE047" else "#2A22C55E"))
                    setStroke(dp(1), Color.parseColor("#66FDE047"))
                }
            } else {
                guideText?.text = "👉 ${snapshot.guidance}"
                guideText?.setTextColor(Color.parseColor("#93C5FD"))
                guideText?.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                guideText?.setTypeface(null, Typeface.NORMAL)
                guideText?.setPadding(0, dp(4), 0, 0)
                guideText?.background = null
            }

            aiText?.text = snapshot.aiHint?.let { "AI：$it" }
                ?: if (needCookie) {
                    "AI：部署已成功。建议现在去「账号」页导入 Cookie，模型列表才能刷出来。"
                } else {
                    "AI：还没有结论，点「🩺 报错」让我自动分析"
                }
        }

        elapsedText?.text = when {
            needCookie -> "已用时 ${elapsedSec / 60} 分 ${elapsedSec % 60} 秒 · 拖动可移动"
            OverlayState.deployStartedAt > 0L ->
                "已用时 ${elapsedSec / 60} 分 ${elapsedSec % 60} 秒 · 拖动可移动"
            else -> "拖动可移动 · 点「🩺 报错」自动分析后台错误"
        }

        // 向导模式精简悬浮窗：隐藏 AI 行与已用时行；报错放大（reportMode）照常显示
        if (!reportMode) {
            val guideVisibility = if (guideMode) View.GONE else View.VISIBLE
            aiText?.visibility = guideVisibility
            elapsedText?.visibility = guideVisibility
        }

        if (!expanded && !reportMode) {
            detailsBox?.visibility = View.GONE
            detailsScroll?.visibility = View.GONE
        }

        if (!needCookie) {
            cookieClipLength = 0
            tutorialStage = 0
            tutorialBrowser = null
            vpnConfirmShown = false
            tutorialFailHint = null
        }
        when {
            needCookie -> renderTutorial(!reportMode)
            tavernMode -> renderTavernGuide(!reportMode)
            else -> {
                renderTutorial(false)
                renderTavernGuide(false)
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
            tut.visibility = View.GONE
            btn?.visibility = View.GONE
            altBtn?.visibility = View.GONE
            switchRow?.visibility = View.GONE
            continueRow?.visibility = View.GONE
            return
        }
        tut.visibility = View.VISIBLE

        val (edge, firefox) = browsersFromCache()
        val detected = edge ?: firefox
        // 已手动切换到某一类、但该类未安装时，回退到检测到的那一类
        val chosen = when (tutorialBrowser) {
            "Edge" -> edge ?: detected
            "火狐" -> firefox ?: detected
            else -> detected
        }
        // S1 之前若被切到未安装的那一类，纠正回检测结果
        if (tutorialBrowser != null && chosen?.first != tutorialBrowser) tutorialBrowser = chosen?.first
        val browserLabel = chosen?.first
        val browserPkg = chosen?.second
        val bothInstalled = edge != null && firefox != null
        if (tutorialStage == 0 && chosen != null) tutorialStage = 1
        if (cookieClipLength > 0) tutorialStage = 4

        tut.setTypeface(null, Typeface.NORMAL)
        tut.background = tutorialPanelBackground()
        continueRow?.visibility = View.GONE
        switchRow?.visibility = if (bothInstalled) View.VISIBLE else View.GONE
        tutorialEdgeSwitch?.let {
            it.setTextColor(Color.parseColor(if (browserLabel == "Edge") "#FDE047" else "#93C5FD"))
            it.setTypeface(it.typeface, if (browserLabel == "Edge") Typeface.BOLD else Typeface.NORMAL)
        }
        tutorialFirefoxSwitch?.let {
            it.setTextColor(Color.parseColor(if (browserLabel == "火狐") "#FDE047" else "#93C5FD"))
            it.setTypeface(it.typeface, if (browserLabel == "火狐") Typeface.BOLD else Typeface.NORMAL)
        }

        when (tutorialStage) {
            4 -> renderTutorialStage4(tut, btn, altBtn, continueRow)
            1 -> renderTutorialStage1(tut, btn, altBtn, continueRow, browserLabel, browserPkg)
            2 -> renderTutorialStage2(tut, btn, altBtn, continueRow, browserLabel, browserPkg)
            3 -> renderTutorialStage3(tut, btn, altBtn, continueRow, browserLabel, browserPkg)
            0 -> renderTutorialStage0(tut, btn, altBtn)
            else -> {
                tut.text = "① 用 ${browserLabel ?: "浏览器"} 打开 aistudio.google.com 并登录"
                tut.setTextColor(Color.parseColor("#DBEAFE"))
                btn?.visibility = View.VISIBLE
                btn?.text = "🌐 在 $browserLabel 打开 AI Studio"
                altBtn?.visibility = View.GONE
            }
        }
    }

    /** S0：没检测到 Edge / 火狐，引导下载官网；不显示「打开 AI Studio」与「装好点这里」。 */
    private fun renderTutorialStage0(
        tut: TextView,
        btn: TextView?,
        altBtn: TextView?
    ) {
        tut.text = "请安装一个支持扩展的浏览器（手机默认浏览器、Chrome 等不支持扩展）"
        tut.setTextColor(Color.parseColor("#DBEAFE"))
        btn?.visibility = View.VISIBLE
        btn?.text = "下载 Edge（官网）"
        btn?.setOnClickListener { TermuxBridge.openUrl(this, TermuxBridge.URL_EDGE_ANDROID) }
        altBtn?.visibility = View.VISIBLE
        altBtn?.text = "下载火狐（官网）"
        altBtn?.setOnClickListener { TermuxBridge.openUrl(this, TermuxBridge.URL_FIREFOX_ANDROID) }
    }

    /** S1：检测到浏览器、扩展还没装；主按钮用指定浏览器打开扩展页，次按钮复制链接。 */
    private fun renderTutorialStage1(
        tut: TextView,
        btn: TextView?,
        altBtn: TextView?,
        continueRow: LinearLayout?,
        browserLabel: String?,
        browserPkg: String?
    ) {
        val label = browserLabel ?: "Edge"
        tut.text = if (label == "火狐") {
            "① 打开 火狐浏览器，安装 Cookie-Editor 扩展"
        } else {
            "① 打开 Edge，安装 Cookie-Editor 扩展"
        }
        tut.setTextColor(if (tutorialFailHint != null) Color.parseColor("#FCA5A5") else Color.parseColor("#DBEAFE"))
        val addonUrl = browserAddonUrl(label)
        btn?.visibility = View.VISIBLE
        btn?.text = if (label == "火狐") "在 火狐 打开扩展安装页" else "在 Edge 打开扩展安装页"
        btn?.setOnClickListener {
            val ok = TermuxBridge.openUrlInPackage(this, addonUrl, browserPkg)
            if (!ok) {
                tutorialFailHint = label
                render()
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
        tut.setOnClickListener(null)
        renderTutorialContinueLink(continueRow)
    }

    /** S2：打开 AI Studio；先原地弹 VPN 确认卡，确认后才用指定浏览器打开。 */
    private fun renderTutorialStage2(
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
                TermuxBridge.openUrlInPackage(this, TermuxBridge.URL_AISTUDIO, browserPkg)
                vpnConfirmShown = false
                tutorialStage = 3
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
        tut.text = "② 打开 AI Studio 并登录（需要能访问 Google）"
        tut.setTextColor(Color.parseColor("#DBEAFE"))
        btn?.visibility = View.VISIBLE
        btn?.text = if (label == "火狐") "用 火狐 打开 AI Studio" else "用 Edge 打开 AI Studio"
        btn?.setOnClickListener { vpnConfirmShown = true; render() }
        altBtn?.visibility = View.VISIBLE
        altBtn?.text = "复制 AI Studio 链接"
        altBtn?.setOnClickListener {
            TermuxBridge.copyToClipboard(this, "AI Studio 链接", TermuxBridge.URL_AISTUDIO)
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
            return
        }
        tut.visibility = View.VISIBLE
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
                getSharedPreferences("overlay_guide", MODE_PRIVATE)
                    .edit().putBoolean("tavern_guide_done", true).apply()
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

    /** S4：剪贴板命中 Cookie JSON 后绿色完成态；读不到剪贴板时也可从「打开 App」推进到本步。 */
    private fun renderTutorialStage4(
        tut: TextView,
        btn: TextView?,
        altBtn: TextView?,
        continueRow: LinearLayout?
    ) {
        continueRow?.visibility = View.GONE
        val head = if (cookieClipLength > 0) "✅ Cookie 已复制（${cookieClipLength} 字符）\n" else ""
        tut.text = "${head}⑤ 回 App →「账号」页 → 点「粘贴剪贴板」"
        tut.setTextColor(Color.parseColor(if (highlightPulse) "#FDE047" else "#86EFAC"))
        tut.setTypeface(null, Typeface.BOLD)
        tut.background = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(Color.parseColor(if (highlightPulse) "#33FDE047" else "#2A22C55E"))
            setStroke(dp(1), Color.parseColor("#66FDE047"))
        }
        btn?.visibility = View.VISIBLE
        btn?.text = "📲 打开 App 去粘贴"
        btn?.setOnClickListener { openApp() }
        altBtn?.visibility = View.GONE
    }

    /** S3：导出 Cookie-Editor JSON；按浏览器给对应菜单路径，按钮直接重开 AI Studio（不再弹 VPN 卡）。 */
    private fun renderTutorialStage3(
        tut: TextView,
        btn: TextView?,
        altBtn: TextView?,
        continueRow: LinearLayout?,
        browserLabel: String?,
        browserPkg: String?
    ) {
        continueRow?.visibility = View.GONE
        val path = if (browserLabel == "火狐") {
            "③ 点右上角竖着的「⋮」（三个点）→「扩展」→ Cookie-Editor"
        } else {
            "③ 点底部「≡」（三横）→「扩展」→ Cookie-Editor"
        }
        tut.text = "$path\n④ 点 Export → 选 Export as JSON（会自动复制）"
        tut.setTextColor(Color.parseColor("#DBEAFE"))
        btn?.visibility = View.VISIBLE
        btn?.text = "🔁 再开一次 AI Studio"
        btn?.setOnClickListener {
            TermuxBridge.openUrlInPackage(this, TermuxBridge.URL_AISTUDIO, browserPkg)
        }
        altBtn?.visibility = View.GONE
    }

    /** S1 推进链接：装好扩展后点这里进入下一步（S2 打开 AI Studio）。 */
    private fun renderTutorialContinueLink(host: LinearLayout?) {
        host ?: return
        host.removeAllViews()
        val link = TextView(this).apply {
            text = "装好点这里 →"
            setTextColor(Color.parseColor("#93C5FD"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(dp(8), dp(2), dp(8), dp(2))
            setOnClickListener { tutorialStage = 2; tutorialFailHint = null; render() }
        }
        host.addView(link)
        host.visibility = View.VISIBLE
    }

    private fun tutorialPanelBackground(): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(8).toFloat()
        setColor(Color.parseColor("#14FFFFFF"))
    }

    /** 找已安装的可用浏览器：Edge 优先，其次火狐。 */
    private fun detectBrowser(): Pair<String, String>? {
        fun installed(pkg: String): Boolean = runCatching { packageManager.getPackageInfo(pkg, 0) }.isSuccess
        val candidates = listOf(
            "Edge" to TermuxBridge.PACKAGE_EDGE,
            "Edge" to TermuxBridge.PACKAGE_EDGE_BETA,
            "Edge" to TermuxBridge.PACKAGE_EDGE_CANARY,
            "火狐" to TermuxBridge.PACKAGE_FIREFOX,
            "火狐" to TermuxBridge.PACKAGE_FIREFOX_BETA,
            "火狐" to TermuxBridge.PACKAGE_FIREFOX_NIGHTLY
        )
        return candidates.firstOrNull { installed(it.second) }
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
        if (label == "火狐") TermuxBridge.URL_COOKIE_EDITOR else TermuxBridge.URL_COOKIE_EDITOR_EDGE

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

    /** 渲染只看缓存，避免每次重绘都探测导致退回 S0。 */
    private fun browsersFromCache(): Pair<Pair<String, String>?, Pair<String, String>?> {
        val edge = edgePkgCache.takeIf { it.isNotEmpty() }?.let { "Edge" to it }
        val firefox = firefoxPkgCache.takeIf { it.isNotEmpty() }?.let { "火狐" to it }
        return edge to firefox
    }

    /** Cookie-Editor 导出的 JSON 是 name/value 数组，用这个特征判断剪贴板里是不是它。 */
    private fun looksLikeCookieJson(text: String): Boolean {
        val t = text.trimStart()
        if (!t.startsWith("[") && !t.startsWith("{")) return false
        return t.contains("\"name\"") && t.contains("\"value\"")
    }

    private fun openApp() {
        // 已经从浏览器那一步过来、但剪贴板内容读不到时，也把教程推进到「回 App 粘贴」这一步
        val snapshot = OverlayState.snapshot
        if (snapshot.online && !snapshot.accountsReady && tutorialStage in 1..3) tutorialStage = 4
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { startActivity(intent) }
        handler.post { render() }
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
