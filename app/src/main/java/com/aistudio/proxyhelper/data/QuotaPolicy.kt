package com.aistudio.proxyhelper.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

object QuotaPolicyResolver {

    val STANDARD_TIERS = listOf("Free", "Plus", "Pro", "Ultra", "Ultra_20X")

    fun normalizeTier(raw: String?): String {
        val t = raw.orEmpty().trim().lowercase()
        return when {
            t == "ultra_20x" || t == "ultra20x" || t.contains("20x") -> "Ultra_20X"
            t.contains("ultra") -> "Ultra"
            t.contains("pro") -> "Pro"
            t.contains("plus") -> "Plus"
            else -> "Free"
        }
    }

    fun builtInDefaultPolicy(): QuotaPolicySnapshot {
        val free = TierQuotaConfig(
            tierName = "Free",
            rules = listOf(
                TierQuotaRuleEntry("hydrogen_models", ModelMatchRule(contains = listOf("hydrogen")), 50, 10_000_000L),
                TierQuotaRuleEntry("flash_models", ModelMatchRule(contains = listOf("flash")), 50, 5_000_000L),
                TierQuotaRuleEntry("pro_models", ModelMatchRule(contains = listOf("pro")), 10, null)
            ),
            defaultLimit = 20
        )
        val plus = TierQuotaConfig(
            tierName = "Plus",
            rules = listOf(
                TierQuotaRuleEntry("hydrogen_models", ModelMatchRule(contains = listOf("hydrogen")), 50, 10_000_000L),
                TierQuotaRuleEntry("flash_models", ModelMatchRule(contains = listOf("flash")), 100, 10_000_000L),
                TierQuotaRuleEntry("pro_models", ModelMatchRule(contains = listOf("pro")), 30, null)
            ),
            defaultLimit = 50
        )
        val pro = TierQuotaConfig(
            tierName = "Pro",
            rules = listOf(
                TierQuotaRuleEntry("hydrogen_models", ModelMatchRule(contains = listOf("hydrogen")), 100, 25_000_000L),
                TierQuotaRuleEntry("argon_models", ModelMatchRule(contains = listOf("argon", "neon")), 50, 15_000_000L),
                TierQuotaRuleEntry("image_models", ModelMatchRule(contains = listOf("image", "imagen")), 50, null),
                TierQuotaRuleEntry("flash_models", ModelMatchRule(contains = listOf("flash")), 150, 25_000_000L),
                TierQuotaRuleEntry("pro_models", ModelMatchRule(contains = listOf("pro")), 50, 13_000_000L)
            ),
            defaultLimit = 50
        )
        val ultra = TierQuotaConfig(
            tierName = "Ultra",
            rules = listOf(
                TierQuotaRuleEntry("hydrogen_models", ModelMatchRule(contains = listOf("hydrogen")), 200, null),
                TierQuotaRuleEntry("argon_models", ModelMatchRule(contains = listOf("argon", "neon")), 200, null),
                TierQuotaRuleEntry("carbon_models", ModelMatchRule(contains = listOf("carbon")), 200, null),
                TierQuotaRuleEntry("image_models", ModelMatchRule(contains = listOf("image", "imagen")), 200, null),
                TierQuotaRuleEntry("video_models", ModelMatchRule(contains = listOf("veo")), 50, null),
                TierQuotaRuleEntry("flash_models", ModelMatchRule(contains = listOf("flash")), 200, null),
                TierQuotaRuleEntry("pro_models", ModelMatchRule(contains = listOf("pro")), 100, null)
            ),
            defaultLimit = 200
        )
        val ultra20x = ultra.copy(tierName = "Ultra_20X")
        val paidRules = listOf(
            PaidModelRuleEntry(
                ruleKey = "ultra_required",
                match = ModelMatchRule(contains = listOf("veo", "ultra", "omni", "carbon")),
                requiredTiers = listOf("Ultra", "Ultra_20X")
            ),
            PaidModelRuleEntry(
                ruleKey = "pro_required",
                match = ModelMatchRule(contains = listOf("argon", "neon", "image", "imagen", "gemini-3.1-pro")),
                requiredTiers = listOf("Pro", "Ultra", "Ultra_20X")
            )
        )
        return QuotaPolicySnapshot(
            tierOrder = STANDARD_TIERS,
            tiers = mapOf(
                "Free" to free,
                "Plus" to plus,
                "Pro" to pro,
                "Ultra" to ultra,
                "Ultra_20X" to ultra20x
            ),
            overrides = emptyMap(),
            paidRules = paidRules,
            syncedFromServer = false
        )
    }

