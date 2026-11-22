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
import com.aistudio.proxyhelper.data.ProxySettings
import com.aistudio.proxyhelper.data.TermuxAction
import com.aistudio.proxyhelper.termux.TermuxBridge

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
    onOpenGuide: () -> Unit
) {
    val context = LocalContext.current
    val env = uiState.environment
    var showTermuxDialog by remember { mutableStateOf(false) }
    var showPermissionDialog by remember { mutableStateOf(false) }

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
            onDeploy = { onExecuteAction(TermuxAction.INSTALL_AND_START) },
            onStart = { onExecuteAction(TermuxAction.START_SERVICE) },
            onStop = { onExecuteAction(TermuxAction.STOP_SERVICE) },
            onUpdate = { onExecuteAction(TermuxAction.UPDATE_AND_REBUILD) },
            onBlocked = {
                when {
                    !env.termuxInstalled -> showTermuxDialog = true
                    !env.termuxRunCommandGranted -> showPermissionDialog = true
                }
            }
        )

        // 4. 首次准备（做完自动消失）
        PrerequisitesCard(
            uiState = uiState,
            onRequestPermission = onRequestTermuxPermission,
            onEnableExternalApps = { onExecuteAction(TermuxAction.ENABLE_EXTERNAL_APPS) },
            onOpenTermux = { TermuxBridge.openTermux(context) },
            onOpenFDroid = { TermuxBridge.openUrl(context, TermuxBridge.URL_TERMUX_FDROID) },
            onOpenGitHub = { TermuxBridge.openUrl(context, TermuxBridge.URL_TERMUX_GITHUB) }
        )

        // 5. 高级设置（默认折叠）
        AdvancedSettingsCard(
            uiState = uiState,
            onSaveSettings = onSaveSettings,
            onExecuteAction = onExecuteAction,
            onCopyActionCommand = onCopyActionCommand,
            onClearCooldowns = onClearCooldowns
        )

        Spacer(modifier = Modifier.height(8.dp))
    }

    if (showTermuxDialog) {
        AlertDialog(
            onDismissRequest = { showTermuxDialog = false },
            title = { Text("先安装 Termux") },
            text = {
                Text(
                    "需要先装 Termux（手机上的终端工具）。\n\n" +
                        "请从 F-Droid 或 GitHub 下载 64 位版本，不要用应用商店的旧版。装好后回到本页继续。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showTermuxDialog = false
                    TermuxBridge.openUrl(context, TermuxBridge.URL_TERMUX_FDROID)
                }) { Text("去 F-Droid 下载") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showTermuxDialog = false
                    TermuxBridge.openUrl(context, TermuxBridge.URL_TERMUX_GITHUB)
                }) { Text("去 GitHub 下载") }
            }
        )
    }

    if (showPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showPermissionDialog = false },
            title = { Text("还差一个授权") },
            text = { Text("需要允许本 App 调用 Termux 执行命令，弹窗里点「允许」即可（只需一次）。") },
            confirmButton = {
                TextButton(onClick = {
                    showPermissionDialog = false
                    onRequestTermuxPermission()
                }) { Text("去授权") }
            },
            dismissButton = {
                TextButton(onClick = { showPermissionDialog = false }) { Text("取消") }
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
    onUpdate: () -> Unit,
    onBlocked: () -> Unit
) {
    val service = uiState.service
    val ready = uiState.environment.termuxInstalled &&
        uiState.environment.termuxRunCommandGranted
    val deployed = uiState.hasDeployed

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
                    text = "手机上已经可以用了。下一步：去「账号」页导入 Cookie，或去「客户端」页复制地址填进酒馆。",
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
            } else {
                Button(
                    onClick = {
                        when {
                            !ready -> onBlocked()
                            !deployed -> onDeploy()
                            else -> onStart()
                        }
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
                        text = if (deployed) "启动服务" else "一键搭建并启动",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                Text(
                    text = if (deployed) {
                        "上次已经装好了，点一下就能重新启动。"
                    } else {
                        "首次约 3~5 分钟：自动安装依赖、编译本地签名引擎并启动服务，全程你不用操作。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
    onRequestPermission: () -> Unit,
    onEnableExternalApps: () -> Unit,
    onOpenTermux: () -> Unit,
    onOpenFDroid: () -> Unit,
    onOpenGitHub: () -> Unit
) {
    val env = uiState.environment
    val allDone = env.termuxInstalled &&
        env.termuxRunCommandGranted &&
        uiState.externalAppsSetupDone
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
                    text = "首次准备（各做一次，做完自动消失）",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }

            if (!env.termuxInstalled) {
                ChecklistRow(
                    ok = false,
                    title = "安装 Termux",
                    subtitle = "必须用 F-Droid / GitHub 的 64 位版本，勿用应用商店旧版"
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
                    title = "Termux 已安装",
                    subtitle = "版本 ${env.termuxVersionName ?: "未知"}"
                )
            }

            if (!env.termuxRunCommandGranted) {
                ChecklistRow(
                    ok = false,
                    title = "授予调用权限",
                    subtitle = "允许本 App 把命令发给 Termux（系统弹窗点「允许」）"
                )
                FilledTonalButton(
                    onClick = onRequestPermission,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("去授权")
                }
            }

            if (env.termuxInstalled && !uiState.externalAppsSetupDone) {
                ChecklistRow(
                    ok = false,
                    title = "开启 Termux 一键调用",
                    subtitle = "会把一条「设置命令」复制到剪贴板并打开 Termux，粘贴回车一次即可（不是安装命令）"
                )
                FilledTonalButton(
                    onClick = onEnableExternalApps,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("复制设置命令并打开 Termux")
                }
            }

            if (env.termuxInstalled) {
                TextButton(
                    onClick = onOpenTermux,
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

@Composable
private fun AdvancedSettingsCard(
    uiState: MainUiState,
    onSaveSettings: (ProxySettings) -> Unit,
    onExecuteAction: (TermuxAction) -> Unit,
    onCopyActionCommand: (TermuxAction) -> Unit,
    onClearCooldowns: () -> Unit
) {
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
                        text = "端口、代理等，不懂可以完全不管",
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
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = proxyInput,
                        onValueChange = { proxyInput = it },
                        label = { Text("网络代理（可选）") },
                        placeholder = { Text("只有需要代理才能访问 Google 时才填，否则留空") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = passwordInput,
                        onValueChange = { passwordInput = it },
                        label = { Text("管理后台密码（可选，留空即不设密码）") },
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
                            val parsedPort = portInput.toIntOrNull()?.coerceIn(1024, 65535) ?: 8080
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
                        onClick = { onCopyActionCommand(TermuxAction.INSTALL_AND_START) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("复制手动安装命令（备用）")
                    }
                    OutlinedButton(
                        onClick = { onExecuteAction(TermuxAction.ACQUIRE_WAKELOCK) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Filled.BatteryChargingFull, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("开启唤醒锁（防杀后台）")
                    }
                    OutlinedButton(
                        onClick = onClearCooldowns,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("重置 429 冷却（遇到报错时用）")
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

            // 一行关键数据
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
            desc = "服务已上线，但还没有任何账号，客户端会拉不到模型。去「账号」页粘贴 Cookie-Editor 导出的 JSON，一键导入全部已登录子账号。"
            actionLabel = "去导入 Cookie"
            action = onGoToAccounts
        }
        !connectionTested -> {
            title = "下一步 ②：测试并配置客户端"
            desc = "账号已就绪（${service.accounts.size} 个）。去「客户端」页点「测试拉取模型列表」，再复制地址填入酒馆 / Cherry Studio。"
            actionLabel = "去配置客户端"
            action = onGoToClients
        }
        else -> {
            title = "全部就绪 ✅"
            desc = "服务、账号、模型均正常。手机重启后只需回到本页点「启动服务」即可恢复；完整使用说明见「教程」页。"
            actionLabel = "查看完整教程"
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
                    Text("使用教程")
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
