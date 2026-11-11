package com.prism.music.data

import android.content.Context

/**
 * Android's own backup (to Google, or copied phone to phone) brings Prism's files to a new install. The sign-ins kept
 * in files of their own stay out of it (res/xml/data_extraction_rules.xml, backup_rules.xml), but the YouTube Music
 * sign-in lives with the settings, which should come along. So a restored copy signs out of it once, at start.
 *
 * A restore is told from an update by the time Android installed the app: the one saved in [PREFS] (which is backed up
 * with the rest) belongs to another install. Prism's own backups leave [PREFS] out, so restoring one of those, with
 * its accounts, signs nothing out.
 */
object RestoredInstall {
    const val PREFS = "install"
    private const val INSTALLED_AT = "installed_at"

    /** True when this is another install's restored data: [signOut] has then been called. */
    fun check(context: Context, signOut: () -> Unit): Boolean {
        val now = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime }.getOrNull() ?: return false
        return check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE), now, signOut)
    }

    internal fun check(prefs: android.content.SharedPreferences, installedAt: Long, signOut: () -> Unit): Boolean {
        val saved = prefs.getLong(INSTALLED_AT, 0L)
        if (saved == installedAt) return false
        prefs.edit().putLong(INSTALLED_AT, installedAt).commit()
        // Never seen (a new install, or the update that brought this in): nothing came from elsewhere.
        if (saved == 0L) return false
        signOut()
        return true
    }
}
