package com.aistudio.proxyhelper.data

/**
 * 把常见报错翻译成人话，给完全不懂命令行的用户看。
 */
object ErrorExplainer {

    data class Explanation(
        val title: String,
        val cause: String,
        val fix: String
    )

    private data class Rule(val pattern: Regex, val result: Explanation)

    private val rules: List<Rule> = listOf(
        Rule(
            Regex(
                """(?is)(errno\s*13|permission denied)[^\n]{0,80}(bind|0\.0\.0\.0|address)|bind[^\n]{0,60}permission denied"""
            ),
            Explanation(
                title = "端口被系统拒绝（Permission denied）",
                cause = "你用的端口小于 1024（常见是 808）。安卓/Linux 规定 1024 以下的端口只有 root 才能用，普通应用无权绑定。",
                fix = "把端口改成 1024 以上，推荐 8080，然后重新启动服务。"
            )
        ),
        Rule(
            Regex("""(?i)(address already in use|errno\s*98)"""),
            Explanation(
                title = "端口已被占用（Address already in use）",
                cause = "已经有一个 aistudio-api 进程在跑，或者别的软件占了这个端口。",
                fix = "先点「停止服务」，再点「启动服务」；仍不行就换一个端口（如 8081）。"
            )
        ),
        Rule(
            Regex("""(?i)no space left on device"""),
            Explanation(
                title = "手机存储空间不足",
                cause = "编译依赖和签名引擎需要几百 MB 空间，当前空间不够。",
                fix = "清理手机存储（至少留 1~2 GB），然后重新安装。"
            )
        ),
        Rule(
            Regex("""(?i)could not resolve host|temporary failure in name resolution|name or service not known|failed to fetch|unable to download"""),
            Explanation(
                title = "网络连接失败（下载不到依赖）",
                cause = "Termux 连不上软件源或 GitHub，常见于没开代理或代理失效。",
                fix = "在「搭建 → 高级设置」里填好代理地址（如 http://127.0.0.1:7890），再点「更新并重编」。"
            )
        ),
        Rule(
            Regex("""(?i)no such file or directory[^\n]{0,40}(install\.sh|start\.sh|aistudio-api)"""),
            Explanation(
                title = "仓库没有克隆成功",
                cause = "下载源码那一步失败了，所以找不到 install.sh。",
                fix = "重新点「一键搭建并启动」；仍失败请检查网络/代理后重试。"
            )
        ),
        Rule(
            Regex("""(?i)(command not found|not found:)\s*[^\n]{0,20}(uv|go|zig|python|curl|git)"""),
            Explanation(
                title = "缺少必要命令（依赖没装全）",
                cause = "安装脚本中途失败，导致 uv / go / zig / python 之一没装上。",
                fix = "点「运行异常？重新安装 / 修复」重新安装一次。"
            )
        ),
        Rule(
            Regex("""(?i)\b403\b|forbidden|permission denied[^\n]{0,40}(google|credential|cookie)"""),
            Explanation(
                title = "Google 侧拒绝了请求（403）",
                cause = "Cookie 失效、账号被风控，或网络环境被判定为异常。",
                fix = "重新用 Cookie-Editor 导出并导入 Cookie；换网络/代理后再试。"
            )
        ),
        Rule(
            Regex("""(?i)\b429\b|rate limit|quota exceeded"""),
            Explanation(
                title = "触发限流（429）",
                cause = "当前账号额度用完或被限流，系统会自动冷却切号。",
                fix = "稍等几分钟；或在「搭建 → 高级设置」点「重置 429 冷却」；也可以导入更多账号。"
            )
        ),
        Rule(
            Regex("""(?i)(killed|out of memory|oom)"""),
            Explanation(
                title = "进程被系统杀掉（内存不足）",
                cause = "手机内存紧张，编译或运行被系统终止。",
                fix = "关掉后台其他应用；在 Termux 通知里点「Acquire Wakelock」，并把 Termux 设为电池无限制。"
            )
        ),
        Rule(
            Regex("""(?i)(spidermonkey|waa-signer|go build|cgo).{0,80}(error|failed)"""),
            Explanation(
                title = "本地签名引擎编译失败",
                cause = "Go / Zig 工具链或源码有问题，常见于依赖下载不完整。",
                fix = "点「更新并重编」重新拉取并编译；仍失败请检查代理。"
            )
        ),
        Rule(
            Regex("""(?i)ssl|certificate|tls"""),
            Explanation(
                title = "证书/HTTPS 握手失败",
                cause = "系统时间不对，或代理拦截了 HTTPS。",
                fix = "校准手机时间；关闭代理的 HTTPS 拦截后再试。"
            )
        )
    )

    fun explain(input: String): List<Explanation> {
        val text = input.trim()
        if (text.isEmpty()) return emptyList()
        val hits = rules.filter { it.pattern.containsMatchIn(text) }.map { it.result }
        return if (hits.isEmpty()) {
            listOf(
                Explanation(
                    title = "暂时没识别出这条报错",
                    cause = "这条信息不在内置的常见错误库里。",
                    fix = "把完整报错复制下来，到 GitHub 仓库的 Issues 留言，或发给会折腾的朋友看。"
                )
            )
        } else {
            hits.distinctBy { it.title }
        }
    }
}
