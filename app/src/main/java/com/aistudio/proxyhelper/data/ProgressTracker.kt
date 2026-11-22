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

    private val errorMarkers: List<Pair<String, List<String>>> = listOf(
        "端口权限不足（小于 1024）" to listOf("errno 13", "bind on address"),
        "端口被占用" to listOf("address already in use", "errno 98"),
        "存储空间不足" to listOf("no space left on device"),
        "梯子没开或已断（无法解析/连接 Google）" to listOf(
            "could not resolve host", "temporary failure in name resolution",
            "failed to fetch", "unable to download", "connection timed out", "network is unreachable"
        ),
        "Google 拒绝访问（403，梯子或 Cookie 有问题）" to listOf(
            "403", "forbidden", "unauthenticated", "invalid_grant"
        ),
        "依赖安装失败" to listOf("command not found", "no such file or directory"),
        "内存不足被系统杀掉" to listOf("killed", "out of memory", "oom"),
        "签名引擎编译失败" to listOf("go build: error", "build failed", "cgo: ")
    )

    fun analyze(log: String): Phase {
        if (log.isBlank()) return Phase("准备中")
        val lower = log.lowercase()

        errorMarkers.forEach { (label, keys) ->
            if (keys.any { lower.contains(it) }) {
                return Phase("搭建失败", error = label)
            }
        }

        if (lower.contains("uvicorn running") ||
            lower.contains("application startup complete") ||
            lower.contains("running on http://")
        ) {
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
