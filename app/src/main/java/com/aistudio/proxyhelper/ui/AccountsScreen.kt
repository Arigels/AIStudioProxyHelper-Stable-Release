package com.aistudio.proxyhelper.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aistudio.proxyhelper.data.AccountSummary
import com.aistudio.proxyhelper.data.EnvironmentStatus
import com.aistudio.proxyhelper.data.ModelDailyQuotaDetail
import com.aistudio.proxyhelper.data.QuotaPolicyResolver
import com.aistudio.proxyhelper.data.QuotaPolicySnapshot
import com.aistudio.proxyhelper.termux.TermuxBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val FALLBACK_DISPLAY_MODELS = listOf(
    "gemini-4-hydrogen",
    "gemini-4-argon",
    "gemini-4-neon",
    "gemini-3.8-flash",
    "gemini-3.7-flash",
    "gemini-3.5-flash-lite",
    "gemini-3.1-pro-preview",
    "gemini-3.6-flash",
    "gemini-3.5-flash",
    "gemini-3.1-flash-image",
    "gemini-3-pro-image",
    "gemma-4-31b-it",
    "veo-3.1-generate-preview",
    "gemini-omni-1.1-flash"
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AccountsScreen(
    uiState: MainUiState,
    onImportCookies: (rawCookies: String, probeMulti: Boolean, namePrefix: String?) -> Unit,
    onRefreshQuota: () -> Unit,
    onOpenConsole: () -> Unit,
    onActivateAccount: (String) -> Unit,
    onToggleAccount: (String, Boolean?) -> Unit,
    onDetectAccount: (String) -> Unit,
    onForceNextAccount: () -> Unit,
    onDeleteAccount: (String) -> Unit,
    onShowMessage: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cookieText by rememberSaveable { mutableStateOf("") }
    var showPasteLimitTips by rememberSaveable { mutableStateOf(false) }
    var pendingDeleteAccount by remember { mutableStateOf<AccountSummary?>(null) }

    val policy = uiState.service.quotaPolicy ?: remember { QuotaPolicyResolver.builtInDefaultPolicy() }
    val hasAccounts = uiState.service.accounts.isNotEmpty()
    var guideExpanded by rememberSaveable(hasAccounts) { mutableStateOf(!hasAccounts) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        val maxBytes = 2 * 1024 * 1024
                        val bytes = stream.readNBytes(maxBytes + 1)
                        if (bytes.size > maxBytes) {
                            error("文件超过 2MB，请确认选择的是 Cookie JSON 文件")
                        }
                        String(bytes, Charsets.UTF_8)
                    } ?: error("无法打开所选文件")
                }
            }
            result.onSuccess { text ->
                if (text.isBlank()) {
                    onShowMessage("选中的文件是空的")
                } else {
                    cookieText = text
                    onShowMessage("已从文件读取（${text.length} 字符），点下方按钮即可导入")
                }
            }.onFailure { e ->
                onShowMessage("读取文件失败：${e.message ?: "未知错误"}")
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // ── 1. 实时额度检测与管理台同步总览 ──
        QuotaDetectionOverviewCard(
            uiState = uiState,
            policy = policy,
            onRefreshQuota = onRefreshQuota,
            onForceNextAccount = onForceNextAccount,
            onOpenConsole = onOpenConsole
        )

        // ── 2. 已导入账号：可用模型与今日实时配额明细 ──
        AccountsQuotaSectionCard(
            uiState = uiState,
            policy = policy,
            onActivateAccount = onActivateAccount,
            onToggleAccount = onToggleAccount,
            onDetectAccount = onDetectAccount,
            onRequestDeleteAccount = { pendingDeleteAccount = it }
        )

        // ── 3. 每日额度对照表（与 AI Studio Proxy 管理平台同步）──
        DailyQuotaTableCard(
            uiState = uiState,
            policy = policy
        )

        // ── 4. 获取 Cookie 教程（先教怎么拿到 Cookie，再让用户粘贴导入）──
        CookieGuideCard(
            env = uiState.environment,
            expanded = guideExpanded,
            onToggleExpand = { guideExpanded = !guideExpanded },
            onShowMessage = onShowMessage
        )

        // ── 5. 粘贴 Cookie 导入 ──
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "粘贴 Cookie 导入",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        onClick = {
                            val clip = TermuxBridge.readFromClipboard(context)
                            if (clip.isBlank()) {
                                onShowMessage("剪贴板是空的，请先用 Cookie-Editor 导出为 JSON")
                            } else {
                                cookieText = clip
                                onShowMessage("已从系统剪贴板读取（${clip.length} 字符）")
                            }
                        },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(
                            Icons.Filled.ContentPaste,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("粘贴剪贴板", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    OutlinedButton(
                        onClick = {
                            filePickerLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                        },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(
                            Icons.Filled.FolderOpen,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("从文件导入", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }

                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showPasteLimitTips = !showPasteLimitTips },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "手机输入法粘贴有字数上限/被截断？点此看解决办法",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                imageVector = if (showPasteLimitTips) {
                                    Icons.Filled.ExpandLess
                                } else {
                                    Icons.Filled.ExpandMore
                                },
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        AnimatedVisibility(visible = showPasteLimitTips) {
                            Column(
                                modifier = Modifier.padding(top = 6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "1. 优先点上面的「粘贴剪贴板」按钮：不要点进输入框用输入法键盘工具栏粘贴（很多手机输入法剪贴板条限 1000~5000 字），该按钮直接调用系统剪贴板，不受输入法字数限制。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "2. 改用「从文件导入」：如果系统剪贴板本身也截断（或从电脑导出），可把 JSON 存成 .json / .txt 文件后点上面「从文件导入」直接读取，彻底无字数限制。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "3. 若在网页后台粘贴：长按输入框空白处选系统原生「粘贴」，不要点输入法键盘上的剪贴板历史。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "4. 减少单次 Cookie 体积：用浏览器无痕窗口或只登录需要用的 Google 账号再 Export as JSON；也可分批登录不同账号多次导入（会自动累加，不会覆盖）。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = cookieText,
                    onValueChange = { cookieText = it },
                    label = { Text("Cookie 内容（Cookie-Editor 导出的 JSON）") },
                    placeholder = { Text("在这里粘贴...") },
                    minLines = 4,
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

        Spacer(modifier = Modifier.height(8.dp))
    }

    pendingDeleteAccount?.let { acc ->
        val label = acc.email ?: acc.name
        AlertDialog(
            onDismissRequest = { pendingDeleteAccount = null },
            title = { Text("确认删除账号") },
            text = {
                Text("确定要删除账号「$label」(u/${acc.authUser}) 吗？此操作会从本地反代凭据库中移除，不可撤销。")
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingDeleteAccount = null
                        onDeleteAccount(acc.id)
                    }
                ) {
                    Text("确认删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteAccount = null }) {
                    Text("取消")
                }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuotaDetectionOverviewCard(
    uiState: MainUiState,
    policy: QuotaPolicySnapshot,
    onRefreshQuota: () -> Unit,
    onForceNextAccount: () -> Unit,
    onOpenConsole: () -> Unit
) {
    val service = uiState.service
    val accounts = service.accounts
    val activeAcc = accounts.firstOrNull { it.id == service.activeAccountId }
    val todayTotalReqs = accounts.sumOf { it.todayTotalRequests }
    val todayTotalTokens = accounts.sumOf { it.todayTotalTokens }
    val totalReqs = accounts.sumOf { it.totalRequests }
    val totalSuccess = accounts.sumOf { it.totalSuccess }
    val total429 = accounts.sumOf { it.totalRateLimited }
    val enabledCount = accounts.count { it.enabled }
    val readyCount = accounts.count { it.enabled && it.isAvailable && !it.sessionExpired }

    val syncTimeText = remember(service.lastCheckedAt) {
        if (service.lastCheckedAt <= 0L) {
            "未同步"
        } else {
            SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(service.lastCheckedAt))
        }
    }

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
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "实时额度检测",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Surface(
                            color = if (service.online && policy.syncedFromServer) {
                                Color(0xFFDCFCE7)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = when {
                                    service.online && policy.syncedFromServer -> "已同步管理台"
                                    service.online -> "服务在线"
                                    else -> "服务未运行"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (service.online && policy.syncedFromServer) {
                                    Color(0xFF15803D)
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Text(
                        text = "上次同步：$syncTimeText · 不用跳转管理台即可看调用与配额",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                FilledTonalButton(
                    onClick = onRefreshQuota,
                    enabled = !uiState.quotaChecking,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    if (uiState.quotaChecking) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = "立即检测",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("立即检测", maxLines = 1)
                }
            }

            service.lastError?.let { err ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "⚠️ $err",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }

            if (service.online) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            Icons.Filled.Schedule,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "每日配额刷新 (美西 00:00 PST)：还剩 ${QuotaPolicyResolver.pacificResetCountdown()}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    QuotaSummaryTile(
                        label = "今日调用",
                        value = "$todayTotalReqs 次",
                        sub = "累计 $totalReqs · 成功 $totalSuccess",
                        modifier = Modifier.weight(1f)
                    )
                    QuotaSummaryTile(
                        label = "今日 Token",
                        value = QuotaPolicyResolver.formatTokenCount(todayTotalTokens),
                        sub = "429 限流：$total429 次",
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    QuotaSummaryTile(
                        label = "可用账号",
                        value = "$readyCount / ${accounts.size}",
                        sub = "已启用 $enabledCount 个 · 模型 ${service.models.size}",
                        modifier = Modifier.weight(1f)
                    )
                    QuotaSummaryTile(
                        label = "当前激活",
                        value = activeAcc?.let { "u/${it.authUser} ${QuotaPolicyResolver.normalizeTier(it.tier)}" } ?: "未激活",
                        sub = activeAcc?.email ?: activeAcc?.name ?: "等待调度或手动激活",
                        modifier = Modifier.weight(1f)
                    )
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onForceNextAccount,
                        enabled = !uiState.quotaChecking && accounts.size > 1,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Filled.SwapHoriz, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("切换下一账号", maxLines = 1)
                    }
                    TextButton(
                        onClick = onOpenConsole,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Filled.OpenInBrowser, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("管理台网页", maxLines = 1)
                    }
                }
            } else {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "反代服务未启动，暂无法读取实时调用与账号额度。请先到「反代」页启动服务后点右上角「立即检测」。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun QuotaSummaryTile(
    label: String,
    value: String,
    sub: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = sub,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccountsQuotaSectionCard(
    uiState: MainUiState,
    policy: QuotaPolicySnapshot,
    onActivateAccount: (String) -> Unit,
    onToggleAccount: (String, Boolean?) -> Unit,
    onDetectAccount: (String) -> Unit,
    onRequestDeleteAccount: (AccountSummary) -> Unit
) {
    val accounts = uiState.service.accounts
    var statusFilter by rememberSaveable { mutableStateOf("all") }
    val expandedStateMap = remember { mutableStateMapOf<String, Boolean>() }

    val filteredAccounts = remember(accounts, statusFilter) {
        when (statusFilter) {
            "ready" -> accounts.filter { it.enabled && it.isAvailable && !it.sessionExpired }
            "limited" -> accounts.filter { !it.sessionExpired && (!it.isAvailable || it.cooldownRemainingSec > 0 || it.modelCooldowns.isNotEmpty()) }
            "expired" -> accounts.filter { it.sessionExpired }
            else -> accounts
        }
    }

    val allExpanded = filteredAccounts.isNotEmpty() && filteredAccounts.all { acc ->
        expandedStateMap[acc.id] ?: (acc.id == uiState.service.activeAccountId)
    }

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
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "账号可用模型与今日配额（${accounts.size}）",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                if (filteredAccounts.isNotEmpty()) {
                    TextButton(
                        onClick = {
                            val target = !allExpanded
                            filteredAccounts.forEach { expandedStateMap[it.id] = target }
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(if (allExpanded) "全部收起" else "展开全部")
                    }
                }
            }

            Text(
                text = "展开每个账号可实时查看今日已调用次数、Token 消耗、可用模型及剩余每日配额；状态与管理平台实时同步。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (accounts.size > 1) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        "all" to "全部 (${accounts.size})",
                        "ready" to "就绪 (${accounts.count { it.enabled && it.isAvailable && !it.sessionExpired }})",
                        "limited" to "限流/冷却",
                        "expired" to "登录失效"
                    ).forEach { (key, label) ->
                        FilterChip(
                            selected = statusFilter == key,
                            onClick = { statusFilter = key },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
            }

            if (filteredAccounts.isEmpty()) {
                Text(
                    text = if (accounts.isEmpty()) {
                        "还没有导入账号。导入后这里会实时显示每个账号的可用模型与每日配额。"
                    } else {
                        "当前筛选条件下没有匹配的账号。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                filteredAccounts.forEachIndexed { idx, acc ->
                    val isActive = acc.id == uiState.service.activeAccountId
                    val expanded = expandedStateMap[acc.id] ?: isActive
                    AccountQuotaItemCard(
                        acc = acc,
                        isActive = isActive,
                        isBusy = uiState.busyAccountId == acc.id,
                        expanded = expanded,
                        allModels = uiState.service.models,
                        policy = policy,
                        onToggleExpand = { expandedStateMap[acc.id] = !expanded },
                        onActivate = { onActivateAccount(acc.id) },
                        onToggleEnabled = { onToggleAccount(acc.id, !acc.enabled) },
                        onDetect = { onDetectAccount(acc.id) },
                        onDelete = { onRequestDeleteAccount(acc) }
                    )
                    if (idx < filteredAccounts.lastIndex) {
                        Spacer(modifier = Modifier.height(2.dp))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccountQuotaItemCard(
    acc: AccountSummary,
    isActive: Boolean,
    isBusy: Boolean,
    expanded: Boolean,
    allModels: List<String>,
    policy: QuotaPolicySnapshot,
    onToggleExpand: () -> Unit,
    onActivate: () -> Unit,
    onToggleEnabled: () -> Unit,
    onDetect: () -> Unit,
    onDelete: () -> Unit
) {
    val normTier = QuotaPolicyResolver.normalizeTier(acc.tier)
    val (tierLabel, tierColor) = tierBadge(acc.tier)

    val baseModels = remember(allModels) {
        (if (allModels.isNotEmpty()) allModels else FALLBACK_DISPLAY_MODELS)
            .map { it.removePrefix("models/").trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    val allowedModels = remember(baseModels, acc.todayModels, acc.tier, policy) {
        val allowedFromList = baseModels.filter {
            QuotaPolicyResolver.isModelAllowedForTier(policy, it, acc.tier)
        }
        val extraUsed = acc.todayModels.keys.filter { used ->
            allowedFromList.none { it.equals(used, ignoreCase = true) }
        }
        (allowedFromList + extraUsed).sortedWith(
            compareByDescending<String> { m ->
                val detail = acc.todayModels[m.lowercase()]
                (detail?.requests ?: 0) > 0 || (detail?.cooldownRemainingSec ?: 0) > 0
            }.thenByDescending { m ->
                acc.todayModels[m.lowercase()]?.requests ?: 0
            }.thenBy { it.lowercase() }
        )
    }

    val lockedModels = remember(baseModels, acc.tier, policy) {
        baseModels.filter { !QuotaPolicyResolver.isModelAllowedForTier(policy, it, acc.tier) }
    }

    val borderBg = when {
        !acc.enabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        acc.sessionExpired -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
        isActive -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    }

    Surface(
        color = borderBg,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // ── 头部：账号信息 + 开关 ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.AccountCircle,
                    contentDescription = null,
                    tint = if (acc.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(30.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = "u/${acc.authUser}",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                            )
                        }
                        Surface(
                            color = tierColor.copy(alpha = 0.14f),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = tierLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = tierColor,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                            )
                        }
                        val (statusText, statusColor) = when {
                            !acc.enabled -> "已停用" to Color(0xFF6B7280)
                            acc.sessionExpired -> "登录失效" to Color(0xFFDC2626)
                            isActive -> "当前激活" to Color(0xFF15803D)
                            acc.cooldownRemainingSec > 0 -> "冷却 ${acc.cooldownRemainingSec}s" to Color(0xFFD97706)
                            !acc.isAvailable -> "限流中" to Color(0xFFD97706)
                            else -> "就绪" to Color(0xFF1D4ED8)
                        }
                        Surface(
                            color = statusColor.copy(alpha = 0.14f),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = statusText,
                                style = MaterialTheme.typography.labelSmall,
                                color = statusColor,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = acc.email ?: acc.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (acc.email != null && acc.name != acc.email) {
                        Text(
                            text = "${acc.name} · 分组: ${acc.group}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (isBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Switch(
                        checked = acc.enabled,
                        onCheckedChange = { onToggleEnabled() }
                    )
                }
            }

            // ── 实时调用汇总行 ──
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "今日 ${acc.todayTotalRequests} 次 · Token ${QuotaPolicyResolver.formatTokenCount(acc.todayTotalTokens)}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "累计 ${acc.totalRequests} (429: ${acc.totalRateLimited})",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (acc.totalRateLimited > 0) Color(0xFFD97706) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ── 操作栏 ──
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilledTonalButton(
                    onClick = onToggleExpand,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(
                        imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (expanded) "收起模型配额" else "可用模型与配额 (${allowedModels.size})",
                        style = MaterialTheme.typography.labelMedium
                    )
                }

                if (acc.enabled && (!isActive || acc.sessionExpired)) {
                    OutlinedButton(
                        onClick = onActivate,
                        enabled = !isBusy,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = if (acc.sessionExpired) "重新激活" else "激活",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }

                OutlinedButton(
                    onClick = onDetect,
                    enabled = !isBusy,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("探测等级/邮箱", style = MaterialTheme.typography.labelMedium)
                }

                TextButton(
                    onClick = onDelete,
                    enabled = !isBusy,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Icon(
                        Icons.Filled.DeleteOutline,
                        contentDescription = "删除账号",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            // ── 展开：该账号可用模型与每日配额明细 ──
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                    Text(
                        text = "可用模型与今日配额（$normTier 等级 · 共 ${allowedModels.size} 个可用模型）",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    allowedModels.forEach { modelId ->
                        val cleanKey = modelId.removePrefix("models/").lowercase()
                        val detail = acc.todayModels[cleanKey]
                        val (resolvedLimit, resolvedTokenLimit) = QuotaPolicyResolver.resolveQuotaForAccountModel(
                            policy = policy,
                            tier = acc.tier,
                            modelId = cleanKey
                        )
                        val dailyQuota = detail?.configuredQuota ?: resolvedLimit
                        val tokenQuota = detail?.configuredTokenQuota ?: resolvedTokenLimit
                        val cooldownSec = detail?.cooldownRemainingSec ?: acc.modelCooldowns[cleanKey] ?: 0

                        ModelQuotaRowItem(
                            modelId = modelId,
                            detail = detail,
                            dailyQuota = dailyQuota,
                            tokenQuota = tokenQuota,
                            cooldownSec = cooldownSec
                        )
                    }

                    if (lockedModels.isNotEmpty()) {
                        Surface(
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "当前 $normTier 暂不可用（需 Pro/Ultra）：${lockedModels.joinToString("、")}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelQuotaRowItem(
    modelId: String,
    detail: ModelDailyQuotaDetail?,
    dailyQuota: Int,
    tokenQuota: Long?,
    cooldownSec: Int
) {
    val usedReqs = detail?.requests ?: 0
    val usedTokens = detail?.totalTokens ?: 0L
    val reqPct = if (dailyQuota > 0) (usedReqs * 100.0 / dailyQuota) else 0.0
    val reqFraction = (reqPct / 100.0).toFloat().coerceIn(0f, 1f)
    val tokPct = if (tokenQuota != null && tokenQuota > 0L) (usedTokens * 100.0 / tokenQuota) else 0.0
    val tokFraction = (tokPct / 100.0).toFloat().coerceIn(0f, 1f)

    val barColor = when {
        cooldownSec > 0 || reqPct >= 100.0 || tokPct >= 100.0 -> Color(0xFFDC2626)
        reqPct >= 80.0 || tokPct >= 80.0 -> Color(0xFFD97706)
        else -> MaterialTheme.colorScheme.primary
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = modelId,
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(6.dp))
                when {
                    cooldownSec > 0 -> {
                        Surface(
                            color = Color(0xFFFEE2E2),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = "429 冷却 (${cooldownSec}s)",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFB91C1C),
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    detail?.dripMode == true -> {
                        Surface(
                            color = Color(0xFFDBEAFE),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = "滴水限流",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF1D4ED8),
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    (detail?.rateLimited ?: 0) > 0 -> {
                        Surface(
                            color = Color(0xFFFEF3C7),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = "429: ${detail?.rateLimited}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFB45309),
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    else -> {
                        Surface(
                            color = Color(0xFFDCFCE7),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = "就绪",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF15803D),
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "今日调用：$usedReqs / $dailyQuota 次",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "${reqPct.toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color = barColor
                )
            }
            LinearProgressIndicator(
                progress = { reqFraction },
                color = barColor,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(4.dp))
            )

            if (tokenQuota != null || usedTokens > 0L) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (tokenQuota != null) {
                            "Token：${QuotaPolicyResolver.formatTokenCount(usedTokens)} / ${QuotaPolicyResolver.formatTokenCount(tokenQuota)}"
                        } else {
                            "Token：${QuotaPolicyResolver.formatTokenCount(usedTokens)}（不限）"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (tokenQuota != null) {
                        Text(
                            text = "${tokPct.toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (tokenQuota != null) {
                    LinearProgressIndicator(
                        progress = { tokFraction },
                        color = MaterialTheme.colorScheme.tertiary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(4.dp))
                    )
                }
            }

            if (detail != null && (detail.reasoningTokens > 0L || detail.cachedTokens > 0L)) {
                Text(
                    text = buildString {
                        if (detail.reasoningTokens > 0L) {
                            append("思考 Token: ${QuotaPolicyResolver.formatTokenCount(detail.reasoningTokens)}")
                        }
                        if (detail.cachedTokens > 0L) {
                            if (isNotEmpty()) append(" · ")
                            append("缓存: ${QuotaPolicyResolver.formatTokenCount(detail.cachedTokens)}")
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DailyQuotaTableCard(
    uiState: MainUiState,
    policy: QuotaPolicySnapshot
) {
    val accounts = uiState.service.accounts
    val tierNames = remember(policy) {
        policy.tierOrder.ifEmpty { policy.tiers.keys.toList() }.ifEmpty { QuotaPolicyResolver.STANDARD_TIERS }
    }
    val preferredTier = remember(accounts, uiState.service.activeAccountId, tierNames) {
        val activeAcc = accounts.firstOrNull { it.id == uiState.service.activeAccountId }
        val candidate = activeAcc?.tier ?: accounts.firstOrNull()?.tier
        val norm = candidate?.let { QuotaPolicyResolver.normalizeTier(it) }
        tierNames.firstOrNull { it.equals(norm, ignoreCase = true) } ?: tierNames.first()
    }
    var selectedTier by rememberSaveable(preferredTier) { mutableStateOf(preferredTier) }
    val activeTierCfg = policy.tiers[selectedTier] ?: policy.tiers.values.firstOrNull()

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
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "每日额度对照表",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Surface(
                    color = if (policy.syncedFromServer) Color(0xFFDCFCE7) else MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = if (policy.syncedFromServer) "同步自 config.yaml" else "内置默认规则",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (policy.syncedFromServer) Color(0xFF15803D) else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Text(
                text = "与 AI Studio Proxy 管理平台「订阅额度」配置一致（美西 00:00 PST 重置）。次数或 Token 任一达到上限时，系统自动优先切换其他账号。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                tierNames.forEach { tierName ->
                    val count = accounts.count { QuotaPolicyResolver.normalizeTier(it.tier).equals(tierName, ignoreCase = true) }
                    val label = if (count > 0) "$tierName ($count)" else tierName
                    FilterChip(
                        selected = selectedTier == tierName,
                        onClick = { selectedTier = tierName },
                        label = { Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold) }
                    )
                }
            }

            if (activeTierCfg != null) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        // 表头
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "模型分类 / 匹配规则",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1.7f)
                            )
                            Text(
                                text = "每日次数",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(0.8f)
                            )
                            Text(
                                text = "每日 Token",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(0.8f)
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                        activeTierCfg.rules.forEach { rule ->
                            val keywords = (rule.match.contains + rule.match.prefixes + rule.match.exact)
                                .distinct()
                                .joinToString(", ")
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1.7f)) {
                                    Text(
                                        text = QuotaPolicyResolver.ruleDisplayLabel(rule.ruleKey),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    if (keywords.isNotBlank()) {
                                        Text(
                                            text = "含: $keywords",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Text(
                                    text = "${rule.dailyLimit} 次",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(0.8f)
                                )
                                Text(
                                    text = rule.dailyTokenLimit?.let { QuotaPolicyResolver.formatTokenCount(it) } ?: "不限",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (rule.dailyTokenLimit != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(0.8f)
                                )
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
                        }

                        // 兜底默认额度
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1.7f)) {
                                Text(
                                    text = "兜底默认 (其他模型)",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "未命中上面规则时使用",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = "${activeTierCfg.defaultLimit} 次",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(0.8f)
                            )
                            Text(
                                text = activeTierCfg.defaultTokenLimit?.let { QuotaPolicyResolver.formatTokenCount(it) } ?: "不限",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(0.8f)
                            )
                        }
                    }
                }
            }

            if (policy.overrides.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "单模型精确覆盖（最高优先级）",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                        policy.overrides.values.forEach { ov ->
                            Text(
                                text = "• ${ov.modelId}：${ov.dailyLimit?.let { "${it} 次/日" } ?: "继承层级"}" +
                                    (ov.dailyTokenLimit?.let { " · Token ${QuotaPolicyResolver.formatTokenCount(it)}" } ?: ""),
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }

            if (policy.paidRules.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                Icons.Filled.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = "模型订阅准入规则",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        policy.paidRules.forEach { rule ->
                            val keywords = (rule.match.contains + rule.match.prefixes + rule.match.exact)
                                .distinct()
                                .joinToString(", ")
                            Text(
                                text = "• 需 ${rule.requiredTiers.joinToString("/")}：$keywords",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = "• 其他普通文本模型（如 flash / hydrogen / gemma）：Free 及以上均可使用",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
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
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onShowMessage: (String) -> Unit
) {
    val context = LocalContext.current
    val hasEdge = env.edgePackage != null
    val firefoxPkg = env.firefoxPackage ?: TermuxBridge.PACKAGE_FIREFOX.takeIf { env.firefoxInstalled }
    val hasFirefox = firefoxPkg != null
    var preferFirefox by rememberSaveable { mutableStateOf(false) }
    val targetBrowser = resolveTargetBrowser(env, preferFirefox)
    // 按实际检测到的浏览器给具体点击路径，未检测到时给通用兜底
    val browserMenuPath = when (targetBrowser?.label) {
        "Edge" -> "点浏览器底部工具栏右侧的「≡」→ 菜单里选「扩展」→ 在扩展列表里点 Cookie-Editor"
        "火狐" -> "点浏览器右上角的「⋮」（三个点）→ 选「扩展」→ 在扩展列表里点 Cookie-Editor"
        else -> "在浏览器里打开「扩展」菜单再点 Cookie-Editor（Edge：底部「≡」→「扩展」；火狐：右上角「⋮」→「扩展」）"
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        ),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleExpand() },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "获取 Cookie 教程（约 3 分钟）",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                    Text(
                        text = "教程步骤点乱了（比如误点「打开 App」跳到了粘贴那步）？点这里从头再来",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable {
                            context.getSharedPreferences("aistudio_tutorial", 0)
                                .edit()
                                .putInt("cookie_max_stage", 0)
                                .putBoolean("cookie_done", false)
                                .apply()
                            onShowMessage("已重置教程：悬浮窗会从第一步重新带你走")
                        }
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
                    GuideStep(number = 4, title = "打开 Cookie-Editor 并导出 JSON") {
                        Text(
                            text = "1. 保持浏览器停留在已登录的 aistudio.google.com 页面。\n" +
                                "2. 打开 Cookie-Editor 扩展：$browserMenuPath。\n" +
                                "3. 扩展面板出现后，点右下角的 Export → 选「Export as JSON」（不要选 Header String 或 Netscape）。导出成功后完整 JSON 会自动复制到剪贴板。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "打不开或面板空白：先确认当前页确实是 aistudio.google.com（不是别的标签页），下拉刷新后再点一次扩展图标；仍不行就重启浏览器再试。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // 步骤 5
                    GuideStep(number = 5, title = "回到本 App 导入（支持剪贴板 / 文件）") {
                        Text(
                            text = "首选：点下面「粘贴剪贴板」按钮。它直接读系统剪贴板，绕过输入法剪贴板条的字数上限（多数手机输入法只允许粘 1000~5000 字），也不用点进输入框。\n" +
                                "其次：点「从文件导入 (.json/.txt)」，把 Cookie-Editor 导出的 JSON 存成文件再选，完全没有字数限制。\n" +
                                "最后：如果一定要手动粘，长按输入框空白处用系统原生「粘贴」，不要用输入法键盘上的剪贴板历史。\n" +
                                "说明：输入法的剪贴板上限 App 无法读取或检测，所以这里直接用上面两个办法绕开。导入后会自动识别所有子账号。",
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
                                "Cookie 已经保存在反代服务里，卸载浏览器不影响反代使用。",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF1B5E20),
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
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

/** 把反代服务返回的等级字段归一化为 Free / Plus / Pro / Ultra 标签。 */
private fun tierBadge(raw: String): Pair<String, Color> {
    val t = raw.trim().lowercase()
    return when {
        t == "ultra_20x" || t == "ultra20x" || t.contains("20x") -> "Ultra 20X" to Color(0xFF7C3AED)
        t.contains("ultra") -> "Ultra" to Color(0xFF7C3AED)
        t.contains("pro") -> "Pro" to Color(0xFF1D4ED8)
        t.contains("plus") -> "Plus" to Color(0xFF0F766E)
        t.contains("free") -> "Free" to Color(0xFF15803D)
        else -> "等级未识别" to Color(0xFF6B7280)
    }
}
