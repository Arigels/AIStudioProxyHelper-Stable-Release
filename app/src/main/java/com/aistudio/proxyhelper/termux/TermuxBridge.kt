package com.aistudio.proxyhelper.termux

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.aistudio.proxyhelper.automation.AutomationController
import com.aistudio.proxyhelper.data.ProxySettings
import com.aistudio.proxyhelper.data.TermuxAction

sealed class TermuxDispatchResult {
    abstract val command: String

    data class SentToTermux(override val command: String) : TermuxDispatchResult()

    data class AutomatedByAccessibility(override val command: String) : TermuxDispatchResult()

    data class FallbackCopied(
        override val command: String,
        val reason: String
    ) : TermuxDispatchResult()
}

object TermuxBridge {

    const val TERMUX_PACKAGE = "com.termux"
    const val PERMISSION_RUN_COMMAND = "com.termux.permission.RUN_COMMAND"
    private const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
    private const val SERVICE_RUN_COMMAND = "com.termux.app.RunCommandService"
    private const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
    private const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
    private const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
    private const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
    private const val EXTRA_SESSION_ACTION = "com.termux.RUN_COMMAND_SESSION_ACTION"

    private const val TERMUX_BASH = "/data/data/com.termux/files/usr/bin/bash"
    private const val TERMUX_HOME = "/data/data/com.termux/files/home"

    /** 把命令输出同时写文件，并回传到 App 的本地日志收集器。 */
    private const val CAPTURE_SUFFIX =
        " 2>&1 | tee ~/.aistudio_last.log; " +
            "curl -s -m 5 -X POST --data-binary @~/.aistudio_last.log " +
            "http://127.0.0.1:8099/log >/dev/null 2>&1 || true"

    const val URL_TERMUX_FDROID = "https://f-droid.org/packages/com.termux/"
    const val URL_TERMUX_GITHUB = "https://github.com/termux/termux-app/releases"
    const val URL_UPSTREAM_REPO = "https://github.com/DeconstructedCube/aistudio-api"
    const val URL_AISTUDIO = "https://aistudio.google.com/"

    // 国内可访问的浏览器与扩展下载渠道
    const val URL_EDGE_ANDROID = "https://www.microsoft.com/zh-cn/edge/download"
    const val URL_COOKIE_EDITOR_EDGE =
        "https://microsoftedge.microsoft.com/addons/detail/cookieeditor/neaplmfkghagebokkhpjpoebhdledlfi?hl=zh-CN"
    const val URL_FIREFOX_ANDROID = "https://www.mozilla.org/zh-CN/firefox/android/"
    const val URL_FIREFOX_ARCHIVE = "https://archive.mozilla.org/pub/fenix/releases/"
    const val URL_COOKIE_EDITOR = "https://addons.mozilla.org/zh-CN/firefox/addon/cookie-editor/"
    // 安卓版火狐要从 AMO 的 android 专区进，桌面页在手机上只会给 .xpi 下载
    const val URL_COOKIE_EDITOR_FIREFOX_ANDROID =
        "https://addons.mozilla.org/zh-CN/android/addon/cookie-editor/"
    const val PACKAGE_EDGE = "com.microsoft.emmx"
    const val PACKAGE_EDGE_BETA = "com.microsoft.emmx.beta"
    const val PACKAGE_EDGE_CANARY = "com.microsoft.emmx.canary"
    const val PACKAGE_FIREFOX = "org.mozilla.firefox"
    const val PACKAGE_FIREFOX_BETA = "org.mozilla.firefox_beta"
    const val PACKAGE_FIREFOX_NIGHTLY = "org.mozilla.fenix"
    const val PACKAGE_FENNEC = "org.mozilla.fennec_fdroid"
    const val PACKAGE_LEMUR = "com.lemurbrowser.exts"

