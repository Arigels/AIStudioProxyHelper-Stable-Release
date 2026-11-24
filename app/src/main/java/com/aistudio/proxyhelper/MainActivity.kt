package com.aistudio.proxyhelper

import android.os.Bundle
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.security.SecureRandom
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aistudio.proxyhelper.automation.AutomationController
import com.aistudio.proxyhelper.data.SettingsRepository
import com.aistudio.proxyhelper.data.TermuxAction
import com.aistudio.proxyhelper.net.LogCollector
import com.aistudio.proxyhelper.network.EnvironmentChecker
import com.aistudio.proxyhelper.overlay.OverlayService
import com.aistudio.proxyhelper.termux.TermuxBridge
import com.aistudio.proxyhelper.ui.AccountsScreen
import com.aistudio.proxyhelper.ui.AgentScreen
import com.aistudio.proxyhelper.ui.DisclaimerDialog
import com.aistudio.proxyhelper.ui.MainViewModel
import com.aistudio.proxyhelper.ui.PermissionWizardDialog
import com.aistudio.proxyhelper.ui.SetupScreen
import com.aistudio.proxyhelper.ui.UsageScreen
import com.aistudio.proxyhelper.ui.theme.AIStudioProxyTheme

class MainActivity : ComponentActivity() {

    /** 与 Compose 里的 viewModel() 共用同一个 ViewModelStore Owner，保证悬浮窗入口能直接触发部署 */
    private val appViewModel: MainViewModel by lazy {
        ViewModelProvider(this)[MainViewModel::class.java]
    }
    private var requestedTab by mutableStateOf<Int?>(null)

    /** 悬浮窗请求「打开账号页并读取剪贴板」的次数；每 +1 触发一次，读完由界面回调清零 */
    private var accountsPasteRequest by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LogCollector.start()
        enableEdgeToEdge()
        handleLaunchIntent(intent)
        setContent {
            AIStudioProxyTheme {
                AIStudioProxyApp(
                    viewModel = appViewModel,
                    requestedTab = requestedTab,
                    onTabHandled = { requestedTab = null },
                    accountsPasteRequest = accountsPasteRequest,
                    onAccountsPasteHandled = { accountsPasteRequest = 0 }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    /** 悬浮窗带过来的动作：切到账号页 / 直接开始半自动部署 / 启动反代 */
    private fun handleLaunchIntent(intent: Intent?) {
        intent ?: return
        val tab = intent.getIntExtra(EXTRA_TAB, -1)
        if (tab in 0..2) {
            requestedTab = tab
            intent.removeExtra(EXTRA_TAB)
        }
        if (intent.getBooleanExtra(EXTRA_ACCOUNTS_PASTE, false)) {
            intent.removeExtra(EXTRA_ACCOUNTS_PASTE)
            requestedTab = 1
            accountsPasteRequest += 1
        }
        if (intent.getBooleanExtra(EXTRA_DEPLOY_NOW, false)) {
            intent.removeExtra(EXTRA_DEPLOY_NOW)
            appViewModel.startDeployFromOverlay()
        }
        if (intent.getBooleanExtra(EXTRA_START_SERVICE, false)) {
            intent.removeExtra(EXTRA_START_SERVICE)
            appViewModel.startServiceFromOverlay()
        }
    }

    companion object {
        const val EXTRA_TAB = "extra_tab"
        const val EXTRA_DEPLOY_NOW = "extra_deploy_now"
        const val EXTRA_START_SERVICE = "extra_start_service"

        /** 悬浮窗第 5 步：打开账号页并在前台读一次剪贴板 */
        const val EXTRA_ACCOUNTS_PASTE = "extra_accounts_paste"
    }
}

private data class NavTab(
    val title: String,
    val icon: ImageVector
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AIStudioProxyApp(
    viewModel: MainViewModel = viewModel(),
    requestedTab: Int? = null,
    onTabHandled: () -> Unit = {},
    accountsPasteRequest: Int = 0,
    onAccountsPasteHandled: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    // 教程完成 = Termux 双开「下一步」+ 复制地址卡片「完成」都点过
    val tutorialDone = remember {
        val guidePrefs = context.getSharedPreferences("aistudio_tutorial", Context.MODE_PRIVATE)
        guidePrefs.getBoolean("tavern_done", false) &&
            guidePrefs.getBoolean("tavern_address_tip_done", false)
    }
    var initialRouteDone by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(requestedTab) {
        if (requestedTab != null) {
            selectedTab = requestedTab
            initialRouteDone = true
            onTabHandled()
        }
    }
    // 首次启动默认落在「反代」页；悬浮窗带 requestedTab 时优先（requestedTab 的 LaunchedEffect 已经把 initialRouteDone 置 true）。
    LaunchedEffect(uiState.statusLoaded, requestedTab) {
        if (uiState.statusLoaded && !initialRouteDone && requestedTab == null) {
            selectedTab = 0
            initialRouteDone = true
        }
    }
    val saveableStateHolder = rememberSaveableStateHolder()
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: ""
    }

    val tabs = remember {
        listOf(
            NavTab("反代", Icons.Filled.RocketLaunch),
            NavTab("账号", Icons.Filled.VpnKey),
            NavTab("解疑", Icons.Filled.MenuBook)
        )
    }

    val termuxPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.refreshEnvironment()
        if (granted) {
            viewModel.showMessage("已获得 Termux 调用权限！如首次使用请再点一次「开启 Termux 一键调用」")
        } else {
            viewModel.showMessage("未授予 Termux 调用权限，可稍后重试")
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    val prefs = remember { SettingsRepository(context) }
    var showDisclaimer by remember { mutableStateOf(!prefs.disclaimerAccepted) }
    var showWizard by remember {
        mutableStateOf(prefs.disclaimerAccepted && !prefs.permissionWizardShown)
    }

    // 从 Termux / 系统设置返回时先同步悬浮窗，再刷新 UI 状态，确保按钮立刻变成「开启/关闭」
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (Settings.canDrawOverlays(context)) {
                    prefs.overlayPromptShown = true
                    if (prefs.overlayDesired && !OverlayService.isRunning) {
                        OverlayService.start(context)
                    }
                }
                viewModel.refreshEnvironment()
                viewModel.refreshServiceStatus(silent = true)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(uiState.snackbarMessage) {
        val msg = uiState.snackbarMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.consumeMessage()
    }

    if (uiState.showVpnReminder) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissVpnReminder() },
            title = { Text("部署前请先开启 VPN") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("反代的部署和运行都需要访问 Google：")
                    Text("1. 先打开 VPN（梯子）")
                    Text("2. 确认能正常打开 Google 再继续")
                    Text("3. 装好之后也别关，反代服务要一直用它")
                    Text(
                        "没开 VPN 的话，部署会卡在下载依赖那一步。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(onClick = { viewModel.confirmVpnReminder() }) {
                    Text("VPN 已开启，继续部署")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissVpnReminder() }) {
                    Text("还没开，先不部署")
                }
            }
        )
    }

