package com.aistudio.proxyhelper.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aistudio.proxyhelper.data.ErrorExplainer

@Composable
fun UsageScreen(
    uiState: MainUiState,
    agentContent: @Composable () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // ── 连接客户端 ──
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
                        imageVector = Icons.Filled.Link,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "连接客户端（酒馆 / Cherry Studio）",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = "协议必须选 Google Gemini，切勿选 OpenAI 格式。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )

                Text(
                    text = "代理地址和模型列表都在「反代」页，那里点一下就能复制地址。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                Text(
                    text = "酒馆参数：API 类型选 Chat Completion，来源选 Google AI Studio，代理密码随意填。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ── 报错翻译器 ──
        ErrorCheckerCard()

        // ── 无障碍详细教程 ──
        AccessibilityHelpCard()

        // ── 常见问题 ──
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "常见问题",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                FaqItem(
                    q = "点一键部署没反应？",
                    a = "多半是没装 Termux：回「反代」页，按「首次准备」卡片下载并安装 64 位 Termux（勿用 Play 商店旧版）。"
                )
                FaqItem(
                    q = "服务一直显示未运行？",
                    a = "打开 Termux 看报错，或点悬浮窗「🩺 报错」让 AI 分析。若是依赖损坏，回「反代」页点「更新并重新编译修复」。"
                )
                FaqItem(
                    q = "导入 Cookie 失败？",
                    a = "先确认 VPN 和反代服务都已启动；再用 Cookie-Editor 重新导出完整 JSON（要包含 __Secure-1PSID 等）。"
                )
                FaqItem(
                    q = "客户端报 403 / 429？",
                    a = "403 多为 Cookie 过期或节点风控，429 为账号限流。可去「反代」页高级设置里点「重置 429 冷却」。"
                )
                FaqItem(
                    q = "部署完成后，本 App 需要一直挂在后台吗？",
                    a = "不需要！反代服务实际运行在 Termux 里，本 App 只是部署与管理助手。搭建和导入账号完成后，可以直接关闭或划掉本 App（悬浮窗也可按需关闭），只要保持 Termux 和 VPN 在后台即可；以后想查看实时额度、导入账号或排查问题再打开就行。"
                )
                FaqItem(
                    q = "模型列表是空的？",
                    a = "通常是没有导入账号或 Cookie 过期，重新导入一次即可。"
                )
                Text(
                    text = "本项目为开源项目，完全免费获取，严禁贩卖。反代存在账号风控风险，本 App 仅作为部署助手，不承担任何责任。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ── AI 助手（放在解疑页最下面）──
        agentContent()

        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun AccessibilityHelpCard() {
    var expanded by rememberSaveable { mutableStateOf(false) }

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
                    imageVector = Icons.Filled.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "无障碍打不开？详细教程",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "「已限制设置」怎么解除、各品牌路径、开了又自动关怎么办",
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
                Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                    AccessibilityGuideBody()
                }
            }
        }
    }
}

@Composable
private fun ErrorCheckerCard() {
    var input by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<ErrorExplainer.Explanation>>(emptyList()) }

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
                    imageVector = Icons.Filled.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "看不懂报错？粘贴进来翻译",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = "把 Termux 里红色的报错复制粘贴到下面，点「帮我看看」，用大白话告诉你哪儿错了、怎么修。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text("粘贴报错信息") },
                placeholder = { Text("例如：error while attempting to bind on address ...") },
                minLines = 3,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = { results = ErrorExplainer.explain(input) },
                enabled = input.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("帮我看看")
            }
            results.forEach { exp ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = exp.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "原因：${exp.cause}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "怎么修：${exp.fix}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FaqItem(q: String, a: String) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(
            text = "Q：$q",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "A：$a",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
