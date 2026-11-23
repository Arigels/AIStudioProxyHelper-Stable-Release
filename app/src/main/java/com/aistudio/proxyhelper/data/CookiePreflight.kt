package com.aistudio.proxyhelper.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 导入前的本地 Cookie 结构预检（完全离线，不访问网络）。
 *
 * 目的：把「复制成了链接」「JSON 被截断」「只导了 aistudio 子域的游客 Cookie」
 * 这类问题在提交给反代服务之前就拦下来，并把服务端返回的原文错误
 * 翻译成用户能照着做的检查清单。
 *
 * 只收集 cookie 的 **name / domain**，绝不记录 value，用于日志排障。
 */
object CookiePreflight {

    /** 判定「已登录 Google」的核心字段：至少命中其中一个才认为这份 Cookie 有登录态。 */
    private val PASS_KEYS = listOf(
        "SID", "HSID", "SSID", "APISID", "SAPISID",
        "__Secure-1PSID", "__Secure-3PSID"
    )

    /** 只用于排障展示的扩展字段（不参与判定）。 */
    private val EXTRA_LOGIN_KEYS = listOf(
        "__Secure-1PAPISID", "__Secure-3PAPISID",
        "__Secure-1PSIDTS", "__Secure-3PSIDTS",
        "__Secure-1PSIDCC", "__Secure-3PSIDCC"
    )

    val LOGIN_KEYS: List<String> = PASS_KEYS + EXTRA_LOGIN_KEYS

    data class Result(
        val ok: Boolean,
        /** 只含 cookie 名，绝不含 value。 */
        val names: List<String> = emptyList(),
        val cookieCount: Int = 0,
        val domains: Set<String> = emptySet(),
        /** ok=false 时给用户看的可执行提示；ok=true 时为 null。 */
        val errorChecklist: String? = null
    ) {
        /** 命中的登录态字段（只含名字）。 */
        val loginKeyHits: List<String>
            get() = names.filter { it in PASS_KEYS }

        /** 命中但只算辅助的登录态字段。 */
        val extraLoginKeyHits: List<String>
            get() = names.filter { it in EXTRA_LOGIN_KEYS }

        /** 只有辅助字段、没有核心字段——服务端大概率仍然 401。 */
        val onlyExtraLoginKeys: Boolean
            get() = loginKeyHits.isEmpty() && extraLoginKeyHits.isNotEmpty()

        /** 排障用：一行字段名列表（不含 value）。 */
        fun debugSummary(): String =
            "条数=$cookieCount；字段=${names.joinToString(",")}；域名=${domains.joinToString(",")}"
    }

    // ── 各类失败提示（都写成可执行清单，而不是一句报错）────────────────────────

    private val EMPTY_TIP = """
        粘贴框是空的。
        - 先在浏览器里用 Cookie-Editor 点 Export → Export as JSON
        - 回 App 点「粘贴剪贴板」，不要点进输入框用输入法粘贴
    """.trimIndent()

    private val LINK_TIP = """
        这是一个链接，不是 Cookie JSON。
        - 「复制 AI Studio 链接」会把剪贴板里的 Cookie 覆盖成网址
        - 请回到 AI Studio 页面，用 Cookie-Editor 点 Export → Export as JSON
        - 再回 App 点「粘贴剪贴板」
    """.trimIndent()

    private val TRUNCATED_TIP = """
        这段 JSON 不完整（多半被截断了）。
        - 优先点「粘贴剪贴板」按钮，别用输入法键盘的剪贴板历史（多数手机限 1000~5000 字）
        - 或把导出的内容存成 .json 文件，用「从文件导入」
    """.trimIndent()

    private val NOT_COOKIE_TIP = """
        这份内容不像 AI Studio 登录 Cookie：
        - 请确认已在 aistudio.google.com 登录 Google 账号
        - 在 Cookie-Editor 里选 Export → Export as JSON
        - 不要复制 AI Studio 链接或别的网页 JSON
    """.trimIndent()

