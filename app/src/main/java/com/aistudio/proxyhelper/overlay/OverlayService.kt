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
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ImageSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.aistudio.proxyhelper.MainActivity
import com.aistudio.proxyhelper.R
import com.aistudio.proxyhelper.agent.AgentConfig
import com.aistudio.proxyhelper.agent.AgentEngine
import com.aistudio.proxyhelper.agent.AgentMode
import com.aistudio.proxyhelper.automation.AutomationController
import com.aistudio.proxyhelper.data.ErrorCodeRules
import com.aistudio.proxyhelper.data.ErrorFinding
import com.aistudio.proxyhelper.data.ErrorKind
import com.aistudio.proxyhelper.data.SettingsRepository
import com.aistudio.proxyhelper.net.LogCollector
import com.aistudio.proxyhelper.termux.TermuxBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val a11yEnabled: Boolean = false,
    /** 运行时完整本机地址（scheme + host + port）；为空时 UI 显示「地址获取中」，不拼默认值 */
    val localBaseUrl: String = ""
)

object OverlayState {
    @Volatile
    var deployStartedAt: Long = 0L

    @Volatile
    var snapshot: OverlaySnapshot = OverlaySnapshot()

    /** 最近一次收到 App 状态快照的时刻（0 = 本进程还从未收到过） */
    @Volatile
    var lastStatusAt: Long = 0L

    /** App 主界面心跳是 6 秒一次；超过 20 秒没收到就不能再断言「反代断线」 */
    private const val STATUS_TIMEOUT_MS = 20_000L

    fun update(snapshot: OverlaySnapshot) {
        this.snapshot = snapshot
        lastStatusAt = System.currentTimeMillis()
    }

    /** 状态是否已失联（App 进程被杀 / 主界面长时间没轮询到） */
    fun statusStale(now: Long = System.currentTimeMillis()): Boolean =
        lastStatusAt == 0L || now - lastStatusAt > STATUS_TIMEOUT_MS

    private val logScanLock = Any()
    private var lastScannedTail: List<String> = emptyList()
    private val lastRefreshedAtByKind = mutableMapOf<ErrorKind, Long>()

    @Volatile
    var lastErrorFinding: ErrorFinding? = null
        private set

    @Volatile
    var lastErrorAt: Long = 0L
        private set

    private val _lastErrorState = MutableStateFlow<Pair<ErrorFinding?, Long?>>(null to null)
    val lastErrorState: StateFlow<Pair<ErrorFinding?, Long?>> = _lastErrorState.asStateFlow()
    /** B09：按 acc_ ID 保留每个账号最近一次服务端错误，供账号页标注异常状态。 */
    private val _accountErrorMarks = MutableStateFlow<Map<String, Pair<ErrorFinding, Long>>>(emptyMap())
    val accountErrorMarks: StateFlow<Map<String, Pair<ErrorFinding, Long>>> = _accountErrorMarks.asStateFlow()
    private val _accountMarkBaselines = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /**
     * B09：该账号仍需显示的异常标记。判据是「累计成功次数是否增长」，不是时间：
     * 标记写入时记下当时的成功次数作基线，之后成功次数增长即视为账号已恢复、标记消失。
     * 30 分钟一直没成功才兜底隐藏，防止标记永久挂在卡片上。
     */
    fun accountMarkFor(accId: String, totalSuccess: Int): ErrorFinding? {
        val map = _accountErrorMarks.value
        val mark = map[accId] ?: return null
        val baseline = _accountMarkBaselines.getOrPut(accId) { totalSuccess }
        if (totalSuccess > baseline) {
            _accountErrorMarks.value = map - accId
            _accountMarkBaselines.remove(accId)
            return null
        }
        if (System.currentTimeMillis() - mark.second >= 30 * 60 * 1000L) return null
        return mark.first
    }

