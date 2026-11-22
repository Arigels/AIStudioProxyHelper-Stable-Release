package com.aistudio.proxyhelper.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aistudio.proxyhelper.automation.AutomationController
import com.aistudio.proxyhelper.data.EnvironmentStatus
import com.aistudio.proxyhelper.data.ImportResult
import com.aistudio.proxyhelper.data.ProxySettings
import com.aistudio.proxyhelper.data.ServiceStatus
import com.aistudio.proxyhelper.data.SettingsRepository
import com.aistudio.proxyhelper.data.TermuxAction
import com.aistudio.proxyhelper.network.EnvironmentChecker
import com.aistudio.proxyhelper.network.ProxyApiClient
import com.aistudio.proxyhelper.termux.TermuxBridge
import com.aistudio.proxyhelper.termux.TermuxDispatchResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class MainUiState(
    val settings: ProxySettings = ProxySettings(),
    val environment: EnvironmentStatus = EnvironmentStatus(),
    val service: ServiceStatus = ServiceStatus(),
    val hasDeployed: Boolean = false,
    val externalAppsSetupDone: Boolean = false,
    val automationStatus: String? = null,
    val deploying: Boolean = false,
    val deployStartedAt: Long = 0L,
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
            externalAppsSetupDone = settingsRepo.externalAppsSetupDone
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
            }
            _uiState.update {
                it.copy(
                    service = status,
                    hasDeployed = it.hasDeployed || status.online,
                    deploying = if (status.online) false else it.deploying,
                    deployStartedAt = if (status.online) 0L else it.deployStartedAt
                )
            }
        }
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
}
