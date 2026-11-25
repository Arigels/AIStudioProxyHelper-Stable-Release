package com.aistudio.proxyhelper.ui

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aistudio.proxyhelper.agent.AgentLine
import com.aistudio.proxyhelper.termux.TermuxBridge

private data class Provider(
    val label: String,
    val baseUrl: String
)

private val providers = listOf(
    Provider("DeepSeek", "https://api.deepseek.com"),
    Provider("硅基流动 SiliconFlow", "https://api.siliconflow.cn/v1"),
    Provider("Kimi 月之暗面", "https://api.moonshot.cn/v1"),
    Provider("智谱 GLM", "https://open.bigmodel.cn/api/paas/v4"),
    Provider("OpenAI", "https://api.openai.com/v1"),
    Provider("Claude（Anthropic 兼容）", "https://api.anthropic.com/v1"),
    Provider("Gemini（官方 Key）", "https://generativelanguage.googleapis.com/v1beta/openai"),
    Provider("自定义地址", "")
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AgentScreen(
    uiState: MainUiState,
    onSwitchSource: (Boolean) -> Unit,
    onSaveConfig: (String, String, String, Boolean, String) -> Unit,
    onFetchModels: (String, String, String, Boolean, String) -> Unit,
    onRun: (String) -> Unit,
    onAskTavern: (String) -> Unit,
    initialProblem: String? = null,
    embedded: Boolean = false
) {
    val context = LocalContext.current
    var problem by rememberSaveable { mutableStateOf("") }
    var mode by rememberSaveable { mutableIntStateOf(0) }
    // AI 排障助手默认只露标题，点开才显示配置 / 输入 / 排查过程 / 结论
    var expanded by rememberSaveable { mutableStateOf(false) }

    // 有错误时预填到输入框，但不自动调用 AI（避免打开 App 就消耗额度）
    LaunchedEffect(initialProblem) {
        if (!initialProblem.isNullOrBlank() && problem.isBlank()) {
            problem = initialProblem
            mode = 0
        }
    }
    var showConfig by remember {
        mutableStateOf(
            if (uiState.agentUseLocal) uiState.agentLocalModel.isBlank()
            else uiState.agentApiKey.isBlank() || uiState.agentModel.isBlank()
        )
    }

    var baseUrl by remember(uiState.agentBaseUrl) { mutableStateOf(uiState.agentBaseUrl) }
    var apiKey by remember(uiState.agentApiKey) { mutableStateOf(uiState.agentApiKey) }
    var model by remember(uiState.agentModel) { mutableStateOf(uiState.agentModel) }
    var useLocalTab by remember(uiState.agentUseLocal) { mutableStateOf(uiState.agentUseLocal) }
    var localModel by remember(uiState.agentLocalModel) { mutableStateOf(uiState.agentLocalModel) }

    var providerMenu by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }
    var keyVisible by remember { mutableStateOf(false) }

    val useLocal = useLocalTab
    val availableModels = if (useLocal) uiState.agentLocalModels else uiState.agentCustomModels

    val currentProvider = providers.firstOrNull { p ->
        p.baseUrl.isNotBlank() &&
            baseUrl.trim().trimEnd('/').equals(p.baseUrl.trim().trimEnd('/'), ignoreCase = true)
    } ?: providers.last()

    val currentModel = if (useLocal) localModel else model

    Column(
        modifier = if (embedded) {
            Modifier.fillMaxWidth()
        } else {
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
        },
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expanded = !expanded },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.SmartToy,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "AI 排障助手",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null
                    )
                }
                AnimatedVisibility(visible = expanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "选一个服务商、填 Key、选模型就能用。它只会做「反代部署」这件事：查状态、读日志、执行命令、改端口、启停服务。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = !useLocal,
                        onClick = {
                            if (useLocalTab) {
                                useLocalTab = false
                                showConfig = true
                                onSwitchSource(false)
                            }
                        },
                        label = { Text("自定义") },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = useLocal,
                        onClick = {
                            if (!useLocalTab) {
                                useLocalTab = true
                                showConfig = true
                                onSwitchSource(true)
                            }
                        },
                        label = { Text("本地反代") },
                        modifier = Modifier.weight(1f)
                    )
                }
                Text(
                    text = if (useLocal) {
                        "当前只用「本地反代」这一个来源：直接调用你自己搭的反代，不用填地址和 Key。"
                    } else {
                        "当前只用「自定义（外接模型）」这一个来源：填你自己的服务商地址和 Key。两个来源不会同时调用。"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if ((useLocal && uiState.agentLocalModel.isNotBlank()) ||
                            (!useLocal && uiState.agentApiKey.isNotBlank() && uiState.agentModel.isNotBlank())
                        ) {
                            "接口配置（当前：$currentModel）"
                        } else {
                            "① 配置接口（先拉取模型并保存）"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(onClick = { showConfig = !showConfig }) {
                        Icon(
                            imageVector = if (showConfig) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (showConfig) "收起" else "配置")
                    }
                }

                AnimatedVisibility(visible = showConfig) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        // 服务商下拉（仅外接模型模式显示）
                        if (!useLocal) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    text = "服务商",
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1
                                )
                                Box(
                                    modifier = Modifier.weight(1f),
                                    contentAlignment = Alignment.CenterEnd
                                ) {
                                    OutlinedButton(onClick = { providerMenu = true }) {
                                        Text(
                                            text = currentProvider.label,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = providerMenu,
                                        onDismissRequest = { providerMenu = false }
                                    ) {
                                        providers.forEach { p ->
                                            DropdownMenuItem(
                                                text = { Text(p.label) },
                                                onClick = {
                                                    providerMenu = false
                                                    if (p.baseUrl.isNotBlank()) baseUrl = p.baseUrl
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        if (useLocal) {
                            Text(
                                text = "本机反代地址：${uiState.settings.localBaseUrl}（自动读取，不需要填）\n" +
                                    "反代服务必须先启动，否则拉不到模型。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            OutlinedTextField(
                                value = baseUrl,
                                onValueChange = { baseUrl = it },
                                label = { Text("接口地址（选服务商后自动填好，可改）") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = apiKey,
                                onValueChange = { apiKey = it },
                                label = { Text("API Key") },
                                placeholder = { Text("粘贴你的 Key") },
                                singleLine = true,
                                visualTransformation = if (keyVisible) {
                                    VisualTransformation.None
                                } else {
                                    PasswordVisualTransformation()
                                },
                                trailingIcon = {
                                    IconButton(onClick = { keyVisible = !keyVisible }) {
                                        Icon(
                                            imageVector = if (keyVisible) {
                                                Icons.Filled.VisibilityOff
                                            } else {
                                                Icons.Filled.Visibility
                                            },
                                            contentDescription = if (keyVisible) "隐藏密钥" else "显示密钥"
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        // 模型下拉
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "模型",
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1
                            )
                            Box(
                                modifier = Modifier.weight(1f),
                                contentAlignment = Alignment.CenterEnd
                            ) {
                                OutlinedButton(onClick = { modelMenu = true }) {
                                    Text(
                                        text = currentModel.ifBlank { "选择模型" },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                DropdownMenu(
                                    expanded = modelMenu,
                                    onDismissRequest = { modelMenu = false }
                                ) {
                                    if (availableModels.isEmpty()) {
                                        DropdownMenuItem(
                                            text = { Text("还没有模型列表，先点「连接并拉取模型」") },
                                            onClick = { modelMenu = false }
                                        )
                                    } else {
                                        availableModels.forEach { name ->
                                            DropdownMenuItem(
                                                text = { Text(name) },
                                                onClick = {
                                                    modelMenu = false
                                                    if (useLocal) localModel = name else model = name
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Button(
                            onClick = {
                                onFetchModels(
                                    baseUrl.trim(),
                                    apiKey.trim(),
                                    model.trim(),
                                    useLocal,
                                    localModel.trim()
                                )
                            },
                            enabled = !uiState.agentFetchingModels,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            if (uiState.agentFetchingModels) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("正在连接…")
                            } else {
                                Text("连接并拉取模型")
                            }
                        }

                        OutlinedButton(
                            onClick = {
                                onSaveConfig(
                                    baseUrl.trim(),
                                    apiKey.trim(),
                                    model.trim(),
                                    useLocal,
                                    localModel.trim()
                                )
                                showConfig = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("保存设置并收起")
                        }
                    }
                }
                    }
                }
            }
        }

        AnimatedVisibility(visible = expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {

        // 问题输入
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = mode == 0,
                        onClick = { mode = 0 },
                        label = { Text("搭建排障") },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = mode == 1,
                        onClick = { mode = 1 },
                        label = { Text("酒馆问答") },
                        modifier = Modifier.weight(1f)
                    )
                }

                Text(
                    text = if (mode == 0) {
                        "② 描述你遇到的问题（或直接让它从零搭建）"
                    } else {
                        "② 问酒馆相关的问题（只使用上方选中的「${if (useLocal) "本地反代" else "自定义"}」模型回答）"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                OutlinedTextField(
                    value = problem,
                    onValueChange = { problem = it },
                    label = {
                        Text(
                            if (mode == 0) "例如：点了搭建但服务一直没起来"
                            else "例如：酒馆连不上 / 模型列表是空的"
                        )
                    },
                    minLines = 3,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = { if (mode == 0) onRun(problem) else onAskTavern(problem) },
                    enabled = !uiState.agentRunning,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (uiState.agentRunning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (mode == 0) "AI 正在排查…" else "AI 正在回答…")
                    } else {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            if (mode == 0) "让 AI 帮我排查" else "问酒馆助手",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                if (mode == 0) {
                    OutlinedButton(
                        onClick = {
                            onRun("我还没开始搭建，请帮我从零把反代服务搭起来，并确认能否正常访问。")
                        },
                        enabled = !uiState.agentRunning,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("从零开始，让 AI 自动搭建", fontWeight = FontWeight.SemiBold)
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(
                            "酒馆地址和密码怎么填",
                            "模型列表是空的怎么办",
                            "报 403 怎么处理",
                            "回复太慢怎么优化"
                        ).forEach { q ->
                            FilterChip(
                                selected = false,
                                onClick = {
                                    problem = q
                                    onAskTavern(q)
                                },
                                label = { Text(q) }
                            )
                        }
                    }
                }
            }
        }

        // 过程
        if (uiState.agentLines.isNotEmpty()) {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "排查过程",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    uiState.agentLines.forEach { line ->
                        AgentLineRow(line)
                    }
                }
            }
        }

        // 结论
        uiState.agentAnswer?.let { answer ->
            val commandLines = remember(answer) {
                extractCommandLines(answer, limit = Int.MAX_VALUE)
            }
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (answer.contains("失败")) {
                                Icons.Filled.Warning
                            } else {
                                Icons.Filled.CheckCircle
                            },
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "AI 的结论",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        if (answer.isNotBlank()) {
                            Spacer(modifier = Modifier.weight(1f))
                            IconButton(
                                onClick = {
                                    TermuxBridge.copyToClipboard(context, "AI 的结论", answer)
                                    Toast.makeText(context, "结论已复制", Toast.LENGTH_SHORT).show()
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.ContentCopy,
                                    contentDescription = "复制结论"
                                )
                            }
                        }
                    }
                    SelectionContainer {
                        Text(
                            text = answer,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    CommandCopyList(
                        lines = commandLines,
                        onCopy = { cmd ->
                            TermuxBridge.copyToClipboard(context, "命令", cmd)
                            Toast.makeText(context, "命令已复制", Toast.LENGTH_SHORT).show()
                        }
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
private fun AgentLineRow(line: AgentLine) {
    val (label, color) = when (line.role) {
        "think" -> "思考" to MaterialTheme.colorScheme.onSurfaceVariant
        "action" -> "执行" to MaterialTheme.colorScheme.primary
        "observation" -> "结果" to MaterialTheme.colorScheme.onSurface
        "answer" -> "结论" to Color(0xFF15803D)
        else -> "错误" to MaterialTheme.colorScheme.error
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = color
            )
            Text(
                text = line.text,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = if (line.role == "action" || line.role == "observation") {
                    FontFamily.Monospace
                } else {
                    FontFamily.Default
                }
            )
        }
    }
}