    /**
     * 增量扫描 LogCollector 最近 60 行：只处理新增行，同 kind 60 秒内只刷新一次，新 kind 覆盖旧的。
     */
    fun scanIncrementalLogs(now: Long = System.currentTimeMillis()) {
        val rawLog = LogCollector.getLog(60)
        synchronized(logScanLock) {
            if (rawLog.isBlank()) {
                lastScannedTail = emptyList()
                return
            }
            val currentLines = rawLog.lines().filter { it.isNotBlank() }
            if (currentLines.isEmpty()) {
                lastScannedTail = emptyList()
                return
            }
            val maxOverlap = minOf(lastScannedTail.size, currentLines.size)
            var overlap = 0
            for (k in maxOverlap downTo 1) {
                if (lastScannedTail.subList(lastScannedTail.size - k, lastScannedTail.size) ==
                    currentLines.subList(0, k)
                ) {
                    overlap = k
                    break
                }
            }
            val newLines = if (overlap > 0) currentLines.drop(overlap) else currentLines
            lastScannedTail = currentLines
            for (line in newLines) {
                val finding = ErrorCodeRules.scanLogLine(line) ?: continue
                val current = lastErrorFinding
                val sameError = current != null &&
                    current.kind == finding.kind &&
                    current.title == finding.title
                if (!sameError || now - lastErrorAt >= 5 * 60 * 1000L) {
                    lastRefreshedAtByKind[finding.kind] = now
                    lastErrorFinding = finding
                    lastErrorAt = now
                    _lastErrorState.value = finding to now
                }
                val accId = finding.account
                if (accId != null) {
                    _accountErrorMarks.value = _accountErrorMarks.value + (accId to (finding to now))
                    _accountMarkBaselines.remove(accId)
                }
            }
        }
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
    private var minimizeButton: TextView? = null
    private var fullContent: LinearLayout? = null
    private var titleRow: LinearLayout? = null
    private var miniBar: LinearLayout? = null
    private var miniDot: View? = null
    private var miniText: TextView? = null
    private var miniAddress: TextView? = null
    private var proxyAddressRow: LinearLayout? = null
    private var proxyAddressText: TextView? = null
    private var addressTipBox: LinearLayout? = null
    private var addressTipValue: TextView? = null
    private var reportMode = false
    private var savedX = 0
    private var savedY = 0

    private var root: LinearLayout? = null
    private var dot: View? = null
    private var titleText: TextView? = null
    private var stageText: TextView? = null
    private var lastErrorMainText: TextView? = null
    private var lastErrorNextText: TextView? = null
    private var lastErrorSolveButton: TextView? = null
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

    /** B08：Termux 环境自检结论。UNKNOWN 表示没测到，按原流程走。 */
    private enum class EnvVerdict { UNKNOWN, CHECKING, OK, NEED_PY, NEED_UV }

    private var envVerdict = EnvVerdict.UNKNOWN
    private var envProbeSent = false
    private var envProbeAt = 0L
    private var envRetryCount = 0
    private var tutorialEdgeSwitch: TextView? = null
    private var tutorialFirefoxSwitch: TextView? = null
    private var detailsBox: LinearLayout? = null
    private var aiText: TextView? = null
    private var elapsedText: TextView? = null

    private var running = true

    /** 详情区（AI 结论 / 已用时）是否展开，和窗口「收起成长条」是两件事 */
    private var detailsExpanded = false

    /** 悬浮窗是否收成横向长条；持久化，重新拉起悬浮窗仍保持 */
    private var minimized = false

    /** 上次应用的收起态；避免每次 render 都 updateViewLayout */
    private var chromeApplied: Boolean? = null

    /** 部署完成待导入 Cookie 时，引导文字闪烁高亮用 */
    private var highlightPulse = false

    /** 剪贴板里已确认是 Cookie-Editor 导出的 JSON 时的字符数（读不到剪贴板时为 0） */
    private var cookieClipLength = 0

    /** 上一阶段用的浏览器名（Edge / 火狐），用于只显示对应那套点击路径 */
    private var tutorialBrowser: String? = null

    /** S1 主按钮打开失败时的红色提示行 */
    private var tutorialFailHint: String? = null

    /** 部署步是否已弹出 VPN 确认卡 */
    private var deployVpnConfirmShown = false

    /** S3 点「复制 AI Studio 链接」前的覆盖确认：避免把剪贴板里的 Cookie JSON 冲成网址 */
    private var linkCopyConfirmShown = false

    /** S3 打开浏览器失败时的红字提示（成功时清空） */
    private var studioOpenError: String? = null

    /** S3 成功打开浏览器的时刻；60 秒内在卡里回显「已打开，去登录导出」 */
    private var studioOpenedAt = 0L

    /** S4→S5 的证据闸门：用户本次明确点过「已导出，去粘贴」才允许停在粘贴步 */
    private var pasteStepConfirmed: Boolean
        get() = guidePrefs.getBoolean("paste_step_confirmed", false)
        set(value) = guidePrefs.edit().putBoolean("paste_step_confirmed", value).apply()

    /** 刚点过「开始部署」的时刻：Activity 启动前 20 秒内按部署中显示，避免卡片闪回 */
    private var deployTapAt = 0L

    /** 打开 Termux 失败时保留红色提示，避免下一次重渲染把它冲掉 */
    private var tavernFailHint = false

    /** 浏览器包名缓存：检测成功才覆盖，检测失败不动旧值（只降级不归零） */
    private var edgePkgCache: String = ""
    private var firefoxPkgCache: String = ""

    /** online 变 false 的时刻；恢复 true 立即清零。心跳 6 秒，阈值取 15 秒≈丢两次以上 */
    private var offlineSince: Long = 0L

    /** 本次服务运行期间是否已经收到过「在线」状态；用于排除服务刚拉起时那次假掉线 */
    private var sawOnlineThisRun = false

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

    /** 复制地址卡片已点「完成」：持久化，教程永久不再出现 */
    private var tavernAddressTipDone: Boolean
        get() = guidePrefs.getBoolean("tavern_address_tip_done", false)
        set(value) = guidePrefs.edit().putBoolean("tavern_address_tip_done", value).apply()

    /** 登录已确认：合并卡不再有「登录好了」，现在只由剪贴板命中 Cookie JSON 反推 */
    private var loginStepDone: Boolean
        get() = guidePrefs.getBoolean("login_step_done", false)
        set(value) = guidePrefs.edit().putBoolean("login_step_done", value).apply()

    /**
     * 教程到达的最远步骤：0 部署、1 浏览器、2 扩展、3 AI Studio + Cookie、4 粘贴；
     * 常规写入只增不减（防止来回跳）；需要回退时走 forceGuideStage()。
     */
    private var guideStageMax: Int
        get() = guidePrefs.getInt("guide_stage_max", 0)
        set(value) {
            val old = guidePrefs.getInt("guide_stage_max", 0)
            if (value > old) guidePrefs.edit().putInt("guide_stage_max", value).apply()
        }

    /** 直接覆盖步骤（迁移 / 回退 / 证据闸门用）。常规 setter 只增不减，不能用来回退。 */
    private fun forceGuideStage(stage: Int) {
        val target = stage.coerceIn(0, 4)
        if (guidePrefs.getInt("guide_stage_max", 0) == target) return
        guidePrefs.edit().putInt("guide_stage_max", target).apply()
    }

    /** 扩展已确认安装（安卓读不到浏览器扩展列表，只能手动确认或剪贴板反推） */
    private var guideExtInstalled: Boolean
        get() = guidePrefs.getBoolean("guide_ext_installed", false)
        set(value) = guidePrefs.edit().putBoolean("guide_ext_installed", value).apply()

    /**
     * 引导状态迁移到 guide_schema = 2。
     *
     * 2.6.16 的一次性迁移把旧 cookie_max_stage 原样搬成新步骤（4 -> 4），
     * 老用户升级后直接落在「粘贴」步，跳过了部署 / 浏览器 / 扩展，而且从没真正导出过 Cookie。
     * 这里按「证据」重算：
     *   cookie_done = true  -> 5
     *   旧 0                -> 0
     *   旧 1                -> 2（浏览器装过了）
     *   旧 2 / 3            -> 3（扩展装过了）
     *   旧 4 / 5            -> 3（回 Cookie 步，等真实剪贴板命中或手动确认才进 4）
     * 另外：从没部署成功过的用户不进 Cookie 步，先回第 1 步部署。
     * 注意 2.6.16 已写入 guide_migrated=true，所以这里判的是 guide_schema，不是 guide_migrated。
     */
    private fun migrateGuidePrefs() {
        if (guidePrefs.getInt("guide_schema", 1) >= GUIDE_SCHEMA) return
        val hasOld = guidePrefs.contains("cookie_max_stage")
        val old = guidePrefs.getInt("cookie_max_stage", 0)
        val current = guidePrefs.getInt("guide_stage_max", 0)
        val done = guidePrefs.getBoolean("cookie_done", false)
        // 2.6.16 已经把 4 写进 guide_stage_max，取两者较大值当作「旧进度」再降级
        val legacy = maxOf(
            if (hasOld) old else 0,
            if (guidePrefs.getBoolean("guide_migrated", false)) current else 0
        )
        val everDeployed = SettingsRepository(applicationContext).hasDeployed ||
            guidePrefs.getBoolean("proxy_ever_online", false)

        var stage = when {
            done -> 5
            legacy <= 0 -> 0
            legacy == 1 -> 2
            else -> 4
        }
        if (!done && stage >= 2 && !everDeployed) stage = 0
        val extInstalled = !done && hasOld && old >= 2

        guidePrefs.edit()
            .putInt("guide_stage_max", stage)
            .putBoolean("guide_ext_installed", extInstalled)
            .putBoolean("paste_step_confirmed", false)
            .putInt("guide_schema", GUIDE_SCHEMA)
            .apply()
        LogCollector.append(
            "[引导] 迁移 guide_schema=$GUIDE_SCHEMA：old=$old current=$current done=$done " +
                "everDeployed=$everDeployed -> stage=$stage"
        )
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
        refreshClipboardState()
        handler.post { render() }
    }

    /**
     * 读一次剪贴板并更新 cookieClipLength。
     * 安卓 10+ 后台读剪贴板可能直接返回空，所以这里只是「尽力而为」：
     * 读不到就算了，主路径是第 4 步的「去粘贴」和第 5 步的「打开App并粘贴Cookie」。
     */
    private fun refreshClipboardState() {
        val text = runCatching { TermuxBridge.readFromClipboard(this) }.getOrNull().orEmpty()
        val looksCookie = looksLikeCookieJson(text)
        cookieClipLength = if (looksCookie) text.length else 0
        val deployedEver = SettingsRepository(applicationContext).hasDeployed ||
            SettingsRepository(applicationContext).deploySucceeded ||
            proxyEverOnline
        if (looksCookie && !cookieDone && deployedEver) {
            // 能导出 JSON 说明扩展已经装好、也登录过了；直接推进到粘贴步，不等用户手点
            guideExtInstalled = true
            loginStepDone = true
            guideStageMax = 4
            LogCollector.append("[引导] 剪贴板命中 Cookie JSON（${text.length} 字符）")
        }
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

    private val logScanRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            if (OverlayState.snapshot.online) {
                val prevFinding = OverlayState.lastErrorFinding
                val prevAt = OverlayState.lastErrorAt
                OverlayState.scanIncrementalLogs()
                if (OverlayState.lastErrorFinding != prevFinding || OverlayState.lastErrorAt != prevAt) {
                    render()
                }
            }
            handler.postDelayed(this, LOG_SCAN_INTERVAL_MS)
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
        migrateGuidePrefs()
        loadBrowserCache()
        refreshBrowserCache()
        buildViews()
        minimized = guidePrefs.getBoolean("overlay_minimized", false)
        applyOverlaySize()
        runCatching {
            (getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager)
                ?.addPrimaryClipChangedListener(clipWatcher)
        }
        // 服务重建后不会再收到「原来那次」剪贴板变化回调：先主动读一次，
        // 避免 Cookie 已经在剪贴板里、界面却还停在导出步
        runCatching { refreshClipboardState() }
        runCatching {
            registerReceiver(unlockReceiver, IntentFilter(Intent.ACTION_USER_PRESENT))
        }
        handler.post(ticker)
        handler.post(logScanRunnable)
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
        dismissCopyTip()
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

        // 标题行：状态点 + 标题 + — 收起 + ✕ 关闭悬浮窗（任何状态都显示）
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
        val minimizeBtn = TextView(this).apply {
            text = "—"
            setTextColor(Color.parseColor("#E5E7EB"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            minWidth = dp(40)
            minHeight = dp(40)
            setOnClickListener { toggleMinimized() }
        }
        val closeBtn = TextView(this).apply {
            text = "✕"
            setTextColor(Color.parseColor("#FCA5A5"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            minWidth = dp(40)
            minHeight = dp(40)
            setOnClickListener { closeOverlay() }
        }
        this.minimizeButton = minimizeBtn
        this.closeButton = closeBtn
        titleRow.addView(dotView)
        titleRow.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        titleRow.addView(minimizeBtn)
        titleRow.addView(closeBtn)
        this.titleRow = titleRow

        // 常驻本机地址行：只有地址文字可点复制，「本机地址：」标签不响应
        val addressRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, 0)
        }
        val addressLabel = TextView(this).apply {
            text = "本机地址："
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        val addressValue = TextView(this).apply {
            setTextColor(Color.parseColor("#93C5FD"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            maxLines = 1
            setOnClickListener { copyLocalAddress(this) }
        }
        addressRow.addView(addressLabel)
        addressRow.addView(
            addressValue,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )

        val stage = TextView(this).apply {
            setTextColor(Color.parseColor("#CBD5E1"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(6), 0, 0)
        }

        val lastErrorMain = TextView(this).apply {
            setTextColor(Color.parseColor("#FCA5A5"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(4), 0, 0)
            visibility = View.GONE
        }
        val lastErrorNext = TextView(this).apply {
            setTextColor(Color.parseColor("#CBD5E1"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, dp(2), 0, 0)
            visibility = View.GONE
        }
        val lastErrorSolveBtn = TextView(this).apply {
            text = "如何解决"
            setTextColor(Color.parseColor("#93C5FD"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.START
            setPadding(0, dp(2), dp(8), dp(2))
            background = null
            visibility = View.GONE
            setOnClickListener { openApp(tab = 2) }
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
        // 第 7 步：复制地址卡片；大号「完成」在这里，点完永久不再出现
        val addressTip = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(2))
            visibility = View.GONE
        }
        val tipAddressRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val tipBadge = TextView(this).apply {
            text = "第7/7步 · 复制地址并完成酒馆连接"
            setTextColor(Color.parseColor("#9AA0A6"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, 0, 0, dp(2))
        }
        val tipAddressLabel = TextView(this).apply {
            text = "本机地址："
            setTextColor(Color.parseColor("#E2E8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        val tipAddressValue = TextView(this).apply {
            setTextColor(Color.parseColor("#93C5FD"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 1
            setOnClickListener { copyLocalAddress(this) }
        }
        tipAddressRow.addView(tipAddressLabel)
        tipAddressRow.addView(
            tipAddressValue,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        val tipHint = TextView(this).apply {
            val hintText = "① 点击上方蓝色地址直接复制，拉新窗口启动酒馆\n" +
                "② 进入酒馆后，点击 🔌\n" +
                "③ API 选择聊天补全 / Chat Completion → 聊天补全来源选择 Google AI Studio → 点击反向代理右边的 ⏷\n" +
                "④ 复制好的地址粘贴进代理服务器 URL 栏，剩下的部分随便填"
            val sp = SpannableString(hintText)
            val apiStart = hintText.indexOf("API")
            if (apiStart >= 0) {
                sp.setSpan(
                    StyleSpan(Typeface.BOLD),
                    apiStart,
                    apiStart + "API".length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            // 箭头用 14dp 的圆形 drawable 顶掉占位字符；drawable 读不到时保留 ⏷ 兜底
            val arrowStart = hintText.indexOf("⏷")
            val arrow = ContextCompat.getDrawable(this@OverlayService, R.drawable.ic_down_circle)
            if (arrowStart >= 0 && arrow != null) {
                arrow.setBounds(0, 0, dp(14), dp(14))
                sp.setSpan(
                    ImageSpan(arrow, ImageSpan.ALIGN_BASELINE),
                    arrowStart,
                    arrowStart + 1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            text = sp
            setTextColor(Color.parseColor("#CBD5E1"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(6), 0, dp(6))
            setLineSpacing(dp(2).toFloat(), 1.15f)
        }
        val doneBtn = TextView(this).apply {
            text = "✅ 完成"
            setTextColor(Color.parseColor("#052E16"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            minHeight = dp(44)
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#4ADE80"))
            }
            setOnClickListener { finishAddressTip() }
        }
        val tipFoot = TextView(this).apply {
            text = "点击完成结束教程"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, dp(4), 0, 0)
        }
        // 地址在上、徽标在下：卡片第一行就是本机地址，避免跟顶部常驻地址行重复
        addressTip.addView(tipAddressRow)
        addressTip.addView(tipBadge)
        addressTip.addView(tipHint)
        addressTip.addView(
            doneBtn,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        addressTip.addView(tipFoot)

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

        val continueRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }

        // 展开态完整内容；收起时整块隐藏，只留下面那条横条
        val full = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        full.addView(addressRow)
        full.addView(stage)
        full.addView(lastErrorMain)
        full.addView(lastErrorNext)
        full.addView(
            lastErrorSolveBtn,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        full.addView(guide)
        full.addView(badge)
        full.addView(tutorial)
        full.addView(switchRow)
        full.addView(tutorialBtn)
        full.addView(tutorialAltBtn)
        full.addView(navRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(30)
        ).apply { topMargin = dp(6) })
        full.addView(continueRow)
        full.addView(addressTip)
        full.addView(actionRow)
        full.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        // 收起态横条：● 反代 :端口  地址 … - ×
        val mini = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        val miniDot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#94A3B8"))
            }
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8))
        }
        val miniTitle = TextView(this).apply {
            text = "反代"
            setTextColor(Color.parseColor("#E5E7EB"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(6), 0, dp(8), 0)
            maxLines = 1
        }
        val miniAddressView = TextView(this).apply {
            setTextColor(Color.parseColor("#93C5FD"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            setPadding(0, 0, dp(10), 0)
            setOnClickListener { copyLocalAddress(this) }
        }
        val miniExpand = TextView(this).apply {
            text = "−"
            setTextColor(Color.parseColor("#E5E7EB"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setOnClickListener { toggleMinimized() }
        }
        val miniClose = TextView(this).apply {
            text = "×"
            setTextColor(Color.parseColor("#FCA5A5"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setOnClickListener { closeOverlay() }
        }
        mini.addView(miniDot)
        mini.addView(miniTitle)
        mini.addView(
            miniAddressView,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        mini.addView(miniExpand, LinearLayout.LayoutParams(dp(36), dp(36)))
        mini.addView(miniClose, LinearLayout.LayoutParams(dp(36), dp(36)))

        panel.addView(titleRow)
        panel.addView(full)
        panel.addView(mini)

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
                    // 收起态只响应拖动与子 View（地址 / - / ×）点击，空白处不做任何事
                    if (!dragging && !reportMode && !minimized) {
                        detailsExpanded = !detailsExpanded
                        details.visibility = if (detailsExpanded) View.VISIBLE else View.GONE
                        scroll.visibility = if (detailsExpanded) View.VISIBLE else View.GONE
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
            lastErrorMainText = lastErrorMain
            lastErrorNextText = lastErrorNext
            lastErrorSolveButton = lastErrorSolveBtn
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
            fullContent = full
            miniBar = mini
            this.miniDot = miniDot
            miniText = miniTitle
            miniAddress = miniAddressView
            proxyAddressRow = addressRow
            proxyAddressText = addressValue
            addressTipBox = addressTip
            addressTipValue = tipAddressValue
        }
    }

    private fun render() {
        val snapshot = OverlayState.snapshot
        val elapsedSec = if (OverlayState.deployStartedAt > 0L) {
            ((System.currentTimeMillis() - OverlayState.deployStartedAt) / 1000L).coerceAtLeast(0L)
        } else {
            0L
        }

        val now = System.currentTimeMillis()
        val statusStale = OverlayState.statusStale(now)
        // 断线防抖：online 变 false 记时间戳，恢复 true 立即清零；恢复时记一次掉线事件（P1）
        val wasOnlineBefore = sawOnlineThisRun
        if (snapshot.online) {
            if (!proxyEverOnline) proxyEverOnline = true
            // 只有「先在线过、再掉线、又恢复」才算一次掉线事件；
            // 服务刚拉起时那一次不算，否则重启几次悬浮窗就会误报频繁掉线
            if (offlineSince > 0L && !statusStale && wasOnlineBefore) {
                recordDropRecovery(now, now - offlineSince)
            }
            sawOnlineThisRun = true
            offlineSince = 0L
        } else if (offlineSince == 0L) {
            offlineSince = now
        }
        // 状态失联（App 进程被杀 / 主界面长时间没轮询）时不能断言「反代断线」，
        // 否则服务刚拉起、第一次状态还没回来就会误报已掉线。
        val takeover = proxyEverOnline && !snapshot.online && !statusStale &&
            offlineSince > 0L && now - offlineSince >= offlineThresholdMs &&
            now - rebuiltAt >= rebuildGraceMs

        // 老用户迁移：cookie_done 未写过时，等拿到真实服务状态再判定，避免升级被教程糊脸
        if (!guidePrefs.contains("cookie_done") && snapshot.online) {
            if (snapshot.accountsReady) {
                cookieDone = true
                forceGuideStage(4)
            } else {
                cookieDone = false
            }
        }
        if (snapshot.accountsReady) {
            if (!cookieDone) cookieDone = true
            // 只在没到 4 时才写，避免每次重绘都写一遍 SharedPreferences
            if (guideStageMax < 4) forceGuideStage(4)
        } else if (cookieDone && guideStageMax < 4) {
            forceGuideStage(4)
        }

        val cookieMode = !cookieDone
        val tavernMode = cookieDone && !tavernDone
        val addressTipMode = cookieDone && tavernDone && !tavernAddressTipDone
        val tutorialDone = cookieDone && tavernDone && tavernAddressTipDone
        // 教程期间不做实时检测器：掉线只让状态点变灰，不拿断线卡顶掉当前步骤
        val showTakeover = takeover && !reportMode && tutorialDone
        val guideMode = (cookieMode || tavernMode || addressTipMode || showTakeover) && !reportMode
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
        (miniDot?.background as? GradientDrawable)?.setColor(Color.parseColor(color))
        val addressShown = snapshot.localBaseUrl.ifBlank { "地址获取中" }
        miniText?.text = "反代："
        miniAddress?.text = addressShown
        proxyAddressText?.text = addressShown
        addressTipValue?.text = addressShown

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
            statusStale -> "状态待同步 · 打开 App 刷新"
            snapshot.error != null -> "阶段：失败（${snapshot.error}）"
            needCookie -> "阶段：服务已启动，还差导入 Cookie"
            snapshot.online -> "阶段：服务已启动"
            else -> "阶段：${snapshot.stage}"
        }
        // 教程期不显示阶段行，避免和卡片抢位置
        stageText?.visibility = if (guideMode) View.GONE else View.VISIBLE

        if (!reportMode) {
            val errFinding = OverlayState.lastErrorFinding
            val errAt = OverlayState.lastErrorAt
            if (!guideMode && errFinding != null && errAt > 0L) {
                lastErrorMainText?.text = ErrorCodeRules.formatSummaryLine(errFinding, errAt)
                lastErrorMainText?.visibility = View.VISIBLE
                if (errFinding.kind != ErrorKind.UNKNOWN && errFinding.nextStep.isNotBlank()) {
                    lastErrorNextText?.text = errFinding.nextStep
                    lastErrorNextText?.visibility = View.VISIBLE
                    lastErrorSolveButton?.visibility = View.VISIBLE
                } else {
                    lastErrorNextText?.visibility = View.GONE
                    lastErrorSolveButton?.visibility = View.GONE
                }
            } else {
                lastErrorMainText?.visibility = View.GONE
                lastErrorNextText?.visibility = View.GONE
                lastErrorSolveButton?.visibility = View.GONE
            }

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

        if (!detailsExpanded && !reportMode) {
            detailsBox?.visibility = View.GONE
            detailsScroll?.visibility = View.GONE
        }

        if (cookieDone) {
            cookieClipLength = 0
            tutorialBrowser = null
            deployVpnConfirmShown = false
            linkCopyConfirmShown = false
            studioOpenError = null
            studioOpenedAt = 0L
            tutorialFailHint = null
        }
        scanEnvReport()
        when {
            showTakeover -> {
                renderTutorial(false)
                renderTavernGuide(false)
                renderAddressTip(false)
                // 频繁掉线提示复用断线卡的位置，一次只出现一张卡
                if (shouldWarnFrequentDrops(now)) {
                    guidePrefs.edit().putLong("drop_tip_shown_at", now).apply()
                    renderFrequentDropCard(dropCount(now))
                } else {
                    renderTakeoverCard(true)
                }
            }
            cookieMode -> {
                renderTakeoverCard(false)
                renderTavernGuide(false)
                renderAddressTip(false)
                renderTutorial(!reportMode)
            }
            tavernMode -> {
                renderTakeoverCard(false)
                renderTutorial(false)
                renderAddressTip(false)
                renderTavernGuide(!reportMode)
            }
            addressTipMode -> {
                renderTakeoverCard(false)
                renderTutorial(false)
                renderTavernGuide(false)
                renderAddressTip(!reportMode)
            }
            else -> {
                renderTutorial(false)
                renderTavernGuide(false)
                renderAddressTip(false)
                renderTakeoverCard(false)
            }
        }
        applyOverlaySize()
    }

    /** 收起 / 展开切换：收起时只留横条；报错放大态先退出再收起。 */
    private fun toggleMinimized() {
        if (reportMode) exitReportMode()
        minimized = !minimized
        guidePrefs.edit().putBoolean("overlay_minimized", minimized).apply()
        chromeApplied = null
        applyOverlaySize(force = true)
        render()
    }

    /** ✕：关闭整个悬浮窗，并写 overlayDesired=false，避免回 App 时被 ON_RESUME 自动拉起。 */
    private fun closeOverlay() {
        SettingsRepository(applicationContext).overlayDesired = false
        guidePrefs.edit().putBoolean("overlay_minimized", false).apply()
        Toast.makeText(this, "悬浮窗已关闭，可在 App「反代」页重新开启", Toast.LENGTH_SHORT).show()
        OverlayService.stop(this)
    }

    /** 展开 280dp / 收起 240dp 的宽度、显隐和位置夹取。 */
    private fun applyOverlaySize(force: Boolean = false) {
        if (reportMode && !force) return
        val panel = root ?: return
        val params = panelParams ?: return
        val targetWidth = dp(if (minimized) 240 else 280)
        if (!force && chromeApplied == minimized && params.width == targetWidth) return
        chromeApplied = minimized
        fullContent?.visibility = if (minimized) View.GONE else View.VISIBLE
        titleRow?.visibility = if (minimized) View.GONE else View.VISIBLE
        miniBar?.visibility = if (minimized) View.VISIBLE else View.GONE
        val h = dp(if (minimized) 8 else 14)
        val v = dp(if (minimized) 6 else 10)
        panel.setPadding(h, v, h, v)
        params.width = targetWidth
        val screenW = resources.displayMetrics.widthPixels
        params.x = params.x.coerceIn(0, (screenW - targetWidth).coerceAtLeast(0))
        runCatching { windowManager?.updateViewLayout(panel, params) }
    }

    private var copyTipView: TextView? = null
    private var copyTipHideRunnable: Runnable? = null

    /** 复制本机地址：只读快照里的完整 URL，UI 层不拼任何 scheme/host/port。 */
    private fun copyLocalAddress(anchor: View? = null) {
        val url = OverlayState.snapshot.localBaseUrl
        if (url.isBlank()) {
            Toast.makeText(this, "地址还没准备好，稍等一下", Toast.LENGTH_SHORT).show()
            return
        }
        TermuxBridge.copyToClipboard(this, "本机地址", url)
        showCopyTip(anchor)
    }

    private fun showCopyTip(anchor: View?, text: String = "本机地址已复制") {
        val tipText = text
        // 屏幕上已有提示时不重建：连点只重置停留时长，动画只播一次，避免叠影和闪烁
        copyTipView?.let { existing ->
            existing.text = tipText
            cancelCopyTipHide()
            existing.animate().cancel()
            existing.alpha = 1f
            scheduleCopyTipHide(existing)
            return
        }

        val tip = TextView(this).apply {
            this.text = tipText
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#E6111827"))
                setStroke(dp(1), Color.parseColor("#40FFFFFF"))
            }
            alpha = 0f
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        val loc = IntArray(2)
        anchor?.getLocationOnScreen(loc)

        runCatching {
            windowManager?.addView(tip, params)
            copyTipView = tip

            tip.post {
                val screenW = resources.displayMetrics.widthPixels
                val screenH = resources.displayMetrics.heightPixels
                val ax = if (anchor != null) loc[0] else dp(16)
                val ay = if (anchor != null) loc[1] else dp(96)
                val ah = anchor?.height ?: 0
                params.x = (ax + (anchor?.width ?: 0) / 2 - tip.width / 2)
                    .coerceIn(dp(8), (screenW - tip.width - dp(8)).coerceAtLeast(dp(8)))
                params.y = if (ay + ah + dp(6) + tip.height <= screenH - dp(8)) {
                    ay + ah + dp(6)
                } else {
                    (ay - tip.height - dp(6)).coerceAtLeast(dp(8))
                }
                runCatching { windowManager?.updateViewLayout(tip, params) }
            }

            tip.animate().alpha(1f).setDuration(120L).start()
            scheduleCopyTipHide(tip)
        }
    }

    /** 停留到点后只渐隐一次，渐隐结束即移除视图 */
    private fun scheduleCopyTipHide(tip: TextView) {
        cancelCopyTipHide()
        val runnable = Runnable {
            copyTipHideRunnable = null
            if (copyTipView === tip) {
                tip.animate().alpha(0f).setDuration(180L).withEndAction {
                    dismissCopyTip()
                }.start()
            }
        }
        copyTipHideRunnable = runnable
        handler.postDelayed(runnable, COPY_TIP_HOLD_MS)
    }

    private fun cancelCopyTipHide() {
        copyTipHideRunnable?.let { handler.removeCallbacks(it) }
        copyTipHideRunnable = null
    }

    private fun dismissCopyTip() {
        cancelCopyTipHide()
        copyTipView?.let { tip ->
            copyTipView = null
            tip.animate().cancel()
            runCatching { windowManager?.removeView(tip) }
        }
    }

    /** 第 7 步点「完成」：教程永久结束。 */
    private fun finishAddressTip() {
        tavernAddressTipDone = true
        render()
        Toast.makeText(
            this,
            "教程已结束，从此不会再出现；若有遗忘的操作或其他问题，可去「解疑」页查看",
            Toast.LENGTH_LONG
        ).show()
    }

    /** 第 7 步：复制地址卡片，只在教程最后出现。 */
    private fun renderAddressTip(show: Boolean) {
        val box = addressTipBox ?: return
        if (!show) {
            box.visibility = View.GONE
            proxyAddressRow?.visibility = View.VISIBLE
            return
        }
        tutorialText?.visibility = View.GONE
        tutorialButton?.visibility = View.GONE
        tutorialAltButton?.visibility = View.GONE
        tutorialBadge?.visibility = View.GONE
        tutorialNavRow?.visibility = View.GONE
        tutorialContinueRow?.visibility = View.GONE
        tutorialSwitchRow?.visibility = View.GONE
        // 卡片里已经有地址，隐藏顶部常驻地址行，避免同一地址出现两遍
        proxyAddressRow?.visibility = View.GONE
        box.visibility = View.VISIBLE
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
        val repo = SettingsRepository(applicationContext)
        val deployingNow = snapshot.deploying || OverlayState.deployStartedAt > 0L
        val everDeployed = snapshot.hasDeployed ||
            repo.hasDeployed ||
            repo.deploySucceeded ||
            proxyEverOnline
        val firstDeploy = !everDeployed
        val deployFailed = firstDeploy && snapshot.error != null && !snapshot.online
        val deployed = everDeployed || snapshot.online

        val computed = when {
            firstDeploy && (deployingNow || deployFailed) -> 0
            cookieDone || snapshot.accountsReady -> 4
            !deployed -> 0
            cookieClipLength > 0 -> 4
            chosen == null -> 1
            // 先装扩展，再进合并卡（打开 AI Studio + 登录 + 导出 Cookie）
            !guideExtInstalled -> 2
            else -> 3
        }
        // 只有从没成功过才回 S0
        if (!deployed && guideStageMax > 0) forceGuideStage(0)
        // 浏览器被卸载：回到重新安装那一步，不能卡在扩展 / Cookie 卡（回退必须走 force）
        if (deployed && chosen == null && guideStageMax >= 2) forceGuideStage(1)
        // 升级迁移：老版本 3 是「扩展」步，合并后 3 是合并卡；扩展没确认过就退回第 2 步，避免跳过安装
        if (!cookieDone && deployed && !guideExtInstalled && guideStageMax >= 3) forceGuideStage(2)
        // 老版本最多存到 5（原第 6 步粘贴），合并后最高是 4；夹一下免得 when(view) 落到 else 把教程整块隐藏
        if (guideStageMax > 4) forceGuideStage(4)
        if (computed > guideStageMax) guideStageMax = computed
        // 证据闸门：停在「粘贴」步必须有证据（剪贴板命中 JSON，或用户本次明确点过「去粘贴」），
        // 否则退回合并卡（AI Studio + Cookie）——这正是升级后直接落在粘贴步的根因
        if (!cookieDone && guideStageMax >= 4 && cookieClipLength <= 0 && !pasteStepConfirmed) {
            forceGuideStage(3)
        }
        val stage = guideStageMax
        // 回看定位：只允许停在 0..stage-1，超出即跟随最新
        val rawView = if (cookieViewStage in 0 until stage) cookieViewStage else stage
        // 已经跑通过一次的用户不再出现「部署」卡（含回看），避免被理解成要重新部署
        val view = if (everDeployed && rawView == 0) 1 else rawView
        val readonly = view < stage
        cookieViewStage = if (readonly) view else -1
        if (view != 1) tutorialFailHint = null
        // 只有合并卡（AI Studio + Cookie）会用到复制链接的覆盖确认；切到别步就清掉
        if (view != 3) {
            linkCopyConfirmShown = false
            studioOpenError = null
        }
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
            "第 ${view + 1}/7 步 · ${stageName(view)}"
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
        3 -> "AI Studio + Cookie"
        4 -> "粘贴"
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

    /** 频繁掉线提示：复用断线卡的位置，不新增第二张卡。 */
    private fun renderFrequentDropCard(count: Int) {
        val tut = tutorialText ?: return
        val btn = tutorialButton
        val altBtn = tutorialAltButton
        val minuteTotal = (offlineTotal(System.currentTimeMillis()) / 1000L).toInt()
        val reason = if (count >= 3) "10 分钟内 $count 次" else "30 分钟内累计离线 ${minuteTotal} 秒"
        tut.visibility = View.VISIBLE
        tut.text = "反代在频繁掉线（$reason）\n" +
            "安卓可能把 Termux 挂起了：\n" +
            "把 Termux 拉成小窗 / 分屏挂在前台，\n" +
            "并在系统设置里允许 Termux 后台运行、关闭电池优化。"
        tut.setTextColor(Color.parseColor("#FDE68A"))
        tut.background = tutorialPanelBackground()
        tut.setOnClickListener(null)
        btn?.visibility = View.VISIBLE
        btn?.text = "打开 Termux"
        btn?.setOnClickListener {
            if (!TermuxBridge.openTermux(this)) {
                Toast.makeText(this, "没能打开 Termux，请从桌面手动启动", Toast.LENGTH_LONG).show()
            }
        }
        altBtn?.visibility = View.VISIBLE
        altBtn?.text = "知道了"
        altBtn?.setOnClickListener { ackDropTip() }
        tutorialBadge?.visibility = View.GONE
        tutorialNavRow?.visibility = View.GONE
        tutorialContinueRow?.visibility = View.GONE
        tutorialSwitchRow?.visibility = View.GONE
    }

    // ── P1 频繁掉线统计 ────────────────────────────────────────────────────────

    /**
     * 记录一次「掉线 → 恢复」事件。只在恢复时刻记一次，避免同一段掉线被重复计数。
     * 用户主动点「停止反代」造成的那一次下线不计入。
     */
    private fun recordDropRecovery(now: Long, offlineMs: Long) {
        val manualStopAt = guidePrefs.getLong("manual_stop_at", 0L)
        if (manualStopAt > 0L && now - manualStopAt < 2 * 60_000L) return
        if (OverlayState.snapshot.deploying || OverlayState.deployStartedAt > 0L) return

        var winStart = guidePrefs.getLong("drop_window_start", 0L)
        var count = guidePrefs.getInt("drop_count", 0)
        if (winStart <= 0L || now - winStart > DROP_WINDOW_MS) {
            winStart = now
            count = 0
        }
        var offStart = guidePrefs.getLong("offline_window_start", 0L)
        var total = guidePrefs.getLong("offline_total_ms", 0L)
        if (offStart <= 0L || now - offStart > OFFLINE_WINDOW_MS) {
            offStart = now
            total = 0L
        }
        guidePrefs.edit()
            .putLong("drop_window_start", winStart)
            .putInt("drop_count", count + 1)
            .putLong("offline_window_start", offStart)
            .putLong("offline_total_ms", total + offlineMs.coerceAtLeast(0L))
            .apply()
        LogCollector.append("[掉线] 掉线恢复第 ${count + 1} 次，本次离线 ${offlineMs / 1000} 秒")
    }

    /** 当前是否该提示「频繁掉线」（含冷却、手动停服、部署中、刚启动的排除条件）。 */
    private fun shouldWarnFrequentDrops(now: Long): Boolean {
        if (!proxyEverOnline) return false
        val snapshot = OverlayState.snapshot
        if (snapshot.deploying || OverlayState.deployStartedAt > 0L) return false
        if (now - rebuiltAt < DROP_GRACE_MS) return false
        val manualStopAt = guidePrefs.getLong("manual_stop_at", 0L)
        if (manualStopAt > 0L && now - manualStopAt < DROP_GRACE_MS) return false
        val ackAt = guidePrefs.getLong("drop_tip_ack_at", 0L)
        if (ackAt > 0L && now - ackAt < DROP_TIP_ACK_MS) return false
        val shownAt = guidePrefs.getLong("drop_tip_shown_at", 0L)
        if (shownAt > 0L && now - shownAt < DROP_TIP_COOLDOWN_MS) return false
        return dropCount(now) >= 3 || offlineTotal(now) >= OFFLINE_TOTAL_LIMIT_MS
    }

    private fun dropCount(now: Long): Int {
        val start = guidePrefs.getLong("drop_window_start", 0L)
        if (start <= 0L || now - start > DROP_WINDOW_MS) return 0
        return guidePrefs.getInt("drop_count", 0)
    }

    private fun offlineTotal(now: Long): Long {
        val start = guidePrefs.getLong("offline_window_start", 0L)
        if (start <= 0L || now - start > OFFLINE_WINDOW_MS) return 0L
        return guidePrefs.getLong("offline_total_ms", 0L)
    }

    /** 用户点「知道了」：7 天内不再提示，并把计数清零。 */
    private fun ackDropTip() {
        guidePrefs.edit()
            .putLong("drop_tip_ack_at", System.currentTimeMillis())
            .putInt("drop_count", 0)
            .putLong("offline_total_ms", 0L)
            .apply()
        render()
    }

    /** 读 LogCollector 里最后一次 ENV_BEGIN…ENV_END 报告，更新 envVerdict。 */
    private fun scanEnvReport() {
        if (envVerdict != EnvVerdict.CHECKING) return
        val lines = LogCollector.getLog(60).lines().map { it.trim() }
        val end = lines.indexOfLast { it == "ENV_END" }
        if (end < 0) return
        val start = lines.subList(0, end).indexOfLast { it == "ENV_BEGIN" }
        if (start < 0) return
        val seg = lines.subList(start + 1, end)
        var py = ""
        var pyVer = ""
        var uv = ""
        for (line in seg) {
            when {
                line.startsWith("PY=") -> py = line.removePrefix("PY=").trim()
                line.startsWith("PYVER=") -> pyVer = line.removePrefix("PYVER=").trim()
                line.startsWith("UV=") -> uv = line.removePrefix("UV=").trim()
            }
        }
        val pyUsable = py.isNotBlank() && py != "MISSING" && pythonAtLeast311(pyVer)
        envVerdict = when {
            !pyUsable -> EnvVerdict.NEED_PY
            uv.isBlank() || uv == "MISSING" -> EnvVerdict.NEED_UV
            else -> EnvVerdict.OK
        }
        handler.post { render() }
    }

    /** 形如 "Python 3.13.1" 判主版本 3、次版本 ≥ 11；解析不出按不合格处理。 */
    private fun pythonAtLeast311(versionLine: String): Boolean {
        val m = Regex("Python\\s+3\\.(\\d+)").find(versionLine) ?: return false
        return (m.groupValues[1].toIntOrNull() ?: -1) >= 11
    }

    /** B08：环境不合格时顶替教程第一步，不新增步骤号。 */
    private fun renderEnvStageCard(
        tut: TextView,
        btn: TextView?,
        altBtn: TextView?,
        continueRow: LinearLayout?
    ) {
        val isPy = envVerdict == EnvVerdict.NEED_PY
        tut.visibility = View.VISIBLE
        tut.setTextColor(Color.parseColor("#FCA5A5"))
        tut.background = tutorialPanelBackground()
        tut.setOnClickListener(null)
        val reason = if (isPy) {
            "Termux 里的 Python 不可用，会导致部署失败\n" +
                "原因：反代依赖需要 Python 3.11 及以上，当前环境没有可用的 Python，安装会中断。"
        } else {
            "Termux 里没有 uv，现在部署会失败。\n" +
                "原因：当前 Termux 环境里没有 uv，依赖解析无法进行，安装会中断。"
        }
        val tail = if (envRetryCount >= 3) {
            "\n重新检测 3 次仍未通过。可以继续部署，但这次很可能失败；失败后点击悬浮窗的「🩺 报错」，把日志发给我。"
        } else if (envRetryCount >= 1) {
            "\n重新检测仍未通过。第一条命令报错时，先在 Termux 执行 termux-change-repo 换回官方软件源，再重跑上面两条。"
        } else {
            ""
        }
        tut.text = reason + "\n" +
            "按顺序复制粘贴并回车运行以下指令：\n" +
            "① pkg update -y && pkg upgrade -y\n" +
            "② pkg install -y python\n" +
            "\n在第①条指令尚未运行完成时，请不要粘贴第二条指令，全部运行完成后点击下方按钮\n" +
            "「我已执行完，重新检测」。" + tail

        tutorialBadge?.visibility = View.VISIBLE
        tutorialBadge?.text = "第 1/7 步 · 环境准备"
        tutorialNavRow?.visibility = View.GONE

        btn?.visibility = View.VISIBLE
        btn?.isEnabled = true
        btn?.alpha = 1f
        if (envRetryCount >= 3) {
            btn?.text = "仍然继续（大概率安装失败）"
            btn?.setOnClickListener {
                envVerdict = EnvVerdict.OK
                deployVpnConfirmShown = true
                render()
            }
            altBtn?.visibility = View.GONE
        } else {
            btn?.text = "复制第 1 条"
            btn?.setOnClickListener {
                TermuxBridge.copyToClipboard(this, "环境准备命令 1", "pkg update -y && pkg upgrade -y")
                showCopyTip(btn, "命令已复制")
            }
            altBtn?.visibility = View.VISIBLE
            altBtn?.text = "复制第 2 条"
            altBtn?.setOnClickListener {
                TermuxBridge.copyToClipboard(this, "环境准备命令 2", "pkg install -y python")
                showCopyTip(altBtn, "命令已复制")
            }
        }

        continueRow?.visibility = View.VISIBLE
        continueRow?.removeAllViews()
        val retry = TextView(this).apply {
            text = "我已执行完，重新检测"
            setTextColor(Color.parseColor("#93C5FD"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(4), dp(6), dp(4), dp(6))
            setOnClickListener {
                envProbeSent = false
                envRetryCount += 1
                envVerdict = EnvVerdict.UNKNOWN
                render()
            }
        }
        continueRow?.addView(retry)
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
        val repo = SettingsRepository(applicationContext)
        // 「跑通过一次」就算部署过：之后掉线 / 超时 / 重启服务都不再回到部署卡
        val everDeployed = snapshot.hasDeployed ||
            repo.hasDeployed ||
            repo.deploySucceeded ||
            proxyEverOnline
        val justTapped = deployTapAt > 0L && System.currentTimeMillis() - deployTapAt < 20_000L
        val deploying = !everDeployed && (snapshot.deploying || justTapped)
        // B08-patch1：上次部署失败也允许再探一次，10 秒节流，避免重试路径永远看不到环境卡
        if (!everDeployed && snapshot.error != null && !snapshot.online &&
            envVerdict == EnvVerdict.UNKNOWN &&
            System.currentTimeMillis() - envProbeAt >= 10_000L
        ) {
            envProbeSent = false
        }
        // B08：首次进部署卡先做一次环境自检，不合格就顶替这一步
        if (termuxNow && !everDeployed && !deploying) {
            if (!envProbeSent) {
                envProbeSent = true
                envProbeAt = System.currentTimeMillis()
                val sent = TermuxBridge.runEnvProbe(this)
                envVerdict = if (sent) EnvVerdict.CHECKING else EnvVerdict.UNKNOWN
                if (sent) {
                    handler.postDelayed({
                        if (envVerdict == EnvVerdict.CHECKING) {
                            envVerdict = EnvVerdict.UNKNOWN
                            render()
                        }
                    }, 5000L)
                }
            }
            when (envVerdict) {
                EnvVerdict.CHECKING -> {
                    if (snapshot.error == null) {
                        tut.text = "正在检查 Termux 环境…"
                        tut.setTextColor(Color.parseColor("#DBEAFE"))
                        tut.setOnClickListener(null)
                        btn?.visibility = View.VISIBLE
                        btn?.text = "正在检查环境…"
                        btn?.isEnabled = false
                        btn?.alpha = 0.5f
                        altBtn?.visibility = View.GONE
                        continueRow?.visibility = View.GONE
                        return
                    }
                }
                EnvVerdict.NEED_PY, EnvVerdict.NEED_UV -> {
                    renderEnvStageCard(tut, btn, altBtn, continueRow)
                    return
                }
                else -> Unit
            }
        }
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
            else -> {
                tut.text = "请开启 VPN 并全程保持连接，并确认酒馆保持关闭、没有在 Termux 运行任何项目。\n" +
                    "点击「开始半自动部署」开始部署，跳转 Termux 后粘贴指令并回车，等待部署完成。"
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
        tut.text = "下载并安装支持拓展程序的浏览器"
        tut.setTextColor(Color.parseColor("#DBEAFE"))
        tut.setOnClickListener { refreshBrowserCache(); render() }
        btn?.visibility = View.VISIBLE
        btn?.text = "下载Edge（官网）"
        btn?.setOnClickListener { TermuxBridge.openUrl(this, TermuxBridge.URL_EDGE_ANDROID) }
        altBtn?.visibility = View.VISIBLE
        altBtn?.text = "下载火狐（play商店）"
        altBtn?.setOnClickListener { TermuxBridge.openUrl(this, TermuxBridge.URL_FIREFOX_PLAY_STORE) }
    }

    /**
     * 第 4 步：一张卡走完打开 AI Studio、登录、切 Playground、导出 Cookie。
     * 网络前提已经放在教程第一张卡（S0），这里不再重复整句。
     */
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
        tut.setOnClickListener(null)
        val menuPath = if (label == "火狐") {
            "④ 点击右上「⋮」→ 扩展 → Cookie-Editor"
        } else {
            "④ 点击底部「≡」→ 扩展 → Cookie-Editor"
        }
        val exportPath = if (label == "火狐") {
            "⑤ 火狐版：点击右下角图标 → JSON，复制 Cookie"
        } else {
            "⑤ Edge 版：滑到页面最右端 → 点击右下角图标 → JSON，复制 Cookie"
        }
        tut.text = "① 点击下面按钮打开 AI Studio\n" +
            "② 点击 Get started / 开始使用 进行账号登录\n" +
            "③ 点击网页左上角三横杠「≡」，切换到 Playground 页面\n" +
            "$menuPath\n" +
            "$exportPath\n\n" +
            "▸ 若拥有多个账号：在 AI Studio 页面左上角点「☰」打开侧边栏 → 拉到最下方点头像 → 点「切换账号」→ 登录其他账号。登录多个账号后，Cookie-Editor 可以一次抓取全部账号的 Cookie，不用一个个导出"
        tut.setTextColor(Color.parseColor("#DBEAFE"))

        // 复制链接会覆盖剪贴板里的 Cookie：覆盖前先确认一次
        if (linkCopyConfirmShown) {
            tut.text = "⚠️ 剪贴板里已经有疑似 Cookie 的内容。\n" +
                "再复制「AI Studio 链接」会把它覆盖成网址，\n" +
                "刚导出好的 Cookie 就得重新导一次。"
            tut.setTextColor(Color.parseColor("#FDE68A"))
            btn?.visibility = View.VISIBLE
            btn?.text = "仍然复制链接"
            btn?.setOnClickListener {
                TermuxBridge.copyToClipboard(this, "AI Studio 链接", TermuxBridge.URL_AISTUDIO)
                linkCopyConfirmShown = false
                Toast.makeText(this, "AI Studio 链接已复制（剪贴板里的 Cookie 已被替换）", Toast.LENGTH_LONG).show()
                render()
            }
            altBtn?.visibility = View.VISIBLE
            altBtn?.text = "取消"
            altBtn?.setOnClickListener {
                linkCopyConfirmShown = false
                render()
            }
            renderTutorialActionLink(continueRow, "去粘贴 →", primary = true) {
                pasteStepConfirmed = true
                guideStageMax = 4
                render()
            }
            return
        }

        // 打开结果直接写在卡里：成功说「已打开」，失败给红字
        val openedRecently = studioOpenedAt > 0L && System.currentTimeMillis() - studioOpenedAt < 60_000L
        val openError = studioOpenError
        when {
            openError != null -> {
                tut.text = "⚠️ $openError\n\n${tut.text}"
                tut.setTextColor(Color.parseColor("#FCA5A5"))
            }
            openedRecently -> {
                tut.text = "✅ 已用 $label 打开 AI Studio\n${tut.text}"
                tut.setTextColor(Color.parseColor("#86EFAC"))
            }
        }

        btn?.visibility = View.VISIBLE
        btn?.text = if (label == "火狐") "用火狐打开 AI Studio" else "用 Edge 打开 AI Studio"
        btn?.setOnClickListener {
            val ok = openAiStudio(browserPkg)
            if (ok) {
                studioOpenError = null
            } else {
                studioOpenError = "没能打开浏览器，链接已复制，请手动粘贴到地址栏"
                Toast.makeText(this, "没能打开浏览器，链接已复制到剪贴板", Toast.LENGTH_LONG).show()
            }
            render()
        }
        altBtn?.visibility = View.VISIBLE
        altBtn?.text = "复制 AI Studio 链接"
        altBtn?.setOnClickListener {
            if (cookieClipLength > 0) {
                linkCopyConfirmShown = true
                render()
            } else {
                TermuxBridge.copyToClipboard(this, "AI Studio 链接", TermuxBridge.URL_AISTUDIO)
                Toast.makeText(this, "AI Studio 链接已复制", Toast.LENGTH_SHORT).show()
            }
        }
        // 手动推进是主路径，不再依赖剪贴板自动检测
        renderTutorialActionLink(continueRow, "去粘贴 →", primary = true) {
            pasteStepConfirmed = true
            guideStageMax = 4
            render()
        }
    }

    /** 酒馆向导：账号就绪后引导在 Termux 新会话启动酒馆；最后一步是「下一步」，不是「完成」。 */
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
        tutorialBadge?.visibility = View.VISIBLE
        tutorialBadge?.text = "第 6/7 步 · Termux 双窗口"
        tutorialNavRow?.visibility = View.GONE
        switchRow?.visibility = View.GONE
        continueRow?.visibility = View.GONE

        val steps = "① 打开 Termux\n" +
            "② 从屏幕左边缘向右滑，拉出白色小窗\n" +
            "③ 点 NEW SESSION\n" +
            "④ 在新窗口输入你的酒馆启动命令（示例：bash start.sh）\n" +
            "⑤ 以后每次用反代都保持两个窗口：一个跑反代，一个跑酒馆"
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
        altBtn?.visibility = View.GONE
        // 「下一步」进入第 7 步「复制地址并完成」；大号「完成」在那一张卡里
        renderTutorialActionLink(continueRow, "下一步 →", primary = true) {
            tavernDone = true
            render()
        }
    }

    /** 第 5 步：回账号页粘贴导入（App 前台读剪贴板，比悬浮窗后台读可靠）。 */
    private fun renderPasteStage(
        tut: TextView,
        btn: TextView?,
        altBtn: TextView?,
        continueRow: LinearLayout?,
        browserPkg: String?
    ) {
        continueRow?.visibility = View.GONE
        tut.text = "如果已经成功导出 Cookie，点击下方按钮一键粘贴到 App 剪贴板。\n" +
            "若跳转后剪贴板没有东西，则手动长按粘贴。\n" +
            "尽量不要用输入法自带剪切板，一般会有字数上限，无法完整粘贴。"
        tut.setTextColor(Color.parseColor(if (highlightPulse) "#FDE047" else "#86EFAC"))
        tut.setTypeface(null, Typeface.BOLD)
        tut.background = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(Color.parseColor(if (highlightPulse) "#33FDE047" else "#2A22C55E"))
            setStroke(dp(1), Color.parseColor("#66FDE047"))
        }
        btn?.visibility = View.VISIBLE
        btn?.text = "打开App并粘贴Cookie"
        btn?.setOnClickListener { openApp(tab = 1, pasteFromClipboard = true) }
        altBtn?.visibility = View.VISIBLE
        altBtn?.text = "重新导出Cookie"
        altBtn?.setOnClickListener { openAiStudio(browserPkg) }
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
        tut.text = "点击下方按钮跳转页面安装Cookie-Editor\n" +
            "若无法成功安装，则点击复制链接手动跳转安装"
        tut.setTextColor(Color.parseColor("#DBEAFE"))
        val addonUrl = browserAddonUrl(label)
        btn?.visibility = View.VISIBLE
        btn?.text = if (label == "火狐") "用火狐打开安装页" else "用 Edge 打开安装页"
        btn?.setOnClickListener {
            val ok = if (browserPkg.isNullOrBlank()) {
                TermuxBridge.openUrl(this, addonUrl)
            } else {
                TermuxBridge.openUrlInPackage(this, addonUrl, browserPkg)
            }
            if (!ok) {
                Toast.makeText(this, "没能打开安装页，请点「复制链接」手动粘贴", Toast.LENGTH_LONG).show()
                render()
            }
        }
        altBtn?.visibility = View.VISIBLE
        altBtn?.text = "复制链接"
        altBtn?.setOnClickListener {
            TermuxBridge.copyToClipboard(this, "Cookie-Editor ${label}扩展链接", addonUrl)
        }
        tut.setOnClickListener(null)
        renderTutorialActionLink(continueRow, "装好了 →") {
            guideExtInstalled = true
            guideStageMax = 3
            tutorialFailHint = null
            render()
        }
    }

    /**
     * 教程卡底部的手动推进链接（只给自动检测不到的那一步用）。
     * primary = true 时做成醒目的主按钮：剪贴板在后台读不到时，这就是唯一可靠的推进路径。
     */
    private fun renderTutorialActionLink(
        host: LinearLayout?,
        text: String,
        primary: Boolean = false,
        action: () -> Unit
    ) {
        host ?: return
        host.removeAllViews()
        val link = TextView(this).apply {
            this.text = text
            setTextColor(Color.parseColor(if (primary) "#86EFAC" else "#93C5FD"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (primary) 13f else 11f)
            setPadding(dp(8), dp(2), dp(8), dp(2))
            if (primary) {
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(10), dp(7), dp(10), dp(7))
                background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(Color.parseColor("#1F22C55E"))
                    setStroke(dp(1), Color.parseColor("#8622C55E"))
                }
            }
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

    private fun openApp(tab: Int? = null, pasteFromClipboard: Boolean = false) {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (tab != null) putExtra(MainActivity.EXTRA_TAB, tab)
            if (pasteFromClipboard) putExtra(MainActivity.EXTRA_ACCOUNTS_PASTE, true)
        }
        val ok = runCatching { startActivity(intent) }.isSuccess
        if (!ok) {
            LogCollector.append("[引导] 打开 App 失败")
            Toast.makeText(this, "打不开 App，请从桌面手动打开「反代助手」", Toast.LENGTH_LONG).show()
        }
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

    /**
     * 打开 AI Studio：一次点击就要有结果。
     * 校验包是否真的能打开 → 定向打开 → 回退默认浏览器 → 全部失败时复制链接兜底。
     * 返回 false 表示所有打开方式都失败，调用方必须给红字 / Toast（链接此时已复制好）。
     */
    private fun openAiStudio(browserPkg: String?): Boolean {
        val url = TermuxBridge.URL_AISTUDIO
        val pkg = browserPkg?.takeIf { it.isNotBlank() }
        if (pkg != null) {
            val installed = isPackageInstalled(pkg)
            val canOpen = installed && TermuxBridge.canOpenUrl(this, url, pkg)
            LogCollector.append("[引导] 定向打开 AI Studio：pkg=$pkg installed=$installed canOpen=$canOpen")
            if (!installed) {
                // 包已卸载 / 被禁用：清掉缓存，回退到「装浏览器」那一步
                if (edgePkgCache == pkg) {
                    edgePkgCache = ""
                    guidePrefs.edit().remove("browser_edge_pkg").apply()
                }
                if (firefoxPkgCache == pkg) {
                    firefoxPkgCache = ""
                    guidePrefs.edit().remove("browser_firefox_pkg").apply()
                }
                forceGuideStage(minOf(guideStageMax, 1))
                LogCollector.append("[引导] 浏览器 $pkg 已不可用，回到「装浏览器」步")
            } else if (canOpen && TermuxBridge.openUrlInPackage(this, url, pkg)) {
                studioOpenedAt = System.currentTimeMillis()
                studioOpenError = null
                LogCollector.append("[引导] 已用 $pkg 打开 AI Studio")
                return true
            } else if (TermuxBridge.openUrl(this, url)) {
                studioOpenedAt = System.currentTimeMillis()
                studioOpenError = null
                LogCollector.append("[引导] 定向失败，已用默认浏览器打开 AI Studio")
                return true
            }
        } else if (TermuxBridge.openUrl(this, url)) {
            studioOpenedAt = System.currentTimeMillis()
            studioOpenError = null
            LogCollector.append("[引导] 已用默认浏览器打开 AI Studio")
            return true
        }

        // 全部失败：至少把链接复制好，保证有兜底
        TermuxBridge.copyToClipboard(this, "AI Studio 链接", url)
        LogCollector.append("[引导] 打开 AI Studio 全部失败，已复制链接兜底")
        return false
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

    /** 放大悬浮窗（最大约分屏尺寸）并显示报错分析结果，字多可上下滑动；点 — 收起，点 ✕ 关闭悬浮窗。 */
    private fun showReport(text: String) {
        val params = panelParams ?: return
        if (!reportMode) {
            savedX = params.x
            savedY = params.y
        }
        reportMode = true
        lastErrorMainText?.visibility = View.GONE
        lastErrorNextText?.visibility = View.GONE
        lastErrorSolveButton?.visibility = View.GONE
        detailsExpanded = true
        detailsBox?.visibility = View.VISIBLE
        aiText?.text = "AI 报错分析（可上下滑动，点右上角 — 收起、✕ 关闭）：\n\n$text"
        aiText?.visibility = View.VISIBLE
        elapsedText?.visibility = View.VISIBLE

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

    /** 点 — 收起报错面板，悬浮窗恢复原来的小尺寸与位置。 */
    private fun exitReportMode() {
        reportMode = false
        detailsExpanded = false
        detailsBox?.visibility = View.GONE
        detailsScroll?.let { sv ->
            sv.visibility = View.GONE
            sv.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val params = panelParams ?: return
        params.height = WindowManager.LayoutParams.WRAP_CONTENT
        params.x = savedX
        params.y = savedY
        chromeApplied = null
        applyOverlaySize(force = true)
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
        /** 复制提示的固定停留时长；连点时以最后一次触发重新计时 */
        private const val COPY_TIP_HOLD_MS = 520L
        /** 悬浮窗日志增量扫描周期 */
        private const val LOG_SCAN_INTERVAL_MS = 5_000L

        @Volatile
        var isRunning: Boolean = false
            private set

        /** 引导状态 schema：2 = 按证据重算，不再照搬旧 cookie_max_stage=4 */
        private const val GUIDE_SCHEMA = 2

        /** P1 频繁掉线：10 分钟内 >= 3 次，或 30 分钟内累计离线 >= 90 秒 */
        private const val DROP_WINDOW_MS = 10 * 60_000L
        private const val OFFLINE_WINDOW_MS = 30 * 60_000L
        private const val OFFLINE_TOTAL_LIMIT_MS = 90_000L
        /** 同一提示 24 小时内只出现一次；用户点过「知道了」后 7 天不再提示 */
        private const val DROP_TIP_COOLDOWN_MS = 24 * 60 * 60_000L
        private const val DROP_TIP_ACK_MS = 7L * 24 * 60 * 60_000L
        /** 服务刚启动 5 分钟内不统计，避免冷启动误报 */
        private const val DROP_GRACE_MS = 5 * 60_000L

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
