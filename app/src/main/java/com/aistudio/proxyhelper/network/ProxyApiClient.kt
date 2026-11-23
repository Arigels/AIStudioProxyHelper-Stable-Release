package com.aistudio.proxyhelper.network

import com.aistudio.proxyhelper.data.AccountSummary
import com.aistudio.proxyhelper.data.ApiKeySummary
import com.aistudio.proxyhelper.data.ImportResult
import com.aistudio.proxyhelper.data.ModelDailyQuotaDetail
import com.aistudio.proxyhelper.data.ProxySettings
import com.aistudio.proxyhelper.data.QuotaPolicyResolver
import com.aistudio.proxyhelper.data.QuotaPolicySnapshot
import com.aistudio.proxyhelper.data.ServiceStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ProxyApiClient {

    private val probeClient = OkHttpClient.Builder()
        .connectTimeout(2500, TimeUnit.MILLISECONDS)
        .readTimeout(4000, TimeUnit.MILLISECONDS)
        .writeTimeout(4000, TimeUnit.MILLISECONDS)
        .build()

    private val actionClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun fetchServiceStatus(settings: ProxySettings): ServiceStatus =
        withContext(Dispatchers.IO) {
            val base = settings.localBaseUrl
            val now = System.currentTimeMillis()

            // 1. Check /health (public)
            val healthOk = try {
                val req = Request.Builder().url("$base/health").get().build()
                probeClient.newCall(req).execute().use { resp ->
                    resp.isSuccessful
                }
            } catch (e: Exception) {
                return@withContext ServiceStatus(
                    online = false,
                    checking = false,
                    lastError = "服务未启动或端口 ${settings.port} 未监听",
                    lastCheckedAt = now
                )
            }

            if (!healthOk) {
                return@withContext ServiceStatus(
                    online = false,
                    checking = false,
                    lastError = "健康检查返回异常状态",
                    lastCheckedAt = now
                )
            }

            // 2. Check /config (protected if AISTUDIO_WEB_PASSWORD is set)
            var signerReady: Boolean? = null
            var signerRunning: Boolean? = null
            var authEnabled = false
            var authError: String? = null
            var yamlContent: String? = null

            runCatching {
                val req = Request.Builder()
                    .url("$base/config")
                    .applyWebAuth(settings)
                    .get()
                    .build()
                probeClient.newCall(req).execute().use { resp ->
                    if (resp.code == 401) {
                        authEnabled = true
                        authError = "管理后台已启用密码，请在设置中填写 Web 管理密码"
                    } else if (resp.isSuccessful) {
                        val body = resp.body?.string().orEmpty()
                        val obj = JSONObject(body)
                        signerReady = obj.optBoolean("signer_ready", false)
                        signerRunning = obj.optBoolean("signer_running", false)
                        authEnabled = obj.optBoolean("auth_enabled", false)
                        yamlContent = obj.optCleanString("yaml_content").takeIf { it.isNotBlank() }
                    }
                }
            }

            // 2.5 Check /config/defaults & merge with yaml_content so quota tables sync with Web Admin
            var defaultsJson: JSONObject? = null
            if (authError == null) {
                runCatching {
                    val req = Request.Builder()
                        .url("$base/config/defaults")
                        .applyWebAuth(settings)
                        .header("Accept", "application/json")
                        .get()
                        .build()
                    probeClient.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) {
                            defaultsJson = JSONObject(resp.body?.string().orEmpty())
                        }
                    }
                }
            }
            val quotaPolicy: QuotaPolicySnapshot = QuotaPolicyResolver.fromConfigSources(
                defaultsJson = defaultsJson,
                yamlContent = yamlContent
            )

            // 3. Check /api/accounts, /api/accounts/active, /rotation
            val rawAccounts = mutableListOf<AccountSummary>()
            runCatching {
                val req = Request.Builder()
                    .url("$base/api/accounts")
                    .applyWebAuth(settings)
                    .header("Accept", "application/json")
                    .get()
                    .build()
                probeClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val arr = JSONArray(resp.body?.string().orEmpty())
                        for (i in 0 until arr.length()) {
                            val item = arr.optJSONObject(i) ?: continue
                            val rawTier = listOf(
                                item.optCleanString("tier"),
                                item.optCleanString("plan"),
                                item.optCleanString("subscription_tier"),
                                item.optCleanString("quota_tier")
                            ).firstOrNull { it.isNotBlank() } ?: "Free"
                            rawAccounts.add(
                                AccountSummary(
                                    id = item.optCleanString("id"),
                                    name = item.optCleanString("name").ifBlank { "Google Account" },
                                    email = item.optCleanString("email").takeIf { it.isNotBlank() },
                                    authUser = item.optCleanString("auth_user").ifBlank { "0" },
                                    tier = rawTier,
                                    enabled = item.optBoolean("enabled", true),
                                    group = item.optCleanString("group").ifBlank { "default" },
                                    lastUsed = item.optCleanString("last_used").takeIf { it.isNotBlank() }
                                )
                            )
                        }
                    }
                }
            }

            var activeAccountId: String? = null
            runCatching {
                val req = Request.Builder()
                    .url("$base/api/accounts/active")
                    .applyWebAuth(settings)
                    .header("Accept", "application/json")
                    .get()
                    .build()
                probeClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val obj = JSONObject(resp.body?.string().orEmpty())
                        activeAccountId = obj.optCleanString("id").takeIf { it.isNotBlank() }
                    }
                }
            }

            var rotationEnabled = false
            val rotationByAccount = mutableMapOf<String, JSONObject>()
            runCatching {
                val req = Request.Builder()
                    .url("$base/rotation")
                    .applyWebAuth(settings)
                    .header("Accept", "application/json")
                    .get()
                    .build()
                probeClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val obj = JSONObject(resp.body?.string().orEmpty())
                        rotationEnabled = obj.optBoolean("enabled", false)
                        val accObj = obj.optJSONObject("accounts")
                        if (accObj != null) {
                            val keys = accObj.keys()
                            while (keys.hasNext()) {
                                val k = keys.next()
                                accObj.optJSONObject(k)?.let { rotationByAccount[k] = it }
                            }
                        }
                    }
                }
            }

            val accounts = rawAccounts.map { acc ->
                val rot = rotationByAccount[acc.id] ?: return@map acc
                val todayModels = mutableMapOf<String, ModelDailyQuotaDetail>()
                val todayObj = rot.optJSONObject("today_models")
                if (todayObj != null) {
                    val mKeys = todayObj.keys()
                    while (mKeys.hasNext()) {
                        val rawModel = mKeys.next()
                        val mObj = todayObj.optJSONObject(rawModel) ?: continue
                        val cleanModel = rawModel.removePrefix("models/").lowercase()
                        val tokenQuota = if (mObj.isNull("configured_token_quota")) {
                            null
                        } else {
                            mObj.optLong("configured_token_quota", 0L).takeIf { it > 0L }
                        }
                        todayModels[cleanModel] = ModelDailyQuotaDetail(
                            modelId = cleanModel,
                            requests = mObj.optInt("requests", 0),
                            success = mObj.optInt("success", 0),
                            rateLimited = mObj.optInt("rate_limited", 0),
                            errors = mObj.optInt("errors", 0),
                            totalTokens = mObj.optLong("total_tokens", 0L),
                            reasoningTokens = mObj.optLong("reasoning_tokens", 0L),
                            cachedTokens = mObj.optLong("cached_tokens", 0L),
                            configuredQuota = mObj.optInt("configured_quota", 20).coerceAtLeast(1),
                            configuredTokenQuota = tokenQuota,
                            quotaUsedPercent = mObj.optDouble("quota_used_percent", 0.0),
                            tokenQuotaUsedPercent = mObj.optDouble("token_quota_used_percent", 0.0),
                            cooldownRemainingSec = mObj.optInt("cooldown_remaining", 0),
                            dripMode = mObj.optBoolean("drip_mode", false),
                            isAvailable = mObj.optBoolean("is_available", true)
                        )
                    }
                }

                val modelCooldowns = mutableMapOf<String, Int>()
                val cdObj = rot.optJSONObject("model_cooldowns")
                if (cdObj != null) {
                    val cdKeys = cdObj.keys()
                    while (cdKeys.hasNext()) {
                        val mk = cdKeys.next()
                        val sec = cdObj.optInt(mk, 0)
                        if (sec > 0) modelCooldowns[mk.removePrefix("models/").lowercase()] = sec
                    }
                }

                val todayReqs = if (rot.has("today_total_requests")) {
                    rot.optInt("today_total_requests", 0)
                } else {
                    todayModels.values.sumOf { it.requests }
                }
                val todayToks = if (rot.has("today_total_tokens")) {
                    rot.optLong("today_total_tokens", 0L)
                } else {
                    todayModels.values.sumOf { it.totalTokens }
                }

                acc.copy(
                    totalRequests = rot.optInt("requests", acc.totalRequests),
                    totalSuccess = rot.optInt("success", acc.totalSuccess),
                    totalRateLimited = rot.optInt("rate_limited", acc.totalRateLimited),
                    totalErrors = rot.optInt("errors", acc.totalErrors),
                    todayTotalRequests = todayReqs,
                    todayTotalTokens = todayToks,
                    lastUsed = rot.optCleanString("last_used").takeIf { it.isNotBlank() } ?: acc.lastUsed,
                    isAvailable = rot.optBoolean("is_available", acc.enabled),
                    sessionExpired = rot.optBoolean("session_expired", false),
                    cooldownRemainingSec = rot.optInt("cooldown_remaining", 0),
                    modelCooldowns = modelCooldowns,
                    todayModels = todayModels
                )
            }

            // 3.5 Check /api-keys
            val apiKeys = mutableListOf<ApiKeySummary>()
            runCatching {
                val req = Request.Builder()
                    .url("$base/api-keys")
                    .applyWebAuth(settings)
                    .header("Accept", "application/json")
                    .get()
                    .build()
                probeClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val arr = JSONArray(resp.body?.string().orEmpty())
                        for (i in 0 until arr.length()) {
                            val item = arr.optJSONObject(i) ?: continue
                            val keyVal = item.optCleanString("key")
                            if (keyVal.isNotBlank()) {
                                apiKeys.add(
                                    ApiKeySummary(
                                        name = item.optCleanString("name").ifBlank { "API Key" },
                                        key = keyVal,
                                        createdAt = item.optCleanString("created_at").takeIf { it.isNotBlank() }
                                    )
                                )
                            }
                        }
                    }
                }
            }

            // 4. Check /v1beta/models
            val models = mutableListOf<String>()
            runCatching {
                val apiKey = apiKeys.firstOrNull()?.key
                    ?: settings.webPassword.takeIf { it.isNotBlank() }
                    ?: settings.apiTestKey
                val req = Request.Builder()
                    .url("$base/v1beta/models")
                    .header("x-goog-api-key", apiKey)
                    .get()
                    .build()
                probeClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val obj = JSONObject(resp.body?.string().orEmpty())
                        val arr = obj.optJSONArray("models")
                        if (arr != null) {
                            for (i in 0 until arr.length()) {
                                val m = arr.optJSONObject(i) ?: continue
                                val name = m.optCleanString("name").removePrefix("models/")
                                if (name.isNotBlank()) {
                                    models.add(name)
                                }
                            }
                        }
                    }
                }
            }

            ServiceStatus(
                online = true,
                checking = false,
                signerReady = signerReady,
                signerRunning = signerRunning,
                authEnabled = authEnabled,
                accounts = accounts,
                activeAccountId = activeAccountId,
                rotationEnabled = rotationEnabled,
                quotaPolicy = quotaPolicy,
                apiKeys = apiKeys,
                models = models,
                lastError = authError,
                lastCheckedAt = now
            )
        }

    suspend fun probeAndImportCookies(
        settings: ProxySettings,
        rawCookies: String,
        namePrefix: String? = null
    ): ImportResult = withContext(Dispatchers.IO) {
        val cleaned = rawCookies.trim()
        if (cleaned.isEmpty()) {
            return@withContext ImportResult(false, "请先粘贴或提取 Cookie 内容")
        }

        val payload = JSONObject().apply {
            put("cookies", cleaned)
            if (!namePrefix.isNullOrBlank()) {
                put("name_prefix", namePrefix.trim())
            }
        }

        val req = Request.Builder()
            .url("${settings.localBaseUrl}/api/accounts/probe-import")
            .applyWebAuth(settings)
            .header("Accept", "application/json")
            .post(payload.toString().toRequestBody(jsonMediaType))
            .build()

        return@withContext try {
            actionClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.isSuccessful) {
                    val obj = JSONObject(body)
                    val count = obj.optInt("imported_count", 0)
                    ImportResult(
                        success = true,
                        message = "探活成功！已自动识别并导入 $count 个 Google 账号",
                        importedCount = count
                    )
                } else {
                    val detail = extractErrorMessage(body, resp.code)
                    ImportResult(false, "自动探活导入失败：$detail")
                }
            }
        } catch (e: Exception) {
            ImportResult(false, "无法连接本机服务（127.0.0.1:${settings.port}），请先到「搭建」页启动服务后再试。")
        }
    }

    suspend fun importCookiesDirect(
        settings: ProxySettings,
        rawCookies: String,
        name: String? = null
    ): ImportResult = withContext(Dispatchers.IO) {
        val cleaned = rawCookies.trim()
        if (cleaned.isEmpty()) {
            return@withContext ImportResult(false, "请先粘贴或提取 Cookie 内容")
        }

        val payload = JSONObject().apply {
            put("cookies", cleaned)
            if (!name.isNullOrBlank()) {
                put("name", name.trim())
            }
        }

        val req = Request.Builder()
            .url("${settings.localBaseUrl}/api/accounts/import-cookies")
            .applyWebAuth(settings)
            .header("Accept", "application/json")
            .post(payload.toString().toRequestBody(jsonMediaType))
            .build()

        return@withContext try {
            actionClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.isSuccessful) {
                    val obj = JSONObject(body)
                    val accName = obj.optString("name", "账号")
                    val cookieCount = obj.optInt("cookie_count", 0)
                    ImportResult(
                        success = true,
                        message = "直接导入成功：$accName（解析到 $cookieCount 条 Cookie）",
                        importedCount = 1
                    )
                } else {
                    val detail = extractErrorMessage(body, resp.code)
                    ImportResult(false, "直接导入失败：$detail")
                }
            }
        } catch (e: Exception) {
            ImportResult(false, "无法连接本机服务（127.0.0.1:${settings.port}），请先到「搭建」页启动服务后再试。")
        }
    }

    suspend fun createApiKey(
        settings: ProxySettings,
        name: String,
        customKey: String? = null
    ): Triple<Boolean, String, ApiKeySummary?> = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("name", name.trim().ifBlank { "App安全密钥" })
            if (!customKey.isNullOrBlank()) {
                put("key", customKey.trim())
            }
        }
        val req = Request.Builder()
            .url("${settings.localBaseUrl}/api-keys")
            .applyWebAuth(settings)
            .header("Accept", "application/json")
            .post(payload.toString().toRequestBody(jsonMediaType))
            .build()

        return@withContext try {
            actionClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.isSuccessful) {
                    val obj = JSONObject(body)
                    val item = ApiKeySummary(
                        name = obj.optCleanString("name").ifBlank { "API Key" },
                        key = obj.optCleanString("key"),
                        createdAt = obj.optCleanString("created_at").takeIf { it.isNotBlank() }
                    )
                    Triple(true, "API 密钥已创建并生效", item)
                } else {
                    val detail = extractErrorMessage(body, resp.code)
                    Triple(false, "创建 API 密钥失败：$detail", null)
                }
            }
        } catch (e: Exception) {
            Triple(false, "无法连接本机服务：${e.message ?: "服务未启动"}", null)
        }
    }

    suspend fun deleteApiKey(
        settings: ProxySettings,
        key: String
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val encoded = android.net.Uri.encode(key, "/")
        val req = Request.Builder()
            .url("${settings.localBaseUrl}/api-keys/$encoded")
            .applyWebAuth(settings)
            .delete()
            .build()
        return@withContext try {
            actionClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    true to "已删除该 API 密钥"
                } else {
                    false to extractErrorMessage(resp.body?.string().orEmpty(), resp.code)
                }
            }
        } catch (e: Exception) {
            false to (e.message ?: "请求失败")
        }
    }

    suspend fun clearAllCooldowns(settings: ProxySettings): Pair<Boolean, String> =
        clearAccountCooldown(settings, accountId = null, model = null)

    suspend fun clearAccountCooldown(
        settings: ProxySettings,
        accountId: String?,
        model: String? = null
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            if (!accountId.isNullOrBlank()) put("account_id", accountId)
            if (!model.isNullOrBlank()) put("model", model)
        }
        val req = Request.Builder()
            .url("${settings.localBaseUrl}/rotation/clear-cooldown")
            .applyWebAuth(settings)
            .post(payload.toString().toRequestBody(jsonMediaType))
            .build()
        return@withContext try {
            actionClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val msg = when {
                        !accountId.isNullOrBlank() && !model.isNullOrBlank() -> "已重置 $model 的 429 冷却"
                        !accountId.isNullOrBlank() -> "已清除该账号的 429 冷却状态"
                        else -> "已清除所有账号的 429 冷却状态"
                    }
                    true to msg
                } else {
                    false to extractErrorMessage(resp.body?.string().orEmpty(), resp.code)
                }
            }
        } catch (e: Exception) {
            false to (e.message ?: "网络请求失败")
        }
    }

    suspend fun activateAccount(
        settings: ProxySettings,
        accountId: String
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val encoded = android.net.Uri.encode(accountId, "@._-")
        val req = Request.Builder()
            .url("${settings.localBaseUrl}/api/accounts/$encoded/activate")
            .applyWebAuth(settings)
            .post("".toRequestBody(jsonMediaType))
            .build()
        return@withContext try {
            actionClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.isSuccessful) {
                    val obj = runCatching { JSONObject(body) }.getOrNull()
                    val label = obj?.optCleanString("email")?.takeIf { it.isNotBlank() }
                        ?: obj?.optCleanString("name")?.takeIf { it.isNotBlank() }
                        ?: accountId
                    true to "已激活账号：$label"
                } else {
                    false to extractErrorMessage(body, resp.code)
                }
            }
        } catch (e: Exception) {
            false to (e.message ?: "切换激活账号失败")
        }
    }

    suspend fun toggleAccount(
        settings: ProxySettings,
        accountId: String,
        enabled: Boolean? = null
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val encoded = android.net.Uri.encode(accountId, "@._-")
        val payload = JSONObject().apply {
            if (enabled != null) put("enabled", enabled)
        }
        val req = Request.Builder()
            .url("${settings.localBaseUrl}/api/accounts/$encoded/toggle")
            .applyWebAuth(settings)
            .post(payload.toString().toRequestBody(jsonMediaType))
            .build()
        return@withContext try {
            actionClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.isSuccessful) {
                    val obj = runCatching { JSONObject(body) }.getOrNull()
                    val nowEnabled = obj?.optBoolean("enabled", enabled ?: true) ?: true
                    true to if (nowEnabled) "账号已启用" else "账号已停用"
                } else {
                    false to extractErrorMessage(body, resp.code)
                }
            }
        } catch (e: Exception) {
            false to (e.message ?: "切换账号状态失败")
        }
    }

    suspend fun detectAccountEmailAndTier(
        settings: ProxySettings,
        accountId: String
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val encoded = android.net.Uri.encode(accountId, "@._-")
        val req = Request.Builder()
            .url("${settings.localBaseUrl}/api/accounts/$encoded/detect-email")
            .applyWebAuth(settings)
            .post("".toRequestBody(jsonMediaType))
            .build()
        return@withContext try {
            actionClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.isSuccessful) {
                    val obj = runCatching { JSONObject(body) }.getOrNull()
                    val email = obj?.optCleanString("email").orEmpty()
                    val tier = obj?.optCleanString("tier").orEmpty()
                    val detail = buildString {
                        if (email.isNotBlank()) append(email) else append("未识别到邮箱")
                        if (tier.isNotBlank()) append(" · 等级：$tier")
                    }
                    true to "探测完成：$detail"
                } else {
                    false to extractErrorMessage(body, resp.code)
                }
            }
        } catch (e: Exception) {
            false to (e.message ?: "探测失败")
        }
    }

    suspend fun forceNextAccount(settings: ProxySettings): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url("${settings.localBaseUrl}/rotation/next")
                .applyWebAuth(settings)
                .post("".toRequestBody(jsonMediaType))
                .build()
            return@withContext try {
                actionClient.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) {
                        val acc = runCatching { JSONObject(body).optJSONObject("account") }.getOrNull()
                        val label = acc?.optCleanString("email")?.takeIf { it.isNotBlank() }
                            ?: acc?.optCleanString("name")?.takeIf { it.isNotBlank() }
                            ?: "下一可用账号"
                        true to "已顺延切换至：$label"
                    } else {
                        false to extractErrorMessage(body, resp.code)
                    }
                }
            } catch (e: Exception) {
                false to (e.message ?: "切换下一账号失败")
            }
        }

    suspend fun deleteAccount(
        settings: ProxySettings,
        accountId: String
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val encoded = android.net.Uri.encode(accountId, "@._-")
        val req = Request.Builder()
            .url("${settings.localBaseUrl}/api/accounts/$encoded")
            .applyWebAuth(settings)
            .delete()
            .build()
        return@withContext try {
            actionClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    true to "已删除该账号"
                } else {
                    false to extractErrorMessage(resp.body?.string().orEmpty(), resp.code)
                }
            }
        } catch (e: Exception) {
            false to (e.message ?: "删除账号失败")
        }
    }

    suspend fun testModelsEndpoint(settings: ProxySettings): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            val apiKey = settings.webPassword.takeIf { it.isNotBlank() } ?: settings.apiTestKey
            val req = Request.Builder()
                .url("${settings.localBaseUrl}/v1beta/models")
                .header("x-goog-api-key", apiKey)
                .get()
                .build()
            return@withContext try {
                actionClient.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) {
                        val arr = JSONObject(body).optJSONArray("models")
                        val count = arr?.length() ?: 0
                        val preview = buildList {
                            if (arr != null) {
                                for (i in 0 until minOf(count, 4)) {
                                    val n = arr.optJSONObject(i)?.optCleanString("name")?.removePrefix("models/")
                                    if (!n.isNullOrBlank()) add(n)
                                }
                            }
                        }.joinToString("、")
                        true to "连接正常！获取到 $count 个可用模型${if (preview.isNotBlank()) "（如 $preview 等）" else ""}"
                    } else {
                        false to extractErrorMessage(body, resp.code)
                    }
                }
            } catch (e: Exception) {
                false to "无法连接到 ${settings.localBaseUrl}：${e.message ?: "服务未启动"}"
            }
        }

    private fun Request.Builder.applyWebAuth(settings: ProxySettings): Request.Builder {
        val pwd = settings.webPassword.trim()
        if (pwd.isNotEmpty()) {
            header("Authorization", "Bearer $pwd")
        }
        return this
    }

    private fun extractErrorMessage(body: String, httpCode: Int): String {
        return try {
            val obj = JSONObject(body)
            val errObj = obj.optJSONObject("error")
            val errMsg = errObj?.optCleanString("message").orEmpty()
            val detail = obj.optCleanString("detail")
            when {
                errMsg.isNotBlank() -> "HTTP $httpCode - $errMsg"
                detail.isNotBlank() -> "HTTP $httpCode - $detail"
                else -> "HTTP $httpCode"
            }
        } catch (_: Exception) {
            "HTTP $httpCode"
        }
    }

    private fun JSONObject.optCleanString(key: String): String {
        if (isNull(key)) return ""
        return optString(key, "").takeUnless { it == "null" } ?: ""
    }
}
