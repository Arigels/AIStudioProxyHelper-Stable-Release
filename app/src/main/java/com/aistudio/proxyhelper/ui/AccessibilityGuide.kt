package com.aistudio.proxyhelper.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 无障碍（自动操控）开启教程内容，供弹窗与「使用」页共用。
 */
@Composable
fun AccessibilityGuideBody() {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "为什么要开？",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "开启后，App 才能自动打开 Termux、自动粘贴命令并按下回车，实现「点一下就搭完」。不开也能用，只是每次要你自己在 Termux 长按粘贴。",
            style = MaterialTheme.typography.bodySmall
        )

        Text(
            text = "① 通用步骤",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "1. 打开系统「设置」→「无障碍」（有的手机叫「辅助功能」）\n" +
                "2. 找到「已下载的应用」或「已下载的服务」\n" +
                "3. 点「AI Studio 反代自动搭建助手」\n" +
                "4. 打开开关，弹窗点「允许 / 确定」\n" +
                "5. 返回本 App，首页会显示「自动操控已开启」",
            style = MaterialTheme.typography.bodySmall
        )
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(
                text = "❗ 找不到本 App？它一定在「已下载的应用」里（有的系统叫「已下载的服务」），" +
                    "不是「已安装的应用/服务」，切换一下那个标签页就能看到。",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(10.dp),
                fontWeight = FontWeight.SemiBold
            )
        }

        Text(
            text = "② 打不开 / 被系统限制",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold
        )
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(
                text = "出现「已限制设置」这行小字（小米、红米、OPPO、vivo、荣耀最常见）\n" +
                    "→ 点它右边的小字「解除限制」或「允许受限制的设置」，再打开开关。\n" +
                    "这行字很小、颜色很浅，容易漏看。",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(10.dp),
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
        Text(
            text = "• 开关是灰色的：先在无障碍页关掉「无障碍快捷方式」，或退出「隐私空间 / 儿童空间」再进\n" +
                "• 列表里找不到本 App：切到「已下载的应用 / 已下载的服务」标签（不是「已安装的应用 / 服务」）\n" +
                "• 打开后过一会自动关闭：设置 → 电池 → 应用 → 本 App → 改成「无限制」，并允许「自启动」\n" +
                "• 提示「此应用可能导致…」风险提示：点「仍要开启」",
            style = MaterialTheme.typography.bodySmall
        )

        Text(
            text = "③ 各品牌具体路径",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "小米 / 红米（HyperOS、MIUI）\n" +
                "设置 → 更多设置 → 无障碍 → 已下载的应用 → 选本 App → 若有「已限制设置」点「解除限制」\n\n" +
                "OPPO / 一加 / realme（ColorOS）\n" +
                "设置 → 系统设置 → 无障碍 → 已下载的服务 → 选本 App → 弹窗点「允许受限制的设置」\n\n" +
                "vivo / iQOO（OriginOS）\n" +
                "设置 → 快捷与辅助 → 无障碍 → 已下载的应用 → 选本 App\n\n" +
                "荣耀 / 华为（MagicOS、EMUI）\n" +
                "设置 → 辅助功能 → 无障碍 → 已下载的应用 → 选本 App\n\n" +
                "三星（One UI）\n" +
                "设置 → 辅助功能 → 已安装的应用程序 → 选本 App",
            style = MaterialTheme.typography.bodySmall
        )

        Text(
            text = "④ 实在开不了怎么办",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "不影响使用：点「一键搭建并启动」时，App 会把命令复制好并自动打开 Termux，你只要长按屏幕点「粘贴」，再按一下回车（或键盘的发送键）即可。整个流程只多这一步。",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
fun AccessibilityGuideDialog(
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "开启自动操控（无障碍）",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                AccessibilityGuideBody()
            }
        },
        confirmButton = {
            Button(onClick = onOpenSettings) {
                Text("打开无障碍设置")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
