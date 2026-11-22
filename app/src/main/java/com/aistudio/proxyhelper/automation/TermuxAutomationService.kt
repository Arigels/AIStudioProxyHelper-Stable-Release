package com.aistudio.proxyhelper.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import com.aistudio.proxyhelper.termux.TermuxBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Accessibility-based automation service that handles opening Termux,
 * waiting for its terminal prompt (including first-time bootstrap),
 * long-pressing to Paste the command, and pressing Enter automatically.
 *
 * Strictly inert unless the user explicitly triggers an action inside our App.
 */
class TermuxAutomationService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var runningJob: Job? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        AutomationController.activeService = this
        if (AutomationController.pendingTask != null) {
            kickPendingTask()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val task = AutomationController.pendingTask ?: return
        if (task.isExpired()) {
            AutomationController.clearTask(null)
            return
        }
        if (runningJob?.isActive != true) {
            kickPendingTask()
        }
    }

    override fun onInterrupt() {
        runningJob?.cancel()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (AutomationController.activeService === this) {
            AutomationController.activeService = null
        }
        scope.cancel()
    }

    fun kickPendingTask() {
        val task = AutomationController.pendingTask ?: return
        if (task.isExpired()) {
            AutomationController.clearTask(null)
            return
        }
        if (runningJob?.isActive == true) return

        runningJob = scope.launch {
            runTaskFlow(task)
        }
    }

    private suspend fun runTaskFlow(task: PendingAutomationTask) {
        try {
            AutomationController.updateStatus("正在等待 Termux 终端就绪...")

            // Step 1: Wait for Termux terminal_view to appear (also handles first-run bootstrap & permission popups)
            val terminalBounds = waitForTermuxTerminalReady(task)
            if (terminalBounds == null) {
                AutomationController.clearTask("未能自动定位 Termux 终端，命令已复制到剪贴板，请手动长按粘贴并回车。")
                showToast("命令已复制，请在 Termux 中长按粘贴并回车")
                return
            }

            // Let bash prompt settle cleanly
            delay(1100L)
            if (AutomationController.pendingTask !== task) return

            // Step 2: Long-press terminal -> click Paste (retry up to 3 times)
            AutomationController.updateStatus("正在自动粘贴命令...")
            var pasted = false
            for (attempt in 1..3) {
                if (AutomationController.pendingTask !== task) return
                val freshBounds = findTermuxTerminalBounds() ?: terminalBounds
                val pressX = freshBounds.centerX().toFloat()
                val pressY = (freshBounds.top + freshBounds.height() * 0.35f)
                    .coerceIn(freshBounds.top + 40f, freshBounds.bottom - 40f)

                performPress(pressX, pressY, durationMs = 750L)

                val pasteClicked = waitForAndClickPaste(maxAttempts = 8, intervalMs = 300L)
                if (pasteClicked) {
                    pasted = true
                    break
                }
                delay(500L)
            }

            if (!pasted) {
                AutomationController.clearTask("自动粘贴未触发，命令已在剪贴板，请长按屏幕点「粘贴」并回车。")
                showToast("命令已复制，请长按 Termux 屏幕点「粘贴」并回车")
                return
            }

            // Step 3: 等待粘贴完成，然后按回车执行（多策略 + 自动重试）
            delay(900L)
            if (AutomationController.pendingTask !== task) return

            AutomationController.updateStatus("正在自动按下回车执行...")
            var enterPressed = false
            for (attempt in 1..2) {
                if (AutomationController.pendingTask !== task) return
                enterPressed = pressKeyboardEnter(terminalBounds)
                if (enterPressed) break
                delay(700L)
            }

            AutomationController.clearTask("已自动向 Termux 输入并启动「${task.label}」！")
            if (enterPressed) {
                showToast("已自动输入并开始执行！若没反应请再点一下键盘「回车」")
            } else {
                showToast("命令已粘贴，请点一下键盘上的「回车」开始执行")
            }
        } catch (_: Exception) {
            AutomationController.clearTask("自动操作中断，命令已在剪贴板，可直接在 Termux 长按粘贴并回车。")
            showToast("命令已复制，请长按 Termux 粘贴并回车")
        }
    }

    private suspend fun waitForTermuxTerminalReady(task: PendingAutomationTask): Rect? {
        val deadline = System.currentTimeMillis() + 40_000L
        while (System.currentTimeMillis() < deadline) {
            if (AutomationController.pendingTask !== task) return null

            // Auto-accept Android runtime permission dialog if Termux asks on first launch (e.g., Notifications)
            tryAcceptSystemPermissionDialog()

            val termuxRoot = findTermuxRootNode()
            if (termuxRoot != null) {
                val isBootstrapping = hasBootstrapProgress(termuxRoot)
                if (!isBootstrapping) {
                    val bounds = findTermuxTerminalBounds(termuxRoot)
                    if (bounds != null && bounds.width() > 100 && bounds.height() > 100) {
                        return bounds
                    }
                } else {
                    AutomationController.updateStatus("Termux 正在首次初始化环境，请稍候...")
                }
            }
            delay(600L)
        }
        return null
    }

    private fun tryAcceptSystemPermissionDialog() {
        val activeRoot = rootInActiveWindow ?: return
        val pkg = activeRoot.packageName?.toString() ?: return
        if (!pkg.contains("permissioncontroller", ignoreCase = true)) return

        val allowKeywords = setOf(
            "允许", "始终允许", "仅在使用中允许", "使用应用时允许",
            "allow", "while using the app"
        )
        val allowNode = findNodeMatching(activeRoot) { node ->
            val cls = node.className?.toString().orEmpty()
            if (!cls.contains("Button", ignoreCase = true) && !node.isClickable) return@findNodeMatching false
            val text = node.text?.toString()?.trim()?.lowercase().orEmpty()
            text in allowKeywords
        }
        allowNode?.let { clickNodeOrParent(it) }
    }

    private fun findTermuxRootNode(): AccessibilityNodeInfo? {
        val active = rootInActiveWindow
        if (active?.packageName?.toString() == TermuxBridge.TERMUX_PACKAGE) {
            return active
        }
        for (w in windows.orEmpty()) {
            val root = w.root ?: continue
            if (root.packageName?.toString() == TermuxBridge.TERMUX_PACKAGE) {
                return root
            }
        }
        return null
    }

    private fun hasBootstrapProgress(root: AccessibilityNodeInfo): Boolean {
        return findNodeMatching(root) { node ->
            val cls = node.className?.toString().orEmpty()
            val text = node.text?.toString()?.lowercase().orEmpty()
            cls.contains("ProgressBar", ignoreCase = true) ||
                text.contains("installing bootstrap") ||
                text.contains("正在安装")
        } != null
    }

    private fun findTermuxTerminalBounds(root: AccessibilityNodeInfo? = findTermuxRootNode()): Rect? {
        if (root == null) return null
        val byId = root.findAccessibilityNodeInfosByViewId("com.termux:id/terminal_view")
            ?.firstOrNull()
        if (byId != null) {
            val rect = Rect()
            byId.getBoundsInScreen(rect)
            if (!rect.isEmpty) return rect
        }
        val byClass = findNodeMatching(root) { node ->
            node.className?.toString()?.contains("TerminalView", ignoreCase = true) == true
        }
        if (byClass != null) {
            val rect = Rect()
            byClass.getBoundsInScreen(rect)
            if (!rect.isEmpty) return rect
        }
        val fallback = Rect()
        root.getBoundsInScreen(fallback)
        return fallback.takeIf { !it.isEmpty }
    }

    private suspend fun waitForAndClickPaste(maxAttempts: Int, intervalMs: Long): Boolean {
        val pasteExact = setOf("paste", "粘贴", "貼上", "贴上")
        repeat(maxAttempts) {
            delay(intervalMs)
            val candidateRoots = buildList {
                rootInActiveWindow?.let { add(it) }
                windows.orEmpty().forEach { w -> w.root?.let { add(it) } }
            }
            for (root in candidateRoots) {
                val pasteNode = findNodeMatching(root) { node ->
                    val t = node.text?.toString()?.trim()?.lowercase().orEmpty()
                    val d = node.contentDescription?.toString()?.trim()?.lowercase().orEmpty()
                    t in pasteExact || d in pasteExact
                }
                if (pasteNode != null) {
                    if (clickNodeOrParent(pasteNode)) return true
                    val rect = Rect()
                    pasteNode.getBoundsInScreen(rect)
                    if (!rect.isEmpty) {
                        performPress(rect.centerX().toFloat(), rect.centerY().toFloat(), durationMs = 60L)
                        return true
                    }
                }
            }
        }
        return false
    }

    private suspend fun pressKeyboardEnter(terminalBounds: Rect): Boolean {
        // 只使用「能确认是回车键」的方式，绝不按坐标盲点（盲点可能点到删除键，把命令改坏）
        tryTriggerImeEnter()
        delay(400L)

        var imeWindow = findImeWindow()
        if (imeWindow == null) {
            performPress(
                terminalBounds.centerX().toFloat(),
                (terminalBounds.top + terminalBounds.height() * 0.30f)
                    .coerceIn(terminalBounds.top + 30f, terminalBounds.bottom - 30f),
                durationMs = 60L
            )
            delay(800L)
            imeWindow = findImeWindow()
        }
        if (imeWindow == null) return false

        val imeRoot = imeWindow.root ?: return false
        val enterNode = findEnterKeyNode(imeRoot) ?: return false

        // 二次校验：确认这个节点确实是回车语义，避免误点是删除/退格
        val text = enterNode.text?.toString()?.trim().orEmpty()
        val desc = enterNode.contentDescription?.toString()?.trim().orEmpty()
        if (!looksLikeEnter(text) && !looksLikeEnter(desc)) return false
        if (looksLikeDelete(text) || looksLikeDelete(desc)) return false

        if (clickNodeOrParent(enterNode)) return true
        val rect = Rect()
        enterNode.getBoundsInScreen(rect)
        if (!rect.isEmpty) {
            performPress(rect.centerX().toFloat(), rect.centerY().toFloat(), 60L)
            return true
        }
        return false
    }

    private fun looksLikeDelete(value: String): Boolean {
        if (value.isBlank()) return false
        val v = value.trim().lowercase()
        return v.contains("删除") || v.contains("退格") || v.contains("delete") ||
            v.contains("backspace") || v.contains("del")
    }

    private fun findImeWindow(): AccessibilityWindowInfo? {
        return windows.orEmpty().firstOrNull { w ->
            w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD
        }
    }

    /**
     * 尝试调用 AccessibilityAction.ACTION_IME_ENTER（API 30+）。
     * 该常量不在所有 SDK 版本里暴露，用反射避免编译期依赖。
     */
    private fun tryTriggerImeEnter() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val focused = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return
        val action = try {
            val cls = Class.forName("android.view.accessibility.AccessibilityNodeInfo\$AccessibilityAction")
            cls.getField("ACTION_IME_ENTER").get(null) as? AccessibilityNodeInfo.AccessibilityAction
        } catch (_: Throwable) {
            null
        }
        if (action != null) {
            val actionId = action.id
            runCatching { focused.performAction(actionId) }
        }
    }

    private fun findEnterKeyNode(imeRoot: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        return findNodeMatching(imeRoot) { node ->
            val t = node.text?.toString()?.trim().orEmpty()
            val d = node.contentDescription?.toString()?.trim().orEmpty()
            val combined = "$t $d".lowercase()
            if (combined.contains("delete") || combined.contains("backspace") || combined.contains("删除")) {
                return@findNodeMatching false
            }
            looksLikeEnter(t) || looksLikeEnter(d)
        }
    }

    /**
     * 在输入法键盘里找最右下角的按键（通常是回车/发送键）。
     * 优先匹配回车语义的描述，其次接受无文字的图标键。
     */
    private fun findBottomRightKeyNode(imeRoot: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val imeRect = Rect()
        imeRoot.getBoundsInScreen(imeRect)
        if (imeRect.isEmpty) return null

        val minKeyWidth = (imeRect.width() * 0.06f).toInt()
        val maxKeyWidth = (imeRect.width() * 0.22f).toInt()
        val bottomRowTop = imeRect.top + imeRect.height() * 0.55f

        var best: AccessibilityNodeInfo? = null
        var bestRight = -1
        var bestArea = Int.MAX_VALUE

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(imeRoot)
        var visited = 0
        while (queue.isNotEmpty() && visited < 900) {
            val node = queue.removeFirst()
            visited++
            val r = Rect()
            node.getBoundsInScreen(r)
            val w = r.width()
            if (!r.isEmpty && w in minKeyWidth..maxKeyWidth &&
                r.top >= bottomRowTop && r.bottom <= imeRect.bottom + 8
            ) {
                val text = node.text?.toString()?.trim().orEmpty()
                val desc = node.contentDescription?.toString()?.trim().orEmpty()
                val isEnterish = looksLikeEnter(text) || looksLikeEnter(desc)
                val isBlankKey = text.isEmpty() && desc.isEmpty()
                if (isEnterish || isBlankKey) {
                    val area = w * r.height()
                    if (r.right > bestRight || (r.right == bestRight && area < bestArea)) {
                        bestRight = r.right
                        bestArea = area
                        best = node
                    }
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return best
    }

    private fun looksLikeEnter(value: String): Boolean {
        if (value.isBlank()) return false
        val v = value.trim().lowercase()
        val exact = setOf(
            "enter", "return", "go", "send", "done", "search", "next",
            "回车", "换行", "确定", "完成", "发送", "执行", "前往", "搜索"
        )
        return v in exact ||
            v.contains("回车") ||
            v.contains("换行") ||
            v.contains("enter") ||
            v.contains("send")
    }

    private fun clickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        var curr: AccessibilityNodeInfo? = node
        var depth = 0
        while (curr != null && depth < 5) {
            if (curr.isClickable && curr.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            curr = curr.parent
            depth++
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun findNodeMatching(
        root: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 600) {
            val node = queue.removeFirst()
            visited++
            if (predicate(node)) return node
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    private suspend fun performPress(x: Float, y: Float, durationMs: Long): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return suspendCancellableCoroutine { cont ->
            val dispatched = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                },
                null
            )
            if (!dispatched && cont.isActive) {
                cont.resume(false)
            }
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
    }
}
