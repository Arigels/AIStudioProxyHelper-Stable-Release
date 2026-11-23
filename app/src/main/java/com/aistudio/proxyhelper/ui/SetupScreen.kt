package com.aistudio.proxyhelper.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aistudio.proxyhelper.automation.AutomationController
import com.aistudio.proxyhelper.data.ProxySettings
import com.aistudio.proxyhelper.data.TermuxAction
import com.aistudio.proxyhelper.network.EnvironmentChecker
import com.aistudio.proxyhelper.termux.TermuxBridge
import kotlinx.coroutines.delay

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SetupScreen(
    uiState: MainUiState,
    onRequestTermuxPermission: () -> Unit,
    onRefreshStatus: () -> Unit,
    onSaveSettings: (ProxySettings) -> Unit,
    onExecuteAction: (TermuxAction) -> Unit,
    onCopyActionCommand: (TermuxAction) -> Unit,
    onCopyText: (String, String) -> Unit,
    onClearCooldowns: () -> Unit,
    onOpenConsole: () -> Unit,
    onGoToAccounts: () -> Unit,
    onGoToClients: () -> Unit,
    onOpenGuide: () -> Unit,
    onAiAssist: () -> Unit,
    onRestartService: () -> Unit,
    onTestModels: () -> Unit,
    onToggleOverlay: () -> Unit,
    onOpenSecurityKeys: () -> Unit,
    onDeleteApiKey: (String) -> Unit
) {
    val context = LocalContext.current
    val env = uiState.environment
    var showTermuxDialog by remember { mutableStateOf(false) }
    var showA11yHelpDialog by remember { mutableStateOf(false) }

    fun openA11yWithHint() {
        showA11yHelpDialog = true
    }

    fun triggerAction(action: TermuxAction) {
        if (!env.termuxInstalled) {
            showTermuxDialog = true
            return
        }
        onExecuteAction(action)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. 状态（反代服务运行中/未运行 + 唯一本机地址复制区）
        ServiceStatusCard(
            uiState = uiState,
            onRefresh = onRefreshStatus,
            onCopyText = onCopyText,
            onOpenConsole = onOpenConsole
        )

        // 1.5 启动反代：紧贴状态卡下面，仅在第一次部署成功（服务上线过）后才出现
        if (uiState.deploySucceeded) {
            RestartProxyCard(
                online = uiState.service.online,
                onRestart = onRestartService
            )
        }

        // 1.6 拉取反代模型：仅在第一次部署成功后才出现，只读列表不耗额度
        if (uiState.deploySucceeded) {
            ModelPullCard(
                uiState = uiState,
                onTestModels = onTestModels
            )
        }

        // 2. 上线后的下一步引导
        NextStepsCard(
            uiState = uiState,
            onGoToAccounts = onGoToAccounts,
            onGoToClients = onGoToClients,
            onOpenGuide = onOpenGuide
        )

        // 2.5 悬浮窗开关（显眼位置，实时同步授权与运行状态）
        OverlayToggleCard(
            canDrawOverlays = uiState.canDrawOverlays,
            overlayRunning = uiState.overlayRunning,
            onToggle = onToggleOverlay
        )

        // 3. 主操作（首次部署 / 部署中 / 停止 / 更新修复）
        PrimaryActionCard(
            uiState = uiState,
            onDeploy = { triggerAction(TermuxAction.INSTALL_AND_START) },
            onStop = { triggerAction(TermuxAction.STOP_SERVICE) },
            onUpdate = { triggerAction(TermuxAction.UPDATE_AND_REBUILD) },
            onAiAssist = onAiAssist
        )

        // 4. 首次准备（Termux 与悬浮窗完成后自动消失）
        PrerequisitesCard(
            uiState = uiState,
            onOpenOverlaySettings = onToggleOverlay,
            onOpenAccessibilitySettings = { openA11yWithHint() },
            onOpenFDroid = { TermuxBridge.openUrl(context, TermuxBridge.URL_TERMUX_FDROID) },
            onOpenGitHub = { TermuxBridge.openUrl(context, TermuxBridge.URL_TERMUX_GITHUB) }
        )

        // 5. 高级设置（默认折叠）
        AdvancedSettingsCard(
            uiState = uiState,
            onSaveSettings = onSaveSettings,
            onExecuteAction = onExecuteAction,
            onCopyActionCommand = onCopyActionCommand,
            onClearCooldowns = onClearCooldowns,
            onRequestTermuxPermission = onRequestTermuxPermission,
            onOpenAccessibilitySettings = { openA11yWithHint() },
            onOpenTermuxBatterySettings = {
                EnvironmentChecker.openTermuxAppDetails(context)
            },
            onOpenSecurityKeys = onOpenSecurityKeys,
            onDeleteApiKey = onDeleteApiKey,
            onCopyText = onCopyText
        )

        Spacer(modifier = Modifier.height(8.dp))
    }

    if (showTermuxDialog) {
        AlertDialog(
            onDismissRequest = { showTermuxDialog = false },
            title = { Text("先安装 Termux") },
            text = {
                Text(
                    "反代服务需要在手机的 Termux 里运行。\n\n" +
                        "请从 F-Droid 或 GitHub 下载 64 位安装包（文件名带 arm64-v8a），切勿使用 Google Play 商店旧版。装好后回到这里点一键部署即可。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showTermuxDialog = false
                    TermuxBridge.openUrl(context, TermuxBridge.URL_TERMUX_FDROID)
                }) { Text("F-Droid 下载") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showTermuxDialog = false
                    TermuxBridge.openUrl(context, TermuxBridge.URL_TERMUX_GITHUB)
                }) { Text("GitHub 下载") }
            }
        )
    }

    if (showA11yHelpDialog) {
        AccessibilityGuideDialog(
            onDismiss = { showA11yHelpDialog = false },
            onOpenSettings = {
                showA11yHelpDialog = false
                AutomationController.openAccessibilitySettings(context)
            }
        )
    }
}

