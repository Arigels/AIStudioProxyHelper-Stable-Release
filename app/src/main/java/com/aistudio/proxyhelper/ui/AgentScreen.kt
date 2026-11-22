package com.aistudio.proxyhelper.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aistudio.proxyhelper.agent.AgentLine

@Composable
fun AgentScreen(
    uiState: MainUiState,
    onSaveConfig: (String, String, String, Boolean, String) -> Unit,
    onTest: () -> Unit,
    onFetchModels: () -> Unit,
    onRun: (String) -> Unit
) {
    var problem by remember { mutableStateOf("") }
    var showConfig by remember(uiState.agentApiKey) {
        mutableStateOf(uiState.agentApiKey.isBlank() && !uiState.agentUseLocal)
    }
    var baseUrl by remember(uiState.agentBaseUrl) { mutableStateOf(uiState.agentBaseUrl) }
    var apiKey by remember(uiState.agentApiKey) { mutableStateOf(uiState.agentApiKey) }
    var model by remember(uiState.agentModel) { mutableStateOf(uiState.agentModel) }
    var useLocal by remember(uiState.agentUseLocal) { mutableStateOf(uiState.agentUseLocal) }
    var localModel by remember(uiState.agentLocalModel) { mutableStateOf(uiState.agentLocalModel) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 标题
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
                    text = "接你自己的 AI 接口（OpenAI 兼容格式），它只会做「反代部署」这件事：" +
                        "查状态、读日志、执行命令、改端口、启停服务。不会碰你的其他数据。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showConfig = !showConfig },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (uiState.agentApiKey.isBlank()) "① 先配置接口（必填）" else "接口配置（已保存）",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        imageVector = if (showConfig) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null
                    )
                }

                AnimatedVisibility(visible = showConfig) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            FilterChip(
                                selected = !useLocal && baseUrl.contains("deepseek"),
                                onClick = {
                                    useLocal = false
                                    baseUrl = "https://api.deepseek.com"
                                    model = "deepseek-chat"
                                },
                                label = { Text("DeepSeek") }
                            )
                            FilterChip(
                                selected = !useLocal && baseUrl.contains("siliconflow"),
                                onClick = {
                                    useLocal = false
                                    baseUrl = "https://api.siliconflow.cn/v1"
                                    model = "deepseek-ai/DeepSeek-V3"
                                },
                                label = { Text("硅基流动") }
                            )
                            FilterChip(
                                selected = !useLocal && baseUrl.contains("moonshot"),
                                onClick = {
                                    useLocal = false
                                    baseUrl = "https://api.moonshot.cn/v1"
                                    model = "moonshot-v1-8k"
                                },
                                label = { Text("Kimi") }
                            )
                            FilterChip(
                                selected = !useLocal && baseUrl.contains("bigmodel"),
                                onClick = {
                                    useLocal = false
                                    baseUrl = "https://open.bigmodel.cn/api/paas/v4"
                                    model = "glm-4-flash"
                                },
                                label = { Text("智谱") }
                            )
                            FilterChip(
                                selected = !useLocal && baseUrl.contains("openai.com"),
                                onClick = {
                                    useLocal = false
                                    baseUrl = "https://api.openai.com/v1"
                                    model = "gpt-4o-mini"
                                },
                                label = { Text("OpenAI") }
                            )
                            FilterChip(
                                selected = !useLocal && baseUrl.contains("googleapis"),
                                onClick = {
                                    useLocal = false
                                    baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai"
                                    model = "gemini-2.0-flash"
                                },
                                label = { Text("Gemini Key") }
                            )
                            FilterChip(
                                selected = useLocal,
                                onClick = { useLocal = true },
                                label = { Text("本机反代（免费）") }
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "使用本机反代模型（免费）",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "用反代出来的 Gemini 模型当助手，不用另外买 Key；需要反代服务已启动",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(checked = useLocal, onCheckedChange = { useLocal = it })
                        }

                        if (useLocal) {
                            OutlinedTextField(
                                value = localModel,
                                onValueChange = { localModel = it },
                                label = { Text("反代模型名") },
                                placeholder = { Text("如 gemini-3.7-flash") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            OutlinedTextField(
                                value = baseUrl,
                                onValueChange = { baseUrl = it },
                                label = { Text("接口地址（OpenAI 兼容）") },
                                placeholder = { Text("如 https://api.deepseek.com") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = apiKey,
                                onValueChange = { apiKey = it },
                                label = { Text("API Key") },
                                placeholder = { Text("sk-...") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = model,
                                onValueChange = { model = it },
                                label = { Text("模型名") },
                                placeholder = { Text("如 deepseek-chat / gpt-4o-mini") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        OutlinedButton(
                            onClick = onFetchModels,
                            enabled = !uiState.agentFetchingModels,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            if (uiState.agentFetchingModels) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("正在拉取模型列表…")
                            } else {
                                Text("拉取可用模型列表")
                            }
                        }

                        if (uiState.agentModels.isNotEmpty()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                uiState.agentModels.take(40).forEach { name ->
                                    val selectedNow =
                                        (if (useLocal) localModel else model) == name
                                    FilterChip(
                                        selected = selectedNow,
                                        onClick = {
                                            if (useLocal) localModel = name else model = name
                                        },
                                        label = { Text(name) }
                                    )
                                }
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
                                onTest()
                            },
                            enabled = !uiState.agentTesting,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            if (uiState.agentTesting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("正在测试连接…")
                            } else {
                                Text("保存并测试连接")
                            }
                        }
                        uiState.agentTestResult?.let { result ->
                            Surface(
                                color = if (result.startsWith("连接失败")) {
                                    MaterialTheme.colorScheme.errorContainer
                                } else {
                                    MaterialTheme.colorScheme.secondaryContainer
                                },
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = if (result.startsWith("连接失败")) result else "连接成功，模型回复：$result",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }
                        }
                        Text(
                            text = "说明：外部 Key 只存本机、直接发往你填的接口；本机模式不会发出任何外部请求。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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
                Text(
                    text = "② 描述你遇到的问题",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                OutlinedTextField(
                    value = problem,
                    onValueChange = { problem = it },
                    label = { Text("例如：点了搭建但服务一直没起来") },
                    minLines = 3,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = { onRun(problem) },
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
                        Text("AI 正在排查…")
                    } else {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("让 AI 帮我排查", fontWeight = FontWeight.Bold)
                    }
                }

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
