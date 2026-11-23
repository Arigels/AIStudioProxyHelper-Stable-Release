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
                    text = "已识别你的机型：${brand.brandName}。下面是这台机器上的具体路径，按顺序做完三步即可。",
                    style = MaterialTheme.typography.bodySmall
                )
                WizardStep(
                    number = "①",
                    title = "先解除系统的「授权限制」",
                    desc = brand.restrictionPath + "\n（不解除的话，下面两步都会被挡住）",
                    actionLabel = "打开权限管理去解除",
                    onAction = onOpenAppDetails
                )
                WizardStep(
                    number = "②",
                    title = "允许显示悬浮窗",
                    desc = brand.overlayPath + "\n授权后 Termux 上方会显示搭建进度。",
                    actionLabel = "去授权",
                    onAction = onOpenOverlay
                )
                WizardStep(
                    number = "③",
                    title = "开启无障碍（自动操控）",
                    desc = brand.a11yPath + "\n开不了也不影响，App 会提示你手动粘贴一次。",
                    actionLabel = "去开启",
                    onAction = onOpenA11y
                )
                TextButton(onClick = onShowRestrictionGuide) {
                    Text("看完整的分品牌步骤")
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