    if (uiState.showPasteDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissPasteDialog() },
            title = { Text("命令已复制，准备去 Termux 粘贴") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("命令已经在剪贴板里了，进 Termux 后按三步操作：")
                    Text("1. 长按 Termux 屏幕空白处")
                    Text("2. 在弹出的菜单里点「粘贴」")
                    Text("3. 按键盘上的回车键执行")
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = uiState.pasteDialogCommand.take(180) +
                                if (uiState.pasteDialogCommand.length > 180) "…" else "",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                    Text(
                        "装完之后回到本 App 点「刷新状态」，服务在跑就说明成功了。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(onClick = { viewModel.openTermuxForPaste() }) {
                    Text("打开 Termux 去粘贴")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.recopyPasteCommand() }) {
                    Text("重新复制")
                }
            }
        )
    }

    uiState.publicNetworkAlert?.let { risk ->
        val adminProtected = uiState.service.authEnabled
        val apiProtected = uiState.service.apiKeys.isNotEmpty()
        val bothProtected = adminProtected && apiProtected
        AlertDialog(
            onDismissRequest = { viewModel.acknowledgePublicNetworkRisk() },
            title = {
                Text(
                    text = if (bothProtected) {
                        "⚠️ 公共/企业网域提示（已设密钥保护）"
                    } else {
                        "⚠️ 检测到公共/企业网域，建议关闭或设密钥"
                    },
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "${risk.reason}（端口 ${uiState.settings.port}）。",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Surface(
                        color = if (bothProtected) Color(0xFFDCFCE7) else MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Text(
                                text = "当前防护状态：",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (bothProtected) Color(0xFF14532D) else MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                text = "• API 访问密钥：${if (apiProtected) "已保护（${uiState.service.apiKeys.size} 个）" else "未设置（同网段可免密盗刷额度）"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (bothProtected) Color(0xFF14532D) else MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                text = "• 管理后台密码：${if (adminProtected) "已启用" else "未启用（同网段可打开 http://${risk.address}:${uiState.settings.port} 操作账号）"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (bothProtected) Color(0xFF14532D) else MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                    if (!bothProtected) {
                        Text(
                            text = "1. 同网段他人可盗刷额度/改账号：反代默认监听 0.0.0.0:${uiState.settings.port}，未同时设置「API 密钥 + 管理密码」时，公司/公共 Wi-Fi 下其他人扫到你的 IP 即可直接调用或打开后台。",
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        Text(
                            text = "1. 已开启密钥隔离：同网段陌生设备无法盗刷你的 Gemini 额度，也无法进入管理后台。",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Text(
                        text = "2. 公司网络审计与 HTTP 明文风险：局域网访问为明文 HTTP，且公司网关仍能看到设备开放监听端口与高频外连 Google 流量，存在 IT 安全审计风险。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "建议：如非必须，在公司/公共 Wi-Fi 下建议关闭反代改用手机 4G/5G 流量；若需在公共网络继续使用，请点下方按钮一键设置访问密钥，是否关闭由你个人决定。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FilledTonalButton(
                        onClick = { viewModel.openSecurityKeyDialog(fromNetworkAlert = true) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Filled.Key, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (bothProtected) "查看 / 修改安全密钥" else "一键设置密钥保护（推荐）")
                    }
                }
            },
            confirmButton = {
                Button(onClick = { viewModel.stopServiceForNetworkRisk() }) {
                    Text("立即关闭反代")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.acknowledgePublicNetworkRisk() }) {
                    Text("我知道风险，继续用")
                }
            }
        )
    }

    if (uiState.showSecurityKeyDialog) {
        SecurityKeyDialog(
            initialAdminPwd = uiState.settings.webPassword,
            initialApiKey = uiState.service.apiKeys.firstOrNull()?.key
                ?: uiState.settings.apiTestKey.takeUnless { it == "aistudio-api" }.orEmpty(),
            serviceOnline = uiState.service.online,
            saving = uiState.savingSecurityKeys,
            onDismiss = { viewModel.dismissSecurityKeyDialog() },
            onSave = { pwd, apiKey, restart ->
                viewModel.applySecurityKeys(pwd, apiKey, restart)
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "AI Studio 反代助手",
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "v$versionName",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = { Icon(tab.icon, contentDescription = tab.title) },
                        label = { Text(tab.title) }
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            saveableStateHolder.SaveableStateProvider(key = selectedTab) {
                when (selectedTab) {
                    0 -> SetupScreen(
                        uiState = uiState,
                        tutorialDone = tutorialDone,
                        onRequestTermuxPermission = {
                            termuxPermLauncher.launch(TermuxBridge.PERMISSION_RUN_COMMAND)
                        },
                        onRefreshStatus = { viewModel.refreshServiceStatus(silent = false) },
                        onSaveSettings = viewModel::saveSettings,
                        onExecuteAction = viewModel::executeTermuxAction,
                        onCopyActionCommand = viewModel::copyActionCommand,
                        onCopyText = viewModel::copyText,
                        onOpenConsole = {
                            TermuxBridge.openUrl(context, uiState.settings.localBaseUrl)
                        },
                        onGoToAccounts = { selectedTab = 1 },
                        onGoToClients = { selectedTab = 2 },
                        onOpenGuide = { selectedTab = 2 },
                        onAiAssist = {
                            selectedTab = 2
                            viewModel.showMessage("已切到「解疑」页，向下滑到底部即可使用 AI 助手")
                        },
                        onRestartService = {
                            viewModel.executeTermuxAction(TermuxAction.START_SERVICE)
                        },
                        onToggleOverlay = viewModel::toggleOverlay,
                        onOpenSecurityKeys = { viewModel.openSecurityKeyDialog(fromNetworkAlert = false) },
                        onDeleteApiKey = viewModel::deleteApiKey
                    )

                    1 -> AccountsScreen(
                        uiState = uiState,
                        onImportCookies = viewModel::importCookies,
                        onRefreshQuota = { viewModel.refreshQuotaDetection(showToast = true) },
                        onOpenConsole = {
                            TermuxBridge.openUrl(context, uiState.settings.localBaseUrl)
                        },
                        onActivateAccount = viewModel::activateAccount,
                        onToggleAccount = viewModel::toggleAccountEnabled,
                        onDetectAccount = viewModel::detectAccountEmailAndTier,
                        onForceNextAccount = viewModel::forceNextAccount,
                        onDeleteAccount = viewModel::deleteAccount,
                        onShowMessage = viewModel::showMessage,
                        autoPasteSignal = accountsPasteRequest,
                        onAutoPasteHandled = onAccountsPasteHandled
                    )

                    2 -> UsageScreen(
                        uiState = uiState,
                        agentContent = {
                            AgentScreen(
                                uiState = uiState,
                                onSwitchSource = viewModel::switchAgentSource,
                                onSaveConfig = viewModel::saveAgentConfig,
                                onFetchModels = viewModel::fetchAgentModels,
                                onRun = viewModel::runAgent,
                                onAskTavern = viewModel::askTavern,
                                initialProblem = when {
                                    uiState.deployError != null -> "反代部署报错：${uiState.deployError}"
                                    uiState.service.lastError != null -> "反代运行报错：${uiState.service.lastError}"
                                    else -> null
                                },
                                embedded = true
                            )
                        }
                    )
                }
            }
        }
    }

    // 首次打开顺序：① 风险与开源免费免责声明 -> ② 权限引导
    if (showDisclaimer) {
        DisclaimerDialog(
            onAccept = {
                prefs.disclaimerAccepted = true
                showDisclaimer = false
                if (!prefs.permissionWizardShown) {
                    showWizard = true
                }
            }
        )
    } else if (showWizard) {
        PermissionWizardDialog(
            onDismiss = {
                showWizard = false
                prefs.permissionWizardShown = true
            },
            onOpenOverlay = {
                val ok = EnvironmentChecker.openOverlayPermissionSettings(context)
                if (!ok) {
                    viewModel.showMessage("打不开悬浮窗授权页，请在系统设置里手动找「显示在其他应用上层」")
                }
            },
            onOpenA11y = {
                AutomationController.openAccessibilitySettings(context)
            }
        )
    }
}

@Composable
private fun SecurityKeyDialog(
    initialAdminPwd: String,
    initialApiKey: String,
    serviceOnline: Boolean,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSave: (adminPwd: String, apiKey: String, restartService: Boolean) -> Unit
) {
    var apiKeyInput by rememberSaveable { mutableStateOf(initialApiKey) }
    var adminPwdInput by rememberSaveable { mutableStateOf(initialAdminPwd) }
    var restartToApply by rememberSaveable { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = {
            Text(
                text = "设置反代安全密钥",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "同时设置「API 访问密钥 + 管理后台密码」，可有效防止同网段陌生人盗刷你的 Gemini 额度或打开后台修改账号。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = apiKeyInput,
                    onValueChange = { apiKeyInput = it },
                    label = { Text("1. API 访问密钥（酒馆/客户端填此 Key）") },
                    placeholder = { Text("如 sk-aistudio-xxxxxx") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            val generated = generateRandomApiKey()
                            apiKeyInput = generated
                            if (adminPwdInput.isBlank()) {
                                adminPwdInput = generated
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("随机生成强密钥", maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = {
                            if (apiKeyInput.isNotBlank()) {
                                adminPwdInput = apiKeyInput.trim()
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("同步到后台密码", maxLines = 1)
                    }
                }

                OutlinedTextField(
                    value = adminPwdInput,
                    onValueChange = { adminPwdInput = it },
                    label = { Text("2. 管理后台密码（保护账号面板）") },
                    placeholder = { Text("留空则不限制打开网页后台") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (serviceOnline && adminPwdInput.trim() != initialAdminPwd) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Checkbox(
                            checked = restartToApply,
                            onCheckedChange = { restartToApply = it }
                        )
                        Text(
                            text = "保存后自动重启反代，让管理后台密码立即生效",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                Text(
                    text = "提示：设置后，酒馆（代理密码）和 Cherry Studio（API Key）都需要填这里的密钥；在报错页让 AI 排查时会自动使用新密钥。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(adminPwdInput, apiKeyInput, restartToApply) },
                enabled = !saving && (apiKeyInput.isNotBlank() || adminPwdInput.isNotBlank())
            ) {
                if (saving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text("保存并启用保护")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !saving
            ) {
                Text("取消")
            }
        }
    )
}

private fun generateRandomApiKey(): String {
    val bytes = ByteArray(10)
    SecureRandom().nextBytes(bytes)
    val hex = bytes.joinToString("") { "%02x".format(it) }
    return "sk-aistudio-$hex"
}
