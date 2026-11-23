package com.aistudio.proxyhelper.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * 按机型识别权限入口，并尽量自动跳转到对应设置页。
 * 厂商私有页面不保证存在，失败会自动回退到系统标准页面。
 */
object BrandGuide {

    data class Info(
        val brandName: String,
        val restrictionPath: String,
        val overlayPath: String,
        val a11yPath: String
    )

    fun current(): Info {
        val key = ("${Build.MANUFACTURER} ${Build.BRAND}").lowercase()
        return when {
            key.contains("xiaomi") || key.contains("redmi") || key.contains("poco") ->
                Info(
                    brandName = "小米 / 红米（HyperOS / MIUI）",
                    restrictionPath = "应用详情 → 权限 → 若出现「受限制的设置」点允许；或在无障碍开关弹窗里点小字「解除限制」",
                    overlayPath = "设置 → 应用设置 → 应用管理 → 本 App → 权限管理 → 显示在其他应用上层",
                    a11yPath = "设置 → 更多设置 → 无障碍 → 已下载的应用 → AI Studio 反代自动搭建助手"
                )

            key.contains("oppo") || key.contains("oneplus") || key.contains("realme") ->
                Info(
                    brandName = "OPPO / 一加 / realme（ColorOS）",
                    restrictionPath = "应用详情 → 权限管理 → 右上角「⋮」→「解除所有授权限制」",
                    overlayPath = "设置 → 应用 → 应用管理 → 本 App → 权限管理 → 显示在其他应用上层",
                    a11yPath = "设置 → 系统设置 → 无障碍 → 已下载的服务 → AI Studio 反代自动搭建助手"
                )

            key.contains("vivo") || key.contains("iqoo") ->
                Info(
                    brandName = "vivo / iQOO（OriginOS）",
                    restrictionPath = "应用详情 → 权限 → 受限制的设置（或 i管家 → 权限管理 → 本 App）",
                    overlayPath = "设置 → 应用与权限 → 权限管理 → 本 App → 悬浮窗 / 显示在其他应用上层",
                    a11yPath = "设置 → 快捷与辅助 → 无障碍 → 已下载的应用 → AI Studio 反代自动搭建助手"
                )

            key.contains("honor") ->
                Info(
                    brandName = "荣耀（MagicOS）",
                    restrictionPath = "应用详情 → 权限 → 受限制的设置",
                    overlayPath = "设置 → 应用 → 应用管理 → 本 App → 权限 → 悬浮窗",
                    a11yPath = "设置 → 辅助功能 → 无障碍 → 已下载的应用 → AI Studio 反代自动搭建助手"
                )

            key.contains("huawei") ->
                Info(
                    brandName = "华为（EMUI / HarmonyOS）",
                    restrictionPath = "应用详情 → 权限 → 受限制的设置",
                    overlayPath = "设置 → 应用 → 应用管理 → 本 App → 权限 → 悬浮窗",
                    a11yPath = "设置 → 辅助功能 → 无障碍 → 已下载的应用 → AI Studio 反代自动搭建助手"
                )

            key.contains("samsung") ->
                Info(
                    brandName = "三星（One UI）",
                    restrictionPath = "应用详情 → 权限 → 特殊访问权限",
                    overlayPath = "设置 → 应用 → 本 App → 在其他应用上层显示",
                    a11yPath = "设置 → 辅助功能 → 已安装的应用程序 → AI Studio 反代自动搭建助手"
                )

            key.contains("meizu") ->
                Info(
                    brandName = "魅族（Flyme）",
                    restrictionPath = "应用详情 → 权限管理 → 受限制的设置",
                    overlayPath = "设置 → 应用管理 → 本 App → 权限 → 悬浮窗",
                    a11yPath = "设置 → 辅助功能 → 无障碍 → 已下载的应用"
                )

            key.contains("google") || key.contains("pixel") ->
                Info(
                    brandName = "原生 Android / Pixel",
                    restrictionPath = "应用详情 → 权限（通常没有额外限制，可直接授权）",
                    overlayPath = "设置 → 应用 → 特殊应用权限 → 显示在其他应用上层",
                    a11yPath = "设置 → 无障碍 → 已下载的应用 → AI Studio 反代自动搭建助手"
                )

            else ->
                Info(
                    brandName = "通用 Android",
                    restrictionPath = "应用详情 → 权限管理，若提示「受限制的设置」请允许；或看弹窗里的小字「解除限制」",
                    overlayPath = "设置 → 应用 → 特殊应用权限 → 显示在其他应用上层",
                    a11yPath = "设置 → 无障碍 → 已下载的应用 → AI Studio 反代自动搭建助手"
                )
        }
    }

    /**
     * 打开本机的「应用权限管理」页（含受限设置开关）。
     * 先尝试厂商私有页面，失败回退到系统应用详情页。
     */
    fun openPermissionEditor(context: Context): Boolean {
        val pkg = context.packageName
        val m = "${Build.MANUFACTURER} ${Build.BRAND}".lowercase()
        val candidates = mutableListOf<Intent>()

        if (m.contains("xiaomi") || m.contains("redmi") || m.contains("poco")) {
            candidates += Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                setClassName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.permissions.PermissionsEditorActivity"
                )
                putExtra("extra_pkgname", pkg)
            }
        }
        if (m.contains("huawei") || m.contains("honor")) {
            candidates += Intent().apply {
                setClassName(
                    "com.huawei.systemmanager",
                    "com.huawei.permissionmanager.ui.MainActivity"
                )
            }
        }
        if (m.contains("oppo") || m.contains("oneplus") || m.contains("realme")) {
            candidates += Intent().apply {
                setClassName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.PermissionManagerActivity"
                )
            }
        }
        if (m.contains("vivo") || m.contains("iqoo")) {
            candidates += Intent().apply {
                setClassName(
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
                )
            }
        }

        candidates += Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:$pkg")
        )

        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val ok = runCatching { context.startActivity(intent) }.isSuccess
            if (ok) return true
        }
        return false
    }
}