    fun fromConfigSources(
        defaultsJson: JSONObject?,
        yamlContent: String?
    ): QuotaPolicySnapshot {
        val base = builtInDefaultPolicy()
        var tiers = base.tiers
        var tierOrder = base.tierOrder
        var overrides = base.overrides
        var paidRules = base.paidRules
        var synced = false

        if (defaultsJson != null) {
            defaultsJson.optJSONObject("tier_quotas")?.let { tq ->
                val parsed = parseTierQuotasFromMap(jsonObjectToMap(tq))
                if (parsed.tiers.isNotEmpty()) {
                    tiers = parsed.tiers
                    tierOrder = parsed.tierOrder
                    overrides = parsed.overrides
                    synced = true
                }
            }
            defaultsJson.optJSONObject("paid_model_rules")?.let { pmr ->
                val parsedPaid = parsePaidModelRulesFromMap(jsonObjectToMap(pmr))
                if (parsedPaid.isNotEmpty()) {
                    paidRules = parsedPaid
                    synced = true
                }
            }
        }

        if (!yamlContent.isNullOrBlank()) {
            val yamlMap = runCatching { SimpleYamlParser.parseRootMap(yamlContent) }.getOrNull()
            if (yamlMap != null) {
                @Suppress("UNCHECKED_CAST")
                val rawTq = yamlMap["tier_quotas"] as? Map<String, Any?>
                if (rawTq != null) {
                    val parsed = parseTierQuotasFromMap(rawTq)
                    if (parsed.tiers.isNotEmpty()) {
                        tiers = parsed.tiers
                        tierOrder = parsed.tierOrder
                        overrides = parsed.overrides
                        synced = true
                    }
                }
                @Suppress("UNCHECKED_CAST")
                val rawPaid = yamlMap["paid_model_rules"] as? Map<String, Any?>
                if (rawPaid != null) {
                    val parsedPaid = parsePaidModelRulesFromMap(rawPaid)
                    if (parsedPaid.isNotEmpty()) {
                        paidRules = parsedPaid
                        synced = true
                    }
                }
            }
        }

        return QuotaPolicySnapshot(
            tierOrder = tierOrder,
            tiers = tiers,
            overrides = overrides,
            paidRules = paidRules,
            syncedFromServer = synced
        )
    }

    fun resolveQuotaForAccountModel(
        policy: QuotaPolicySnapshot,
        tier: String?,
        modelId: String?
    ): Pair<Int, Long?> {
        val cleanModel = modelId.orEmpty().trim().removePrefix("models/").lowercase()
        val normTier = normalizeTier(tier)
        val tierCfg = policy.tiers.entries.firstOrNull {
            it.key.equals(normTier, ignoreCase = true)
        }?.value ?: policy.tiers["Free"] ?: TierQuotaConfig("Free")

        var dailyLimit = tierCfg.defaultLimit
        var tokenLimit = tierCfg.defaultTokenLimit
        if (cleanModel.isNotEmpty()) {
            for (rule in tierCfg.rules) {
                if (rule.match.matches(cleanModel)) {
                    dailyLimit = rule.dailyLimit
                    tokenLimit = rule.dailyTokenLimit ?: tierCfg.defaultTokenLimit
                    break
                }
            }
            val ov = policy.overrides[cleanModel]
            if (ov != null) {
                if (ov.dailyLimit != null && ov.dailyLimit > 0) {
                    dailyLimit = ov.dailyLimit
                }
                if (ov.dailyTokenLimit != null && ov.dailyTokenLimit > 0) {
                    tokenLimit = ov.dailyTokenLimit
                }
            }
        }
        return dailyLimit to tokenLimit
    }

    fun requiredTiersForModel(
        policy: QuotaPolicySnapshot,
        modelId: String
    ): List<String> {
        val clean = modelId.trim().removePrefix("models/").lowercase()
        for (rule in policy.paidRules) {
            if (rule.match.matches(clean) && rule.requiredTiers.isNotEmpty()) {
                return rule.requiredTiers
            }
        }
        return emptyList()
    }

    fun isModelAllowedForTier(
        policy: QuotaPolicySnapshot,
        modelId: String,
        tier: String?
    ): Boolean {
        val reqTiers = requiredTiersForModel(policy, modelId)
        if (reqTiers.isEmpty()) return true
        val normAccTier = normalizeTier(tier).lowercase()
        return reqTiers.any { normalizeTier(it).lowercase() == normAccTier }
    }

