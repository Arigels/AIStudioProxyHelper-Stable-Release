package com.aistudio.proxyhelper.network

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.aistudio.proxyhelper.automation.AutomationController
import com.aistudio.proxyhelper.data.EnvironmentStatus
import com.aistudio.proxyhelper.termux.TermuxBridge
import java.net.Inet4Address
import java.net.NetworkInterface

object EnvironmentChecker {

    fun inspect(context: Context): EnvironmentStatus {
        val abis = Build.SUPPORTED_ABIS ?: emptyArray()
        val is64Bit = abis.any { it == "arm64-v8a" || it == "x86_64" }
        val abiSummary = abis.joinToString(", ").ifBlank { "未知" }

        val termuxInfo = getTermuxPackageInfo(context)
        val termuxInstalled = termuxInfo != null
        val termuxVersion = termuxInfo?.versionName

        val runCmdGranted = ContextCompat.checkSelfPermission(
            context,
            TermuxBridge.PERMISSION_RUN_COMMAND
        ) == PackageManager.PERMISSION_GRANTED

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val batteryIgnored = powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: false

        val detectedEdgePkg = listOf(
            TermuxBridge.PACKAGE_EDGE,
            TermuxBridge.PACKAGE_EDGE_BETA,
            TermuxBridge.PACKAGE_EDGE_CANARY
        ).firstOrNull { isPackageInstalled(context, it) }

        return EnvironmentStatus(
            is64BitArm = is64Bit,
            abiSummary = abiSummary,
            termuxInstalled = termuxInstalled,
            termuxVersionName = termuxVersion,
            termuxRunCommandGranted = runCmdGranted,
            accessibilityEnabled = AutomationController.isServiceEnabled(context),
            firefoxInstalled = isPackageInstalled(context, TermuxBridge.PACKAGE_FIREFOX),
            edgeInstalled = detectedEdgePkg != null,
            edgePackage = detectedEdgePkg,
            lemurInstalled = isPackageInstalled(context, TermuxBridge.PACKAGE_LEMUR),
            batteryOptimizationIgnored = batteryIgnored,
            lanIpAddress = detectLanIpv4()
        )
    }

    fun openBatteryOptimizationSettings(context: Context) {
        val directIntent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        try {
            context.startActivity(directIntent)
        } catch (_: Exception) {
            val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(fallback) }
        }
    }

    fun openTermuxAppDetails(context: Context) {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${TermuxBridge.TERMUX_PACKAGE}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        runCatching { context.startActivity(intent) }
    }

    /** 打开本 App 的「应用详情」页。 */
    fun openAppDetails(context: Context) {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        runCatching { context.startActivity(intent) }
    }

    /**
     * 打开「显示在其他应用上层」（悬浮窗）授权页。
     * 加 FLAG_ACTIVITY_CLEAR_TASK 避免系统复用之前停留在「无障碍」等旧页面的设置任务栈。
     */
    fun openOverlayPermissionSettings(context: Context): Boolean {
        val pkg = context.packageName
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        val candidates = listOf(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$pkg")),
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
        )
        for (intent in candidates) {
            intent.addFlags(flags)
            val ok = runCatching { context.startActivity(intent) }.isSuccess
            if (ok) return true
        }
        return false
    }

    private fun isPackageInstalled(context: Context, packageName: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(packageName, 0)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun getTermuxPackageInfo(context: Context) = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                TermuxBridge.TERMUX_PACKAGE,
                PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(TermuxBridge.TERMUX_PACKAGE, 0)
        }
    } catch (_: PackageManager.NameNotFoundException) {
        null
    } catch (_: Exception) {
        null
    }

    private fun detectLanIpv4(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            val candidates = mutableListOf<Pair<Int, String>>()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp || iface.isLoopback || iface.isVirtual) continue
                val name = iface.name.lowercase()
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress ?: continue
                        val priority = when {
                            name.startsWith("wlan") || name.startsWith("ap") -> 0
                            name.startsWith("eth") || name.startsWith("en") -> 1
                            addr.isSiteLocalAddress -> 2
                            else -> 3
                        }
                        candidates.add(priority to host)
                    }
                }
            }
            candidates.minByOrNull { it.first }?.second
        } catch (_: Exception) {
            null
        }
    }
}
