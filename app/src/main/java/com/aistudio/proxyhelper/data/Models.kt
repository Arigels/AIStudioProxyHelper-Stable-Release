package com.aistudio.proxyhelper.data

data class ProxySettings(
    val port: Int = 8080,
    val proxyUrl: String = "",
    val webPassword: String = "",
    val installDir: String = "\$HOME/aistudio-api",
    val repoUrl: String = "https://github.com/DeconstructedCube/aistudio-api.git",
    val apiTestKey: String = "aistudio-api"
) {
    val localBaseUrl: String
        get() = "http://127.0.0.1:$port"

    fun lanBaseUrl(lanIp: String?): String? {
        return lanIp?.takeIf { it.isNotBlank() }?.let { "http://$it:$port" }
    }
}

data class EnvironmentStatus(
    val is64BitArm: Boolean = true,
    val abiSummary: String = "",
    val termuxInstalled: Boolean = false,
    val termuxVersionName: String? = null,
    val termuxRunCommandGranted: Boolean = false,
    val accessibilityEnabled: Boolean = false,
    val batteryOptimizationIgnored: Boolean = false,
    val lanIpAddress: String? = null
)

data class AccountSummary(
    val id: String,
    val name: String,
    val email: String?,
    val authUser: String,
    val tier: String,
    val enabled: Boolean,
    val group: String
)

data class ServiceStatus(
    val online: Boolean = false,
    val checking: Boolean = false,
    val signerReady: Boolean? = null,
    val signerRunning: Boolean? = null,
    val authEnabled: Boolean = false,
    val accounts: List<AccountSummary> = emptyList(),
    val models: List<String> = emptyList(),
    val lastError: String? = null,
    val lastCheckedAt: Long = 0L
)

data class ImportResult(
    val success: Boolean,
    val message: String,
    val importedCount: Int = 0
)

enum class TermuxAction(
    val title: String,
    val subtitle: String,
    val runInBackground: Boolean = false
) {
    INSTALL_AND_START(
        title = "一键搭建并启动",
        subtitle = "自动安装 Git/Python/uv/Go/Zig、编译本地 WAA 签名引擎并启动反代服务"
    ),
    INSTALL_ONLY(
        title = "仅安装与编译依赖",
        subtitle = "克隆仓库并执行 bash install.sh，完成后不立即启动"
    ),
    START_SERVICE(
        title = "启动反代服务",
        subtitle = "执行 bash start.sh --port，拉起 FastAPI 网关与本地 IPC 签名器"
    ),
    STOP_SERVICE(
        title = "停止反代服务",
        subtitle = "结束后台运行中的 aistudio-api 进程",
        runInBackground = true
    ),
    UPDATE_AND_REBUILD(
        title = "拉取更新并重编引擎",
        subtitle = "执行 git pull && bash install.sh --rebuild 修复或升级版本"
    ),
    ACQUIRE_WAKELOCK(
        title = "开启 Termux 唤醒锁",
        subtitle = "执行 termux-wake-lock，减少锁屏断网或杀后台",
        runInBackground = true
    ),
    ENABLE_EXTERNAL_APPS(
        title = "开启 Termux 外部调用权限",
        subtitle = "写入 allow-external-apps = true 并重载 Termux 配置（首次需要粘贴到 Termux 执行一次）"
    )
}