@Composable
private fun OverlayToggleCard(
    canDrawOverlays: Boolean,
    overlayRunning: Boolean,
    onToggle: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "悬浮窗",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = when {
                        !canDrawOverlays -> "需在系统设置里允许「显示在其他应用上层」，开启后可拖拽查看状态并一键 AI 识别报错"
                        overlayRunning -> "已开启：可拖拽移动，遇到报错点悬浮窗上的「🩺 报错」即可让 AI 自动分析"
                        else -> "已授权：点右侧「开启」在 Termux / 酒馆上方显示悬浮窗与 AI 报错按钮"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = onToggle,
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(
                    when {
                        !canDrawOverlays -> "去授权"
                        overlayRunning -> "关闭"
                        else -> "开启"
                    }
                )
            }
        }
    }
}

@Composable
private fun PrimaryActionCard(
    uiState: MainUiState,
    onDeploy: () -> Unit,
    onStop: () -> Unit,
    onUpdate: () -> Unit,
    onAiAssist: () -> Unit
) {
    val service = uiState.service
    val deployed = uiState.deploySucceeded
    val context = LocalContext.current

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            uiState.deployError?.let { err ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "⚠️ 检测到：$err",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            text = hintForError(err),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            if (service.online) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF16A34A)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "反代服务正在运行",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = "搭建已完成。下一步：去「账号」页导入 Cookie，然后复制上方本机地址填进酒馆（详细配置见「解疑」页）。不用时可点下方按钮停止服务以省电。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Surface(
                    color = Color(0xFFE8F5E9),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "💡 本 App 不用一直挂在后台：反代服务实际运行在 Termux 里。部署和导入账号完成后，你可以直接关闭或划掉本 App（若开启了悬浮窗也可随时关闭），只要保持 Termux 和 VPN 在后台运行即可；之后需要看额度、改设置或遇到问题再打开本 App 就好。",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF1B5E20),
                        modifier = Modifier.padding(10.dp)
                    )
                }
                OutlinedButton(
                    onClick = onStop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("停止反代服务")
                }
            } else if (uiState.deploying) {
                var now by remember { mutableStateOf(System.currentTimeMillis()) }
                LaunchedEffect(uiState.deploying) {
                    while (uiState.deploying) {
                        now = System.currentTimeMillis()
                        delay(1000L)
                    }
                }
                val elapsedSec = ((now - uiState.deployStartedAt) / 1000L).coerceAtLeast(0L)

                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "正在搭建反代服务…",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = "已用时 ${elapsedSec / 60} 分 ${elapsedSec % 60} 秒（一般需要 5~15 分钟）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "流程：安装依赖 → 克隆仓库 → 编译本地签名引擎。编译阶段长时间没有输出属于正常现象，请不要关闭 Termux。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (elapsedSec > 20 * 60) {
                    Text(
                        text = "⚠️ 已超过 20 分钟仍未启动，可能失败了：请打开 Termux 查看最后几行红色报错，或点「重新安装」。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { TermuxBridge.openTermux(context) },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Filled.Terminal, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("打开 Termux 看进度", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = onDeploy) {
                        Text("重新安装", maxLines = 1)
                    }
                }
            } else if (deployed) {
                Text(
                    text = "维护与修复",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "日常使用请点上方「启动反代」。只有遇到反代版本过旧或运行报错修不好时，才需要点下面更新重装。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(
                    onClick = onUpdate,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("更新并重新编译修复")
                }
            } else {
                Button(
                    onClick = onDeploy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.RocketLaunch,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "一键部署反代",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                Text(
                    text = "首次部署用（约 5~15 分钟）：会先提醒开 VPN，再复制命令并弹窗教你进 Termux 粘贴回车，自动装好依赖并启动服务。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                TextButton(
                    onClick = onAiAssist,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("不会弄？去「解疑」页让 AI 帮我排查")
                }
            }
        }
    }
}

