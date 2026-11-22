package com.aistudio.proxyhelper.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aistudio.proxyhelper.agent.AgentConfig
import com.aistudio.proxyhelper.agent.AgentEngine
import com.aistudio.proxyhelper.agent.AgentLine
import com.aistudio.proxyhelper.automation.AutomationController
import com.aistudio.proxyhelper.data.EnvironmentStatus
import com.aistudio.proxyhelper.data.ImportResult
import com.aistudio.proxyhelper.data.ProxySettings
import com.aistudio.proxyhelper.data.ServiceStatus
import com.aistudio.proxyhelper.data.SettingsRepository
import com.aistudio.proxyhelper.data.TermuxAction
import com.aistudio.proxyhelper.network.EnvironmentChecker
import com.aistudio.proxyhelper.net.LogCollector
import com.aistudio.proxyhelper.data.ProgressTracker
import com.aistudio.proxyhelper.overlay.OverlaySnapshot
import com.aistudio.proxyhelper.overlay.OverlayState
import com.aistudio.proxyhelper.network.ProxyApiClient
import com.aistudio.proxyhelper.termux.TermuxBridge
import com.aistudio.proxyhelper.termux.TermuxDispatchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class MainUiState(
    val settings: ProxySettings = ProxySettings(),
    val environment: EnvironmentStatus = EnvironmentStatus(),
    val service: ServiceStatus = ServiceStatus(),
    val hasDeployed: Boolean = false,
    val externalAppsSetupDone: Boolean = false,
    val automationStatus: String? = null,
    val deploying: Boolean = false,
    val deployStartedAt: Long = 0L,
    val deployStage: String = "准备中",
    val deployError: String? = null,
    val agentBaseUrl: String = "",
    val agentApiKey: String = "",
    val agentModel: String = "",
    val agentUseLocal: Boolean = false,
    val agentLocalModel: String = "",
    val agentLocalKey: String = "",
    val agentRunning: Boolean = false,
    val agentTesting: Boolean = false,
    val agentTestResult: String? = null,
    val agentLines: List<AgentLine> = emptyList(),
    val agentAnswer: String? = null,
    val importingCookie: Boolean = false,
    val lastImportResult: ImportResult? = null,
    val testingModels: Boolean = false,
    val lastTestResult: Pair<Boolean, String>? = null,
    val lastDispatchedCommand: String? = null,
    val snackbarMessage: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsRepo = SettingsRepository(application)
    private val apiClient = ProxyApiClient()

    private val _uiState = MutableStateFlow(
        MainUiState(
            settings = settingsRepo.load(),
            environment = EnvironmentChecker.inspect(application),
            hasDeployed = settingsRepo.hasDeployed,
            externalAppsSetupDone = settingsRepo.externalAppsSetupDone,
            agentBaseUrl = settingsRepo.agentBaseUrl,
            agentApiKey = settingsRepo.agentApiKey,
            agentModel = settingsRepo.agentModel,
            agentUseLocal = settingsRepo.agentUseLocal,
            agentLocalModel = settingsRepo.agentLocalModel,
            agentLocalKey = settingsRepo.agentLocalKey
        )
    )
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        startStatusPolling()
        viewModelScope.launch {
            AutomationController.statusMessage.collect { msg ->
                _uiState.update { it.copy(automationStatus = msg) }
            }
        }
    }

    private fun startStatusPolling() {
        viewModelScope.launch {
            while (isActive) {
                refreshServiceStatus(silent = true)
                delay(6000L)
            }
        }
    }

    fun refreshEnvironment() {
        val env = EnvironmentChecker.inspect(getApplication())
        _uiState.update { it.copy(environment = env) }
    }

    fun refreshServiceStatus(silent: Boolean = false) {
        viewModelScope.launch {
            if (!silent) {
                _uiState.update { it.copy(service = it.service.copy(checking = true)) }
            }
            val currentSettings = _uiState.value.settings
            val status = apiClient.fetchServiceStatus(currentSettings)
            if (status.online) {
                settingsRepo.hasDeployed = true
                OverlayState.deployStartedAt = 0L
            }
            _uiState.update {
                it.copy(
                    service = status,
                    hasDeployed = it.hasDeployed || status.online,
                    deploying = if (status.online) false else it.deploying,
                    deployStartedAt = if (status.online) 0L else it.deployStartedAt
                )
            }
            updateOverlaySnapshot(status)
        }
    }

    private fun updateOverlaySnapshot(status: ServiceStatus) {
        val env = EnvironmentChecker.inspect(getApplication())
        val phase = ProgressTracker.analyze(LogCollector.getLog(200))
        val state = _uiState.value
        val guidance = when {
            status.online && status.accounts.isEmpty() ->
                "服务已启动，下一步：去「账号」页导入 Cookie"
            status.online && state.lastTestResult?.first != true ->
                "下一步：去「使用」页测试模型，把地址填进酒馆"
            status.online -> "一切就绪，可以连酒馆开玩了"
            phase.error != null && phase.error.contains("梯子") ->
                "梯子可能没开或已断，检查代理后再试"
            phase.error != null && phase.error.contains("403") ->
                "Google 拒绝了访问：先确认梯子正常，再重新导入 Cookie"
            phase.error != null -> "出错了，点开 App 按提示修复"
            state.deploying -> "正在${phase.label}，编译阶段没输出是正常的，请耐心等"
            !env.termuxInstalled -> "还没安装 Termux，去 F-Droid 下载"
            !env.accessibilityEnabled -> "建议开启自动操控权限，可全自动搭建"
            else -> "点「一键搭建并启动」开始"
        }

        _uiState.update {
            it.copy(
                deployStage = phase.label,
                deployError = if (status.online) null else phase.error
            )
        }
        OverlayState.update(
            OverlaySnapshot(
                online = status.online,
                port = state.settings.port,
                stage = phase.label,
                error = phase.error,
                guidance = guidance,
                aiHint = state.agentAnswer?.lineSequence()?.firstOrNull()?.take(80)
            )
        )
    }

    fun saveSettings(newSettings: ProxySettings) {
        val normalized = newSettings.copy(
            port = if (newSettings.port < 1024) 8080 else newSettings.port.coerceAtMost(65535),
            proxyUrl = newSettings.proxyUrl.trim(),
            webPassword = newSettings.webPassword.trim(),
            installDir = newSettings.installDir.trim().ifBlank { "\$HOME/aistudio-api" },
            repoUrl = newSettings.repoUrl.trim()
                .ifBlank { "https://github.com/DeconstructedCube/aistudio-api.git" },
            apiTestKey = newSettings.apiTestKey.trim().ifBlank { "aistudio-api" }
        )
        settingsRepo.save(normalized)
        _uiState.update {
            it.copy(
                settings = normalized,
                snackbarMessage = "部署配置已保存（端口 ${normalized.port}）"
            )
        }
        refreshServiceStatus(silent = false)
    }

    fun executeTermuxAction(action: TermuxAction) {
        val ctx = getApplication<Application>()
        val settings = _uiState.value.settings
        val env = EnvironmentChecker.inspect(ctx)
        _uiState.update { it.copy(environment = env) }

        if (!env.termuxInstalled) {
            val cmd = TermuxBridge.buildCommand(action, settings)
            TermuxBridge.copyToClipboard(ctx, action.title, cmd)
            _uiState.update {
                it.copy(
                    lastDispatchedCommand = cmd,
                    snackbarMessage = "未检测到 Termux，请先从 F-Droid 或 GitHub 安装；命令已复制。"
                )
            }
            return
        }

        val preferAccessibility = !settingsRepo.externalAppsSetupDone ||
            !env.termuxRunCommandGranted ||
            action == TermuxAction.INSTALL_AND_START

        if (action == TermuxAction.INSTALL_AND_START || action == TermuxAction.INSTALL_ONLY) {
            OverlayState.deployStartedAt = System.currentTimeMillis()
        }

        when (val result = TermuxBridge.runInTermux(ctx, action, settings, preferAccessibility)) {
            is TermuxDispatchResult.AutomatedByAccessibility -> {
                val markDeployed = action == TermuxAction.INSTALL_AND_START ||
                    action == TermuxAction.INSTALL_ONLY
                val markExternal = action == TermuxAction.INSTALL_AND_START ||
                    action == TermuxAction.ENABLE_EXTERNAL_APPS
                if (markDeployed) settingsRepo.hasDeployed = true
                if (markExternal) settingsRepo.externalAppsSetupDone = true
                _uiState.update {
                    it.copy(
                        lastDispatchedCommand = result.command,
                        hasDeployed = it.hasDeployed || markDeployed,
                        externalAppsSetupDone = it.externalAppsSetupDone || markExternal,
                        deploying = it.deploying || markDeployed,
                        deployStartedAt = if (markDeployed && !it.deploying) {
                            System.currentTimeMillis()
                        } else {
                            it.deployStartedAt
                        },
                        snackbarMessage = "正在自动操控 Termux 执行「${action.title}」，请勿切出..."
                    )
                }
            }

            is TermuxDispatchResult.SentToTermux -> {
                val markDeployed = action == TermuxAction.INSTALL_AND_START ||
                    action == TermuxAction.INSTALL_ONLY
                val markExternal = action == TermuxAction.ENABLE_EXTERNAL_APPS
                if (markDeployed) settingsRepo.hasDeployed = true
                if (markExternal) settingsRepo.externalAppsSetupDone = true
                _uiState.update {
                    it.copy(
                        lastDispatchedCommand = result.command,
                        hasDeployed = it.hasDeployed || markDeployed,
                        externalAppsSetupDone = it.externalAppsSetupDone || markExternal,
                        deploying = it.deploying || markDeployed,
                        deployStartedAt = if (markDeployed && !it.deploying) {
                            System.currentTimeMillis()
                        } else {
                            it.deployStartedAt
                        },
                        snackbarMessage = when (action) {
                            TermuxAction.INSTALL_AND_START ->
                                "已在 Termux 开始一键搭建，请稍候…完成后状态会自动变绿"
                            else -> "已向 Termux 发送任务：${action.title}"
                        }
                    )
                }
                if (action == TermuxAction.STOP_SERVICE ||
                    action == TermuxAction.START_SERVICE ||
                    action == TermuxAction.INSTALL_AND_START
                ) {
                    viewModelScope.launch {
                        delay(2500L)
                        refreshServiceStatus(silent = true)
                    }
                }
            }

            is TermuxDispatchResult.FallbackCopied -> {
                val markDeployed = action == TermuxAction.INSTALL_AND_START ||
                    action == TermuxAction.INSTALL_ONLY
                val markExternal = action == TermuxAction.ENABLE_EXTERNAL_APPS ||
                    result.command.contains("allow-external-apps")
                if (markDeployed) settingsRepo.hasDeployed = true
                if (markExternal) settingsRepo.externalAppsSetupDone = true
                _uiState.update {
                    it.copy(
                        lastDispatchedCommand = result.command,
                        hasDeployed = it.hasDeployed || markDeployed,
                        externalAppsSetupDone = it.externalAppsSetupDone || markExternal,
                        deploying = it.deploying || markDeployed,
                        deployStartedAt = if (markDeployed && !it.deploying) {
                            System.currentTimeMillis()
                        } else {
                            it.deployStartedAt
                        },
                        snackbarMessage = result.reason
                    )
                }
            }
        }
    }

    fun runManualFallback(action: TermuxAction) {
        val ctx = getApplication<Application>()
        val result = TermuxBridge.copyAndOpenTermux(ctx, action, _uiState.value.settings)
        val markDeployed = action == TermuxAction.INSTALL_AND_START ||
            action == TermuxAction.INSTALL_ONLY
        val markExternal = action == TermuxAction.INSTALL_AND_START ||
            action == TermuxAction.ENABLE_EXTERNAL_APPS
        if (markDeployed) settingsRepo.hasDeployed = true
        if (markExternal) settingsRepo.externalAppsSetupDone = true
        _uiState.update {
            it.copy(
                lastDispatchedCommand = result.command,
                hasDeployed = it.hasDeployed || markDeployed,
                externalAppsSetupDone = it.externalAppsSetupDone || markExternal,
                snackbarMessage = result.reason
            )
        }
    }

    fun copyActionCommand(action: TermuxAction) {
        val ctx = getApplication<Application>()
        val cmd = TermuxBridge.buildCommand(action, _uiState.value.settings)
        TermuxBridge.copyToClipboard(ctx, action.title, cmd)
        _uiState.update {
            it.copy(
                lastDispatchedCommand = cmd,
                snackbarMessage = "已复制「${action.title}」命令到剪贴板"
            )
        }
    }

    fun copyText(label: String, text: String) {
        TermuxBridge.copyToClipboard(getApplication(), label, text)
        _uiState.update { it.copy(snackbarMessage = "已复制$label：$text") }
    }

    fun importCookies(rawCookies: String, probeMultiAccount: Boolean, namePrefix: String?) {
        viewModelScope.launch {
            _uiState.update { it.copy(importingCookie = true, lastImportResult = null) }
            val settings = _uiState.value.settings
            val res = if (probeMultiAccount) {
                apiClient.probeAndImportCookies(settings, rawCookies, namePrefix)
            } else {
                apiClient.importCookiesDirect(settings, rawCookies, namePrefix)
            }
            _uiState.update {
                it.copy(
                    importingCookie = false,
                    lastImportResult = res,
                    snackbarMessage = res.message
                )
            }
            if (res.success) {
                refreshServiceStatus(silent = true)
            }
        }
    }

    fun clearAllCooldowns() {
        viewModelScope.launch {
            val (ok, msg) = apiClient.clearAllCooldowns(_uiState.value.settings)
            _uiState.update { it.copy(snackbarMessage = msg) }
            if (ok) refreshServiceStatus(silent = true)
        }
    }

    fun testModelsConnectivity() {
        viewModelScope.launch {
            _uiState.update { it.copy(testingModels = true, lastTestResult = null) }
            val res = apiClient.testModelsEndpoint(_uiState.value.settings)
            _uiState.update {
                it.copy(
                    testingModels = false,
                    lastTestResult = res,
                    snackbarMessage = res.second
                )
            }
            if (res.first) {
                refreshServiceStatus(silent = true)
            }
        }
    }

    fun showMessage(msg: String) {
        _uiState.update { it.copy(snackbarMessage = msg) }
    }

    fun consumeMessage() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }

    // ─── AI 排障助手 ───

    fun saveAgentConfig(
        baseUrl: String,
        key: String,
        model: String,
        useLocal: Boolean,
        localModel: String
    ) {
        settingsRepo.agentBaseUrl = baseUrl
        settingsRepo.agentApiKey = key
        settingsRepo.agentModel = model
        settingsRepo.agentUseLocal = useLocal
        settingsRepo.agentLocalModel = localModel
        _uiState.update {
            it.copy(
                agentBaseUrl = baseUrl.trim(),
                agentApiKey = key.trim(),
                agentModel = model.trim(),
                agentUseLocal = useLocal,
                agentLocalModel = localModel.trim(),
                snackbarMessage = "AI 助手配置已保存"
            )
        }
    }

    fun runAgent(problem: String) {
        val state = _uiState.value
        if (!state.agentUseLocal && state.agentApiKey.isBlank()) {
            _uiState.update { it.copy(agentAnswer = "请先填写 AI 接口地址和 API Key，或改用本机反代模型") }
            return
        }
        if (state.agentUseLocal && !state.service.online) {
            _uiState.update {
                it.copy(agentAnswer = "本机反代服务还没运行，无法使用本机模型。请先在「搭建」页启动服务，或改用外部 API。")
            }
            return
        }
        viewModelScope.launch {
            _uiState.update {
                it.copy(agentRunning = true, agentLines = emptyList(), agentAnswer = null)
            }
            val engine = AgentEngine()
            val config = AgentConfig(
                baseUrl = state.agentBaseUrl,
                apiKey = state.agentApiKey,
                model = state.agentModel,
                useLocalProxy = state.agentUseLocal,
                localBaseUrl = state.settings.localBaseUrl,
                localModel = state.agentLocalModel.ifBlank { "gemini-3.7-flash" },
                localApiKey = state.agentLocalKey.ifBlank { "aistudio-api" }
            )
            try {
                val answer = engine.run(
                    config = config,
                    problem = problem.ifBlank { "我的反代服务有问题，帮我检查一下" },
                    execute = { name, args -> executeAgentTool(name, args) },
                    onLine = { line ->
                        _uiState.update { it.copy(agentLines = it.agentLines + line) }
                    }
                )
                _uiState.update { it.copy(agentRunning = false, agentAnswer = answer) }
                OverlayState.update(
                    OverlayState.snapshot.copy(
                        aiHint = answer.lineSequence().firstOrNull()?.take(80)
                    )
                )
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(agentRunning = false, agentAnswer = "运行失败：${e.message}")
                }
            }
        }
    }

    fun testAgentConnection() {
        val state = _uiState.value
        viewModelScope.launch {
            _uiState.update { it.copy(agentTesting = true, agentTestResult = null) }
            val engine = AgentEngine()
            val config = AgentConfig(
                baseUrl = state.agentBaseUrl,
                apiKey = state.agentApiKey,
                model = state.agentModel,
                useLocalProxy = state.agentUseLocal,
                localBaseUrl = state.settings.localBaseUrl,
                localModel = state.agentLocalModel.ifBlank { "gemini-3.7-flash" },
                localApiKey = state.agentLocalKey.ifBlank { "aistudio-api" }
            )
            val result = withContext(Dispatchers.IO) {
                runCatching { engine.ping(config) }
            }
            _uiState.update {
                it.copy(
                    agentTesting = false,
                    agentTestResult = result.getOrElse { e ->
                        "连接失败：${e.message?.take(120) ?: "未知错误"}"
                    }
                )
            }
        }
    }

    private suspend fun executeAgentTool(name: String, args: String): String =
        withContext(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            val settings = _uiState.value.settings
            when (name) {
                "get_status" -> buildStatusText()
                "get_log" -> LogCollector.getLog()
                    .ifBlank { "（暂无日志：可能还没执行过命令，或 Termux 无法回传）" }
                "run" -> {
                    if (args.isBlank()) {
                        "缺少命令参数"
                    } else if (TermuxBridge.runRawCommand(ctx, args)) {
                        "命令已发送到 Termux，稍后用 get_log 查看输出"
                    } else {
                        "发送失败：请确认 Termux 已安装并开启 allow-external-apps"
                    }
                }
                "install" -> {
                    val r = TermuxBridge.runInTermux(
                        ctx, TermuxAction.INSTALL_AND_START, settings, true
                    )
                    "已触发一键安装：${describeResult(r)}"
                }
                "start" -> {
                    val r = TermuxBridge.runInTermux(
                        ctx, TermuxAction.START_SERVICE, settings, false
                    )
                    "已触发启动：${describeResult(r)}"
                }
                "stop" -> {
                    val r = TermuxBridge.runInTermux(
                        ctx, TermuxAction.STOP_SERVICE, settings, true
                    )
                    "已触发停止：${describeResult(r)}"
                }
                "wait" -> {
                    val secs = args.trim().toIntOrNull()?.coerceIn(1, 120) ?: 30
                    delay(secs * 1000L)
                    "已等待 $secs 秒"
                }
                "set_port" -> {
                    val p = args.trim().toIntOrNull()
                    when {
                        p == null -> "参数不是数字"
                        p < 1024 -> "端口 $p 小于 1024，会报 permission denied，请改用 8080 之类的端口"
                        else -> {
                            saveSettings(settings.copy(port = p.coerceAtMost(65535)))
                            "端口已改为 ${p.coerceAtMost(65535)}，需要重新启动服务生效"
                        }
                    }
                }
                else -> "未知工具：$name"
            }
        }

    private fun describeResult(result: TermuxDispatchResult): String = when (result) {
        is TermuxDispatchResult.SentToTermux -> "已通过 Termux 后台执行"
        is TermuxDispatchResult.AutomatedByAccessibility -> "已通过自动操控执行"
        is TermuxDispatchResult.FallbackCopied -> "已复制命令并打开 Termux，需要用户手动粘贴回车"
    }

    private suspend fun buildStatusText(): String {
        val ctx = getApplication<Application>()
        val env = EnvironmentChecker.inspect(ctx)
        val settings = _uiState.value.settings
        val status = apiClient.fetchServiceStatus(settings)
        return buildString {
            appendLine("服务在线：${status.online}")
            appendLine("端口：${settings.port}")
            if (status.online) {
                appendLine("签名引擎：ready=${status.signerReady} running=${status.signerRunning}")
                appendLine("账号数：${status.accounts.size}")
                appendLine("模型数：${status.models.size}")
                if (status.accounts.isEmpty()) appendLine("提示：还没有导入任何账号")
            } else {
                appendLine("Termux 已安装：${env.termuxInstalled}")
                appendLine("Termux 外部调用权限：${env.termuxRunCommandGranted}")
                appendLine("自动操控（无障碍）：${env.accessibilityEnabled}")
                appendLine("是否安装过：${_uiState.value.hasDeployed}")
            }
            appendLine("代理设置：${settings.proxyUrl.ifBlank { "未设置" }}")
            appendLine("最近日志：")
            appendLine(LogCollector.getLog(30).ifBlank { "（空）" })
        }
    }
}