    fun ruleDisplayLabel(ruleKey: String): String {
        return when (ruleKey.lowercase()) {
            "hydrogen_models" -> "Hydrogen 旗舰"
            "argon_models" -> "Argon / Neon"
            "carbon_models" -> "Carbon 旗舰"
            "image_models" -> "生图模型 (Image)"
            "video_models" -> "视频模型 (Veo)"
            "flash_models" -> "Flash 系列"
            "pro_models" -> "Pro 系列"
            else -> ruleKey
        }
    }

    fun formatTokenCount(tokens: Long?): String {
        if (tokens == null || tokens <= 0L) return "0"
        return when {
            tokens >= 1_000_000L -> {
                val m = tokens / 1_000_000.0
                if (tokens % 1_000_000L == 0L) "${tokens / 1_000_000L}M"
                else String.format(Locale.US, "%.1fM", m)
            }
            tokens >= 1_000L -> {
                val k = tokens / 1_000.0
                if (tokens % 1_000L == 0L) "${tokens / 1_000L}K"
                else String.format(Locale.US, "%.1fK", k)
            }
            else -> tokens.toString()
        }
    }

    fun pacificResetCountdown(): String {
        return try {
            val zone = ZoneId.of("America/Los_Angeles")
            val now = ZonedDateTime.now(zone)
            val nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay(zone)
            val mins = Duration.between(now, nextMidnight).toMinutes().coerceAtLeast(0L)
            val h = mins / 60
            val m = mins % 60
            "${h} 小时 ${m} 分"
        } catch (_: Exception) {
            "每天 00:00 PST 刷新"
        }
    }

    private data class ParsedTierQuotas(
        val tierOrder: List<String>,
        val tiers: Map<String, TierQuotaConfig>,
        val overrides: Map<String, QuotaOverrideEntry>
    )

    @Suppress("UNCHECKED_CAST")
    private fun parseTierQuotasFromMap(raw: Map<String, Any?>): ParsedTierQuotas {
        val tiers = linkedMapOf<String, TierQuotaConfig>()
        val order = mutableListOf<String>()
        val overrides = linkedMapOf<String, QuotaOverrideEntry>()

        for ((key, value) in raw) {
            if (key == "overrides") {
                val ovMap = value as? Map<String, Any?> ?: continue
                for ((mKey, ovVal) in ovMap) {
                    val cleanModel = mKey.trim().removePrefix("models/").lowercase()
                    if (cleanModel.isEmpty()) continue
                    when (ovVal) {
                        is Number -> {
                            val d = ovVal.toInt()
                            if (d > 0) overrides[cleanModel] = QuotaOverrideEntry(cleanModel, d, null)
                        }
                        is Map<*, *> -> {
                            val d = asPositiveInt(ovVal["daily_limit"])
                            val tok = asPositiveLong(ovVal["daily_token_limit"])
                            if (d != null || tok != null) {
                                overrides[cleanModel] = QuotaOverrideEntry(cleanModel, d, tok)
                            }
                        }
                    }
                }
                continue
            }

            val tierMap = value as? Map<String, Any?> ?: continue
            val defaultLimit = asPositiveInt(tierMap["default_limit"]) ?: 20
            val defaultTokenLimit = asPositiveLong(tierMap["default_token_limit"])
            val rules = mutableListOf<TierQuotaRuleEntry>()

            for ((rKey, rVal) in tierMap) {
                if (rKey == "default_limit" || rKey == "default_token_limit") continue
                val ruleMap = rVal as? Map<String, Any?> ?: continue
                val matchMap = ruleMap["match"] as? Map<String, Any?> ?: emptyMap()
                val match = ModelMatchRule(
                    exact = asStringList(matchMap["exact"]),
                    prefixes = asStringList(matchMap["prefixes"]),
                    contains = asStringList(matchMap["contains"])
                )
                val dailyLimit = asPositiveInt(ruleMap["daily_limit"]) ?: defaultLimit
                val dailyTokenLimit = if (ruleMap.containsKey("daily_token_limit")) {
                    asPositiveLong(ruleMap["daily_token_limit"])
                } else {
                    defaultTokenLimit
                }
                rules.add(TierQuotaRuleEntry(rKey, match, dailyLimit, dailyTokenLimit))
            }

            order.add(key)
            tiers[key] = TierQuotaConfig(
                tierName = key,
                rules = rules,
                defaultLimit = defaultLimit,
                defaultTokenLimit = defaultTokenLimit
            )
        }
        return ParsedTierQuotas(order, tiers, overrides)
    }

