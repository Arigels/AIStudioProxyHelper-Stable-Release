package com.aistudio.proxyhelper.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
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
import com.aistudio.proxyhelper.network.EnvironmentChecker
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
    val scrollState = rememberScrollState()

    var showConfigEditor by remember { mutableStateOf(false) }
    var showCommandPreview by remember { mutableStateOf(false) }

    var portInput by remember(uiState.settings.port) { mutableStateOf(uiState.settings.port.toString()) }
    var proxyInput by remember(uiState.settings.proxyUrl) { mutableStateOf(uiState.settings.proxyUrl) }
    var passwordInput by remember(uiState.settings.webPassword) { mutableStateOf(uiState.settings.webPassword) }
    var dirInput by remember(uiState.settings.installDir) { mutableStateOf(uiState.settings.installDir) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Live Service Status Card
        ServiceStatusCard(
            uiState = uiState,
            onRefresh = onRefreshStatus,
            onCopyText = onCopyText,
            onClearCooldowns = onClearCooldowns,
            onOpenConsole = onOpenConsole
        )

        // 2. Contextual next-steps guide (shown once the service is online)
        NextStepsCard(
            uiState = uiState,
            onGoToAccounts = onGoToAccounts,
            onGoToClients = onGoToClients,
            onOpenGuide = onOpenGuide
        )

        // 3. Primary One-Click Deployment Card
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.RocketLaunch,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "一键搭建与服务控制",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    TextButton(onClick = { showConfigEditor = !showConfigEditor }) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (showConfigEditor) "收起参数" else "端口/代理")
                    }
                }

                Text(
                    text = "纯本地 IPC 签名架构（SpiderMonkey 152 + HTTP/2），无需无头浏览器，常驻内存仅约 35~50 MB。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Expandable Config Editor
                AnimatedVisibility(visible = showConfigEditor) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedTextField(
                                value = portInput,
                                onValueChange = { portInput = it.filter { ch -> ch.isDigit() } },
                                label = { Text("监听端口（默认 8080）") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = proxyInput,
                                onValueChange = { proxyInput = it },
                                label = { Text("网络代理（可选，如 http://127.0.0.1:7890）") },
                                placeholder = { Text("留空则由 install.sh 自动探测常见代理端口") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = passwordInput,
                                onValueChange = { passwordInput = it },
                                label = { Text("Web 后台密码 AISTUDIO_WEB_PASSWORD（可选）") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = dirInput,
                                onValueChange = { dirInput = it },
                                label = { Text("Termux 安装目录（建议保持在 \$HOME 内）") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
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
                                        showConfigEditor = false
                                    }
                                ) {
                                    Text("保存参数")
                                }
                            }
                        }
                    }
                }

                // Main CTA: One-click Install & Start
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { onExecuteAction(TermuxAction.INSTALL_AND_START) },
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Filled.RocketLaunch, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("一键全新安装并启动", fontWeight = FontWeight.Bold)
                    }
                    IconButton(
                        onClick = { onCopyActionCommand(TermuxAction.INSTALL_AND_START) }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ContentCopy,
                            contentDescription = "复制安装命令"
                        )
                    }
                }

                // Start / Stop Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        onClick = { onExecuteAction(TermuxAction.START_SERVICE) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("启动服务")
                    }
                    OutlinedButton(
                        onClick = { onExecuteAction(TermuxAction.STOP_SERVICE) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("停止服务")
                    }
                }

                // Install Only / Update & Rebuild Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { onExecuteAction(TermuxAction.INSTALL_ONLY) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Filled.Build, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("仅安装编译", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    OutlinedButton(
                        onClick = { onExecuteAction(TermuxAction.UPDATE_AND_REBUILD) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Filled.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("更新并重编", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }

                // Wakelock & Open Termux Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { onExecuteAction(TermuxAction.ACQUIRE_WAKELOCK) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Filled.BatteryChargingFull, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("唤醒锁保活", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    OutlinedButton(
                        onClick = {
                            if (!TermuxBridge.openTermux(context)) {
                                TermuxBridge.openUrl(context, TermuxBridge.URL_TERMUX_FDROID)
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Filled.Terminal, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("打开 Termux", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }

                // Toggle Command Preview
                TextButton(
                    onClick = { showCommandPreview = !showCommandPreview },
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(if (showCommandPreview) "隐藏底层命令" else "查看 / 复制手动命令")
                    Icon(
                        imageVector = if (showCommandPreview) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null
                    )
                }

                AnimatedVisibility(visible = showCommandPreview) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CommandPreviewItem(
                            title = "一键安装并启动命令",
                            command = TermuxBridge.buildCommand(TermuxAction.INSTALL_AND_START, uiState.settings),
                            onCopy = { onCopyActionCommand(TermuxAction.INSTALL_AND_START) }
                        )
                        CommandPreviewItem(
                            title = "日常启动命令",
                            command = TermuxBridge.buildCommand(TermuxAction.START_SERVICE, uiState.settings),
                            onCopy = { onCopyActionCommand(TermuxAction.START_SERVICE) }
                        )
                    }
                }
            }
        }

        // 4. Environment Readiness & Termux Setup Checklist
        EnvironmentChecklistCard(
            uiState = uiState,
            onRequestTermuxPermission = onRequestTermuxPermission,
            onEnableExternalApps = { onExecuteAction(TermuxAction.ENABLE_EXTERNAL_APPS) },
            onOpenFDroid = { TermuxBridge.openUrl(context, TermuxBridge.URL_TERMUX_FDROID) },
            onOpenGitHub = { TermuxBridge.openUrl(context, TermuxBridge.URL_TERMUX_GITHUB) },
            onOpenBatterySettings = { EnvironmentChecker.openBatteryOptimizationSettings(context) },
            onOpenTermuxSettings = { EnvironmentChecker.openTermuxAppDetails(context) }
        )

        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun ServiceStatusCard(
    uiState: MainUiState,
    onRefresh: () -> Unit,
    onCopyText: (String, String) -> Unit,
    onClearCooldowns: () -> Unit,
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

            // Metrics Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatusMetricChip(
                    label = "WAA 签名引擎",
                    value = when {
                        !service.online -> "未启动"
                        service.signerRunning == true -> "IPC 运行中"
                        service.signerReady == true -> "已就绪"
                        else -> "检测中"
                    },
                    modifier = Modifier.weight(1f)
                )
                StatusMetricChip(
                    label = "账号数",
                    value = if (service.online) "${service.accounts.size}" else "-",
                    modifier = Modifier.weight(1f)
                )
                StatusMetricChip(
                    label = "可用模型",
                    value = if (service.online) "${service.models.size}" else "-",
                    modifier = Modifier.weight(1f)
                )
            }

            // Endpoints
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

            if (service.lastError != null && service.online) {
                Text(
                    text = "提示：${service.lastError}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            if (service.online) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        onClick = onOpenConsole,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("打开管理面板")
                    }
                    OutlinedButton(
                        onClick = onClearCooldowns,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("重置 429 冷却")
                    }
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
private fun EnvironmentChecklistCard(
    uiState: MainUiState,
    onRequestTermuxPermission: () -> Unit,
    onEnableExternalApps: () -> Unit,
    onOpenFDroid: () -> Unit,
    onOpenGitHub: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onOpenTermuxSettings: () -> Unit
) {
    val env = uiState.environment

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Shield,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "首次准备 & 环境体检",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            // 1. Architecture
            ChecklistRow(
                ok = env.is64BitArm,
                title = "64 位手机架构检测",
                subtitle = if (env.is64BitArm) {
                    "已支持 (${env.abiSummary})"
                } else {
                    "检测到 32 位系统 (${env.abiSummary})，SpiderMonkey 签名引擎需要 64 位 arm64-v8a"
                }
            )

            // 2. Termux installed
            ChecklistRow(
                ok = env.termuxInstalled,
                title = "Termux 运行环境",
                subtitle = if (env.termuxInstalled) {
                    "已安装 (版本 ${env.termuxVersionName ?: "未知"}) — 请确保来自 F-Droid 或 GitHub"
                } else {
                    "尚未安装！请务必从 F-Droid 或 GitHub Releases 下载，切勿安装 Google Play 商店旧版"
                }
            )
            if (!env.termuxInstalled) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onOpenFDroid,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("F-Droid 下载")
                    }
                    OutlinedButton(
                        onClick = onOpenGitHub,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("GitHub 下载")
                    }
                }
            }

            // 3. RUN_COMMAND Permission & allow-external-apps
            ChecklistRow(
                ok = env.termuxRunCommandGranted,
                title = "App 调用 Termux 权限",
                subtitle = if (env.termuxRunCommandGranted) {
                    "已授权 RUN_COMMAND；首次使用还需在 Termux 中启用 allow-external-apps"
                } else {
                    "授权后即可一键把安装/启动命令直接发送到 Termux 自动运行"
                }
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!env.termuxRunCommandGranted) {
                    FilledTonalButton(
                        onClick = onRequestTermuxPermission,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("授予调用权限")
                    }
                }
                OutlinedButton(
                    onClick = onEnableExternalApps,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("开启 external-apps", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            // 4. Battery optimization
            ChecklistRow(
                ok = env.batteryOptimizationIgnored,
                title = "后台保活与电池白名单",
                subtitle = "建议同时将本 App 与 Termux 设为「电池无限制」，并在下拉通知栏点击 Termux 的 Acquire Wakelock"
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onOpenBatterySettings,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("本 App 电池白名单", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(
                    onClick = onOpenTermuxSettings,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Termux 电池设置", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
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

@Composable
private fun CommandPreviewItem(
    title: String,
    command: String,
    onCopy: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                TextButton(onClick = onCopy) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("复制")
                }
            }
            Text(
                text = command,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