    private val NO_LOGIN_TIP = """
        这份 Cookie 里没有登录态字段（SID / HSID / SSID / APISID / SAPISID / __Secure-1PSID）。
        常见的三种情况：
        1. 浏览器其实没登录：页面能打开 AI Studio，但右上角没有头像，导出的是游客 Cookie
        2. 导出的是别的网站：必须在 aistudio.google.com 页面里打开 Cookie-Editor 再导出
        3. 只导出了 AI Studio 子域的少量 Cookie：缺少 .google.com 的 SAPISID，服务端生成 SAPISIDHASH 时会 401
        处理办法：回浏览器 → 确认右上角头像就是要用的账号 → 在 aistudio.google.com 页面上重新 Export as JSON。
    """.trimIndent()

    private val BAD_DOMAIN_TIP = """
        这份 Cookie 不在 google.com 域下：
        - 请在 aistudio.google.com 页面打开 Cookie-Editor 再导出
        - 不要从别的网站、别的标签页或别的浏览器导出
    """.trimIndent()

    private fun fail(
        tip: String,
        names: List<String> = emptyList(),
        count: Int = 0,
        domains: Set<String> = emptySet()
    ) = Result(ok = false, names = names, cookieCount = count, domains = domains, errorChecklist = tip)

    /**
     * 解析并校验一份待导入的 Cookie 文本。
     * 支持三种形态：Cookie-Editor 的 JSON 数组、{"cookies":[...]} 包装、
     * 以及 `NAME=value; NAME2=value2` 的 Header String。
     */
    fun inspect(raw: String): Result {
        val text = raw.trim()
        if (text.isEmpty()) return fail(EMPTY_TIP)

        val lower = text.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://")) return fail(LINK_TIP)

        val names = mutableListOf<String>()
        val domains = mutableSetOf<String>()
        var count = 0

        if (text.startsWith("[") || text.startsWith("{")) {
            val parsed = runCatching {
                val array = if (text.startsWith("[")) {
                    JSONArray(text)
                } else {
                    val obj = JSONObject(text)
                    obj.optJSONArray("cookies")
                        // 单个 cookie 对象也放行，交给服务端判定
                        ?: JSONArray().put(obj)
                }
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    count++
                    item.optString("name").trim().takeIf { it.isNotEmpty() }?.let { names += it }
                    item.optString("domain").trim().takeIf { it.isNotEmpty() }
                        ?.let { domains += it.lowercase() }
                }
            }
            if (parsed.isFailure) return fail(TRUNCATED_TIP)
        } else {
            // Header String / Netscape：只认 name=value 段，不做 value 校验
            text.split(';', '\n').forEach { part ->
                val eq = part.indexOf('=')
                if (eq > 0) {
                    val name = part.substring(0, eq).trim()
                    if (name.isNotEmpty() && !name.contains(' ')) {
                        names += name
                        count++
                    }
                }
            }
            if (count == 0) return fail(NOT_COOKIE_TIP)
        }

        if (count == 0 || names.isEmpty()) return fail(NOT_COOKIE_TIP, names, count, domains)

        val hits = names.filter { it in PASS_KEYS }
        if (hits.isEmpty()) return fail(NO_LOGIN_TIP, names, count, domains)

        if (domains.isNotEmpty() && domains.none { it.contains("google.com") }) {
            return fail(BAD_DOMAIN_TIP, names, count, domains)
        }

        return Result(ok = true, names = names, cookieCount = count, domains = domains)
    }

    /**
     * 把服务端返回的登录态错误翻译成检查清单（其他错误原样返回）。
     * 服务端 `/api/accounts/probe-import` 原文：未探测到有效已登录 Google 账号。
     */
    fun translateServerError(raw: String): String {
        val text = raw
        val unauthorized = listOf(
            "未探测到有效", "未检测到有效", "有效已登录", "no valid",
            "401", "unauthorized", "session 无效", "session无效"
        ).any { text.contains(it, ignoreCase = true) }
        if (!unauthorized) return raw

        return """
            服务端没有从这份 Cookie 里探到有效账号，请依次检查：
            1. VPN 能不能打开 aistudio.google.com
            2. 浏览器里是否真的登录了 Google（右上角有头像）
            3. 导出的是不是 aistudio.google.com 页面的 Cookie-Editor → Export as JSON
            4. 账号是否被 Google 要求验证（unusual activity）
            5. 多账号时分别试 /u/0/ 和 /u/1/，确认 u/0 是要用的那个号
            （电脑上旧 Cookie 还能用，不代表这份新导出的 Cookie 有效，两者相互独立）
        """.trimIndent()
    }
}
