package com.aistudio.proxyhelper.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private const val MAX_DISPLAYED_COMMANDS = 5

private val COMMAND_PREFIXES = listOf(
    "pkg ",
    "apt ",
    "apt-get ",
    "pip ",
    "pip3 ",
    "python ",
    "python3 ",
    "uv ",
    "curl ",
    "wget ",
    "git ",
    "cd ",
    "bash ",
    "sh ",
    "nano ",
    "chmod ",
    "mkdir ",
    "rm ",
    "tar ",
    "unzip ",
    "export ",
    "echo ",
    "termux-",
    "proot-distro",
    "./"
)

fun extractCommandLines(text: String, limit: Int = 5): List<String> {
    if (text.isBlank() || limit <= 0) return emptyList()
    val matches = text.split('\n').mapNotNull { rawLine ->
        var cleaned = rawLine.trim()
        while (cleaned.startsWith("$ ") || cleaned.startsWith("# ") || cleaned.startsWith("> ")) {
            cleaned = cleaned.substring(2).trimStart()
        }
        if (COMMAND_PREFIXES.any { cleaned.startsWith(it) }) cleaned else null
    }
    return matches.take(limit)
}

@Composable
fun CommandCopyList(
    lines: List<String>,
    onCopy: (String) -> Unit
) {
    if (lines.isEmpty()) return
    val displayed = lines.take(MAX_DISPLAYED_COMMANDS)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        displayed.forEach { cmd ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 10.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = cmd,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(onClick = { onCopy(cmd) }) {
                        Text("复制命令")
                    }
                }
            }
        }
        if (lines.size > MAX_DISPLAYED_COMMANDS) {
            Text(
                text = "共 ${lines.size} 条命令，此处列出前 5 条",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
