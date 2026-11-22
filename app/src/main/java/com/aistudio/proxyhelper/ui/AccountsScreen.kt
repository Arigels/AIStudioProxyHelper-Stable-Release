package com.aistudio.proxyhelper.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.viewinterop.AndroidView
import com.aistudio.proxyhelper.data.AccountSummary
import com.aistudio.proxyhelper.termux.TermuxBridge

@Composable
fun AccountsScreen(
    uiState: MainUiState,
    onImportCookies: (rawCookies: String, probeMulti: Boolean, namePrefix: String?) -> Unit,
    onShowMessage: (String) -> Unit
) {
    val context = LocalContext.current
    var modeIndex by remember { mutableIntStateOf(0) } // 0 = Paste JSON/Header, 1 = Built-in WebView Login
    var cookieText by remember { mutableStateOf("") }
    var namePrefix by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Mode Switcher
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = modeIndex == 0,
                onClick = { modeIndex = 0 },
                label = { Text("粘贴 Cookie 导入") },
                leadingIcon = {
                    Icon(Icons.Filled.VpnKey, contentDescription = null, modifier = Modifier.size(18.dp))
                },
                modifier = Modifier.weight(1f)
            )
            FilterChip(
                selected = modeIndex == 1,
                onClick = { modeIndex = 1 },
                label = { Text("内置网页登录提取") },
                leadingIcon = {
                    Icon(Icons.Filled.Public, contentDescription = null, modifier = Modifier.size(18.dp))
                },
                modifier = Modifier.weight(1f)
            )
        }

        if (modeIndex == 0) {
            PasteImportContent(
                uiState = uiState,
                cookieText = cookieText,
                onCookieTextChange = { cookieText = it },
                namePrefix = namePrefix,
                onNamePrefixChange = { namePrefix = it },
                onPasteClipboard = {
                    val clip = TermuxBridge.readFromClipboard(context)
                    if (clip.isBlank()) {
                        onShowMessage("剪贴板为空，请先用 Cookie-Editor 导出 JSON")
                    } else {
                        cookieText = clip
                        onShowMessage("已从剪贴板粘贴 (${clip.length} 字符)")
                    }
                },
                onImport = { probeMulti ->
                    onImportCookies(cookieText, probeMulti, namePrefix.ifBlank { null })
                },
                onOpenAIStudioExternal = {
                    TermuxBridge.openUrl(context, TermuxBridge.URL_AISTUDIO)
                }
            )
        } else {
            BuiltInCookieExtractor(
                importing = uiState.importingCookie,
                onExtractAndImport = { extracted ->
                    cookieText = extracted
                    modeIndex = 0
                    onImportCookies(extracted, true, namePrefix.ifBlank { null })
                },
                onExtractToBox = { extracted ->
                    cookieText = extracted
                    modeIndex = 0
                    onShowMessage("已提取 Cookie 到输入框，请点击导入")
                },
                onShowMessage = onShowMessage,
                onOpenExternalBrowser = {
                    TermuxBridge.openUrl(context, TermuxBridge.URL_AISTUDIO)
                }
            )
        }
    }
}

