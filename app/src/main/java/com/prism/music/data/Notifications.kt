package com.prism.music.data

import com.prism.music.tr
import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.prism.music.PrismActivity
import com.prism.music.R

/**
 * Prism's own notifications (besides playback and downloads, which have theirs): each kind has its channel, so the
 * listener can also silence one in Android's settings, and each is off when its switch in Prism's Settings is.
 */
object Notifications {
    const val NEW_RELEASES = "new_releases"
    const val REWIND = "rewind"
    const val RECOGNITION = "recognition"
    const val CONNECT = "connect"
    /** "Time for a fresh Spotify download?" every few months (1.8.1), quiet: no sound, no pop-up. */
    const val IMPORT_REMINDERS = "import_reminders"

    fun createChannels(context: Context) {
        val nm = NotificationManagerCompat.from(context)
        nm.createNotificationChannel(
            NotificationChannelCompat.Builder(IMPORT_REMINDERS, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(tr(R.string.imp_remind_channel)).setDescription(tr(R.string.imp_remind_channel_desc)).build()
        )
        // Pairing codes are wanted on screen at once (the other device is waiting for them).
        nm.createNotificationChannel(
            NotificationChannelCompat.Builder(CONNECT, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName(tr(R.string.prism_connect)).setDescription(tr(R.string.pairing_codes_when_another_device_wants)).build()
        )
        listOf(
            Triple(NEW_RELEASES, tr(R.string.new_releases), tr(R.string.new_music_from_artists_you_follow)),
            Triple(REWIND, tr(R.string.rewind_3), tr(R.string.when_your_year_in_music_is)),
            Triple(RECOGNITION, tr(R.string.song_recognition), tr(R.string.songs_recognized_after_you_were_offline)),
        ).forEach { (id, name, description) ->
            nm.createNotificationChannel(
                NotificationChannelCompat.Builder(id, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                    .setName(name).setDescription(description).build()
            )
        }
    }

    /** Whether Android lets Prism post at all (the permission on 13+, and notifications not blocked for the app). */
    fun allowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /** Opens Prism at [link] (a deep link MainActivity understands, e.g. "prism://releases"), or just Prism. */
    fun openIntent(context: Context, link: String?, requestCode: Int): PendingIntent {
        val intent = Intent(context, PrismActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (link != null) intent.data = Uri.parse(link)
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Posts a notification on [channel]; quietly does nothing when notifications aren't allowed. */
    fun post(context: Context, channel: String, id: Int, title: String, text: String, link: String?, build: (NotificationCompat.Builder) -> Unit = {}) {
        if (!allowed(context)) return
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openIntent(context, link, id))
            .also(build)
            .build()
        @Suppress("MissingPermission")
        runCatching { NotificationManagerCompat.from(context).notify(channel, id, n) }
    }
}
