package com.aistudio.proxyhelper.ui

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aistudio.proxyhelper.R
import com.aistudio.proxyhelper.data.ErrorCodeRules
import com.aistudio.proxyhelper.data.guideFor
import com.aistudio.proxyhelper.termux.TermuxBridge

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
        // ── ⓪ 测试功能醒目公告（常显，置顶）──
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Color(0xFFFFF4D6),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = Color(0xFFB45309),
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "AI 排障助手为测试功能，并未完善优化，谨慎使用",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF8A6D00)
                )
            }
        }

        // ── ① 最近报错与解决办法（常开，置顶；读 B04 的 lastError，不再粘贴）──
        ErrorCheckerCard(uiState)

        // ── ② AI 排障助手（默认只露标题，点开才是完整排障窗口）──
        agentContent()

        // ── ③ Termux 多开窗口教程（默认收起）──
        UsageCollapsibleCard(title = "Termux 多开窗口教程") {
            Text(
                text = "① 打开 Termux",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "② 从屏幕左边缘向右滑，拉出白色小窗",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "③ 点 NEW SESSION",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "④ 在新窗口输入你的酒馆启动命令（示例：bash start.sh）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "⑤ 以后每次用反代都保持两个窗口：一个跑反代，一个跑酒馆",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // ── ④ 连接客户端（默认收起）──
        UsageCollapsibleCard(title = "连接客户端（酒馆）") {
            ConnectClientContent(uiState)
        }

        // ── ⑤ 无障碍详细教程（默认收起）──
        AccessibilityHelpCard()

        // ── ⑥ 常见问题（默认收起）──
        UsageCollapsibleCard(title = "常见问题") {
            FaqItem(
                q = "点半自动部署没反应？",
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
                a = "403 多为 Cookie 过期或节点风控，请重新导出导入 Cookie；429 为账号限流或今日额度用尽，请等待冷却自动结束（切勿频繁强行重试以免触发风控），或到「账号」页切换/导入备用小号，每天下午 4 点（美西 00:00 PST）会重置每日配额。"
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

        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun ConnectClientContent(uiState: MainUiState) {
    val context = LocalContext.current
    val address = uiState.settings.localBaseUrl

    Text(
        text = "本机地址：$address",
        style = MaterialTheme.typography.bodyMedium,
        color = Color(0xFF2563EB),
        modifier = Modifier.clickable {
            TermuxBridge.copyToClipboard(context, "本机地址", address)
            Toast.makeText(context, "已复制本机地址", Toast.LENGTH_SHORT).show()
        }
    )

    Text(
        text = "协议必须选 Google Gemini，切勿选 OpenAI 格式。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error
    )

    Text(
        text = "代理地址在「反代」页，也可在悬浮窗直接复制；模型列表和额度看「账号」页。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

    // ①-④ 与悬浮窗教程第 7/7 步保持一致；插头用 emoji，圆形向下箭头用 drawable
    val arrowSize = with(LocalDensity.current) { 14.dp.toSp() }
    val hint = buildAnnotatedString {
        append("① 点击上方蓝色地址直接复制，拉新窗口启动酒馆\n")
        append("② 进入酒馆后，点击 🔌\n")
        append("③ API 选择聊天补全 / Chat Completion → 聊天补全来源选择 Google AI Studio → 点击反向代理右边的 ")
        appendInlineContent("down", "[向下箭头]")
        append("\n④ 复制好的地址粘贴进代理服务器 URL 栏，剩下的部分随便填")
    }
    BasicText(
        text = hint,
        style = MaterialTheme.typography.bodySmall.copy(
            color = MaterialTheme.colorScheme.onSurfaceVariant
        ),
        inlineContent = mapOf(
            "down" to InlineTextContent(
                Placeholder(
                    width = arrowSize,
                    height = arrowSize,
                    placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter
                )
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_down_circle),
                    contentDescription = null,
                    modifier = Modifier.size(14.dp)
                )
            }
        )
    )
}

@Composable
private fun UsageCollapsibleCard(
    title: String,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
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
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    subtitle?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    content = content
                )
            }
        }
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
private fun ErrorCheckerCard(uiState: MainUiState) {
    val finding = uiState.lastError
    val errorAt = uiState.lastErrorAt ?: 0L
    val context = LocalContext.current

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
                    text = "最近报错与解决办法",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            if (finding == null) {
                Text(
                    text = "当前没有检测到报错。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "遇到别的报错，可以直接用上方的 AI 排障助手提问。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                val guide = guideFor(finding.kind)
                Text(
                    text = ErrorCodeRules.formatSummaryLine(finding, errorAt),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "自查",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    guide.checks.forEachIndexed { index, item ->
                        Text(
                            text = "${index + 1}. $item",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                Text(
                    text = "解决办法",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    guide.fixes.forEachIndexed { index, item ->
                        Text(
                            text = "${index + 1}. $item",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
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
