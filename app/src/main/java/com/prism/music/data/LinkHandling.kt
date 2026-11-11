package com.prism.music.data

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * YouTube Music links opening in Prism (Reddit R10). The manifest asks for music.youtube.com, but from Android 12 an app
 * that doesn't own a website only opens its links once the listener turns them on in Prism's "Open by default" page.
 */
object LinkHandling {
    private const val HOST = "music.youtube.com"

    /** Whether tapped music.youtube.com links open in Prism; null below Android 12 (there the phone asks each time). */
    fun opensYouTubeMusicLinks(context: Context): Boolean? {
        if (Build.VERSION.SDK_INT < 31) return null
        val dvm = context.getSystemService(android.content.pm.verify.domain.DomainVerificationManager::class.java) ?: return null
        val state = runCatching { dvm.getDomainVerificationUserState(context.packageName) }.getOrNull() ?: return null
        if (!state.isLinkHandlingAllowed) return false
        return state.hostToStateMap[HOST]?.let {
            it == android.content.pm.verify.domain.DomainVerificationUserState.DOMAIN_STATE_SELECTED ||
                it == android.content.pm.verify.domain.DomainVerificationUserState.DOMAIN_STATE_VERIFIED
        } ?: false
    }

    /** Prism's "Open by default" page (Android 12+), else its app info page. */
    fun openSettings(context: Context) {
        val pkg = Uri.parse("package:${context.packageName}")
        val intents = buildList {
            if (Build.VERSION.SDK_INT >= 31) add(Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, pkg))
            add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg))
        }
        for (i in intents) {
            try {
                context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: ActivityNotFoundException) {
            }
        }
    }
}