    /**
     * Build the exact bash command for each action, matching the official aistudio-api Termux guide.
     */
    fun buildCommand(action: TermuxAction, settings: ProxySettings): String {
        val dirExpr = normalizeDirExpr(settings.installDir)
        val repoQuoted = shellQuote(settings.repoUrl.trim())
        val port = settings.port.coerceIn(1024, 65535)
        val proxyFlag = settings.proxyUrl.trim().takeIf { it.isNotBlank() }?.let {
            " --proxy ${shellQuote(it)}"
        } ?: ""

        val envExports = buildList {
            settings.proxyUrl.trim().takeIf { it.isNotBlank() }?.let {
                val q = shellQuote(it)
                add("export AISTUDIO_PROXY=$q")
                add("export HTTP_PROXY=$q")
                add("export HTTPS_PROXY=$q")
            }
            settings.webPassword.trim().takeIf { it.isNotBlank() }?.let {
                add("export AISTUDIO_WEB_PASSWORD=${shellQuote(it)}")
            }
            add("export AISTUDIO_PORT=$port")
        }.joinToString(" && ")

        val ensureRepoBlock = buildString {
            append("pkg update -y && pkg install -y git && ")
            append("if [ -d $dirExpr/.git ]; then ")
            append("cd $dirExpr && git pull --ff-only || true; ")
            append("else ")
            append("git clone $repoQuoted $dirExpr && cd $dirExpr; ")
            append("fi")
        }

        val enableExternalBlock =
            "(mkdir -p ~/.termux && " +
                "(grep -q '^allow-external-apps' ~/.termux/termux.properties 2>/dev/null " +
                "&& sed -i 's/^allow-external-apps.*/allow-external-apps = true/' ~/.termux/termux.properties " +
                "|| echo 'allow-external-apps = true' >> ~/.termux/termux.properties) && " +
                "termux-reload-settings || true)"

        return when (action) {
            TermuxAction.ENABLE_EXTERNAL_APPS -> {
                "$enableExternalBlock && echo '[OK] 已开启 Termux 一键调用，现在可返回反代助手！'"
            }

            TermuxAction.INSTALL_AND_START -> {
                "($enableExternalBlock && (termux-wake-lock 2>/dev/null || true) && " +
                    "$envExports && $ensureRepoBlock && bash install.sh --start --port $port$proxyFlag)" +
                    CAPTURE_SUFFIX
            }

            TermuxAction.INSTALL_ONLY -> {
                "$envExports && $ensureRepoBlock && bash install.sh$proxyFlag"
            }

            TermuxAction.START_SERVICE -> {
                "if [ ! -d $dirExpr ]; then " +
                    "echo '[错误] 尚未安装 aistudio-api，请先执行一键安装。'; exit 1; " +
                    "fi && " +
                    "if pgrep -f 'main.py server' >/dev/null 2>&1; then " +
                    "pkill -f 'main.py server' 2>/dev/null || true; " +
                    "pkill -f 'waa-signer' 2>/dev/null || true; sleep 1; " +
                    "fi && " +
                    "cd $dirExpr && $envExports && (termux-wake-lock 2>/dev/null || true) && bash start.sh --port $port"
            }

            TermuxAction.STOP_SERVICE -> {
                "if pkill -f 'main.py server' 2>/dev/null || pkill -f 'waa-signer' 2>/dev/null; then " +
                    "echo '[OK] 已停止反代进程'; " +
                    "else echo '[提示] 没有找到正在运行的反代进程（可能本来就没启动）'; fi"
            }

            TermuxAction.UPDATE_AND_REBUILD -> {
                "if [ ! -d $dirExpr/.git ]; then " +
                    "echo '[错误] 未检测到仓库目录，请先执行一键安装。'; exit 1; " +
                    "fi && cd $dirExpr && $envExports && git pull --ff-only && bash install.sh --rebuild$proxyFlag"
            }

            TermuxAction.ACQUIRE_WAKELOCK -> {
                "if command -v termux-wake-lock >/dev/null 2>&1; then " +
                    "termux-wake-lock && echo '[OK] 已申请唤醒锁：Termux 会尽量保持 CPU 唤醒，减少锁屏后被挂起。' " +
                    "&& echo '[提示] 唤醒锁不能防止系统杀后台，请同时在「电池 → 本 App/Termux」里设为「无限制」。'; " +
                    "else echo '[错误] 没有找到 termux-wake-lock，请确认用的是 F-Droid 版 Termux 且 termux-tools 完整。'; fi"
            }
        }
    }

