package com.aistudio.proxyhelper.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.aistudio.proxyhelper.data.ProxySettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** 网络不通时的三类结论。文案只在这里写一份，界面与悬浮窗都引用 message。 */
enum class NetworkVerdict(val message: String) {
    NO_PROXY("网络未连通：未检测到 VPN 或代理，先开启 VPN 或代理后重试。"),
    NODE_SUSPECT("网络未连通：VPN 或代理已开启，但仍连不上 aistudio.google.com，更换节点后重试。"),
    UNKNOWN("网络状态无法确认：请检查 VPN、代理与账号状态，或查看日志。")
}

object NetworkJudge {

    /** 判定目标：能不能连上 Google 的 AI Studio 首页。 */
    private const val TARGET_URL = "https://aistudio.google.com/"

    private val judgeClient = OkHttpClient.Builder()
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(1500, TimeUnit.MILLISECONDS)
        .callTimeout(3000, TimeUnit.MILLISECONDS)
        .build()

    /** 系统里是否有已连接的 VPN，或用户填了本机代理地址。 */
    private fun hasVpnOrProxy(context: Context, settings: ProxySettings): Boolean {
        if (settings.proxyUrl.isNotBlank()) return true
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        @Suppress("DEPRECATION")
        val networks = cm.allNetworks
        for (network in networks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            ) {
                return true
            }
        }
        return false
    }

    /** 当前活动网络是否真的能上网；飞行模式、断网时返回 false。 */
    private fun hasActiveNetwork(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val active = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(active) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** 3 秒内能否连到目标；连上就算能通，不要求 2xx。 */
    private suspend fun canReachTarget(): Boolean = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(TARGET_URL).head().build()
        runCatching {
            judgeClient.newCall(req).execute().use { resp ->
                resp.isSuccessful || resp.code in 300..499
            }
        }.getOrDefault(false)
    }

    /**
     * 只在用户手动刷新、或刚掉线的那一刻调用，不做定时轮询。
     * 返回 null 表示网络正常，不显示任何提示。
     */
    suspend fun judge(context: Context, settings: ProxySettings): NetworkVerdict? {
        if (!hasActiveNetwork(context)) return NetworkVerdict.UNKNOWN
        if (canReachTarget()) return null
        return if (hasVpnOrProxy(context, settings)) {
            NetworkVerdict.NODE_SUSPECT
        } else {
            NetworkVerdict.NO_PROXY
        }
    }
}
