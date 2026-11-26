package com.aistudio.proxyhelper.data

/**
 * 错误内容库：按 B04 判定结果（ErrorKind）给出三条自查与三条解决办法。
 * 纯静态常量，不发请求、不读文件；界面层只渲染这里的内容，不自行拼接新句子。
 */
data class ErrorGuide(
    val kind: ErrorKind,
    val checks: List<String>,
    val fixes: List<String>
)

private val REGION_GUIDE = ErrorGuide(
    kind = ErrorKind.REGION,
    checks = listOf(
        "报错里同时出现 403 与「Region not supported」。",
        "出问题的账号是新导入的，或最近更换过网络节点。",
        "同一时间其它账号正常，只有这个账号报错。"
    ),
    fixes = listOf(
        "更换支持地区的网络节点，再发一次请求。",
        "换一个支持地区的账号，重新导出并导入 Cookie。",
        "仍然不通时，在浏览器直接打开 AI Studio 登录该账号，确认页面本身能进。"
    )
)

private val RISK_GUIDE = ErrorGuide(
    kind = ErrorKind.RISK,
    checks = listOf(
        "浏览器登录同一账号时，出现「异常活动」或「需要验证」提示。",
        "最近频繁更换网络节点，或短时间内发出大量请求。",
        "同一账号在其它设备上也被拦下。"
    ),
    fixes = listOf(
        "在浏览器完成 Google 的验证流程。",
        "验证通过后，重新用 Cookie-Editor 导出该账号的 Cookie 并导入。",
        "暂停这个账号 30 分钟，降低请求频率后再试。"
    )
)

private val COOKIE_GUIDE = ErrorGuide(
    kind = ErrorKind.COOKIE,
    checks = listOf(
        "账号卡片显示「登录失效」。",
        "最近改过密码、退出过登录，或清理过浏览器数据。",
        "导出的内容不是完整 JSON，而是链接或截断的一段文字。"
    ),
    fixes = listOf(
        "在浏览器重新登录 AI Studio。",
        "用 Cookie-Editor 导出 JSON 并复制。",
        "回到账号页重新导入该账号的 Cookie。"
    )
)

private val RATE_GUIDE = ErrorGuide(
    kind = ErrorKind.RATE,
    checks = listOf(
        "报错里带 429 或配额字样。",
        "同一个模型在短时间内被大量请求。",
        "账号卡片显示「429 冷却 Ns」或「429 限流」。"
    ),
    fixes = listOf(
        "等冷却结束，额度在每天下午 4 点（北京时间）重置。",
        "切换到其它可用账号继续。",
        "减少同时发出的请求数，或换用其它模型。"
    )
)

private val UPSTREAM_GUIDE = ErrorGuide(
    kind = ErrorKind.UPSTREAM,
    checks = listOf(
        "报错状态码是 500、502、503 或 504。",
        "反代页显示「反代服务正在运行」，端口正常监听。",
        "所有账号在同一时间都失败。"
    ),
    fixes = listOf(
        "等待几分钟后重试。",
        "在反代页点击「停止反代服务」，再点击「重启反代」，重新发起请求。",
        "持续出现时，把完整报错发给下方的 AI 排障助手提问；仍然没有结论就把完整日志发到群里。"
    )
)

private val UNKNOWN_GUIDE = ErrorGuide(
    kind = ErrorKind.UNKNOWN,
    checks = listOf(
        "完整复制报错原文，包含状态码与整句英文。",
        "确认报错来自反代，而不是酒馆自己的报错。",
        "查看账号页是否同时有「登录失效」或「429」标记。"
    ),
    fixes = listOf(
        "把报错原文发给下方的 AI 排障助手提问。",
        "在反代页点击「停止反代服务」，再点击「重启反代」后重试。",
        "仍然无法解决时，把完整日志发到群里。"
    )
)

private val ALL_GUIDES: Map<ErrorKind, ErrorGuide> = listOf(
    REGION_GUIDE,
    RISK_GUIDE,
    COOKIE_GUIDE,
    RATE_GUIDE,
    UPSTREAM_GUIDE,
    UNKNOWN_GUIDE
).associateBy { it.kind }

/**
 * 取某个判定结果对应的自查与解决办法；未收录的 kind 一律回落到 UNKNOWN，不返回空列表。
 */
fun guideFor(kind: ErrorKind): ErrorGuide = ALL_GUIDES[kind] ?: UNKNOWN_GUIDE
