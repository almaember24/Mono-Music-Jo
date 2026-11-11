package com.prism.music.playback

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.prism.music.ui.theme.AppIcon
import com.prism.music.ui.theme.AppIcons

// Android Auto shows a media app with its media service's own icon, not the launcher's, and services have no aliases:
// so there's one playback service per app icon, each declared with that icon in the manifest, and only the one for
// the chosen icon is enabled. They're all the same service.
class PlaybackServiceSpectrum : PlaybackService()
class PlaybackServiceSolid : PlaybackService()
class PlaybackServiceLight : PlaybackService()
class PlaybackServiceWave : PlaybackService()
class PlaybackServiceMidnight : PlaybackService()
// 1.7.7's icons.
class PlaybackServiceDusk : PlaybackService()
class PlaybackServicePaper : PlaybackService()
class PlaybackServiceNeon : PlaybackService()
class PlaybackServicePastel : PlaybackService()
class PlaybackServiceLine : PlaybackService()
class PlaybackServiceGold : PlaybackService()

object PlaybackServices {
    /** The playback service class running in this process, if one is. */
    @Volatile var running: Class<out PlaybackService>? = null
    /** Debug builds only, for the in-app tests: hands the running service a player error, as if the player had one. */
    @Volatile var failForTest: ((androidx.media3.common.PlaybackException) -> Unit)? = null
    /** Debug builds only, for the in-app tests: Prism swiped away from Recents (#274). */
    @Volatile var taskRemovedForTest: (() -> Unit)? = null

    fun classFor(icon: AppIcon): Class<out PlaybackService> = when (icon) {
        AppIcon.CLASSIC -> PlaybackService::class.java
        AppIcon.SPECTRUM -> PlaybackServiceSpectrum::class.java
        AppIcon.SOLID -> PlaybackServiceSolid::class.java
        AppIcon.LIGHT -> PlaybackServiceLight::class.java
        AppIcon.WAVE -> PlaybackServiceWave::class.java
        AppIcon.MIDNIGHT -> PlaybackServiceMidnight::class.java
        AppIcon.DUSK -> PlaybackServiceDusk::class.java
        AppIcon.PAPER -> PlaybackServicePaper::class.java
        AppIcon.NEON -> PlaybackServiceNeon::class.java
        AppIcon.PASTEL -> PlaybackServicePastel::class.java
        AppIcon.LINE -> PlaybackServiceLine::class.java
        AppIcon.GOLD -> PlaybackServiceGold::class.java
    }

    /**
     * The service to connect to: the one running, or (none running) the one for the current app icon, enabled now and
     * the others disabled. Never switched while one runs: disabling a running service's component stops it, music and
     * all, so a new icon reaches Android Auto the next time playback starts from scratch.
     */
    fun target(context: Context): Class<out PlaybackService> {
        running?.let { return it }
        val want = classFor(AppIcons.current(context))
        runCatching {
            val pm = context.packageManager
            pm.setComponentEnabledSetting(ComponentName(context, want), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            AppIcon.entries.map { classFor(it) }.filter { it != want }.forEach { cls ->
                val cn = ComponentName(context, cls)
                if (pm.getComponentEnabledSetting(cn) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                    pm.setComponentEnabledSetting(cn, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
                }
            }
        }
        return want
    }
}
