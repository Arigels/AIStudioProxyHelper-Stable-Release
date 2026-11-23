package com.aistudio.proxyhelper.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import com.aistudio.proxyhelper.data.BrandGuide

/**
 * 首次使用引导：按「解除限制 → 悬浮窗 → 无障碍」的顺序走，先讲清楚再跳系统设置。
 */
@Composable
fun PermissionWizardDialog(
    onDismiss: () -> Unit,
    onOpenOverlay: () -> Unit,
    onOpenA11y: () -> Unit,
    onOpenAppDetails: () -> Unit,
    onShowRestrictionGuide: () -> Unit,
    onOpenRestrictionSettings: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "首次使用：3 步权限设置",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val brand = BrandGuide.current()
                Text(
                    text = "已识别你的机型：${brand.brandName}。下面两步做完就能自动搭建。",
                    style = MaterialTheme.typography.bodySmall
                )
                Surface(
                    color = Color(0xFFFFF4D6),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "⚠️ 本 App 可能被安卓手机限制权限。如果开关打不开、是灰的，或提示「已限制设置」，" +
                            "请按系统提示先解除限制，再回来开启。",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF8A6D00),
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(10.dp)
                    )
                }
                WizardStep(
                    number = "①",
                    title = "允许显示悬浮窗",
                    desc = brand.overlayPath + "\n授权后 Termux 上方会显示搭建进度。",
                    actionLabel = "去授权",
                    onAction = onOpenOverlay
                )
                WizardStep(
                    number = "②",
                    title = "开启无障碍（自动操控）",
                    desc = brand.a11yPath + "\n开不了也不影响，App 会提示你手动粘贴一次。",
                    actionLabel = "去开启",
                    onAction = onOpenA11y
                )
                TextButton(onClick = onShowRestrictionGuide) {
                    Text("开关打不开？看分品牌解除方法")
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("完成后开始使用") }
        },
        dismissButton = {
            TextButton(onClick = onOpenRestrictionSettings) {
                Text("直接去无障碍设置")
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
