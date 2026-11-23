package com.aistudio.proxyhelper

import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LogCollector.start()
        enableEdgeToEdge()
        setContent {
            AIStudioProxyTheme {
                AIStudioProxyApp()
            }
        }
    }
}

private data class NavTab(
    val title: String,
    val icon: ImageVector
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AIStudioProxyApp(viewModel: MainViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val context = LocalContext.current
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
            when (selectedTab) {
                0 -> SetupScreen(
                    uiState = uiState,
                    onRequestTermuxPermission = {
                        termuxPermLauncher.launch(TermuxBridge.PERMISSION_RUN_COMMAND)
                    },
                    onRefreshStatus = { viewModel.refreshServiceStatus(silent = false) },
                    onSaveSettings = viewModel::saveSettings,
                    onExecuteAction = viewModel::executeTermuxAction,
                    onCopyActionCommand = viewModel::copyActionCommand,
                    onCopyText = viewModel::copyText,
                    onClearCooldowns = viewModel::clearAllCooldowns,
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
                    onTestModels = viewModel::testModelsConnectivity,
                    onToggleOverlay = viewModel::toggleOverlay
                )

                1 -> AccountsScreen(
                    uiState = uiState,
                    onImportCookies = viewModel::importCookies,
                    onShowMessage = viewModel::showMessage
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
                            embedded = true
                        )
                    }
                )
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
