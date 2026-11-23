package com.aistudio.proxyhelper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aistudio.proxyhelper.data.AccountSummary
import com.aistudio.proxyhelper.data.EnvironmentStatus
import com.aistudio.proxyhelper.termux.TermuxBridge

@Composable
fun AccountsScreen(
    uiState: MainUiState,
    onImportCookies: (rawCookies: String, probeMulti: Boolean, namePrefix: String?) -> Unit,
    onShowMessage: (String) -> Unit
) {
    val context = LocalContext.current
    var cookieText by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        CookieGuideCard(
            env = uiState.environment,
            onShowMessage = onShowMessage
        )

        // ── 导入 ──
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "粘贴 Cookie 导入",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    FilledTonalButton(onClick = {
                        val clip = TermuxBridge.readFromClipboard(context)
                        if (clip.isBlank()) {
                            onShowMessage("剪贴板是空的，请先用 Cookie-Editor 导出")
                        } else {
                            cookieText = clip
                            onShowMessage("已从剪贴板粘贴")
                        }
                    }) {
                        Icon(
                            Icons.Filled.ContentPaste,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("粘贴剪贴板")
                    }
                }

                OutlinedTextField(
                    value = cookieText,
                    onValueChange = { cookieText = it },
                    label = { Text("Cookie 内容（Cookie-Editor 导出的 JSON）") },
                    placeholder = { Text("在这里粘贴...") },
                    minLines = 5,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth()
                )

                if (!uiState.service.online) {
                    Text(
                        text = "注意：服务还没启动。请先到「反代」页启动服务，再来导入。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Button(
                    onClick = { onImportCookies(cookieText, true, null) },
                    enabled = !uiState.importingCookie && cookieText.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (uiState.importingCookie) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("正在识别账号...")
                    } else {
                        Icon(
                            Icons.Filled.VpnKey,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("导入并自动识别所有账号", fontWeight = FontWeight.Bold)
                    }
                }

                uiState.lastImportResult?.let { res ->
                    Surface(
                        color = if (res.success) {
                            Color(0xFFDCFCE7)
                        } else {
                            MaterialTheme.colorScheme.errorContainer
                        },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (res.success) {
                                    Icons.Filled.CheckCircle
                                } else {
                                    Icons.Filled.ErrorOutline
                                },
                                contentDescription = null,
                                tint = if (res.success) Color(0xFF15803D) else MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = res.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (res.success) Color(0xFF14532D) else MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }
        }

        // ── 已导入账号 ──
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "已导入账号（${uiState.service.accounts.size}）",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "每个账号右侧的标签是 Google 当前订阅等级：Free（免费）/ Pro / Ultra，方便你挑账号用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (uiState.service.accounts.isEmpty()) {
                    Text(
                        text = "还没有账号。导入后就可以在酒馆等客户端里直接刷出模型了。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    uiState.service.accounts.forEachIndexed { idx, acc ->
                        AccountRowItem(acc)
                        if (idx < uiState.service.accounts.lastIndex) {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

private data class DetectedBrowser(
    val label: String,
    val packageName: String,
    val addonUrl: String
)

private fun resolveTargetBrowser(
    env: EnvironmentStatus,
    preferFirefox: Boolean
): DetectedBrowser? {
    val edgePkg = env.edgePackage
    val firefoxPkg = env.firefoxPackage ?: TermuxBridge.PACKAGE_FIREFOX.takeIf { env.firefoxInstalled }
    val edge = edgePkg?.let {
        DetectedBrowser("Edge", it, TermuxBridge.URL_COOKIE_EDITOR_EDGE)
    }
    val firefox = firefoxPkg?.let {
        DetectedBrowser("火狐", it, TermuxBridge.URL_COOKIE_EDITOR)
    }
    return when {
        edge != null && firefox != null -> if (preferFirefox) firefox else edge
        edge != null -> edge
        firefox != null -> firefox
        else -> null
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CookieGuideCard(
    env: EnvironmentStatus,
    onShowMessage: (String) -> Unit
) {
    val context = LocalContext.current
    val hasEdge = env.edgePackage != null
    val firefoxPkg = env.firefoxPackage ?: TermuxBridge.PACKAGE_FIREFOX.takeIf { env.firefoxInstalled }
    val hasFirefox = firefoxPkg != null
    var preferFirefox by rememberSaveable { mutableStateOf(false) }
    val targetBrowser = resolveTargetBrowser(env, preferFirefox)

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        ),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "获取 Cookie 教程（约 3 分钟）",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Surface(
                color = Color(0xFFFFF4D6),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "⚠️ 开始以下操作前，请先确认 VPN 已开启：登录 Google 账号必须能访问 Google。",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF8A6D00),
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(10.dp)
                )
            }
            Text(
                text = "⚠️ 手机版 Chrome 不支持浏览器扩展；Microsoft Edge 与火狐都支持，本教程会自动识别你安装的浏览器。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )

            // 步骤 1
            GuideStep(number = 1, title = "安装支持扩展的浏览器（Edge / 火狐）") {
                if (env.edgeInstalled) {
                    Text(
                        text = "✅ 已检测到 Microsoft Edge，可以直接使用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF15803D),
                        fontWeight = FontWeight.SemiBold
                    )
                    OutlinedButton(
                        onClick = {
                            val pkg = env.edgePackage ?: TermuxBridge.PACKAGE_EDGE
                            if (!TermuxBridge.launchPackage(context, pkg)) {
                                onShowMessage("未能打开 Edge，请手动从桌面启动")
                            }
                        },
                        shape = RoundedCornerShape(10.dp)
                    ) { Text("打开 Edge") }
                } else {
                    Text(
                        text = "推荐 Microsoft Edge（安卓版支持扩展，官网可直连下载）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = { TermuxBridge.openUrl(context, TermuxBridge.URL_EDGE_ANDROID) },
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Filled.OpenInBrowser, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("下载 Edge（官网）")
                    }
                }

                if (hasFirefox && firefoxPkg != null) {
                    Text(
                        text = "✅ 已检测到火狐浏览器，也可以使用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF15803D),
                        fontWeight = FontWeight.SemiBold
                    )
                    OutlinedButton(
                        onClick = {
                            if (!TermuxBridge.launchPackage(context, firefoxPkg)) {
                                onShowMessage("未能打开火狐，请手动从桌面启动")
                            }
                        },
                        shape = RoundedCornerShape(10.dp)
                    ) { Text("打开火狐") }
                } else {
                    Text(
                        text = "备选：火狐浏览器（Firefox）国际版，官网可直接下载 APK。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                TermuxBridge.openUrl(context, TermuxBridge.URL_FIREFOX_ANDROID)
                            },
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("下载火狐（官网）", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        OutlinedButton(
                            onClick = {
                                TermuxBridge.openUrl(context, TermuxBridge.URL_FIREFOX_ARCHIVE)
                            },
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("备用下载地址", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }

                Text(
                    text = if (env.lemurInstalled) {
                        "✅ 已检测到狐猴浏览器（Lemur），也可以用它装扩展。"
                    } else {
                        "备选：狐猴浏览器（Lemur）—— 在国内应用商店搜索「狐猴浏览器」即可安装，自带扩展商店。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (env.lemurInstalled) Color(0xFF15803D) else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 步骤 2
            GuideStep(number = 2, title = "安装 Cookie-Editor 扩展") {
                if (hasEdge && hasFirefox) {
                    Text(
                        text = "同时检测到 Edge 和火狐，可点选要使用的浏览器：",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = !preferFirefox,
                            onClick = { preferFirefox = false },
                            label = { Text("使用 Edge", maxLines = 1) },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = preferFirefox,
                            onClick = { preferFirefox = true },
                            label = { Text("使用火狐", maxLines = 1) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                if (targetBrowser != null) {
                    Text(
                        text = "已自动识别到你安装了 ${targetBrowser.label}，可直接打开或复制对应扩展链接。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = {
                            val ok = TermuxBridge.openUrlInPackage(
                                context,
                                targetBrowser.addonUrl,
                                targetBrowser.packageName
                            )
                            if (!ok) {
                                onShowMessage("未能用 ${targetBrowser.label} 打开，请用下方按钮复制链接后手动粘贴")
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Filled.OpenInBrowser, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "在 ${targetBrowser.label} 打开扩展页",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            TermuxBridge.copyToClipboard(
                                context,
                                "Cookie-Editor ${targetBrowser.label}扩展链接",
                                targetBrowser.addonUrl
                            )
                            onShowMessage("${targetBrowser.label} Cookie-Editor 扩展链接已复制，去${targetBrowser.label}地址栏粘贴打开")
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "复制${targetBrowser.label} CE 扩展链接",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                } else {
                    Text(
                        text = "还没检测到 Edge 或火狐：请先完成第 1 步，或直接复制对应浏览器的扩展链接到地址栏粘贴打开。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                TermuxBridge.copyToClipboard(
                                    context,
                                    "Cookie-Editor Edge 链接",
                                    TermuxBridge.URL_COOKIE_EDITOR_EDGE
                                )
                                onShowMessage("Edge 扩展链接已复制，去 Edge 地址栏粘贴打开")
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("复制 Edge CE 扩展链接", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        OutlinedButton(
                            onClick = {
                                TermuxBridge.copyToClipboard(
                                    context,
                                    "Cookie-Editor 火狐链接",
                                    TermuxBridge.URL_COOKIE_EDITOR
                                )
                                onShowMessage("火狐扩展链接已复制，去火狐地址栏粘贴打开")
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("复制火狐 CE 扩展链接", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Text(
                        text = "提示：Edge 用 Edge 扩展链接，火狐用 Firefox 扩展链接，两个不通用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 步骤 3
            GuideStep(number = 3, title = "登录 Google AI Studio") {
                Text(
                    text = "需要能访问 Google 的网络环境。有多个账号时，点右上角头像「添加其他账号」把账号全部登好（导入时会自动识别）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (targetBrowser != null) {
                    OutlinedButton(
                        onClick = {
                            val ok = TermuxBridge.openUrlInPackage(
                                context,
                                TermuxBridge.URL_AISTUDIO,
                                targetBrowser.packageName
                            )
                            if (!ok) {
                                onShowMessage("未能用 ${targetBrowser.label} 打开，请用下方「复制链接」手动粘贴")
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Filled.OpenInBrowser, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "在 ${targetBrowser.label} 打开 AI Studio",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                } else {
                    OutlinedButton(
                        onClick = { TermuxBridge.openUrl(context, TermuxBridge.URL_AISTUDIO) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Filled.OpenInBrowser, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("打开 AI Studio", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                OutlinedButton(
                    onClick = {
                        TermuxBridge.copyToClipboard(
                            context,
                            "AI Studio 链接",
                            TermuxBridge.URL_AISTUDIO
                        )
                        onShowMessage("AI Studio 链接已复制，去浏览器地址栏粘贴打开")
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("复制 AI Studio 链接", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            // 步骤 4
            GuideStep(number = 4, title = "导出 Cookie") {
                Text(
                    text = "点浏览器右上角（或工具栏）的 Cookie-Editor 图标 → Export → Export as JSON。内容会自动复制到剪贴板。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 步骤 5
            GuideStep(number = 5, title = "回到本 App 粘贴导入") {
                Text(
                    text = "在下方「粘贴 Cookie 导入」里点「粘贴剪贴板」，再点「导入并自动识别所有账号」即可。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Surface(
                color = Color(0xFFE8F5E9),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "✅ 以上步骤全部完成后，Edge / 火狐浏览器和 Cookie-Editor 就可以按需卸载了——" +
                        "Cookie 已经保存在本 App 里，卸载浏览器不影响反代使用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF1B5E20),
                    modifier = Modifier.padding(10.dp)
                )
            }
        }
    }
}

@Composable
private fun GuideStep(
    number: Int,
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .background(
                    color = MaterialTheme.colorScheme.primary,
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = number.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimary,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            content()
        }
    }
}

@Composable
private fun AccountRowItem(acc: AccountSummary) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Filled.AccountCircle,
            contentDescription = null,
            tint = if (acc.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(32.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = acc.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = acc.email ?: "未探测到邮箱",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                val (tierLabel, tierColor) = tierBadge(acc.tier)
                Surface(
                    color = tierColor.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = tierLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = tierColor,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

/** 把反代服务返回的等级字段归一化为 Free / Pro / Ultra 标签。 */
private fun tierBadge(raw: String): Pair<String, Color> {
    val t = raw.trim().lowercase()
    return when {
        t.contains("ultra") -> "Ultra" to Color(0xFF7C3AED)
        t.contains("pro") -> "Pro" to Color(0xFF1D4ED8)
        t.contains("free") -> "Free" to Color(0xFF15803D)
        else -> "等级未识别" to Color(0xFF6B7280)
    }
}