private fun hintForError(err: String): String = when {
    err.contains("梯子") ->
        "请确认梯子/VPN 已开启；若用本地代理，去「高级设置 → 网络代理」填写（如 http://127.0.0.1:7890），再点「重新安装」或「启动反代」。"
    err.contains("403") ->
        "先确认能正常打开 Google，再重新用 Cookie-Editor 导出并导入 Cookie。"
    err.contains("端口") ->
        "去「高级设置」把端口改成 1024 以上（推荐 8080），再启动。"
    err.contains("存储") ->
        "清理手机空间（建议留 1~2GB）后重新安装。"
    err.contains("内存") ->
        "关掉后台应用，并给 Termux 开电池无限制后重试。"
    else ->
        "打开 Termux 查看最后几行报错，或到「解疑」页底部让 AI 帮你排查。"
}

@Composable
private fun PrerequisitesCard(
    uiState: MainUiState,
    onOpenOverlaySettings: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenFDroid: () -> Unit,
    onOpenGitHub: () -> Unit
) {
    val env = uiState.environment
    val canDrawOverlay = uiState.canDrawOverlays
    val allDone = env.termuxInstalled && canDrawOverlay
    if (allDone) return

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Shield,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "首次准备（做完自动消失）",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }

            // 1. Termux
            if (!env.termuxInstalled) {
                ChecklistRow(
                    ok = false,
                    title = "1. 安装 Termux",
                    subtitle = "务必从 F-Droid 或 GitHub 下载 64 位版（arm64-v8a），勿用 Play 商店旧版"
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onOpenFDroid,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("F-Droid 下载", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    OutlinedButton(
                        onClick = onOpenGitHub,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("GitHub 下载", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            } else {
                ChecklistRow(
                    ok = true,
                    title = "1. Termux 已安装",
                    subtitle = "版本 ${env.termuxVersionName ?: "已就绪"}"
                )
            }

            // 2. 权限提醒 + 悬浮窗 + 无障碍（可选）
            Surface(
                color = Color(0xFFFFF4D6),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "⚠️ 本 App 可能会被安卓手机限制权限。请注意权限无法开启时的系统提示，" +
                        "按照系统的提示步骤解除限制后，再回来开启下面的权限。",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF8A6D00),
                    modifier = Modifier.padding(10.dp)
                )
            }

            ChecklistRow(
                ok = canDrawOverlay,
                title = "2. 悬浮窗授权",
                subtitle = if (canDrawOverlay) {
                    "已允许「显示在其他应用上层」"
                } else {
                    "允许后可在 Termux 上方看到状态小窗，并用「🩺 报错」让 AI 分析错误"
                }
            )
            if (!canDrawOverlay) {
                FilledTonalButton(
                    onClick = onOpenOverlaySettings,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("去授权悬浮窗")
                }
            }

            ChecklistRow(
                ok = env.accessibilityEnabled,
                title = "3. 无障碍（可选，用于悬浮窗读屏）",
                subtitle = if (env.accessibilityEnabled) {
                    "已开启：悬浮窗「🩺 报错」可直接读取屏幕上的报错文字"
                } else {
                    "不开也能正常部署和使用；开启后悬浮窗报错识别可顺带读取 Termux 屏幕文字"
                }
            )
            if (!env.accessibilityEnabled) {
                FilledTonalButton(
                    onClick = onOpenAccessibilitySettings,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("去开启无障碍（可选）")
                }
            }
        }
    }
}

