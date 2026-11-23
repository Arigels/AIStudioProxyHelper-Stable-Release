package com.aistudio.proxyhelper.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aistudio.proxyhelper.agent.AgentConfig
import com.aistudio.proxyhelper.agent.AgentEngine
import com.aistudio.proxyhelper.agent.AgentLine
import com.aistudio.proxyhelper.agent.AgentMode
import com.aistudio.proxyhelper.automation.AutomationController
import com.aistudio.proxyhelper.data.EnvironmentStatus
import com.aistudio.proxyhelper.data.ImportResult
import com.aistudio.proxyhelper.data.ProxySettings
import com.aistudio.proxyhelper.data.PublicNetworkRisk
import com.aistudio.proxyhelper.data.ServiceStatus
import com.aistudio.proxyhelper.data.SettingsRepository
import com.aistudio.proxyhelper.data.TermuxAction
import com.aistudio.proxyhelper.network.EnvironmentChecker
import com.aistudio.proxyhelper.net.LogCollector
import com.aistudio.proxyhelper.data.ProgressTracker
import com.aistudio.proxyhelper.overlay.OverlayService
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
    val agentFetchingModels: Boolean = false,
    val agentCustomModels: List<String> = emptyList(),
    val agentLocalModels: List<String> = emptyList(),
    val agentLines: List<AgentLine> = emptyList(),
    val agentAnswer: String? = null,
    val importingCookie: Boolean = false,
    val lastImportResult: ImportResult? = null,
    val testingModels: Boolean = false,
    val lastTestResult: Pair<Boolean, String>? = null,
    val lastDispatchedCommand: String? = null,
    val snackbarMessage: String? = null,
    val showPasteDialog: Boolean = false,
    val pasteDialogCommand: String = "",
    val showVpnReminder: Boolean = false,
    val pendingDeployAction: TermuxAction? = null,
    val canDrawOverlays: Boolean = false,
    val overlayRunning: Boolean = false,
    val deploySucceeded: Boolean = false,
    val publicNetworkAlert: PublicNetworkRisk? = null,
    val showSecurityKeyDialog: Boolean = false,
    val savingSecurityKeys: Boolean = false,
    val quotaChecking: Boolean = false,
    val busyAccountId: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsRepo = SettingsRepository(application)
    private val apiClient = ProxyApiClient()
    private var acknowledgedNetworkKey: String? = null

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
            agentLocalKey = settingsRepo.agentLocalKey,
            canDrawOverlays = android.provider.Settings.canDrawOverlays(application),
            overlayRunning = OverlayService.isRunning,
            deploySucceeded = settingsRepo.deploySucceeded
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
                // 同步刷新悬浮窗授权与运行状态：用户从系统设置返回后界面立即切换为「开启/关闭」
                val canDraw = android.provider.Settings.canDrawOverlays(getApplication())
                val running = OverlayService.isRunning && canDraw
                if (_uiState.value.canDrawOverlays != canDraw || _uiState.value.overlayRunning != running) {
                    _uiState.update { it.copy(canDrawOverlays = canDraw, overlayRunning = running) }
                }
                delay(6000L)
            }
        }
    }

    fun refreshEnvironment() {
        val ctx = getApplication<Application>()
        val env = EnvironmentChecker.inspect(ctx)
        val canDraw = android.provider.Settings.canDrawOverlays(ctx)
        val running = OverlayService.isRunning && canDraw
        _uiState.update {
            it.copy(
                environment = env,
                canDrawOverlays = canDraw,
                overlayRunning = running
            )
        }
    }

    fun toggleOverlay() {
        val ctx = getApplication<Application>()
        val canDraw = android.provider.Settings.canDrawOverlays(ctx)
        if (!canDraw) {
            val ok = EnvironmentChecker.openOverlayPermissionSettings(ctx)
            if (!ok) {
                showMessage("打不开悬浮窗授权页，请在系统设置里手动找到「显示在其他应用上层」")
            }
            return
        }
        if (OverlayService.isRunning) {
            OverlayService.stop(ctx)
            settingsRepo.overlayDesired = false
            _uiState.update { it.copy(canDrawOverlays = true, overlayRunning = false) }
        } else {
            OverlayService.start(ctx)
            settingsRepo.overlayDesired = true
            _uiState.update { it.copy(canDrawOverlays = true, overlayRunning = true) }
        }
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
                settingsRepo.deploySucceeded = true
                OverlayState.deployStartedAt = 0L
            }
            _uiState.update {
                it.copy(
                    service = status,
                    hasDeployed = it.hasDeployed || status.online,
                    deploySucceeded = it.deploySucceeded || status.online,
                    deploying = if (status.online) false else it.deploying,
                    deployStartedAt = if (status.online) 0L else it.deployStartedAt
                )
            }
            updateOverlaySnapshot(status)
        }
    }

    private fun updateOverlaySnapshot(status: ServiceStatus) {
        val ctx = getApplication<Application>()
        val termuxInstalled = EnvironmentChecker.isTermuxInstalled(ctx)
        val accessibilityEnabled = AutomationController.isServiceEnabled(ctx)
        val phase = ProgressTracker.analyze(LogCollector.getLog(200))
        val visibleError = if (status.online) null else phase.error
        val state = _uiState.value
        val guidance = when {
            status.online && status.accounts.isEmpty() ->
                "服务已启动！下一步：去「账号」页导出并粘贴 Cookie，之后就能用 AI Studio 反代"
            status.online && state.lastTestResult?.first != true ->
                "下一步：在「反代」页拉取模型列表，并复制地址填进酒馆"
            status.online -> "一切就绪，可以连酒馆开玩了"
            visibleError != null && visibleError.contains("梯子") ->
                "梯子可能没开或已断，检查代理后再试"
            visibleError != null && visibleError.contains("403") ->
                "Google 拒绝了访问：先确认梯子正常，再重新导入 Cookie"
            visibleError != null -> "出错了，点开 App 按提示修复"
            state.deploying -> "正在${phase.label}，编译阶段没输出是正常的，请耐心等"
            !termuxInstalled -> "还没安装 Termux，去 F-Droid 下载"
            !accessibilityEnabled -> "建议开启自动操控权限，可自动完成搭建操作"
            // 只有「从没部署成功过」才提示去点半自动部署；已部署过但服务没跑时，界面上是「启动反代」
            !state.deploySucceeded -> "点「半自动部署反代」开始"
            else -> "反代没在跑，点「启动反代」重新拉起服务"
        }

        if (visibleError != null) {
            OverlayState.deployStartedAt = 0L
        }

        val networkRisk = if (status.online) {
            EnvironmentChecker.inspectPublicNetworkRisk(ctx)
        } else {
            null
        }
        val alertToShow = networkRisk?.takeIf { it.key != acknowledgedNetworkKey }

        _uiState.update {
            it.copy(
                deployStage = phase.label,
                deployError = visibleError,
                deploying = if (visibleError != null) false else it.deploying,
                publicNetworkAlert = alertToShow
            )
        }
        OverlayState.update(
            OverlaySnapshot(
                online = status.online,
                accountsReady = status.accounts.isNotEmpty(),
                port = state.settings.port,
                stage = phase.label,
                error = visibleError,
                guidance = guidance,
                aiHint = state.agentAnswer?.lineSequence()?.firstOrNull()?.take(80),
                deploying = state.deploying,
                hasDeployed = state.hasDeployed,
                termuxInstalled = termuxInstalled,
                a11yEnabled = accessibilityEnabled
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
        executeTermuxActionInternal(action, skipVpnCheck = false)
    }

    /** 用户确认「VPN 已开启」后，继续执行挂起的部署动作。 */
    fun confirmVpnReminder() {
        val pending = _uiState.value.pendingDeployAction
        _uiState.update { it.copy(showVpnReminder = false, pendingDeployAction = null) }
        if (pending != null) {
            executeTermuxActionInternal(pending, skipVpnCheck = true)
        }
    }

    fun dismissVpnReminder() {
        _uiState.update { it.copy(showVpnReminder = false, pendingDeployAction = null) }
    }

    fun acknowledgePublicNetworkRisk() {
        val current = _uiState.value.publicNetworkAlert ?: return
        acknowledgedNetworkKey = current.key
        _uiState.update {
            it.copy(
                publicNetworkAlert = null,
                snackbarMessage = "已保留反代运行；在公共网络下请注意保管好管理密码与端口"
            )
        }
    }

    fun stopServiceForNetworkRisk() {
        val current = _uiState.value.publicNetworkAlert
        if (current != null) {
            acknowledgedNetworkKey = current.key
        }
        _uiState.update { it.copy(publicNetworkAlert = null) }
        executeTermuxActionInternal(TermuxAction.STOP_SERVICE, skipVpnCheck = true)
    }

    fun openSecurityKeyDialog(fromNetworkAlert: Boolean = false) {
        if (fromNetworkAlert) {
            val current = _uiState.value.publicNetworkAlert
            if (current != null) {
                acknowledgedNetworkKey = current.key
            }
        }
        _uiState.update {
            it.copy(
                publicNetworkAlert = null,
                showSecurityKeyDialog = true
            )
        }
    }

    fun dismissSecurityKeyDialog() {
        _uiState.update { it.copy(showSecurityKeyDialog = false) }
    }

    fun applySecurityKeys(
        adminPassword: String,
        apiKey: String,
        restartToApplyAdminPwd: Boolean
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(savingSecurityKeys = true) }
            val oldSettings = _uiState.value.settings
            val cleanPwd = adminPassword.trim()
            val cleanApiKey = apiKey.trim()
            val serviceOnline = _uiState.value.service.online

            var apiKeyNotice: String? = null
            if (cleanApiKey.isNotEmpty() && serviceOnline) {
                val exists = _uiState.value.service.apiKeys.any { it.key == cleanApiKey }
                if (!exists) {
                    val (ok, msg, _) = apiClient.createApiKey(oldSettings, "App安全密钥", cleanApiKey)
                    apiKeyNotice = if (ok) "API 密钥已即时生效" else msg
                } else {
                    apiKeyNotice = "该 API 密钥已在服务端生效"
                }
            }

            val updated = oldSettings.copy(
                webPassword = cleanPwd,
                apiTestKey = cleanApiKey.ifBlank { cleanPwd }.ifBlank { oldSettings.apiTestKey }
            )
            settingsRepo.save(updated)

            val needRestart = serviceOnline &&
                restartToApplyAdminPwd &&
                cleanPwd != oldSettings.webPassword

            _uiState.update {
                it.copy(
                    settings = updated,
                    savingSecurityKeys = false,
                    showSecurityKeyDialog = false,
                    snackbarMessage = buildString {
                        if (apiKeyNotice != null) append(apiKeyNotice)
                        else append("安全密钥设置已保存")
                        if (needRestart) append("；正在重启反代使管理后台密码生效…")
                    }
                )
            }

            if (needRestart) {
                executeTermuxActionInternal(TermuxAction.START_SERVICE, skipVpnCheck = true)
            } else {
                refreshServiceStatus(silent = false)
            }
        }
    }

    fun createApiKey(name: String, customKey: String? = null) {
        viewModelScope.launch {
            val currentSettings = _uiState.value.settings
            val (ok, msg, item) = apiClient.createApiKey(currentSettings, name, customKey)
            if (ok && item != null && item.key.isNotBlank()) {
                val updated = currentSettings.copy(apiTestKey = item.key)
                settingsRepo.save(updated)
                _uiState.update { it.copy(settings = updated, snackbarMessage = msg) }
            } else {
                _uiState.update { it.copy(snackbarMessage = msg) }
            }
            refreshServiceStatus(silent = true)
        }
    }

    fun deleteApiKey(key: String) {
        viewModelScope.launch {
            val (ok, msg) = apiClient.deleteApiKey(_uiState.value.settings, key)
            _uiState.update { it.copy(snackbarMessage = msg) }
            if (ok) refreshServiceStatus(silent = true)
        }
    }

    private fun executeTermuxActionInternal(action: TermuxAction, skipVpnCheck: Boolean) {
        val ctx = getApplication<Application>()
        val settings = _uiState.value.settings
        val env = EnvironmentChecker.inspect(ctx)
        _uiState.update { it.copy(environment = env) }

        val isDeploy = action == TermuxAction.INSTALL_AND_START ||
            action == TermuxAction.INSTALL_ONLY
        if (isDeploy && !skipVpnCheck) {
            _uiState.update {
                it.copy(showVpnReminder = true, pendingDeployAction = action)
            }
            return
        }

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

        if (isDeploy || action == TermuxAction.UPDATE_AND_REBUILD) {
            LogCollector.clear()
        }

        // 部署类动作：不依赖无障碍自动操作，改为「复制 + 打开 Termux + 弹窗提示粘贴回车」
        if (action == TermuxAction.INSTALL_AND_START ||
            action == TermuxAction.INSTALL_ONLY ||
            action == TermuxAction.ENABLE_EXTERNAL_APPS
        ) {
            if (isDeploy) {
                OverlayState.deployStartedAt = System.currentTimeMillis()
            }
            runManualFallback(action)
            return
        }

        val preferAccessibility = !settingsRepo.externalAppsSetupDone ||
            !env.termuxRunCommandGranted

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
                                "已在 Termux 开始半自动部署，请稍候…完成后状态会自动变绿"
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
                val a11yHint = if (AutomationController.isServiceEnabled(ctx) &&
                    !AutomationController.isServiceConnected()
                ) {
                    "（注：无障碍开关是开的，但服务没连上——去系统设置里关掉再打开一次，或重启手机）"
                } else {
                    ""
                }
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
                        snackbarMessage = result.reason + a11yHint,
                        showPasteDialog = true,
                        pasteDialogCommand = result.command
                    )
                }
            }
        }
    }

    fun runManualFallback(action: TermuxAction) {
        val ctx = getApplication<Application>()
        // 只复制命令，不自动跳 Termux：先把步骤给用户看清楚，再由用户点按钮跳转
        val cmd = TermuxBridge.buildCommand(action, _uiState.value.settings)
        TermuxBridge.copyToClipboard(ctx, action.title, cmd)
        val markDeployed = action == TermuxAction.INSTALL_AND_START ||
            action == TermuxAction.INSTALL_ONLY
        val markDeploying = action == TermuxAction.INSTALL_AND_START
        val markExternal = action == TermuxAction.INSTALL_AND_START ||
            action == TermuxAction.ENABLE_EXTERNAL_APPS
        if (markDeployed) settingsRepo.hasDeployed = true
        if (markExternal) settingsRepo.externalAppsSetupDone = true
        _uiState.update {
            it.copy(
                lastDispatchedCommand = cmd,
                hasDeployed = it.hasDeployed || markDeployed,
                externalAppsSetupDone = it.externalAppsSetupDone || markExternal,
                deploying = it.deploying || markDeploying,
                deployError = if (markDeployed) null else it.deployError,
                deployStartedAt = if (markDeploying && !it.deploying) {
                    System.currentTimeMillis()
                } else {
                    it.deployStartedAt
                },
                snackbarMessage = "命令已复制",
                showPasteDialog = true,
                pasteDialogCommand = cmd
            )
        }
    }

    /** 用户看完步骤后，主动点按钮才跳转到 Termux。 */
    fun openTermuxForPaste() {
        TermuxBridge.openTermux(getApplication())
        _uiState.update {
            it.copy(
                showPasteDialog = false,
                snackbarMessage = "Termux 已打开：长按屏幕 → 点「粘贴」→ 按回车"
            )
        }
    }

    fun dismissPasteDialog() {
        _uiState.update { it.copy(showPasteDialog = false) }
    }

    fun recopyPasteCommand() {
        val cmd = _uiState.value.pasteDialogCommand
        if (cmd.isBlank()) return
        TermuxBridge.copyToClipboard(getApplication(), "部署命令", cmd)
        _uiState.update { it.copy(snackbarMessage = "命令已重新复制") }
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

    fun refreshQuotaDetection(showToast: Boolean = true) {
        viewModelScope.launch {
            _uiState.update { it.copy(quotaChecking = true) }
            val currentSettings = _uiState.value.settings
            val status = apiClient.fetchServiceStatus(currentSettings)
            if (status.online) {
                settingsRepo.hasDeployed = true
                settingsRepo.deploySucceeded = true
                OverlayState.deployStartedAt = 0L
            }
            val msg = if (!showToast) {
                null
            } else if (!status.online) {
                status.lastError ?: "反代服务未运行，请先到「反代」页启动服务"
            } else if (status.lastError != null) {
                status.lastError
            } else {
                val todayCalls = status.accounts.sumOf { it.todayTotalRequests }
                "已同步管理台数据：${status.accounts.size} 个账号，今日调用 $todayCalls 次，可用模型 ${status.models.size} 个"
            }
            _uiState.update {
                it.copy(
                    service = status,
                    quotaChecking = false,
                    hasDeployed = it.hasDeployed || status.online,
                    deploySucceeded = it.deploySucceeded || status.online,
                    deploying = if (status.online) false else it.deploying,
                    deployStartedAt = if (status.online) 0L else it.deployStartedAt,
                    snackbarMessage = msg ?: it.snackbarMessage
                )
            }
            updateOverlaySnapshot(status)
        }
    }

    fun activateAccount(accountId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(busyAccountId = accountId) }
            val (ok, msg) = apiClient.activateAccount(_uiState.value.settings, accountId)
            _uiState.update { it.copy(busyAccountId = null, snackbarMessage = msg) }
            if (ok) refreshServiceStatus(silent = true)
        }
    }

    fun toggleAccountEnabled(accountId: String, enabled: Boolean? = null) {
        viewModelScope.launch {
            _uiState.update { it.copy(busyAccountId = accountId) }
            val (ok, msg) = apiClient.toggleAccount(_uiState.value.settings, accountId, enabled)
            _uiState.update { it.copy(busyAccountId = null, snackbarMessage = msg) }
            if (ok) refreshServiceStatus(silent = true)
        }
    }

    fun detectAccountEmailAndTier(accountId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(busyAccountId = accountId) }
            val (ok, msg) = apiClient.detectAccountEmailAndTier(_uiState.value.settings, accountId)
            _uiState.update { it.copy(busyAccountId = null, snackbarMessage = msg) }
            if (ok) refreshServiceStatus(silent = true)
        }
    }

    fun forceNextAccount() {
        viewModelScope.launch {
            _uiState.update { it.copy(quotaChecking = true) }
            val (ok, msg) = apiClient.forceNextAccount(_uiState.value.settings)
            _uiState.update { it.copy(quotaChecking = false, snackbarMessage = msg) }
            if (ok) refreshServiceStatus(silent = true)
        }
    }

    fun deleteAccount(accountId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(busyAccountId = accountId) }
            val (ok, msg) = apiClient.deleteAccount(_uiState.value.settings, accountId)
            _uiState.update { it.copy(busyAccountId = null, snackbarMessage = msg) }
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

    fun switchAgentSource(useLocal: Boolean) {
        settingsRepo.agentUseLocal = useLocal
        _uiState.update {
            it.copy(
                agentUseLocal = useLocal,
                snackbarMessage = if (useLocal) {
                    "已切换为「本地反代」（仅使用反代模型，不会调用外接 API）"
                } else {
                    "已切换为「自定义」（仅使用外接模型，不会调用本地反代）"
                }
            )
        }
    }

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
        val label = if (useLocal) {
            "本地反代${if (localModel.isNotBlank()) "（$localModel）" else "（未选模型）"}"
        } else {
            "自定义${if (model.isNotBlank()) "（$model）" else "（未选模型）"}"
        }
        _uiState.update {
            it.copy(
                agentBaseUrl = baseUrl.trim(),
                agentApiKey = key.trim(),
                agentModel = model.trim(),
                agentUseLocal = useLocal,
                agentLocalModel = localModel.trim(),
                snackbarMessage = "已保存 AI 设置：$label"
            )
        }
    }

    private fun buildAgentConfig(): AgentConfig {
        val s = _uiState.value
        return AgentConfig(
            baseUrl = s.agentBaseUrl,
            apiKey = s.agentApiKey,
            model = s.agentModel,
            useLocalProxy = s.agentUseLocal,
            localBaseUrl = s.settings.localBaseUrl,
            localModel = s.agentLocalModel,
            localApiKey = s.agentLocalKey.ifBlank { "aistudio-api" }
        )
    }

    private fun requireModelMessage(config: AgentConfig): String? {
        val chosen = if (config.useLocalProxy) config.localModel else config.model
        return if (chosen.isBlank()) {
            "还没有选择模型：请先点「连接并拉取模型」，选中一个模型后再点「保存设置」。"
        } else {
            null
        }
    }

    fun askTavern(question: String) {
        val state = _uiState.value
        // 严格按用户保存的单一书签执行：自定义 或 本地反代，绝不串用两个途径
        val config = buildAgentConfig()
        if (config.useLocalProxy && !state.service.online) {
            _uiState.update {
                it.copy(agentAnswer = "当前书签为「本地反代」，但反代服务未运行。请先去「反代」页启动反代，或切到「自定义」书签。")
            }
            return
        }
        if (!config.useLocalProxy && state.agentApiKey.isBlank()) {
            _uiState.update {
                it.copy(agentAnswer = "当前书签为「自定义」，请先填写 API Key 并保存，或切到「本地反代」书签。")
            }
            return
        }
        requireModelMessage(config)?.let { msg ->
            _uiState.update { it.copy(agentAnswer = msg) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(agentRunning = true, agentLines = emptyList(), agentAnswer = null) }
            val engine = AgentEngine()
            try {
                val answer = engine.run(
                    config = config,
                    problem = question.ifBlank { "酒馆怎么配置才能用上反代模型？" },
                    mode = AgentMode.TAVERN,
                    execute = { _, _ -> "" },
                    onLine = { line -> _uiState.update { it.copy(agentLines = it.agentLines + line) } }
                )
                _uiState.update { it.copy(agentRunning = false, agentAnswer = answer) }
            } catch (e: Exception) {
                _uiState.update { it.copy(agentRunning = false, agentAnswer = "运行失败：${e.message}") }
            }
        }
    }

    fun runAgent(problem: String) {
        val state = _uiState.value
        if (!state.agentUseLocal && state.agentApiKey.isBlank()) {
            _uiState.update { it.copy(agentAnswer = "当前书签为「自定义」，请先填写 AI 接口地址和 API Key，或切到「本地反代」书签。") }
            return
        }
        if (state.agentUseLocal && !state.service.online) {
            _uiState.update {
                it.copy(agentAnswer = "当前书签为「本地反代」，但本机反代服务还没运行。请先在「反代」页启动服务，或切到「自定义」书签。")
            }
            return
        }
        val config = buildAgentConfig()
        requireModelMessage(config)?.let { msg ->
            _uiState.update { it.copy(agentAnswer = msg) }
            return
        }
        viewModelScope.launch {
            _uiState.update {
                it.copy(agentRunning = true, agentLines = emptyList(), agentAnswer = null)
            }
            val engine = AgentEngine()
            try {
                val answer = engine.run(
                    config = config,
                    problem = problem.ifBlank { "我的反代服务有问题，帮我检查一下" },
                    mode = AgentMode.TROUBLESHOOT,
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

    fun fetchAgentModels(
        baseUrl: String,
        key: String,
        model: String,
        useLocal: Boolean,
        localModel: String
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(agentFetchingModels = true) }
            val engine = AgentEngine()
            val config = AgentConfig(
                baseUrl = baseUrl,
                apiKey = key,
                model = model,
                useLocalProxy = useLocal,
                localBaseUrl = _uiState.value.settings.localBaseUrl,
                localModel = localModel,
                localApiKey = _uiState.value.agentLocalKey.ifBlank { "aistudio-api" }
            )
            val result = withContext(Dispatchers.IO) {
                runCatching { engine.listModels(config) }
            }
            val fetched = result.getOrDefault(emptyList())
            _uiState.update { state ->
                state.copy(
                    agentFetchingModels = false,
                    agentCustomModels = if (useLocal) state.agentCustomModels else fetched,
                    agentLocalModels = if (useLocal) fetched else state.agentLocalModels,
                    snackbarMessage = result.fold(
                        onSuccess = { list ->
                            if (list.isEmpty()) "没有拉取到模型" else "已获取 ${list.size} 个模型，请点选一个后点「保存设置」"
                        },
                        onFailure = { e -> "拉取失败：${e.message?.take(100) ?: "未知错误"}" }
                    )
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
