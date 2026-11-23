package com.aistudio.proxyhelper.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.CheckCircle
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.aistudio.proxyhelper.agent.AgentLine

private data class Provider(
    val label: String,
    val baseUrl: String,
    val defaultModel: String,
    val isLocal: Boolean = false
)

private val providers = listOf(
    Provider("本机反代（免费，不用 Key）", "", "", isLocal = true),
    Provider("DeepSeek", "https://api.deepseek.com", ""),
    Provider("硅基流动 SiliconFlow", "https://api.siliconflow.cn/v1", ""),
    Provider("Kimi 月之暗面", "https://api.moonshot.cn/v1", ""),
    Provider("智谱 GLM", "https://open.bigmodel.cn/api/paas/v4", ""),
    Provider("OpenAI", "https://api.openai.com/v1", ""),
    Provider("Claude（Anthropic 兼容）", "https://api.anthropic.com/v1", ""),
    Provider("Gemini（官方 Key）", "https://generativelanguage.googleapis.com/v1beta/openai", ""),
    Provider("自定义地址", "", "")
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AgentScreen(
    uiState: MainUiState,
    onSaveConfig: (String, String, String, Boolean, String) -> Unit,
    onFetchModels: (String, String, String, Boolean, String) -> Unit,
    onRun: (String) -> Unit,
    onAskTavern: (String) -> Unit
) {
    var problem by remember { mutableStateOf("") }
    var mode by remember { mutableIntStateOf(0) }
    var showConfig by remember {
        mutableStateOf(uiState.agentApiKey.isBlank() && !uiState.agentUseLocal)
    }

    var baseUrl by remember(uiState.agentBaseUrl) { mutableStateOf(uiState.agentBaseUrl) }
    var apiKey by remember(uiState.agentApiKey) { mutableStateOf(uiState.agentApiKey) }
    var model by remember(uiState.agentModel) { mutableStateOf(uiState.agentModel) }
    var useLocal by remember(uiState.agentUseLocal) { mutableStateOf(uiState.agentUseLocal) }
    var localModel by remember(uiState.agentLocalModel) { mutableStateOf(uiState.agentLocalModel) }

    var providerMenu by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }
    var keyVisible by remember { mutableStateOf(false) }

    val currentProvider = providers.firstOrNull { p ->
        if (p.isLocal) {
            useLocal
        } else {
            !useLocal && p.baseUrl.isNotBlank() &&
                baseUrl.trim().trimEnd('/').equals(p.baseUrl.trim().trimEnd('/'), ignoreCase = true)
        }
    } ?: providers.last()

    val currentModel = if (useLocal) localModel else model

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.SmartToy,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "AI 排障助手",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = "选一个服务商、填 Key、选模型就能用。它只会做「反代部署」这件事：查状态、读日志、执行命令、改端口、启停服务。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (uiState.agentUseLocal || uiState.agentApiKey.isNotBlank()) {
                            "接口配置（已保存，点此修改）"
                        } else {
                            "① 配置接口（必填）"
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
                        // 服务商下拉
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "服务商",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedButton(onClick = { providerMenu = true }) {
                                Text(currentProvider.label)
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
                                            if (p.isLocal) {
                                                useLocal = true
                                            } else {
                                                useLocal = false
                                                if (p.baseUrl.isNotBlank()) baseUrl = p.baseUrl
                                                if (p.defaultModel.isNotBlank()) model = p.defaultModel
                                            }
                                        }
                                    )
                                }
                            }
                        }

                        if (useLocal) {
                            Text(
                                text = "使用本机反代模型：不需要 Key，但反代服务必须已启动。",
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
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "模型",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedButton(onClick = { modelMenu = true }) {
                                Text(currentModel.ifBlank { "选择模型" })
                            }
                            DropdownMenu(
                                expanded = modelMenu,
                                onDismissRequest = { modelMenu = false }
                            ) {
                                if (uiState.agentModels.isEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text("还没有模型列表，先点「拉取模型」") },
                                        onClick = { modelMenu = false }
                                    )
                                } else {
                                    uiState.agentModels.forEach { name ->
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
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("保存设置")
                        }

                        if (uiState.agentModels.isNotEmpty()) {
                            Text(
                                text = "可用模型（${uiState.agentModels.size} 个，点一个选中）",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                maxItemsInEachRow = 2
                            ) {
                                uiState.agentModels.take(60).forEach { name ->
                                    val chosen = if (useLocal) localModel else model
                                    FilterChip(
                                        selected = chosen == name,
                                        onClick = {
                                            if (useLocal) localModel = name else model = name
                                        },
                                        label = {
                                            Text(
                                                text = name,
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                        }
                                    )
                                }
                            }
                        } else if (!uiState.agentFetchingModels) {
                            Text(
                                text = "还没有模型列表，点上面「连接并拉取模型」",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

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
                        "② 问酒馆相关的问题（服务在跑时用反代模型免费回答）"
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
                    enabled = !uiState.agentRunning &&
                        (uiState.agentUseLocal || uiState.agentApiKey.isNotBlank()),
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
                        enabled = !uiState.agentRunning &&
                            (uiState.agentUseLocal || uiState.agentApiKey.isNotBlank()),
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
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
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
                    }
                    Text(
                        text = answer,
                        style = MaterialTheme.typography.bodyMedium
                    )
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