@Composable
private fun AdvancedSettingsCard(
    uiState: MainUiState,
    onSaveSettings: (ProxySettings) -> Unit,
    onExecuteAction: (TermuxAction) -> Unit,
    onCopyActionCommand: (TermuxAction) -> Unit,
    onClearCooldowns: () -> Unit,
    onRequestTermuxPermission: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenTermuxBatterySettings: () -> Unit,
    onOpenSecurityKeys: () -> Unit,
    onDeleteApiKey: (String) -> Unit,
    onCopyText: (String, String) -> Unit
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }

    var portInput by remember(uiState.settings.port) {
        mutableStateOf(uiState.settings.port.toString())
    }
    var proxyInput by remember(uiState.settings.proxyUrl) {
        mutableStateOf(uiState.settings.proxyUrl)
    }
    var passwordInput by remember(uiState.settings.webPassword) {
        mutableStateOf(uiState.settings.webPassword)
    }
    var dirInput by remember(uiState.settings.installDir) {
        mutableStateOf(uiState.settings.installDir)
    }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "高级设置",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "端口、代理、保活与权限，不懂可以完全不管",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = portInput,
                        onValueChange = { portInput = it.filter { c -> c.isDigit() } },
                        label = { Text("端口（默认 8080，一般不用改）") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = portInput.toIntOrNull()?.let { it in 1..1023 } == true,
                        supportingText = {
                            val v = portInput.toIntOrNull()
                            if (v != null && v in 1..1023) {
                                Text("⚠️ 小于 1024 的端口需要 root，保存时会自动改成 8080")
                            } else {
                                Text("可用范围 1024~65535，推荐 8080")
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = proxyInput,
                        onValueChange = { proxyInput = it },
                        label = { Text("网络代理（可选）") },
                        placeholder = { Text("如 http://127.0.0.1:7890，留空自动探测") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = passwordInput,
                        onValueChange = { passwordInput = it },
                        label = { Text("管理后台密码（可选，留空不设密码）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = dirInput,
                        onValueChange = { dirInput = it },
                        label = { Text("安装目录（默认即可）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            val rawPort = portInput.toIntOrNull() ?: 8080
                            val parsedPort = if (rawPort < 1024) 8080 else rawPort.coerceAtMost(65535)
                            portInput = parsedPort.toString()
                            onSaveSettings(
                                uiState.settings.copy(
                                    port = parsedPort,
                                    proxyUrl = proxyInput.trim(),
                                    webPassword = passwordInput.trim(),
                                    installDir = dirInput.trim().ifBlank { "\$HOME/aistudio-api" }
                                )
                            )
                            expanded = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("保存")
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                    Text(
                        text = "安全密钥（防公司/公共 Wi-Fi 盗刷额度）",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = if (uiState.service.apiKeys.isEmpty()) {
                            "当前未设置 API 访问密钥（/v1beta 接口免密开放）。在公共网络下建议设置安全密钥。"
                        } else {
                            "已启用 ${uiState.service.apiKeys.size} 个 API 访问密钥（需带密钥才能调用反代）。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    uiState.service.apiKeys.forEach { item ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.name,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = item.key,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                IconButton(onClick = { onCopyText("API 密钥", item.key) }) {
                                    Icon(
                                        Icons.Filled.ContentCopy,
                                        contentDescription = "复制密钥",
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                IconButton(onClick = { onDeleteApiKey(item.key) }) {
                                    Icon(
                                        Icons.Filled.DeleteOutline,
                                        contentDescription = "删除密钥",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                    FilledTonalButton(
                        onClick = onOpenSecurityKeys,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Filled.Key, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("设置安全密钥（管理密码 + API 密钥）", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                    OutlinedButton(
                        onClick = onOpenAccessibilitySettings,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            if (uiState.environment.accessibilityEnabled) {
                                "自动操控权限：已开启（点击管理）"
                            } else {
                                "开启自动操控权限（无障碍）"
                            }
                        )
                    }
                    if (!uiState.environment.termuxRunCommandGranted) {
                        OutlinedButton(
                            onClick = onRequestTermuxPermission,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("授予 Termux 后台静默调用权限")
                        }
                    }
                    OutlinedButton(
                        onClick = onOpenTermuxBatterySettings,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Filled.BatteryChargingFull, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("设置 Termux 电池无限制（防杀后台）")
                    }
                    OutlinedButton(
                        onClick = { onExecuteAction(TermuxAction.ACQUIRE_WAKELOCK) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("开启唤醒锁（防锁屏休眠，不防杀后台）")
                    }
                    OutlinedButton(
                        onClick = onClearCooldowns,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("重置 429 冷却（限流报错时清除计时）")
                    }
                    OutlinedButton(
                        onClick = { onCopyActionCommand(TermuxAction.INSTALL_AND_START) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("复制手动安装命令（备用）")
                    }
                    TextButton(
                        onClick = { TermuxBridge.openTermux(context) },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Icon(Icons.Filled.Terminal, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("打开 Termux")
                    }
                }
            }
        }
    }
}

@Composable
private fun RestartProxyCard(
    online: Boolean,
    onRestart: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "启动反代",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "下次打开 Termux 或酒馆之前，先点一下这里把反代服务跑起来；" +
                    "如果状态已经是「运行中」，就不用管它。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Surface(
                color = Color(0xFFFFF4D6),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "⚠️ 反代服务运行时必须开着 VPN，没开 VPN 会一直失败。",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF8A6D00),
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(10.dp)
                )
            }
            Button(
                onClick = onRestart,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(if (online) "重启反代服务" else "启动反代")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelPullCard(
    uiState: MainUiState,
    onTestModels: () -> Unit
) {
    val models = uiState.service.models
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "拉取反代模型",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "检测反代是否能正常拿到可用模型列表。这一步只是读列表，不消耗任何额度。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = onTestModels,
                enabled = !uiState.testingModels && uiState.service.online,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (uiState.testingModels) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("正在拉取…")
                } else {
                    Text(if (uiState.service.online) "拉取模型列表" else "服务未运行，先启动反代")
                }
            }
            uiState.lastTestResult?.let { (ok, msg) ->
                Surface(
                    color = if (ok) Color(0xFFDCFCE7) else MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }
            if (models.isNotEmpty()) {
                Text(
                    text = "可用模型（${models.size}）",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    models.take(16).forEach { model ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                text = model,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ServiceStatusCard(
    uiState: MainUiState,
    onRefresh: () -> Unit,
    onCopyText: (String, String) -> Unit,
    onOpenConsole: () -> Unit
) {
    val service = uiState.service
    val settings = uiState.settings

    val containerColor = if (service.online) {
        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(if (service.online) Color(0xFF16A34A) else Color(0xFF94A3B8))
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (service.online) "反代服务运行中" else "反代服务未运行",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                IconButton(onClick = onRefresh) {
                    if (service.checking) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新状态")
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatusMetricChip(
                    label = "签名引擎",
                    value = when {
                        !service.online -> "未启动"
                        service.signerRunning == true -> "运行中"
                        service.signerReady == true -> "已就绪"
                        else -> "检测中"
                    },
                    modifier = Modifier.weight(1f)
                )
                StatusMetricChip(
                    label = "账号",
                    value = if (service.online) "${service.accounts.size}" else "-",
                    modifier = Modifier.weight(1f)
                )
                StatusMetricChip(
                    label = "模型",
                    value = if (service.online) "${service.models.size}" else "-",
                    modifier = Modifier.weight(1f)
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))

            AddressCopyRow(
                label = "本机代理地址（填进酒馆 / Cherry Studio 用）",
                url = settings.localBaseUrl,
                onCopy = { onCopyText("本机地址", settings.localBaseUrl) }
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = { onCopyText("代理地址", settings.localBaseUrl) },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("复制地址", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(
                    onClick = { onCopyText("Gemini 地址", "${settings.localBaseUrl}/v1beta") },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("复制 /v1beta", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            if (service.online) {
                FilledTonalButton(
                    onClick = onOpenConsole,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("在浏览器打开管理面板")
                }
            }
        }
    }
}

@Composable
private fun NextStepsCard(
    uiState: MainUiState,
    onGoToAccounts: () -> Unit,
    onGoToClients: () -> Unit,
    onOpenGuide: () -> Unit
) {
    val service = uiState.service
    if (!service.online) return

    val accountReady = service.accounts.isNotEmpty()
    val connectionTested = uiState.lastTestResult?.first == true

    val title: String
    val desc: String
    val actionLabel: String
    val action: () -> Unit

    when {
        !accountReady -> {
            title = "下一步 ①：导入 Google Cookie"
            desc = "服务已上线！现在去「账号」页粘贴 Cookie-Editor 导出的 JSON，导入后就能刷出模型。"
            actionLabel = "去导入 Cookie"
            action = onGoToAccounts
        }
        !connectionTested -> {
            title = "下一步 ②：拉取模型并配置客户端"
            desc = "账号已就绪（${service.accounts.size} 个）。点上方「拉取模型列表」确认正常，然后复制本机地址填入酒馆（配置参数见「解疑」页）。"
            actionLabel = "查看酒馆配置说明"
            action = onGoToClients
        }
        else -> {
            title = "全部就绪 ✅"
            desc = "服务、账号、模型均正常。你可以直接关闭本 App，仅保留 Termux 和 VPN 在后台即可；以后需要查额度或遇到问题时再打开本 App。"
            actionLabel = "查看解疑说明"
            action = onOpenGuide
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (accountReady && connectionTested) Icons.Filled.CheckCircle else Icons.Filled.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = action,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(actionLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = onOpenGuide) {
                    Text("使用说明", maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun StatusMetricChip(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun AddressCopyRow(
    label: String,
    url: String,
    onCopy: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = url,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium
            )
        }
        IconButton(onClick = onCopy) {
            Icon(
                imageVector = Icons.Filled.ContentCopy,
                contentDescription = "复制 $label",
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun ChecklistRow(
    ok: Boolean,
    title: String,
    subtitle: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = if (ok) Icons.Filled.CheckCircle else Icons.Filled.Warning,
            contentDescription = null,
            tint = if (ok) Color(0xFF16A34A) else Color(0xFFD97706),
            modifier = Modifier
                .padding(top = 2.dp)
                .size(20.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
