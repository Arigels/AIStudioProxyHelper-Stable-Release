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
    val firefoxInstalled: Boolean = false,
    val firefoxPackage: String? = null,
    val edgeInstalled: Boolean = false,
    val edgePackage: String? = null,
    val lemurInstalled: Boolean = false,
    val batteryOptimizationIgnored: Boolean = false,
    val lanIpAddress: String? = null
)

data class ModelDailyQuotaDetail(
    val modelId: String,
    val requests: Int = 0,
    val success: Int = 0,
    val rateLimited: Int = 0,
    val errors: Int = 0,
    val totalTokens: Long = 0L,
    val reasoningTokens: Long = 0L,
    val cachedTokens: Long = 0L,
    val configuredQuota: Int = 20,
    val configuredTokenQuota: Long? = null,
    val quotaUsedPercent: Double = 0.0,
    val tokenQuotaUsedPercent: Double = 0.0,
    val cooldownRemainingSec: Int = 0,
    val dripMode: Boolean = false,
    val isAvailable: Boolean = true
)

data class AccountSummary(
    val id: String,
    val name: String,
    val email: String?,
    val authUser: String,
    val tier: String,
    val enabled: Boolean,
    val group: String,
    val totalRequests: Int = 0,
    val totalSuccess: Int = 0,
    val totalRateLimited: Int = 0,
    val totalErrors: Int = 0,
    val todayTotalRequests: Int = 0,
    val todayTotalTokens: Long = 0L,
    val lastUsed: String? = null,
    val isAvailable: Boolean = true,
    val sessionExpired: Boolean = false,
    val cooldownRemainingSec: Int = 0,
    val modelCooldowns: Map<String, Int> = emptyMap(),
    val todayModels: Map<String, ModelDailyQuotaDetail> = emptyMap()
)

data class ModelMatchRule(
    val exact: List<String> = emptyList(),
    val prefixes: List<String> = emptyList(),
    val contains: List<String> = emptyList()
) {
    fun matches(normalizedModel: String): Boolean {
        val clean = normalizedModel.trim().removePrefix("models/").lowercase()
        if (clean.isEmpty()) return false
        if (exact.any { it.trim().removePrefix("models/").lowercase() == clean }) return true
        if (prefixes.any { clean.startsWith(it.trim().lowercase()) }) return true
        if (contains.any { clean.contains(it.trim().lowercase()) }) return true
        return false
    }
}

data class TierQuotaRuleEntry(
    val ruleKey: String,
    val match: ModelMatchRule,
    val dailyLimit: Int,
    val dailyTokenLimit: Long? = null
)

data class TierQuotaConfig(
    val tierName: String,
    val rules: List<TierQuotaRuleEntry> = emptyList(),
    val defaultLimit: Int = 20,
    val defaultTokenLimit: Long? = null
)

data class QuotaOverrideEntry(
    val modelId: String,
    val dailyLimit: Int? = null,
    val dailyTokenLimit: Long? = null
)

data class PaidModelRuleEntry(
    val ruleKey: String,
    val match: ModelMatchRule,
    val requiredTiers: List<String>
)

data class QuotaPolicySnapshot(
    val tierOrder: List<String> = emptyList(),
    val tiers: Map<String, TierQuotaConfig> = emptyMap(),
    val overrides: Map<String, QuotaOverrideEntry> = emptyMap(),
    val paidRules: List<PaidModelRuleEntry> = emptyList(),
    val syncedFromServer: Boolean = false
)

data class ApiKeySummary(
    val name: String,
    val key: String,
    val createdAt: String? = null
)

data class ServiceStatus(
    val online: Boolean = false,
    val checking: Boolean = false,
    val signerReady: Boolean? = null,
    val signerRunning: Boolean? = null,
    val authEnabled: Boolean = false,
    val accounts: List<AccountSummary> = emptyList(),
    val activeAccountId: String? = null,
    val rotationEnabled: Boolean = false,
    val quotaPolicy: QuotaPolicySnapshot? = null,
    val apiKeys: List<ApiKeySummary> = emptyList(),
    val models: List<String> = emptyList(),
    val lastError: String? = null,
    val lastCheckedAt: Long = 0L
)

data class ImportResult(
    val success: Boolean,
    val message: String,
    val importedCount: Int = 0
)

data class PublicNetworkRisk(
    val key: String,
    val reason: String,
    val address: String
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
        subtitle = "减少锁屏后被系统挂起；不能防止被杀后台，建议同时关闭电池优化",
        runInBackground = true
    ),
    ENABLE_EXTERNAL_APPS(
        title = "开启 Termux 外部调用权限",
        subtitle = "写入 allow-external-apps = true 并重载 Termux 配置（首次需要粘贴到 Termux 执行一次）"
    )
}
