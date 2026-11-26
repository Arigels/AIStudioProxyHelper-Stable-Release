package com.aistudio.proxyhelper.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ErrorKind {
    REGION,
    RISK,
    COOKIE,
    RATE,
    UPSTREAM,
    UNKNOWN
}

data class ErrorFinding(
    val code: String,
    val kind: ErrorKind,
    val title: String,
    val cause: String,
    val nextStep: String,
    val rawSnippet: String = ""
)

/**
 * 集中错误判定器：按「状态码 + 关键片段」双条件识别，不依赖中文提示语。
 * 判定顺序固定：地区不支持 → 风控要求验证 → Cookie 失效 → 限流 → 上游异常 → 未知。
 */
object ErrorCodeRules {

    private val code403Regex = Regex("""\b403\b""")
    private val code401Regex = Regex("""\b401\b""")
    private val code429Regex = Regex("""\b429\b""")
    private val code5xxRegex = Regex("""\b(503|502|500|504)\b""")

    private val regionFragmentRegex = Regex(
        """(?i)(region\s+not\s+supported|unsupported\s+region|location\s+is\s+not\s+supported|not\s+available\s+in\s+your\s+(country|region)|country.{0,10}region|geo[_-]?restricted|region[_-]?restricted|unsupported\s+location|region_not_supported)"""
    )
    private val riskFragmentRegex = Regex(
        """(?i)(unusual\s+activity|verif(y|ication)|captcha|recaptcha|suspicious|security\s+check|account\s+(has\s+been\s+)?disabled|tos[_-]?violation|abuse|challenge|robot|risk)"""
    )
    private val cookieFragmentRegex = Regex(
        """(?i)(cookie|session\s*(expired|invalid|revoked)|expired|revoked|unauthenticated|unauthorized|not\s+logged\s+in|login\s+required|re-?auth|invalid[_-]?auth|invalid\s+credentials?|credential|sapisid|psid|auth\s*(failed|error))"""
    )
    private val rateFragmentRegex = Regex(
        """(?i)(rate\s*limit|ratelimit|too\s+many\s+requests|quota|resource[_-]?exhausted|limit\s+exceeded|throttl|try\s+again\s+later)"""
    )
    private val upstreamFragmentRegex = Regex(
        """(?i)(service\s+unavailable|temporarily\s+unavailable|unavailable|bad\s+gateway|gateway\s+timeout|internal\s+server\s+error|upstream|overloaded|backend\s+error)"""
    )

    private val errorLineHintRegex = Regex(
        """(?i)(\b(400|401|403|404|407|408|409|410|413|418|422|429|500|502|503|504|520|521|522|524)\b|\b(error|exception|traceback|fatal|panic|errno)\b|报错|异常|服务端拒绝|请求失败|连接失败)"""
    )

    fun analyze(input: String): ErrorFinding {
        val text = input.trim()
        if (text.isEmpty()) {
            return unknownFinding("")
        }

        val has403 = code403Regex.containsMatchIn(text)
        val has401 = code401Regex.containsMatchIn(text)

        if (has403 && regionFragmentRegex.containsMatchIn(text)) {
            return ErrorFinding(
                code = "403",
                kind = ErrorKind.REGION,
                title = "403（账号所在地区不支持，无法使用该服务）",
                cause = "账号所在地区不支持，无法使用该服务",
                nextStep = "更换支持地区的账号，或更换网络节点后重试。"
            )
        }

        if (has403 && riskFragmentRegex.containsMatchIn(text)) {
            return ErrorFinding(
                code = "403",
                kind = ErrorKind.RISK,
                title = "403（账号被 Google 判定为异常活动，需要完成验证）",
                cause = "账号被 Google 判定为异常活动，需要完成验证",
                nextStep = "在浏览器登录该账号完成验证，再重新导出并导入 Cookie。"
            )
        }

        if ((has401 || has403) && cookieFragmentRegex.containsMatchIn(text)) {
            return ErrorFinding(
                code = if (has401) "401" else "403",
                kind = ErrorKind.COOKIE,
                title = "401 / 403（Cookie 已失效或已被撤销）",
                cause = "Cookie 已失效或已被撤销",
                nextStep = "重新用 Cookie-Editor 导出并导入该账号的 Cookie。"
            )
        }

        if (code429Regex.containsMatchIn(text) && rateFragmentRegex.containsMatchIn(text)) {
            return ErrorFinding(
                code = "429",
                kind = ErrorKind.RATE,
                title = "429（请求过于频繁或额度耗尽，触发限流）",
                cause = "请求过于频繁或额度耗尽，触发限流",
                nextStep = "等待冷却自动结束，或切换到其它账号。"
            )
        }

        val match5xx = code5xxRegex.find(text)
        if (match5xx != null && upstreamFragmentRegex.containsMatchIn(text)) {
            return ErrorFinding(
                code = match5xx.value,
                kind = ErrorKind.UPSTREAM,
                title = "503（上游服务暂时异常，请稍后重试）",
                cause = "上游服务暂时异常或不可用",
                nextStep = "等待片刻后重试，或点击「立即检测」确认服务状态。"
            )
        }

        return unknownFinding(text)
    }

    /**
     * 扫描单行日志；普通业务日志返回 null，未收录的错误行返回 UNKNOWN。
     */
    fun scanLogLine(line: String): ErrorFinding? {
        val text = line.trim()
        if (text.isEmpty()) return null
        val finding = analyze(text)
        if (finding.kind != ErrorKind.UNKNOWN) return finding
        return if (errorLineHintRegex.containsMatchIn(text)) finding else null
    }

    fun formatTimeHHmm(timestampMs: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestampMs))

    fun formatSummaryLine(finding: ErrorFinding, timestampMs: Long): String =
        "最近错误 ${formatTimeHHmm(timestampMs)} · ${finding.title}"

    private fun unknownFinding(rawText: String): ErrorFinding = ErrorFinding(
        code = "unknown",
        kind = ErrorKind.UNKNOWN,
        title = "暂未识别（这条报错未收录）",
        cause = "这条报错未收录",
        nextStep = "",
        rawSnippet = rawText.take(200)
    )
}