    @Suppress("UNCHECKED_CAST")
    private fun parsePaidModelRulesFromMap(raw: Map<String, Any?>): List<PaidModelRuleEntry> {
        val list = mutableListOf<PaidModelRuleEntry>()
        for ((rKey, rVal) in raw) {
            val map = rVal as? Map<String, Any?> ?: continue
            val match = ModelMatchRule(
                exact = asStringList(map["exact"]),
                prefixes = asStringList(map["prefixes"]),
                contains = asStringList(map["contains"])
            )
            val reqTiers = asStringList(map["required_tiers"])
            if (reqTiers.isNotEmpty()) {
                list.add(PaidModelRuleEntry(rKey, match, reqTiers))
            }
        }
        return list
    }

    private fun jsonObjectToMap(obj: JSONObject): Map<String, Any?> {
        val map = linkedMapOf<String, Any?>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            map[k] = jsonValueToKotlin(obj.opt(k))
        }
        return map
    }

    private fun jsonArrayToList(arr: JSONArray): List<Any?> {
        val list = ArrayList<Any?>(arr.length())
        for (i in 0 until arr.length()) {
            list.add(jsonValueToKotlin(arr.opt(i)))
        }
        return list
    }

    private fun jsonValueToKotlin(value: Any?): Any? {
        return when (value) {
            null, JSONObject.NULL -> null
            is JSONObject -> jsonObjectToMap(value)
            is JSONArray -> jsonArrayToList(value)
            else -> value
        }
    }

    private fun asStringList(raw: Any?): List<String> {
        val list = raw as? List<*> ?: return emptyList()
        return list.mapNotNull { it?.toString()?.trim()?.takeIf { s -> s.isNotEmpty() } }
    }

    private fun asPositiveInt(raw: Any?): Int? {
        return when (raw) {
            is Number -> raw.toInt().takeIf { it > 0 }
            is String -> raw.trim().toIntOrNull()?.takeIf { it > 0 }
            else -> null
        }
    }

    private fun asPositiveLong(raw: Any?): Long? {
        return when (raw) {
            is Number -> raw.toLong().takeIf { it > 0L }
            is String -> raw.trim().toLongOrNull()?.takeIf { it > 0L }
            else -> null
        }
    }
}

/**
 * Lightweight indentation-based YAML parser for syncing config.yaml sections
 * (supports PyYAML unindented lists, inline flow lists/maps, numbers, booleans, quotes, comments).
 */
object SimpleYamlParser {

    private data class YamlLine(val indent: Int, val text: String)

    private class Cursor(val lines: List<YamlLine>, var index: Int = 0) {
        fun peek(): YamlLine? = lines.getOrNull(index)
        fun next(): YamlLine = lines[index++]
    }

    fun parseRootMap(yaml: String): Map<String, Any?> {
        val lines = preprocess(yaml)
        if (lines.isEmpty()) return emptyMap()
        val cursor = Cursor(lines)
        val root = parseMapping(cursor, lines[0].indent)
        return root
    }

    private fun preprocess(yaml: String): List<YamlLine> {
        val result = mutableListOf<YamlLine>()
        for (rawLine in yaml.lineSequence()) {
            val expanded = rawLine.replace("\t", "  ")
            val stripped = stripCommentOutsideQuotes(expanded).trimEnd()
            if (stripped.isBlank()) continue
            val indent = stripped.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
            val text = stripped.substring(indent)
            if (text.isNotEmpty()) {
                result.add(YamlLine(indent, text))
            }
        }
        return result
    }

    private fun parseMapping(cursor: Cursor, baseIndent: Int): Map<String, Any?> {
        val map = linkedMapOf<String, Any?>()
        while (true) {
            val line = cursor.peek() ?: break
            if (line.indent < baseIndent) break
            if (line.indent > baseIndent) {
                // Unexpected extra indent; consume to avoid infinite loop
                cursor.next()
                continue
            }
            if (line.text == "-" || line.text.startsWith("- ")) break

            val colonIdx = findKeyColonIndex(line.text)
            if (colonIdx <= 0) {
                cursor.next()
                continue
            }

            cursor.next()
            val key = unquote(line.text.substring(0, colonIdx).trim())
            val rest = line.text.substring(colonIdx + 1).trim()

            if (rest.isNotEmpty()) {
                map[key] = parseInlineValue(rest)
            } else {
                val next = cursor.peek()
                map[key] = when {
                    next == null -> null
                    (next.text == "-" || next.text.startsWith("- ")) && next.indent >= baseIndent ->
                        parseSequence(cursor, next.indent)
                    next.indent > baseIndent ->
                        parseMapping(cursor, next.indent)
                    else -> null
                }
            }
        }
        return map
    }

