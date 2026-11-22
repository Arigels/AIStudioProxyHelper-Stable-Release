package com.aistudio.proxyhelper.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
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

@Composable
fun SetupScreen(
    uiState: MainUiState,
    onRequestTermuxPermission: () -> Unit,
    onRefreshStatus: () -> Unit,
    onSaveSettings: (ProxySettings) -> Unit,
    onExecuteAction: (TermuxAction) -> Unit,
    onRunManualFallback: (TermuxAction) -> Unit,
    onCopyActionCommand: (TermuxAction) -> Unit,
    onCopyText: (String, String) -> Unit,
    onClearCooldowns: () -> Unit,
    onOpenConsole: () -> Unit,
    onGoToAccounts: () -> Unit,
    onGoToClients: () -> Unit,
    onOpenGuide: () -> Unit
) {
    val context = LocalContext.current
    val env = uiState.environment
    var showTermuxDialog by remember { mutableStateOf(false) }
    var showA11yHelpDialog by remember { mutableStateOf(false) }
    var pendingAutoAction by remember { mutableStateOf<TermuxAction?>(null) }

    fun openA11yWithHint() {
        showA11yHelpDialog = true
    }

    fun triggerSmartAction(action: TermuxAction) {
        if (!env.termuxInstalled) {
            showTermuxDialog = true
            return
        }
        val canDirectIpc = env.termuxRunCommandGranted &&
            uiState.externalAppsSetupDone &&
            action != TermuxAction.INSTALL_AND_START
        if (env.accessibilityEnabled || canDirectIpc) {
            onExecuteAction(action)
        } else {
            // Guide user to enable Accessibility auto-controller (or skip to one-tap manual paste)
            pendingAutoAction = action
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. 状态
        ServiceStatusCard(
            uiState = uiState,
            onRefresh = onRefreshStatus,
            onCopyText = onCopyText,
            onOpenConsole = onOpenConsole
        )

        // 2. 上线后的下一步引导
        NextStepsCard(
            uiState = uiState,
            onGoToAccounts = onGoToAccounts,
            onGoToClients = onGoToClients,
            onOpenGuide = onOpenGuide
        )

        // 3. 唯一主操作
        PrimaryActionCard(
            uiState = uiState,
            onDeploy = { triggerSmartAction(TermuxAction.INSTALL_AND_START) },
            onStart = { triggerSmartAction(TermuxAction.START_SERVICE) },
            onStop = { triggerSmartAction(TermuxAction.STOP_SERVICE) },
            onUpdate = { triggerSmartAction(TermuxAction.UPDATE_AND_REBUILD) }
        )

        // 4. 首次准备（只保留 2 步，做完自动消失）
        PrerequisitesCard(
            uiState = uiState,
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
            }
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
                        "请从 F-Droid 或 GitHub 下载 64 位安装包（文件名带 arm64-v8a），切勿使用 Google Play 商店旧版。装好后回到这里点一键搭建即可。"
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

    pendingAutoAction?.let { action ->
        AlertDialog(
            onDismissRequest = { pendingAutoAction = null },
            title = { Text("开启自动操控权限（推荐）") },
            text = {
                Text(
                    "在系统「无障碍」里开启「AI Studio 反代自动搭建助手」后，本 App 会自动打开 Termux、自动粘贴命令并按回车开始搭建，全程不用你手动操作。\n\n" +
                        "（仅在你点击按钮时临时运行，不会收集任何隐私）\n\n" +
                        "⚠️ 小米/红米、OPPO、vivo、荣耀等手机会弹一行很小的「已限制设置」，要再点它旁边的「解除限制」才能打开开关。"
                )
            },
            confirmButton = {
                Button(onClick = {
                    pendingAutoAction = null
                    openA11yWithHint()
                }) {
                    Text("去开启（推荐）")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingAutoAction = null
                    onRunManualFallback(action)
                }) {
                    Text("不开，自己粘贴")
                }
            }
        )
    }

    if (showA11yHelpDialog) {
        AlertDialog(
            onDismissRequest = { showA11yHelpDialog = false },
            title = { Text("开启自动操控，只需要在这里点几下") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "1. 点下面的「打开无障碍设置」",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "2. 找到「AI Studio 反代自动搭建助手」，打开右边的开关，弹窗点「允许」",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "3. ⚠️ 重点：如果开关旁边出现一行小字「已限制设置」（小米/红米/OPPO/vivo/荣耀常见），" +
                            "要再点它右边的小字「解除限制」（有的手机叫「允许受限制的设置」），然后才能把开关打开。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "4. 打开后返回本 App，首页会显示「自动操控已开启」。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "如果实在打不开也没关系：点「一键搭建并启动」时 App 会把命令复制好并打开 Termux，你只要长按屏幕点「粘贴」再按回车即可。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    showA11yHelpDialog = false
                    AutomationController.openAccessibilitySettings(context)
                }) {
                    Text("打开无障碍设置")
                }
            },
            dismissButton = {
                TextButton(onClick = { showA11yHelpDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun PrimaryActionCard(
    uiState: MainUiState,
    onDeploy: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onUpdate: () -> Unit
) {
    val service = uiState.service
    val deployed = uiState.hasDeployed
    val autoReady = uiState.environment.accessibilityEnabled
    val context = LocalContext.current

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
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
                    text = "搭建已完成。下一步：去「账号」页导入 Cookie，或去「使用」页复制地址填进酒馆。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(
                    onClick = onStop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("停止服务")
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
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Filled.Terminal, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("打开 Termux 看进度", maxLines = 1)
                    }
                    TextButton(onClick = onDeploy) {
                        Text("重新安装")
                    }
                }
            } else {
                Button(
                    onClick = {
                        if (!deployed) onDeploy() else onStart()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(
                        imageVector = if (deployed) Icons.Filled.PlayArrow else Icons.Filled.RocketLaunch,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = when {
                            deployed -> "一键启动服务"
                            autoReady -> "一键全自动搭建"
                            else -> "一键搭建并启动"
                        },
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                Text(
                    text = when {
                        deployed -> "上次已经装好了，点一下自动拉起服务。"
                        autoReady -> "自动操控已就绪：点击后将自动打开 Termux、输入并执行搭建（首次约 3~5 分钟）。"
                        else -> "首次约 3~5 分钟：自动安装依赖、编译本地签名引擎并启动服务。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                uiState.automationStatus?.let { status ->
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = status,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                        )
                    }
                }

                if (deployed) {
                    TextButton(
                        onClick = onUpdate,
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("运行异常？重新安装 / 修复")
                    }
                }
            }
        }
    }
}

@Composable
private fun PrerequisitesCard(
    uiState: MainUiState,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenFDroid: () -> Unit,
    onOpenGitHub: () -> Unit
) {
    val env = uiState.environment
    val allDone = env.termuxInstalled && (env.accessibilityEnabled || uiState.hasDeployed)
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
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("F-Droid 下载")
                    }
                    OutlinedButton(
                        onClick = onOpenGitHub,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("GitHub 下载")
                    }
                }
            } else {
                ChecklistRow(
                    ok = true,
                    title = "1. Termux 已安装",
                    subtitle = "版本 ${env.termuxVersionName ?: "已就绪"}"
                )
            }

            // 2. Accessibility auto-controller
            if (!env.accessibilityEnabled) {
                ChecklistRow(
                    ok = false,
                    title = "2. 开启自动操控权限（推荐）",
                    subtitle = "在系统无障碍中开启「AI Studio 反代自动搭建助手」，即可全自动搭建。\n" +
                        "⚠️ 小米/红米/OPPO/vivo/荣耀 等手机常提示「已限制设置」，需要点它旁边的小字「解除限制」才能打开开关。"
                )
                FilledTonalButton(
                    onClick = onOpenAccessibilitySettings,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("去开启自动操控权限")
                }
            } else {
                ChecklistRow(
                    ok = true,
                    title = "2. 自动操控已开启",
                    subtitle = "现在可以直接点上方「一键全自动搭建」"
                )
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
    onOpenTermuxBatterySettings: () -> Unit
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
                        Text("开启唤醒锁（防锁屏断线）")
                    }
                    OutlinedButton(
                        onClick = onClearCooldowns,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("重置 429 冷却（遇到报错时用）")
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
private fun ServiceStatusCard(
    uiState: MainUiState,
    onRefresh: () -> Unit,
    onCopyText: (String, String) -> Unit,
    onOpenConsole: () -> Unit
) {
    val service = uiState.service
    val settings = uiState.settings
    val lanUrl = settings.lanBaseUrl(uiState.environment.lanIpAddress)

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
                label = "本机地址",
                url = settings.localBaseUrl,
                onCopy = { onCopyText("本机地址", settings.localBaseUrl) }
            )
            if (lanUrl != null) {
                AddressCopyRow(
                    label = "局域网地址",
                    url = lanUrl,
                    onCopy = { onCopyText("局域网地址", lanUrl) }
                )
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
            title = "下一步 ②：测试并配置客户端"
            desc = "账号已就绪（${service.accounts.size} 个）。去「使用」页测试模型列表，并复制地址填入酒馆 / Cherry Studio。"
            actionLabel = "去配置客户端"
            action = onGoToClients
        }
        else -> {
            title = "全部就绪 ✅"
            desc = "服务、账号、模型均正常。手机重启后只需回到本页点「一键启动服务」即可恢复。"
            actionLabel = "查看使用说明"
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
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(actionLabel)
                }
                TextButton(onClick = onOpenGuide) {
                    Text("使用说明")
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
