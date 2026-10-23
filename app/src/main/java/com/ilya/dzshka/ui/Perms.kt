package com.ilya.dzshka.ui

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast

object Perms {
    fun ignoringBattery(ctx: Context) = ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

    @SuppressLint("BatteryLife")
    fun askBattery(ctx: Context) {
        runCatching {
            ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
        }.onFailure { appDetails(ctx) }
    }

    fun exactAlarms(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 31) runCatching {
            ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}")))
        }.onFailure { appDetails(ctx) }
    }

    fun isXiaomi() = Build.MANUFACTURER.equals("Xiaomi", true) || Build.BRAND.lowercase() in setOf("xiaomi", "redmi", "poco")

    fun autostart(ctx: Context) {
        val tries = listOf(
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            ComponentName("com.miui.securitycenter", "com.miui.powercenter.PowerSettings"),
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
        )
        for (c in tries) {
            val ok = runCatching { ctx.startActivity(Intent().setComponent(c).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
            if (ok) return
        }
        Toast.makeText(ctx, "Не нашёл экран автозапуска — открываю настройки приложения", Toast.LENGTH_LONG).show()
        appDetails(ctx)
    }

    fun appDetails(ctx: Context) = runCatching {
        ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
    }

    fun notifSettings(ctx: Context) = runCatching {
        ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
    }.onFailure { appDetails(ctx) }

    fun canOverlay(ctx: Context) = Settings.canDrawOverlays(ctx)

    fun askOverlay(ctx: Context) = runCatching {
        ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
    }.onFailure { appDetails(ctx) }

    fun canFullScreen(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 34 || ctx.getSystemService(android.app.NotificationManager::class.java).canUseFullScreenIntent()

    fun askFullScreen(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 34) runCatching {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${ctx.packageName}")))
        }.onFailure { appDetails(ctx) }
    }

    /** Xiaomi: «Экран блокировки» и «Всплывающие окна в фоне» в своих настройках. */
    fun xiaomiPerms(ctx: Context) {
        val i = Intent("miui.intent.action.APP_PERM_EDITOR")
            .setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
            .putExtra("extra_pkgname", ctx.packageName)
        runCatching { ctx.startActivity(i) }.onFailure { appDetails(ctx) }
    }
}