@Composable
private fun PasteImportContent(
    uiState: MainUiState,
    cookieText: String,
    onCookieTextChange: (String) -> Unit,
    namePrefix: String,
    onNamePrefixChange: (String) -> Unit,
    onPasteClipboard: () -> Unit,
    onImport: (probeMulti: Boolean) -> Unit,
    onOpenAIStudioExternal: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Guide Card
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            ),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "如何获取 Cookie（推荐手机 Firefox / Edge）",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(
                        onClick = onOpenAIStudioExternal,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Filled.OpenInBrowser,
                            contentDescription = "外部浏览器打开 AI Studio",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Text(
                    text = "1. 在浏览器安装扩展 Cookie-Editor 并打开 aistudio.google.com\n" +
                        "2. 登录谷歌账号（多账号可点右上角头像“添加其他账号”全部登好）\n" +
                        "3. 点击 Cookie-Editor → Export → Export as JSON，回到下方一键粘贴导入",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Import Form
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
                        text = "凭据导入到本机后台",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    FilledTonalButton(onClick = onPasteClipboard) {
                        Icon(Icons.Filled.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("粘贴剪贴板")
                    }
                }

                OutlinedTextField(
                    value = namePrefix,
                    onValueChange = onNamePrefixChange,
                    label = { Text("账号备注前缀（可选）") },
                    placeholder = { Text("例如：主号池") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = cookieText,
                    onValueChange = onCookieTextChange,
                    label = { Text("Cookie 内容（支持 JSON 数组 / Header / Netscape）") },
                    placeholder = { Text("粘贴 Cookie-Editor 导出的 JSON 或 Header 字符串...") },
                    minLines = 5,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth()
                )

                if (!uiState.service.online) {
                    Text(
                        text = "注意：当前检测到本机反代服务未运行，请先在「搭建」页启动服务后再导入。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Button(
                    onClick = { onImport(true) },
                    enabled = !uiState.importingCookie && cookieText.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (uiState.importingCookie) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("正在探活并识别子账号...")
                    } else {
                        Text("自动探活并导入全部子账号（推荐）", fontWeight = FontWeight.Bold)
                    }
                }

                OutlinedButton(
                    onClick = { onImport(false) },
                    enabled = !uiState.importingCookie && cookieText.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("单账号 / Bundle 直接导入（跳过自动探活）")
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
                                imageVector = if (res.success) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline,
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

        // Imported Accounts List
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "已导入账号 (${uiState.service.accounts.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                if (uiState.service.accounts.isEmpty()) {
                    Text(
                        text = if (uiState.service.online) {
                            "暂未导入任何账号，导入后即可在酒馆/客户端直接刷出模型。"
                        } else {
                            "服务未启动，启动后将自动同步展示账号池。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    uiState.service.accounts.forEachIndexed { idx, acc ->
                        AccountRowItem(acc)
                        if (idx < uiState.service.accounts.lastIndex) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
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
                text = "${acc.email ?: "未探测邮箱"} · u/${acc.authUser} · 分组 ${acc.group}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.secondaryContainer
        ) {
            Text(
                text = acc.tier,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun BuiltInCookieExtractor(
    importing: Boolean,
    onExtractAndImport: (String) -> Unit,
    onExtractToBox: (String) -> Unit,
    onShowMessage: (String) -> Unit,
    onOpenExternalBrowser: () -> Unit
) {
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "登录 Google AI Studio 后点击下方「提取并导入」。若 Google 提示浏览器不安全，请改用手机 Firefox / Edge 装 Cookie-Editor 导出后粘贴。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            val merged = collectGoogleCookies()
                            if (merged.isBlank()) {
                                onShowMessage("未读取到有效 Google 登录 Cookie，请先完成登录再重试")
                            } else {
                                onExtractAndImport(merged)
                            }
                        },
                        enabled = !importing,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("提取并自动导入", maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = {
                            val merged = collectGoogleCookies()
                            if (merged.isBlank()) {
                                onShowMessage("当前尚未获取到 Cookie，请先登录")
                            } else {
                                onExtractToBox(merged)
                            }
                        }
                    ) {
                        Text("填到输入框")
                    }
                    IconButton(onClick = { webViewRef?.reload() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新网页")
                    }
                    IconButton(onClick = onOpenExternalBrowser) {
                        Icon(Icons.Filled.OpenInBrowser, contentDescription = "外部浏览器打开")
                    }
                }
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = RoundedCornerShape(12.dp)
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        val cm = CookieManager.getInstance()
                        cm.setAcceptCookie(true)
                        cm.setAcceptThirdPartyCookies(this, true)

                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                        // Remove "; wv" token so standard login pages render cleanly
                        val defaultUa = settings.userAgentString
                        settings.userAgentString = defaultUa.replace("; wv", "")

                        webChromeClient = WebChromeClient()
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean = false
                        }
                        loadUrl(TermuxBridge.URL_AISTUDIO)
                        webViewRef = this
                    }
                }
            )
        }
    }
}

private fun collectGoogleCookies(): String {
    val cm = CookieManager.getInstance()
    cm.flush()
    val domains = listOf(
        "https://aistudio.google.com/",
        "https://accounts.google.com/",
        "https://www.google.com/",
        "https://google.com/"
    )
    val merged = linkedMapOf<String, String>()
    for (url in domains) {
        val raw = cm.getCookie(url) ?: continue
        raw.split(";").forEach { pair ->
            val idx = pair.indexOf('=')
            if (idx > 0) {
                val k = pair.substring(0, idx).trim()
                val v = pair.substring(idx + 1).trim()
                if (k.isNotEmpty() && v.isNotEmpty()) {
                    merged[k] = v
                }
            }
        }
    }
    if (merged.isEmpty()) return ""
    return merged.entries.joinToString("; ") { "${it.key}=${it.value}" }
}
