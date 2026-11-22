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
    }
}
