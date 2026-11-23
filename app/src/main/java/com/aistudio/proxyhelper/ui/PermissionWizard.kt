package com.aistudio.proxyhelper.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.aistudio.proxyhelper.data.BrandGuide

/**
 * 首次打开 App 时弹出的风险提示、免责声明与开源免费声明。
 */
@Composable
fun DisclaimerDialog(
    onAccept: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { /* 首次必须阅读并确认 */ },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        ),
        title = {
            Text(
                text = "使用前必读 · 风险与声明",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "⚠️ 反代存在账号风控风险",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            text = "使用反代服务可能会触发 Google 风控，导致账号被限流、验证甚至封禁。请优先使用备用小号，切勿使用重要主力账号。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }

                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "📌 免责声明",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "本 App 仅作为手机端本地部署助手，不提供任何代理节点或账号服务，不对因使用反代造成的账号封禁、限流、数据损失等后果承担任何责任。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Surface(
                    color = Color(0xFFFFF4D6),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "🆓 开源免费 · 禁止贩卖",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF8A6D00)
                        )
                        Text(
                            text = "本项目为开源项目，完全免费获取，严禁任何形式的倒卖、付费代搭打包或商业贩卖。如果你是花钱买来的，请立即申请退款！",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF8A6D00)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onAccept) {
                Text("我已了解风险，继续使用")
            }
        }
    )
}

/**
 * 首次使用权限引导：提醒系统权限限制，并提供悬浮窗与无障碍（可选）授权入口。
 */
@Composable
fun PermissionWizardDialog(
    onDismiss: () -> Unit,
    onOpenOverlay: () -> Unit,
    onOpenA11y: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "首次使用：权限设置",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val brand = BrandGuide.current()
                Text(
                    text = "已识别你的机型：${brand.brandName}。",
                    style = MaterialTheme.typography.bodySmall
                )
                Surface(
                    color = Color(0xFFFFF4D6),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "⚠️ 本 App 可能会被安卓手机限制权限。请注意权限无法开启时的系统提示，" +
                            "按照系统的提示步骤解除限制后，再回来开启下面的权限。",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF8A6D00),
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(10.dp)
                    )
                }
                WizardStep(
                    number = "①",
                    title = "允许显示悬浮窗",
                    desc = brand.overlayPath + "\n授权后可在 Termux/酒馆上方显示状态，遇到报错可点悬浮窗「🩺 报错」让 AI 分析。",
                    actionLabel = "去授权悬浮窗",
                    onAction = onOpenOverlay
                )
                WizardStep(
                    number = "②",
                    title = "开启无障碍（可选）",
                    desc = brand.a11yPath + "\n用于悬浮窗识别当前屏幕报错文字；不开也不影响部署和日常使用。",
                    actionLabel = "去开启无障碍",
                    onAction = onOpenA11y
                )
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("完成后开始使用") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("稍后设置")
            }
        }
    )
}

@Composable
private fun WizardStep(
    number: String,
    title: String,
    desc: String,
    actionLabel: String,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 2.dp)
        ) {
            Text(
                text = number,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = onAction) {
                Text(actionLabel)
            }
        }
    }
}
