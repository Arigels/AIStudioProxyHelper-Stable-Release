package com.aistudio.proxyhelper.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.aistudio.proxyhelper.MainActivity
import com.aistudio.proxyhelper.data.ProgressTracker
import com.aistudio.proxyhelper.data.SettingsRepository
import com.aistudio.proxyhelper.net.LogCollector
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 悬浮窗：在 Termux 等其它应用上方显示搭建阶段与运行状态。
 * 只有用户手动开启「显示在其他应用上层」后才会出现。
 */
class OverlayService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .connectTimeout(1200, TimeUnit.MILLISECONDS)
        .readTimeout(1500, TimeUnit.MILLISECONDS)
        .build()

    private var windowManager: WindowManager? = null
    private var bubble: TextView? = null
    private var running = true
    private var lastText = ""

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            refresh()
            handler.postDelayed(this, 1500L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundSafely()
        windowManager = getSystemService(WINDOW_SERVICE) as? WindowManager
        addBubble()
        handler.post(ticker)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        bubble?.let { view ->
            runCatching { windowManager?.removeView(view) }
        }
        bubble = null
        super.onDestroy()
    }

    private fun addBubble() {
        if (bubble != null) return
        if (!Settings.canDrawOverlays(this)) return

        val text = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(14), dp(9), dp(14), dp(9))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.parseColor("#CC111827"))
                setStroke(dp(1), Color.parseColor("#66FFFFFF"))
            }
            text = "反代助手：准备中"
            setOnClickListener {
                val intent = Intent(this@OverlayService, MainActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            }
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
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(80)
        }

        runCatching {
            windowManager?.addView(text, params)
            bubble = text
        }
    }

    private fun refresh() {
        val settings = SettingsRepository(applicationContext).load()
        val online = isServiceOnline(settings.port)
        val log = LogCollector.getLog(200)
        val phase = ProgressTracker.analyze(log)
        val elapsedSec = if (OverlayState.deployStartedAt > 0L) {
            ((System.currentTimeMillis() - OverlayState.deployStartedAt) / 1000L).coerceAtLeast(0L)
        } else {
            0L
        }

        val text = when {
            online -> "● 运行中 :${settings.port}"
            phase.error != null -> "✕ ${phase.error}"
            phase.done -> "● 服务已启动"
            OverlayState.deployStartedAt > 0L ->
                "◐ ${phase.label} · ${elapsedSec / 60}:${(elapsedSec % 60).toString().padStart(2, '0')}"
            else -> "○ 未运行"
        }

        if (text != lastText) {
            lastText = text
            bubble?.text = text
        }
    }

    private fun isServiceOnline(port: Int): Boolean {
        return try {
            val request = Request.Builder()
                .url("http://127.0.0.1:$port/health")
                .get()
                .build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
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
            .setContentText("正在显示搭建进度")
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

/** 与 ViewModel 共享的搭建起始时间，供悬浮窗计算已用时长。 */
object OverlayState {
    @Volatile
    var deployStartedAt: Long = 0L
}
