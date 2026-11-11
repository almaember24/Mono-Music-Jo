package com.prism.music.ui.lockscreen

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * D43 (1.8.3): phones whose maker adds permissions of its own before an app may show a screen over the lock screen or
 * start one from the background. Android can't read them, so Prism can't tell whether they're allowed: the lock screen's
 * settings show one more step on these phones, with a button to the maker's own permission page where it opens.
 */
enum class LockScreenMaker {
    /** vivo and iQOO (Funtouch OS / OriginOS): "Display on lock screen" and "Background pop-ups". */
    VIVO,
    /** Xiaomi, Redmi and POCO (MIUI / HyperOS): "Show on Lock screen" and "Open new windows while running in the background". */
    XIAOMI;

    /** The maker's permission page for [packageName], in the order to try (the newest first). */
    fun pages(packageName: String): List<Intent> = when (this) {
        VIVO -> listOf(
            Intent().setComponent(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.SoftPermissionDetailActivity"))
                .putExtra("packagename", packageName),
            Intent().setComponent(ComponentName("com.iqoo.secure", "com.iqoo.secure.safecenter.SoftPermissionDetailActivity"))
                .putExtra("packagename", packageName),
        )
        XIAOMI -> listOf(
            Intent("miui.intent.action.APP_PERM_EDITOR")
                .setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity"))
                .putExtra("extra_pkgname", packageName),
            Intent("miui.intent.action.APP_PERM_EDITOR")
                .setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.permissions.AppPermissionsEditorActivity"))
                .putExtra("extra_pkgname", packageName),
        )
    }

    companion object {
        /** The maker from Android's build fields ([Build.MANUFACTURER], [Build.BRAND]), or null for every other phone. */
        fun of(manufacturer: String = Build.MANUFACTURER, brand: String = Build.BRAND): LockScreenMaker? {
            val names = listOf(manufacturer, brand).map { it.trim().lowercase() }
            return when {
                names.any { it == "vivo" || it == "iqoo" } -> VIVO
                names.any { it == "xiaomi" || it == "redmi" || it == "poco" } -> XIAOMI
                else -> null
            }
        }

        /** Opens the maker's page for Prism: the first that opens, else Prism's App info. */
        fun openPage(context: Context, maker: LockScreenMaker) {
            val opened = maker.pages(context.packageName).any { page ->
                runCatching { context.startActivity(page) }.recoverCatching { context.startActivity(page.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
            }
            if (!opened) LockScreenGate.openAppInfo(context)
        }
    }
}

/**
 * D43 (1.8.3): when a start of the lock screen was quietly refused (the phone woke with it not up), the settings page says
 * so, instead of the player just not coming up. Cleared as soon as one comes up.
 */
internal object LockScreenRefusals {
    private const val PREFS = "lock_screen_gate"
    private const val KEY = "refused_at"
    /** How long the page keeps saying it: a refusal from last month says little about the phone today. */
    const val SHOWN_FOR_MS = 7 * 24 * 60 * 60_000L

    fun note(context: Context, now: Long = System.currentTimeMillis()) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY, now).apply()

    fun clear(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()

    fun lately(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        val at = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY, 0L)
        return at > 0 && now - at in 0 until SHOWN_FOR_MS
    }
}
