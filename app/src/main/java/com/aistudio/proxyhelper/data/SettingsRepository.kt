package com.aistudio.proxyhelper.data

import android.content.Context
import android.content.SharedPreferences

class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): ProxySettings {
        val storedPort = prefs.getInt(KEY_PORT, 8080)
        return ProxySettings(
            port = if (storedPort < 1024) 8080 else storedPort.coerceAtMost(65535),
            proxyUrl = prefs.getString(KEY_PROXY_URL, "") ?: "",
            webPassword = prefs.getString(KEY_WEB_PASSWORD, "") ?: "",
            installDir = prefs.getString(KEY_INSTALL_DIR, "\$HOME/aistudio-api")
                ?.takeIf { it.isNotBlank() } ?: "\$HOME/aistudio-api",
            repoUrl = prefs.getString(
                KEY_REPO_URL,
                "https://github.com/DeconstructedCube/aistudio-api.git"
            )?.takeIf { it.isNotBlank() }
                ?: "https://github.com/DeconstructedCube/aistudio-api.git",
            apiTestKey = prefs.getString(KEY_API_TEST_KEY, "aistudio-api")
                ?.takeIf { it.isNotBlank() } ?: "aistudio-api"
        )
    }

    fun save(settings: ProxySettings) {
        prefs.edit()
            .putInt(KEY_PORT, settings.port.coerceIn(1024, 65535))
            .putString(KEY_PROXY_URL, settings.proxyUrl.trim())
            .putString(KEY_WEB_PASSWORD, settings.webPassword.trim())
            .putString(KEY_INSTALL_DIR, settings.installDir.trim().ifBlank { "\$HOME/aistudio-api" })
            .putString(
                KEY_REPO_URL,
                settings.repoUrl.trim().ifBlank { "https://github.com/DeconstructedCube/aistudio-api.git" }
            )
            .putString(KEY_API_TEST_KEY, settings.apiTestKey.trim().ifBlank { "aistudio-api" })
            .apply()
    }

    /** 是否成功发起过安装（用于区分首次安装 / 日常启动）。 */
    var hasDeployed: Boolean
        get() = prefs.getBoolean(KEY_HAS_DEPLOYED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_HAS_DEPLOYED, value).apply()
        }

    /** 用户是否已执行过「开启 Termux 一键调用」的一次性设置。 */
    var externalAppsSetupDone: Boolean
        get() = prefs.getBoolean(KEY_EXTERNAL_APPS_DONE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_EXTERNAL_APPS_DONE, value).apply()
        }

    /** AI 助手使用的接口地址（OpenAI 兼容）。 */
    var agentBaseUrl: String
        get() = prefs.getString(KEY_AGENT_BASE_URL, "https://api.deepseek.com") ?: ""
        set(value) {
            prefs.edit().putString(KEY_AGENT_BASE_URL, value.trim()).apply()
        }

    /** AI 助手使用的 API Key。 */
    var agentApiKey: String
        get() = prefs.getString(KEY_AGENT_API_KEY, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_AGENT_API_KEY, value.trim()).apply()
        }

    /** AI 助手使用的模型名。 */
    var agentModel: String
        get() = prefs.getString(KEY_AGENT_MODEL, "deepseek-chat") ?: ""
        set(value) {
            prefs.edit().putString(KEY_AGENT_MODEL, value.trim()).apply()
        }

    /** AI 助手是否改用本机反代出来的模型（免费，无需外部 Key）。 */
    var agentUseLocal: Boolean
        get() = prefs.getBoolean(KEY_AGENT_USE_LOCAL, false)
        set(value) {
            prefs.edit().putBoolean(KEY_AGENT_USE_LOCAL, value).apply()
        }

    /** 本机反代模型名。 */
    var agentLocalModel: String
        get() = prefs.getString(KEY_AGENT_LOCAL_MODEL, "gemini-3.7-flash") ?: ""
        set(value) {
            prefs.edit().putString(KEY_AGENT_LOCAL_MODEL, value.trim()).apply()
        }

    /** 本机反代使用的 API Key（服务未配置鉴权时任意非空即可）。 */
    var agentLocalKey: String
        get() = prefs.getString(KEY_AGENT_LOCAL_KEY, "aistudio-api") ?: ""
        set(value) {
            prefs.edit().putString(KEY_AGENT_LOCAL_KEY, value.trim()).apply()
        }

    companion object {
        private const val PREFS_NAME = "aistudio_proxy_helper_prefs"
        private const val KEY_PORT = "port"
        private const val KEY_PROXY_URL = "proxy_url"
        private const val KEY_WEB_PASSWORD = "web_password"
        private const val KEY_INSTALL_DIR = "install_dir"
        private const val KEY_REPO_URL = "repo_url"
        private const val KEY_API_TEST_KEY = "api_test_key"
        private const val KEY_HAS_DEPLOYED = "has_deployed"
        private const val KEY_EXTERNAL_APPS_DONE = "external_apps_done"
        private const val KEY_AGENT_BASE_URL = "agent_base_url"
        private const val KEY_AGENT_API_KEY = "agent_api_key"
        private const val KEY_AGENT_MODEL = "agent_model"
        private const val KEY_AGENT_USE_LOCAL = "agent_use_local"
        private const val KEY_AGENT_LOCAL_MODEL = "agent_local_model"
        private const val KEY_AGENT_LOCAL_KEY = "agent_local_key"
    }
}
