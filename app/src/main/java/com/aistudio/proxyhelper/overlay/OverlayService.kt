package com.aistudio.proxyhelper.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
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
import com.aistudio.proxyhelper.data.SettingsRepository
import com.aistudio.proxyhelper.net.LogCollector
import kotlinx.coroutines.launch

/** 悬浮窗展示的快照，由 App 主界面轮询时写入。 */
data class OverlaySnapshot(
    val online: Boolean = false,
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
 * 悬浮窗：在 Termux 等其它应用上方显示「当前阶段 + AI 提示 + 下一步该做什么」。
 * 点一下可展开/收起，展开后能看到 AI 的最近结论与已用时长。
 */
class OverlayService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val diagnosisScope =
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)
    private var windowManager: WindowManager? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var detailsScroll: ScrollView? = null
    private var closeButton: TextView? = null
    private var reportMode = false

    private var root: LinearLayout? = null
    private var dot: View? = null
    private var titleText: TextView? = null
    private var stageText: TextView? = null
    private var guideText: TextView? = null
    private var detailsBox: LinearLayout? = null
    private var aiText: TextView? = null
    private var elapsedText: TextView? = null

    private var running = true
    private var expanded = false

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            render()
            handler.postDelayed(this, 1500L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundSafely()
        windowManager = getSystemService(WINDOW_SERVICE) as? WindowManager
        buildViews()
        handler.post(ticker)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
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
                setColor(Color.parseColor("#E6111827"))
                setStroke(dp(1), Color.parseColor("#40FFFFFF"))
            }
        }

        // 标题行：状态点 + 标题
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
        val closeButton = TextView(this).apply {
            text = "✕"
            setTextColor(Color.parseColor("#FCA5A5"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(12), 0, dp(2), 0)
            visibility = View.GONE
            setOnClickListener { exitReportMode() }
        }
        this.closeButton = closeButton
        titleRow.addView(dotView)
        titleRow.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        titleRow.addView(closeButton)

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

        val details = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val ai = TextView(this).apply {
            setTextColor(Color.parseColor("#FDE68A"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, dp(6), 0, 0)
        }
        val elapsed = TextView(this).apply {
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, dp(4), 0, 0)
        }

        // 操作行：报错 + 打开 App，始终可见
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        val diagnoseText = TextView(this).apply {
            text = "🩺 报错"
            setTextColor(Color.parseColor("#FDE68A"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Color.parseColor("#33FFFFFF"))
            }
            setOnClickListener { runDiagnose() }
        }
        val openAppText = TextView(this).apply {
            text = "打开 App"
            setTextColor(Color.parseColor("#93C5FD"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(dp(12), dp(4), 0, 0)
            setOnClickListener { openApp() }
        }
        actionRow.addView(diagnoseText)
        actionRow.addView(openAppText)

        details.addView(ai)
        details.addView(elapsed)

        // 报错结果放在可滚动区域里，文字多了可以上下滑
        val scroll = ScrollView(this).apply {
            visibility = View.GONE
            isFillViewport = true
            addView(details)
        }
        detailsScroll = scroll

        panel.addView(titleRow)
        panel.addView(stage)
        panel.addView(guide)
        panel.addView(actionRow)
        panel.addView(scroll)

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

        // 支持拖拽：按下记录起点，移动时更新窗口位置，抬手时若几乎没移动则视为点击（展开/收起）
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
                        runCatching { windowManager?.updateViewLayout(panel, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) {
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
            detailsBox = details
            aiText = ai
            elapsedText = elapsed
        }
    }

    private fun render() {
        val snapshot = OverlayState.snapshot
        val elapsedSec = if (OverlayState.deployStartedAt > 0L) {
            ((System.currentTimeMillis() - OverlayState.deployStartedAt) / 1000L).coerceAtLeast(0L)
        } else {
            0L
        }

        val color = when {
            snapshot.error != null -> "#EF4444"
            snapshot.online -> "#22C55E"
            OverlayState.deployStartedAt > 0L -> "#F59E0B"
            else -> "#94A3B8"
        }
        (dot?.background as? GradientDrawable)?.setColor(Color.parseColor(color))

        titleText?.text = when {
            snapshot.error != null -> "反代助手 · 出错了"
            snapshot.online -> "反代助手 · 运行中 :${snapshot.port}"
            OverlayState.deployStartedAt > 0L -> "反代助手 · 搭建中"
            else -> "反代助手"
        }

        stageText?.text = when {
            snapshot.error != null -> "阶段：失败（${snapshot.error}）"
            snapshot.online -> "阶段：服务已启动"
            else -> "阶段：${snapshot.stage}"
        }

        guideText?.text = "👉 ${snapshot.guidance}"

        if (!reportMode) {
            aiText?.text = snapshot.aiHint?.let { "AI：$it" }
                ?: "AI：还没有结论，点「🩺 报错」让我看看"
        }

        elapsedText?.text = if (OverlayState.deployStartedAt > 0L) {
            "已用时 ${elapsedSec / 60} 分 ${elapsedSec % 60} 秒 · 拖动可移动 · 点一下展开/收起"
        } else {
            "拖动可移动 · 点一下展开/收起"
        }

        if (!expanded && !reportMode) {
            detailsBox?.visibility = View.GONE
            detailsScroll?.visibility = View.GONE
        }
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { startActivity(intent) }
    }

    /** 抓日志 + 当前屏幕文本，交给 AI 解析错误原因。 */
    private fun runDiagnose() {
        val prefs = SettingsRepository(applicationContext)
        val settings = prefs.load()
        val serviceOnline = OverlayState.snapshot.online
        val useLocal = prefs.agentUseLocal || serviceOnline
        if (!useLocal && prefs.agentApiKey.isBlank()) {
            showReport("还没有可用的 AI：请打开 App 的「解疑」页底部，选择「外接模型」填 Key，或先启动本机反代。")
            return
        }
        showReport("正在读取后台日志和屏幕内容，请稍等…")
        diagnosisScope.launch {
            val config = com.aistudio.proxyhelper.agent.AgentConfig(
                baseUrl = prefs.agentBaseUrl,
                apiKey = prefs.agentApiKey,
                model = prefs.agentModel,
                useLocalProxy = useLocal,
                localBaseUrl = "http://127.0.0.1:${settings.port}",
                localModel = prefs.agentLocalModel,
                localApiKey = prefs.agentLocalKey.ifBlank { "aistudio-api" }
            )
            val log = LogCollector.getLog(120)
            val screen = com.aistudio.proxyhelper.automation.AutomationController
                .captureActiveWindowText()
            if (useLocal && prefs.agentLocalModel.isBlank()) {
                showReport("本机反代还没选择模型：打开 App 的「解疑」页底部，切到「本地反代」，拉取模型并选一个。")
                return@launch
            }
            val payload = buildString {
                appendLine("【最近命令日志】")
                appendLine(log.ifBlank { "（空）" })
                appendLine("【当前屏幕文本】")
                appendLine(screen.take(2000).ifBlank { "（读不到屏幕文本）" })
            }
            val answer = runCatching {
                com.aistudio.proxyhelper.agent.AgentEngine().run(
                    config = config,
                    problem = "请根据下面内容判断错误原因，并给出 1-3 步可操作的解决办法：\n$payload",
                    mode = com.aistudio.proxyhelper.agent.AgentMode.TROUBLESHOOT,
                    execute = { _, _ -> "悬浮窗模式下不执行工具，请到 App 内操作" },
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

    /** 放大悬浮窗并显示报错分析结果（可上下滚动）。 */
    private fun showReport(text: String) {
        reportMode = true
        expanded = true
        aiText?.text = "AI 分析：\n$text"
        aiText?.visibility = View.VISIBLE
        elapsedText?.visibility = View.VISIBLE
        detailsScroll?.visibility = View.VISIBLE
        closeButton?.visibility = View.VISIBLE

        val params = panelParams ?: return
        val dm = resources.displayMetrics
        params.width = (dm.widthPixels * 0.92f).toInt()
        params.height = (dm.heightPixels * 0.55f).toInt()
        root?.let { view ->
            runCatching { windowManager?.updateViewLayout(view, params) }
        }
    }

    /** 点 ✕ 收起报错面板，悬浮窗恢复原来的小尺寸。 */
    private fun exitReportMode() {
        reportMode = false
        expanded = false
        detailsScroll?.visibility = View.GONE
        closeButton?.visibility = View.GONE
        val params = panelParams ?: return
        params.width = dp(280)
        params.height = WindowManager.LayoutParams.WRAP_CONTENT
        root?.let { view ->
            runCatching { windowManager?.updateViewLayout(view, params) }
        }
    }

    private fun expandDetails() {
        expanded = true
        detailsBox?.visibility = View.VISIBLE
        render()
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
            .setContentText("正在显示搭建进度与下一步提示")
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
