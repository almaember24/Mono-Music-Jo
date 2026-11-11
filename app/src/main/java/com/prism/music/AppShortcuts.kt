package com.prism.music

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat

/**
 * The shortcuts a long press on Prism's app icon shows (Reddit 1.7: "long-press app icon quick actions"). Each opens a
 * prism:// link that [MainActivity] handles like a widget's. Published at start; dynamic, so they follow whichever
 * launcher icon (activity alias) is picked.
 */
object AppShortcuts {
    private class Spec(val id: String, val short: String, val long: String, val link: String, val icon: Int)

    private val specs = listOf(
        Spec("search", tr(R.string.search), tr(R.string.search), "prism://search", R.drawable.ic_shortcut_search),
        Spec("recognize", tr(R.string.recognize), tr(R.string.widget_recognize_empty), "prism://recognize", R.drawable.ic_shortcut_recognize),
        Spec("liked", tr(R.string.liked_songs), tr(R.string.liked_songs), "prism://liked", R.drawable.ic_shortcut_liked),
        Spec("downloads", tr(R.string.downloads), tr(R.string.downloads), "prism://downloads", R.drawable.ic_shortcut_downloads),
    )

    fun publish(context: Context) {
        val max = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context).coerceAtLeast(1)
        val shortcuts = specs.take(max).mapIndexed { i, s ->
            ShortcutInfoCompat.Builder(context, s.id)
                .setShortLabel(s.short)
                .setLongLabel(s.long)
                .setIcon(IconCompat.createWithResource(context, s.icon))
                .setRank(i)
                .setIntent(Intent(Intent.ACTION_VIEW, Uri.parse(s.link), context, PrismActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
                .build()
        }
        ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
    }
}
