package com.aistudio.proxyhelper

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aistudio.proxyhelper.data.SettingsRepository
import com.aistudio.proxyhelper.data.TermuxAction
import com.aistudio.proxyhelper.net.LogCollector
import com.aistudio.proxyhelper.overlay.OverlayService
import com.aistudio.proxyhelper.termux.TermuxBridge
import com.aistudio.proxyhelper.ui.AccountsScreen
import com.aistudio.proxyhelper.ui.AgentScreen
import com.aistudio.proxyhelper.ui.MainViewModel
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

    val tabs = remember {
        listOf(
            NavTab("搭建", Icons.Filled.RocketLaunch),
            NavTab("账号", Icons.Filled.VpnKey),
            NavTab("使用", Icons.Filled.MenuBook),
            NavTab("AI", Icons.Filled.SmartToy)
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

    // 从 Termux / 系统设置返回时刷新状态
    val lifecycleOwner = LocalLifecycleOwner.current
    val prefs = remember { SettingsRepository(context) }
    var showOverlayPrompt by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!Settings.canDrawOverlays(context) && !prefs.overlayPromptShown) {
            showOverlayPrompt = true
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshEnvironment()
                viewModel.refreshServiceStatus(silent = true)
                if (Settings.canDrawOverlays(context)) {
                    prefs.overlayPromptShown = true
                    if (prefs.overlayDesired && !OverlayService.isRunning) {
                        OverlayService.start(context)
                    }
                }
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "AI Studio 反代助手",
                        fontWeight = FontWeight.Bold
                    )
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
                    onRunManualFallback = viewModel::runManualFallback,
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
                        selectedTab = 3
                        viewModel.runAgent(
                            "我还没开始搭建，请帮我从零把反代服务搭起来，并确认能否正常访问。"
                        )
                    }
                )

                1 -> AccountsScreen(
                    uiState = uiState,
                    onImportCookies = viewModel::importCookies,
                    onShowMessage = viewModel::showMessage
                )

                2 -> UsageScreen(
                    uiState = uiState,
                    onCopyText = viewModel::copyText,
                    onTestModels = viewModel::testModelsConnectivity,
                    onRestartService = {
                        viewModel.executeTermuxAction(TermuxAction.START_SERVICE)
                    }
                )

                3 -> AgentScreen(
                    uiState = uiState,
                    onSaveConfig = viewModel::saveAgentConfig,
                    onTest = viewModel::testAgentConnection,
                    onFetchModels = viewModel::fetchAgentModels,
                    onRun = viewModel::runAgent
                )
            }
        }
    }

    if (showOverlayPrompt) {
        AlertDialog(
            onDismissRequest = { showOverlayPrompt = false },
            title = { Text("开启悬浮窗权限") },
            text = {
                Text(
                    "开启后，Termux 上方会显示搭建进度、AI 结论和「下一步该做什么」，" +
                        "搭建时不用来回切 App。只需要授权一次。\n\n" +
                        "⚠️ 小米/红米、OPPO、vivo、荣耀 等系统可能提示「已限制设置」，" +
                        "需要先点「解除限制」才能打开开关。详细步骤见「搭建」页的权限引导。"
                )
            },
            confirmButton = {
                Button(onClick = {
                    showOverlayPrompt = false
                    prefs.overlayPromptShown = true
                    prefs.overlayDesired = true
                    runCatching {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }) {
                    Text("去授权")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showOverlayPrompt = false
                    prefs.overlayPromptShown = true
                    prefs.overlayDesired = false
                }) {
                    Text("暂不需要")
                }
            }
        )
    }
}
