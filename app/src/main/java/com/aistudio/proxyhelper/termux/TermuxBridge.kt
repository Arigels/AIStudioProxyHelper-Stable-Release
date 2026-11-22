package com.aistudio.proxyhelper.termux

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.aistudio.proxyhelper.data.ProxySettings
import com.aistudio.proxyhelper.data.TermuxAction

sealed class TermuxDispatchResult {
    data class SentToTermux(val command: String) : TermuxDispatchResult()
    data class FallbackCopied(val command: String, val reason: String) : TermuxDispatchResult()
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

    const val URL_TERMUX_FDROID = "https://f-droid.org/packages/com.termux/"
    const val URL_TERMUX_GITHUB = "https://github.com/termux/termux-app/releases"
    const val URL_UPSTREAM_REPO = "https://github.com/DeconstructedCube/aistudio-api"
    const val URL_AISTUDIO = "https://aistudio.google.com/"

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

        return when (action) {
            TermuxAction.ENABLE_EXTERNAL_APPS -> {
                "mkdir -p ~/.termux && " +
                    "(grep -q '^allow-external-apps' ~/.termux/termux.properties 2>/dev/null " +
                    "&& sed -i 's/^allow-external-apps.*/allow-external-apps = true/' ~/.termux/termux.properties " +
                    "|| echo 'allow-external-apps = true' >> ~/.termux/termux.properties) && " +
                    "termux-reload-settings && echo '[OK] 已开启 allow-external-apps，现在可返回反代助手一键操作！'"
            }

            TermuxAction.INSTALL_AND_START -> {
                "$envExports && $ensureRepoBlock && bash install.sh --start --port $port$proxyFlag"
            }

            TermuxAction.INSTALL_ONLY -> {
                "$envExports && $ensureRepoBlock && bash install.sh$proxyFlag"
            }

            TermuxAction.START_SERVICE -> {
                "if [ ! -d $dirExpr ]; then " +
                    "echo '[错误] 尚未安装 aistudio-api，请先执行一键安装。'; exit 1; " +
                    "fi && cd $dirExpr && $envExports && (termux-wake-lock 2>/dev/null || true) && bash start.sh --port $port"
            }

            TermuxAction.STOP_SERVICE -> {
                "pkill -f 'main.py server' 2>/dev/null || true; " +
                    "pkill -f 'waa-signer' 2>/dev/null || true; " +
                    "echo '[OK] 已发送停止信号'"
            }

            TermuxAction.UPDATE_AND_REBUILD -> {
                "if [ ! -d $dirExpr/.git ]; then " +
                    "echo '[错误] 未检测到仓库目录，请先执行一键安装。'; exit 1; " +
                    "fi && cd $dirExpr && $envExports && git pull --ff-only && bash install.sh --rebuild$proxyFlag"
            }

            TermuxAction.ACQUIRE_WAKELOCK -> {
                "termux-wake-lock && echo '[OK] Termux 唤醒锁已激活'"
            }
        }
    }

    /**
     * Dispatch a command via Termux's official RUN_COMMAND service intent.
     * Falls back to copying the command to clipboard if Termux rejects the intent.
     */
    fun runInTermux(
        context: Context,
        action: TermuxAction,
        settings: ProxySettings
    ): TermuxDispatchResult {
        val command = buildCommand(action, settings)

        // Enabling allow-external-apps itself requires pasting into Termux first
        if (action == TermuxAction.ENABLE_EXTERNAL_APPS) {
            copyToClipboard(context, "一次性开启 Termux 一键调用", command)
            openTermux(context)
            return TermuxDispatchResult.FallbackCopied(
                command = command,
                reason = "已复制「一次性开启 Termux 一键调用」的设置命令并打开 Termux。" +
                    "这是权限设置（不是安装命令），粘贴回车执行一次即可，之后按钮就能全自动执行。"
            )
        }

        // Keep terminal open after command finishes so user can inspect logs/errors
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
        } catch (e: SecurityException) {
            setupCommandFallback(context, settings)
        } catch (e: Exception) {
            if (!isTermuxInstalled(context)) {
                copyToClipboard(context, action.title, command)
                TermuxDispatchResult.FallbackCopied(
                    command = command,
                    reason = "未检测到 Termux，请先从 F-Droid 或 GitHub 安装 64 位版本后再试（安装命令已复制备用）。"
                )
            } else {
                setupCommandFallback(context, settings)
            }
        }
    }

    /**
     * Termux 拒绝执行（最常见原因是尚未开启 allow-external-apps）。
     * 此时只复制「一次性开启一键调用」的设置命令，避免让用户误把完整安装命令当成权限设置。
     */
    private fun setupCommandFallback(
        context: Context,
        settings: ProxySettings
    ): TermuxDispatchResult {
        val setupCmd = buildCommand(TermuxAction.ENABLE_EXTERNAL_APPS, settings)
        copyToClipboard(context, "一次性开启 Termux 一键调用", setupCmd)
        openTermux(context)
        return TermuxDispatchResult.FallbackCopied(
            command = setupCmd,
            reason = "还差一步：已复制「一次性开启 Termux 一键调用」的设置命令并打开 Termux，" +
                "粘贴回车执行一次即可，之后按钮就能全自动执行。"
        )
    }

    private fun isTermuxInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
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
            // No browser available
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
