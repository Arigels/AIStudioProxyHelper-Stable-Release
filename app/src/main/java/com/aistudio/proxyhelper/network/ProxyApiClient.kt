package com.aistudio.proxyhelper.network

import com.aistudio.proxyhelper.data.AccountSummary
import com.aistudio.proxyhelper.data.ImportResult
import com.aistudio.proxyhelper.data.ProxySettings
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
                    }
                }
            }

            // 3. Check /api/accounts
            val accounts = mutableListOf<AccountSummary>()
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
                            accounts.add(
                                AccountSummary(
                                    id = item.optString("id"),
                                    name = item.optString("name", "Google Account"),
                                    email = item.optString("email").takeIf { it.isNotBlank() && it != "null" },
                                    authUser = item.optString("auth_user", "0"),
                                    tier = item.optString("tier", "Free"),
                                    enabled = item.optBoolean("enabled", true),
                                    group = item.optString("group", "default")
                                )
                            )
                        }
                    }
                }
            }

            // 4. Check /v1beta/models
            val models = mutableListOf<String>()
            runCatching {
                val apiKey = settings.webPassword.takeIf { it.isNotBlank() } ?: settings.apiTestKey
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
                                val name = m.optString("name").removePrefix("models/")
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
            ImportResult(false, "连接本机服务失败：${e.message ?: "请确认服务已启动"}")
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
            ImportResult(false, "连接本机服务失败：${e.message ?: "请确认服务已启动"}")
        }
    }

    suspend fun clearAllCooldowns(settings: ProxySettings): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url("${settings.localBaseUrl}/rotation/clear-cooldown")
                .applyWebAuth(settings)
                .post("{}".toRequestBody(jsonMediaType))
                .build()
            return@withContext try {
                actionClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        true to "已清除所有账号的 429 冷却状态"
                    } else {
                        false to extractErrorMessage(resp.body?.string().orEmpty(), resp.code)
                    }
                }
            } catch (e: Exception) {
                false to (e.message ?: "网络请求失败")
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
                                    val n = arr.optJSONObject(i)?.optString("name")?.removePrefix("models/")
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
            when {
                errObj != null && errObj.has("message") -> "HTTP $httpCode - ${errObj.optString("message")}"
                obj.has("detail") -> "HTTP $httpCode - ${obj.optString("detail")}"
                else -> "HTTP $httpCode"
            }
        } catch (_: Exception) {
            "HTTP $httpCode"
        }
    }
}
