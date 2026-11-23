package com.aistudio.proxyhelper.automation

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.aistudio.proxyhelper.termux.TermuxBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PendingAutomationTask(
    val command: String,
    val label: String,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun isExpired(ttlMs: Long = 50_000L): Boolean {
        return System.currentTimeMillis() - createdAt > ttlMs
    }
}

object AutomationController {

    @Volatile
    var activeService: TermuxAutomationService? = null

    @Volatile
    var pendingTask: PendingAutomationTask? = null
        private set

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    fun isServiceEnabled(context: Context): Boolean {
        if (activeService != null) return true
        val enabled = try {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
        } catch (_: Exception) {
            null
        } ?: return false

        val expectedFull = ComponentName(context, TermuxAutomationService::class.java)
            .flattenToString()
        val expectedShort = ComponentName(context, TermuxAutomationService::class.java)
            .flattenToShortString()

        return enabled.split(':').any { entry ->
            val trimmed = entry.trim()
            trimmed.equals(expectedFull, ignoreCase = true) ||
                trimmed.equals(expectedShort, ignoreCase = true)
        }
    }

    /** 无障碍服务是否已经真正连接（仅开关打开但进程未起时会返回 false）。 */
    fun isServiceConnected(): Boolean = activeService != null

    fun openAccessibilitySettings(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    /**
     * Copy command to clipboard, launch Termux, and trigger the Accessibility auto-controller
     * to automatically paste & press Enter in Termux.
     *
     * @return true if the auto-controller service is running and took over the task.
     */
    fun enqueueAndRun(context: Context, command: String, label: String): Boolean {
        // 末尾补一个换行：部分 Termux 版本粘贴即执行，能直接省掉回车这一步
        TermuxBridge.copyToClipboard(context, label, command + "\n")
        pendingTask = PendingAutomationTask(command = command, label = label)
        _statusMessage.value = "正在打开 Termux 并准备自动输入..."

        val launched = TermuxBridge.openTermux(context)
        if (!launched) {
            pendingTask = null
            _statusMessage.value = null
            return false
        }

        val service = activeService
        if (service != null) {
            service.kickPendingTask()
            return true
        }
        return false
    }

    fun clearTask(finalMessage: String? = null) {
        pendingTask = null
        _statusMessage.value = finalMessage
    }

    fun updateStatus(message: String?) {
        _statusMessage.value = message
    }
}
