package com.aistudio.proxyhelper.data

/**
 * 根据 Termux 回传的日志判断当前搭建阶段与错误。
 */
object ProgressTracker {

    data class Phase(
        val label: String,
        val error: String? = null,
        val done: Boolean = false
    )

    private val phaseMarkers: List<Pair<String, List<String>>> = listOf(
        "安装系统依赖" to listOf("pkg install", "installing packages", "pkg update", "packages will be installed"),
        "克隆仓库" to listOf("cloning into", "receiving objects", "resolving deltas", "remote: enumerating"),
        "安装 Python 依赖" to listOf("uv sync", "resolved ", "installed ", "creating virtual environment"),
        "编译本地签名引擎" to listOf(
            "go build", "go: downloading", "zig build", "spidermonkey", "waa-signer", "cgo"
        ),
        "启动服务" to listOf("start.sh", "uvicorn", "main.py server")
    )

    private val errorMarkers: List<Pair<String, List<Regex>>> = listOf(
        "端口权限不足（小于 1024）" to listOf(
            Regex("""errno\s*13"""),
            Regex("""bind\s+on\s+address""")
        ),
        "端口被占用" to listOf(
            Regex("""address\s+already\s+in\s+use"""),
            Regex("""errno\s*98""")
        ),
        "存储空间不足" to listOf(
            Regex("""no\s+space\s+left\s+on\s+device""")
        ),
        "梯子没开或已断（无法解析/连接 Google）" to listOf(
            Regex("""could\s+not\s+resolve\s+host"""),
            Regex("""temporary\s+failure\s+in\s+name\s+resolution"""),
            Regex("""failed\s+to\s+fetch"""),
            Regex("""unable\s+to\s+download"""),
            Regex("""connection\s+timed\s+out"""),
            Regex("""network\s+is\s+unreachable""")
        ),
        "Google 拒绝访问（403，梯子或 Cookie 有问题）" to listOf(
            Regex("""(?:http[/\s]*|status[:\s]*|error[:\s]*|code[=:\s]*)403\b"""),
            Regex("""\b403\s+forbidden\b"""),
            Regex("""\bforbidden\b"""),
            Regex("""\bunauthenticated\b"""),
            Regex("""\binvalid_grant\b""")
        ),
        "依赖安装失败" to listOf(
            Regex("""command\s+not\s+found"""),
            Regex("""no\s+such\s+file\s+or\s+directory""")
        ),
        "内存不足被系统杀掉" to listOf(
            Regex("""\bkilled\b"""),
            Regex("""out\s+of\s+memory"""),
            Regex("""\boom[-_\s]?kill""")
        ),
        "签名引擎编译失败" to listOf(
            Regex("""go\s+build:\s*error"""),
            Regex("""build\s+failed"""),
            Regex("""cgo:\s*error""")
        )
    )

    private val startupMarkers = listOf(
        "uvicorn running",
        "application startup complete",
        "running on http://"
    )

    fun analyze(log: String): Phase {
        if (log.isBlank()) return Phase("准备中")
        val lower = log.lowercase()

        val lastStartupIdx = startupMarkers.maxOf { lower.lastIndexOf(it) }
        val tail = if (lastStartupIdx >= 0) lower.substring(lastStartupIdx) else lower

        errorMarkers.forEach { (label, patterns) ->
            if (patterns.any { it.containsMatchIn(tail) }) {
                return Phase("搭建失败", error = label)
            }
        }

        if (lastStartupIdx >= 0) {
            return Phase("服务已启动", done = true)
        }

        var bestIndex = -1
        var bestLabel: String? = null
        phaseMarkers.forEach { (label, keys) ->
            keys.forEach { key ->
                val idx = lower.lastIndexOf(key)
                if (idx > bestIndex) {
                    bestIndex = idx
                    bestLabel = label
                }
            }
        }
        return Phase(bestLabel ?: "进行中")
    }
}
