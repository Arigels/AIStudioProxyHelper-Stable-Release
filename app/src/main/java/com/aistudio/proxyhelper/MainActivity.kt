package com.aistudio.proxyhelper

import android.os.Bundle
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
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.aistudio.proxyhelper.data.TermuxAction
import com.aistudio.proxyhelper.termux.TermuxBridge
import com.aistudio.proxyhelper.ui.AccountsScreen
import com.aistudio.proxyhelper.ui.MainViewModel
import com.aistudio.proxyhelper.ui.SetupScreen
import com.aistudio.proxyhelper.ui.UsageScreen
import com.aistudio.proxyhelper.ui.theme.AIStudioProxyTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
            NavTab("使用", Icons.Filled.MenuBook)
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
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
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
                    onOpenGuide = { selectedTab = 2 }
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
            }
        }
    }
}