    /**
     * Execute an action in Termux using the smoothest available method:
     * 1) If preferAccessibility is true (e.g. first-time install before external-apps is ready)
     *    and Accessibility auto-controller is active -> auto-open Termux, paste & press Enter.
     * 2) Try official Termux RUN_COMMAND IPC.
     * 3) If IPC fails and Accessibility is active -> auto-open Termux, paste & press Enter.
     * 4) Otherwise -> copy command & open Termux for one-tap manual paste.
     */
    fun runInTermux(
        context: Context,
        action: TermuxAction,
        settings: ProxySettings,
        preferAccessibility: Boolean = false
    ): TermuxDispatchResult {
        val command = buildCommand(action, settings)

        if (!isTermuxInstalled(context)) {
            copyToClipboard(context, action.title, command)
            return TermuxDispatchResult.FallbackCopied(
                command = command,
                reason = "未检测到 Termux，请先安装 64 位版本后再试。"
            )
        }

        // 首次 / 优先走无障碍：交给 AccessibilityService 全自动完成
        if (preferAccessibility && AutomationController.isServiceConnected()) {
            if (AutomationController.enqueueAndRun(context, command, action.title)) {
                return TermuxDispatchResult.AutomatedByAccessibility(command)
            }
        }

        // 无障碍不可用时，退化为手动粘贴：复制命令并打开 Termux
        if (action == TermuxAction.ENABLE_EXTERNAL_APPS) {
            if (AutomationController.isServiceEnabled(context) &&
                AutomationController.enqueueAndRun(context, command, action.title)
            ) {
                return TermuxDispatchResult.AutomatedByAccessibility(command)
            }
            copyToClipboard(context, "开启 Termux 一键调用", command)
            openTermux(context)
            return TermuxDispatchResult.FallbackCopied(
                command = command,
                reason = "已复制设置命令并打开 Termux，长按屏幕点「粘贴」并回车执行一次即可。"
            )
        }

        // 命令执行完后保留终端界面，方便用户查看日志和报错
        val wrappedForInteractive = if (action.runInBackground) {
            command
        } else {
            "$command; echo ''; echo '===== 任务执行结束（按回车退出当前窗口）====='; read -r _"
        }

        val intent = Intent(ACTION_RUN_COMMAND).apply {
            setClassName(TERMUX_PACKAGE, SERVICE_RUN_COMMAND)
            putExtra(EXTRA_COMMAND_PATH, TERMUX_BASH)
            putExtra(EXTRA_ARGUMENTS, arrayOf("-lc", wrappedForInteractive))
            putExtra(EXTRA_WORKDIR, TERMUX_HOME)
            putExtra(EXTRA_BACKGROUND, action.runInBackground)
            putExtra(EXTRA_SESSION_ACTION, if (action.runInBackground) "2" else "0")
        }

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            TermuxDispatchResult.SentToTermux(command)
        } catch (_: Exception) {
            if (AutomationController.isServiceConnected() &&
                AutomationController.enqueueAndRun(context, command, action.title)
            ) {
                TermuxDispatchResult.AutomatedByAccessibility(command)
            } else {
                copyToClipboard(context, action.title, command)
                TermuxDispatchResult.FallbackCopied(
                    command = command,
                    reason = "已复制「${action.title}」命令，请在弹窗里点「打开 Termux 去粘贴」后长按粘贴并回车。"
                )
            }
        }
    }

    /**
     * Explicit manual copy + open Termux (when user chooses to skip Accessibility).
     */
    fun copyAndOpenTermux(
        context: Context,
        action: TermuxAction,
        settings: ProxySettings
    ): TermuxDispatchResult.FallbackCopied {
        val command = buildCommand(action, settings)
        copyToClipboard(context, action.title, command)
        openTermux(context)
        return TermuxDispatchResult.FallbackCopied(
            command = command,
            reason = "已复制「${action.title}」命令并打开 Termux，请长按屏幕点「粘贴」并回车执行。"
        )
    }

    private fun isTermuxInstalled(context: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    TERMUX_PACKAGE,
                    android.content.pm.PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun copyToClipboard(context: Context, label: String, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    fun readFromClipboard(context: Context): String {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return ""
        val clip = clipboard.primaryClip ?: return ""
        if (clip.itemCount <= 0) return ""
        return clip.getItemAt(0).coerceToText(context)?.toString() ?: ""
    }

    fun openTermux(context: Context): Boolean {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(TERMUX_PACKAGE)
            ?: return false
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(launchIntent)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun openUrl(context: Context, url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            // 没有可用的浏览器
        }
    }

    /** 指定用某个浏览器打开链接（如 Edge / 火狐），避免被系统默认浏览器截走；失败则回退系统默认打开。 */
    fun openUrlInPackage(context: Context, url: String, packageName: String?): Boolean {
        if (!packageName.isNullOrBlank()) {
            val targeted = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                setPackage(packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val ok = runCatching { context.startActivity(targeted) }.isSuccess
            if (ok) return true
        }
        openUrl(context, url)
        return false
    }

    /** 执行任意 shell 命令（供 AI 助手排障使用），输出会回传到 App 日志。 */
    fun runRawCommand(context: Context, command: String): Boolean {
        if (command.isBlank()) return false
        val wrapped = "($command)$CAPTURE_SUFFIX"
        val intent = Intent(ACTION_RUN_COMMAND).apply {
            setClassName(TERMUX_PACKAGE, SERVICE_RUN_COMMAND)
            putExtra(EXTRA_COMMAND_PATH, TERMUX_BASH)
            putExtra(EXTRA_ARGUMENTS, arrayOf("-lc", wrapped))
            putExtra(EXTRA_WORKDIR, TERMUX_HOME)
            putExtra(EXTRA_BACKGROUND, true)
            putExtra(EXTRA_SESSION_ACTION, "2")
        }
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** 尝试直接打开指定包名的 App（如已安装的火狐）。 */
    fun launchPackage(context: Context, packageName: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun normalizeDirExpr(raw: String): String {
        val trimmed = raw.trim().ifBlank { "\$HOME/aistudio-api" }
        return when {
            trimmed == "~" -> "\"\$HOME\""
            trimmed.startsWith("~/") -> "\"\$HOME/${trimmed.removePrefix("~/").replace("\"", "\\\"")}\""
            trimmed.startsWith("\$HOME") -> "\"${trimmed.replace("\"", "\\\"")}\""
            else -> shellQuote(trimmed)
        }
    }

    private fun shellQuote(value: String): String {
        return "'${value.replace("'", "'\"'\"'")}'"
    }
}