    private fun parseSequence(cursor: Cursor, listIndent: Int): List<Any?> {
        val list = mutableListOf<Any?>()
        while (true) {
            val line = cursor.peek() ?: break
            if (line.indent != listIndent) break
            if (line.text != "-" && !line.text.startsWith("- ")) break

            cursor.next()
            val afterDash = line.text.removePrefix("-").trim()
            if (afterDash.isEmpty()) {
                val next = cursor.peek()
                val itemVal = when {
                    next == null -> null
                    next.indent > listIndent && (next.text == "-" || next.text.startsWith("- ")) ->
                        parseSequence(cursor, next.indent)
                    next.indent > listIndent ->
                        parseMapping(cursor, next.indent)
                    else -> null
                }
                list.add(itemVal)
            } else {
                val colonIdx = findKeyColonIndex(afterDash)
                if (colonIdx > 0) {
                    val itemMap = linkedMapOf<String, Any?>()
                    val k = unquote(afterDash.substring(0, colonIdx).trim())
                    val vStr = afterDash.substring(colonIdx + 1).trim()
                    if (vStr.isNotEmpty()) {
                        itemMap[k] = parseInlineValue(vStr)
                    } else {
                        val next = cursor.peek()
                        itemMap[k] = when {
                            next == null -> null
                            (next.text == "-" || next.text.startsWith("- ")) && next.indent > listIndent ->
                                parseSequence(cursor, next.indent)
                            next.indent > listIndent ->
                                parseMapping(cursor, next.indent)
                            else -> null
                        }
                    }
                    val next = cursor.peek()
                    if (next != null && next.indent > listIndent && next.text != "-" && !next.text.startsWith("- ")) {
                        itemMap.putAll(parseMapping(cursor, next.indent))
                    }
                    list.add(itemMap)
                } else {
                    list.add(parseInlineValue(afterDash))
                }
            }
        }
        return list
    }

    private fun parseInlineValue(raw: String): Any? {
        val s = raw.trim()
        if (s == "null" || s == "~") return null
        if (s == "true" || s == "True" || s == "TRUE") return true
        if (s == "false" || s == "False" || s == "FALSE") return false
        if (s == "[]") return emptyList<Any?>()
        if (s == "{}") return emptyMap<String, Any?>()
        if (s.startsWith("[") && s.endsWith("]")) {
            val inner = s.substring(1, s.length - 1).trim()
            if (inner.isEmpty()) return emptyList<Any?>()
            return splitFlowItems(inner).map { parseInlineValue(it) }
        }
        if ((s.startsWith("\"") && s.endsWith("\"") && s.length >= 2) ||
            (s.startsWith("'") && s.endsWith("'") && s.length >= 2)
        ) {
            return unquote(s)
        }
        s.toLongOrNull()?.let { return it }
        s.toDoubleOrNull()?.let { return it }
        return s
    }

    private fun splitFlowItems(inner: String): List<String> {
        val items = mutableListOf<String>()
        val sb = StringBuilder()
        var quote: Char? = null
        for (ch in inner) {
            if (quote != null) {
                sb.append(ch)
                if (ch == quote) quote = null
            } else {
                when (ch) {
                    '\'', '"' -> {
                        quote = ch
                        sb.append(ch)
                    }
                    ',' -> {
                        items.add(sb.toString().trim())
                        sb.clear()
                    }
                    else -> sb.append(ch)
                }
            }
        }
        val tail = sb.toString().trim()
        if (tail.isNotEmpty()) items.add(tail)
        return items
    }

    private fun findKeyColonIndex(text: String): Int {
        var quote: Char? = null
        for (i in text.indices) {
            val ch = text[i]
            if (quote != null) {
                if (ch == quote) quote = null
            } else {
                when (ch) {
                    '\'', '"' -> quote = ch
                    ':' -> {
                        val nextIsSpaceOrEnd = i == text.lastIndex || text[i + 1].isWhitespace()
                        if (nextIsSpaceOrEnd) return i
                    }
                }
            }
        }
        return -1
    }

    private fun stripCommentOutsideQuotes(line: String): String {
        var quote: Char? = null
        for (i in line.indices) {
            val ch = line[i]
            if (quote != null) {
                if (ch == quote) quote = null
            } else {
                when (ch) {
                    '\'', '"' -> quote = ch
                    '#' -> {
                        if (i == 0 || line[i - 1].isWhitespace()) {
                            return line.substring(0, i)
                        }
                    }
                }
            }
        }
        return line
    }

    private fun unquote(s: String): String {
        if (s.length >= 2 &&
            ((s.startsWith("\"") && s.endsWith("\"")) ||
                (s.startsWith("'") && s.endsWith("'")))
        ) {
            return s.substring(1, s.length - 1)
        }
        return s
    }
}
