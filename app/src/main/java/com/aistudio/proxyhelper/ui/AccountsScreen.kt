package com.aistudio.proxyhelper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.runtime.remember
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
    var cookieText by remember { mutableStateOf("") }

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
                        text = "注意：服务还没启动。请先到「搭建」页点一下启动，再来导入。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "⚠️ 导入前请先打开梯子（VPN）：服务导入 Cookie 时要访问 Google，没梯子会导入失败。",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(10.dp)
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

@Composable
private fun CookieGuideCard(
    env: EnvironmentStatus,
    onShowMessage: (String) -> Unit
) {
    val context = LocalContext.current

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
            Text(
                text = "⚠️ 手机版 Chrome / Edge 不支持浏览器扩展，装不了 Cookie-Editor，请按下面步骤操作。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )

            // 步骤 1
            GuideStep(number = 1, title = "安装一个支持扩展的浏览器") {
                if (env.firefoxInstalled) {
                    Text(
                        text = "✅ 已检测到火狐浏览器，可以直接使用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF15803D),
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                if (!TermuxBridge.launchPackage(context, TermuxBridge.PACKAGE_FIREFOX)) {
                                    onShowMessage("未能打开火狐，请手动从桌面启动")
                                }
                            },
                            shape = RoundedCornerShape(10.dp)
                        ) { Text("打开火狐") }
                        OutlinedButton(
                            onClick = { TermuxBridge.openUrl(context, TermuxBridge.URL_FIREFOX_ANDROID) },
                            shape = RoundedCornerShape(10.dp)
                        ) { Text("重新下载") }
                    }
                } else {
                    Text(
                        text = "推荐「火狐浏览器（Firefox）国际版」，官网可直接下载 APK，国内可访问。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                TermuxBridge.openUrl(context, TermuxBridge.URL_FIREFOX_ANDROID)
                            },
                            shape = RoundedCornerShape(10.dp)
                        ) { Text("下载火狐（官网）") }
                        OutlinedButton(
                            onClick = {
                                TermuxBridge.openUrl(context, TermuxBridge.URL_FIREFOX_ARCHIVE)
                            },
                            shape = RoundedCornerShape(10.dp)
                        ) { Text("备用下载地址") }
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
                Text(
                    text = "在火狐里打开下面的扩展页 → 点「添加到 Firefox」→ 允许安装（狐猴浏览器则在它自带的扩展商店里搜 Cookie-Editor）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = { TermuxBridge.openUrl(context, TermuxBridge.URL_COOKIE_EDITOR) },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Filled.OpenInBrowser, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("打开 Cookie-Editor 扩展页")
                }
            }

            // 步骤 3
            GuideStep(number = 3, title = "登录 Google AI Studio") {
                Text(
                    text = "需要能访问 Google 的网络环境。有多个账号时，点右上角头像「添加其他账号」把账号全部登好（导入时会自动识别）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(
                    onClick = { TermuxBridge.openUrl(context, TermuxBridge.URL_AISTUDIO) },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Filled.OpenInBrowser, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("打开 AI Studio")
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
            Text(
                text = "${acc.email ?: "未探测到邮箱"} · 额度等级 ${acc.tier}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
