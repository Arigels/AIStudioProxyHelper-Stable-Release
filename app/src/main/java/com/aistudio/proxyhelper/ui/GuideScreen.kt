package com.aistudio.proxyhelper.ui

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
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Update
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aistudio.proxyhelper.data.TermuxAction

private data class GuideStepData(
    val title: String,
    val desc: String,
    val done: Boolean,
    val jumpTab: Int
)

@Composable
fun GuideScreen(
    uiState: MainUiState,
    onGoToTab: (Int) -> Unit,
    onExecuteAction: (TermuxAction) -> Unit,
    onCopyActionCommand: (TermuxAction) -> Unit
) {
    val env = uiState.environment
    val service = uiState.service
    val settings = uiState.settings

    val steps = listOf(
        GuideStepData(
            title = "准备 Termux 环境",
            desc = "安装 F-Droid/GitHub 版 Termux，并在搭建页完成「授予调用权限」与「开启 Termux 一键调用」",
            done = env.termuxInstalled && env.termuxRunCommandGranted,
            jumpTab = 0
        ),
        GuideStepData(
            title = "一键部署反代服务",
            desc = "自动安装依赖、编译本地 WAA 签名引擎并启动 ${settings.port} 端口服务",
            done = service.online,
            jumpTab = 0
        ),
        GuideStepData(
            title = "导入 Google Cookie",
            desc = "粘贴 Cookie-Editor 导出的 JSON，自动识别全部已登录子账号",
            done = service.accounts.isNotEmpty(),
            jumpTab = 1
        ),
        GuideStepData(
            title = "测试并配置客户端",
            desc = "测试 /v1beta/models 拉取模型，复制地址填入酒馆 / Cherry Studio",
            done = uiState.lastTestResult?.first == true,
            jumpTab = 3
        )
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // ── 进度总览 ──
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.MenuBook,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "使用教程与后续指引",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = "按下面 4 步走完即可正常使用。已完成的步骤会根据当前状态自动打勾。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                steps.forEach { step ->
                    StepProgressRow(step = step, onGoToTab = onGoToTab)
                }
            }
        }

        // ── 重启恢复（重点） ──
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SectionHeader(
                    icon = Icons.Filled.RestartAlt,
                    title = "手机重启 / 服务掉线后怎么办（重点）"
                )
                Text(
                    text = "反代服务跑在 Termux 里，手机重启、系统清理后台后需要重新拉起。恢复方法（二选一）：",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                GuideNumberedLine(
                    number = "1",
                    text = "回到本 App 的「搭建」页，直接点【启动服务】按钮（会自动附带唤醒锁），等状态卡变绿即可，无需重新安装。"
                )
                GuideNumberedLine(
                    number = "2",
                    text = "手动方式：打开 Termux，执行 bash start.sh（或点下方复制命令后粘贴回车）。"
                )
                Text(
                    text = "提示："
                        + "• 若 Termux 整个被系统杀掉，先打开一次 Termux 再点启动；"
                        + "• 日常建议点【唤醒锁保活】并在系统设置里把 Termux 设为电池无限制；"
                        + "• 电脑端酒馆/客户端无需任何操作，地址不变，服务恢复后自动可用；"
                        + "• 每天下午 4 点（美西午夜）额度自动重置，无需重启。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { onExecuteAction(TermuxAction.START_SERVICE) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("一键恢复服务")
                    }
                    OutlinedButton(
                        onClick = { onExecuteAction(TermuxAction.ACQUIRE_WAKELOCK) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Filled.BatteryChargingFull, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("唤醒锁保活", maxLines = 1)
                    }
                }
                TextButton(
                    onClick = { onCopyActionCommand(TermuxAction.START_SERVICE) },
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("复制手动启动命令")
                }
            }
        }

        // ── 首次部署详情 ──
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SectionHeader(icon = Icons.Filled.RocketLaunch, title = "首次部署流程")
                GuideNumberedLine(
                    number = "1",
                    text = "安装 Termux：务必从 F-Droid 或 GitHub Releases 下载 64 位版（勿用 Google Play 商店旧版）。"
                )
                GuideNumberedLine(
                    number = "2",
                    text = "打开本 App →「搭建」页 → 完成首次准备：授予调用权限 + 开启 Termux 一键调用（设置命令粘贴到 Termux 执行一次）。"
                )
                GuideNumberedLine(
                    number = "3",
                    text = "点【一键搭建并启动】，Termux 会自动装 Git/Python/uv/Go/Zig、编译本地 WAA IPC 签名引擎并启动 ${settings.port} 端口。"
                )
                GuideNumberedLine(
                    number = "4",
                    text = "等待「搭建」页状态卡变绿，即部署完成。首次编译约需数分钟，请耐心等待。"
                )
                FilledTonalButton(
                    onClick = { onGoToTab(0) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Filled.Terminal, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("去「搭建」页操作")
                }
            }
        }

        // ── Cookie 导入详情 ──
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SectionHeader(icon = Icons.Filled.VpnKey, title = "导入 Google Cookie（必做）")
                GuideNumberedLine(
                    number = "1",
                    text = "在手机 Firefox / Edge 安装 Cookie-Editor 扩展，打开 aistudio.google.com 登录谷歌账号（多账号可在右上角头像「添加其他账号」全部登好）。"
                )
                GuideNumberedLine(
                    number = "2",
                    text = "点击 Cookie-Editor 图标 → Export → Export as JSON（自动复制到剪贴板）。"
                )
                GuideNumberedLine(
                    number = "3",
                    text = "回到本 App「账号」页 → 点【粘贴剪贴板】→ 点【自动探活并导入全部子账号】，系统会全自动识别 u/0、u/1... 多账号并建档。"
                )
                GuideNumberedLine(
                    number = "4",
                    text = "也可以直接用「账号」页的内置网页登录提取功能：登录后点【提取并自动导入】。"
                )
                FilledTonalButton(
                    onClick = { onGoToTab(1) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("去「账号」页导入")
                }
            }
        }

        // ── 客户端配置详情 ──
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SectionHeader(icon = Icons.Filled.Link, title = "连接客户端（酒馆 / Cherry Studio 等）")
                GuideNumberedLine(
                    number = "1",
                    text = "在「客户端」页点【测试拉取模型列表】，看到模型数量即代表链路正常。"
                )
                GuideNumberedLine(
                    number = "2",
                    text = "酒馆（SillyTavern）：API 类型选 Chat Completion，来源选 Google AI Studio，反向代理地址填 http://127.0.0.1:${settings.port}（电脑连手机填手机局域网 IP），代理密码随意填。"
                )
                GuideNumberedLine(
                    number = "3",
                    text = "Cherry Studio / NextChat：模型提供商必须选 Google Gemini（严禁选 OpenAI 格式），API Base URL 填 http://127.0.0.1:${settings.port} 或带版本 http://127.0.0.1:${settings.port}/v1beta，API Key 任意非空即可。"
                )
                FilledTonalButton(
                    onClick = { onGoToTab(3) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("去「客户端」页复制地址")
                }
            }
        }

        // ── 常见问题 ──
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SectionHeader(icon = Icons.Filled.Info, title = "常见问题（FAQ）")
                FaqItem(
                    q = "点一键安装没反应？",
                    a = "多半是未授予调用权限或未开启 Termux 一键调用。回「搭建」页「首次准备」卡逐项完成即可；命令会自动复制并打开 Termux，粘贴执行一次即可。"
                )
                FaqItem(
                    q = "服务一直显示未运行？",
                    a = "打开 Termux 查看报错。常见原因：使用了 Play 商店旧版 Termux、32 位系统不支持、下载依赖失败（可在「搭建」页填代理后点【更新并重编】）。"
                )
                FaqItem(
                    q = "客户端报 403 / 429？",
                    a = "账号被限流或凭据失效。系统会自动冷却切号；也可在首页点【重置 429 冷却】。仍不行就重新导出 Cookie 导入一次。"
                )
                FaqItem(
                    q = "模型列表是空的？",
                    a = "通常是没有账号或 Cookie 过期。先确认「账号」页有账号且已启用，必要时重新导入 Cookie。"
                )
                FaqItem(
                    q = "想换个端口？",
                    a = "「搭建」页展开【高级设置】修改端口保存，然后重新点【一键搭建并启动】或【启动服务】即可（换端口后客户端地址也要同步修改）。"
                )
                FaqItem(
                    q = "官方更新后怎么升级？",
                    a = "点下方【拉取更新并重编】即可执行 git pull + install.sh --rebuild，升级完再点【启动服务】。"
                )
                Text(
                    text = "免责声明：本项目仅供个人学习与安全研究，请合理使用并自行注意账号风控。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ── 更新与修复 ──
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SectionHeader(icon = Icons.Filled.Update, title = "更新与修复")
                Text(
                    text = "运行异常、版本过旧或依赖损坏时，拉取上游更新并重新编译本地签名引擎即可修复。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { onExecuteAction(TermuxAction.UPDATE_AND_REBUILD) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("拉取更新并重编")
                    }
                    OutlinedButton(
                        onClick = { onCopyActionCommand(TermuxAction.UPDATE_AND_REBUILD) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("复制命令")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun StepProgressRow(
    step: GuideStepData,
    onGoToTab: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (step.done) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (step.done) Color(0xFF16A34A) else MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = step.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = step.desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (!step.done) {
            TextButton(onClick = { onGoToTab(step.jumpTab) }) {
                Text("去做")
            }
        }
    }
}

@Composable
private fun SectionHeader(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun GuideNumberedLine(number: String, text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = number,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 2.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
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
