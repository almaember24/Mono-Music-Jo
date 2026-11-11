package com.prism.music.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import com.prism.music.R
import com.prism.music.container
import com.prism.music.data.db.RecognitionEntity
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import com.prism.music.ui.screens.RecognizeActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The Recognize widget: a round button that opens the listening card ([RecognizeActivity]; the microphone can only be
 * used from the foreground, so the widget itself never listens). Made wider ([Shape]: a row at 3×1 / 4×1, a card from
 * 3×2) it also shows the last song recognized, with Play, or that one is saved to look up once there's a network.
 */
class RecognizeWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = RecognizeWidgets.refresh(context)
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) = RecognizeWidgets.refresh(context)
}

object RecognizeWidgets {
    const val ACTION_PLAY = "com.prism.music.widget.RECOGNIZED_PLAY"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    enum class Shape(val layout: Int) {
        /** Just the round button (1×1, 2×1, or anything narrow). */
        BUTTON(R.layout.widget_recognize_small),
        /** One row: the last song, Play and the button (3×1, 4×1). */
        ROW(R.layout.widget_recognize_row),
        /** The last song as a card with a Play pill, beside a big button (3×2 and up). */
        CARD(R.layout.widget_recognize),
    }

    /** Which layout fits a widget [widthDp] × [heightDp] big. */
    fun shapeFor(widthDp: Int, heightDp: Int) = when {
        widthDp < 180 -> Shape.BUTTON
        heightDp < 130 -> Shape.ROW
        else -> Shape.CARD
    }

    /** The card's two lines for the newest recognition (or none yet). */
    fun lines(context: Context, r: RecognitionEntity?): Pair<String, String> = when {
        r == null -> com.prism.music.tr(R.string.widget_recognize_empty) to com.prism.music.tr(R.string.widget_recognize_empty_hint)
        r.status == "pending" -> com.prism.music.tr(R.string.widget_recognize_empty) to com.prism.music.tr(R.string.widget_recognize_waiting)
        else -> (r.title ?: "") to (r.artist ?: "")
    }

    /** Draws every placed Recognize widget from the newest recognition. Called when one is found, saved or removed. */
    fun refresh(context: Context) {
        val app = context.applicationContext
        val manager = AppWidgetManager.getInstance(app) ?: return
        val ids = runCatching { manager.getAppWidgetIds(ComponentName(app, RecognizeWidget::class.java)) }.getOrNull()
        if (ids == null || ids.isEmpty()) return
        scope.launch {
            val latest = runCatching { app.container.db.recognitions().latest() }.getOrNull()
            val art = latest?.cover?.let { Widgets.squareCover(app, it, 168, 0.14f) }
            for (id in ids) runCatching { manager.updateAppWidget(id, views(app, manager, id, latest, art)) }
        }
    }

    private fun views(context: Context, manager: AppWidgetManager, id: Int, r: RecognitionEntity?, art: android.graphics.Bitmap?): RemoteViews {
        // Android 12+ picks the layout by the size it's drawn at; older launchers say the size in the options.
        if (Build.VERSION.SDK_INT >= 31) return RemoteViews(
            mapOf(
                SizeF(40f, 40f) to small(context),
                SizeF(180f, 40f) to song(context, Shape.ROW, r, art),
                SizeF(180f, 130f) to song(context, Shape.CARD, r, art),
            ),
        )
        val o = manager.getAppWidgetOptions(id)
        return when (val shape = shapeFor(o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH), o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT))) {
            Shape.BUTTON -> small(context)
            else -> song(context, shape, r, art)
        }
    }

    private fun listen(context: Context) = PendingIntent.getActivity(
        context, 20, Intent(context, RecognizeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun small(context: Context) = RemoteViews(context.packageName, R.layout.widget_recognize_small).apply {
        setOnClickPendingIntent(R.id.rw_listen, listen(context))
        if (Widgets.whiteLight(context)) setInt(R.id.rw_disc, "setBackgroundResource", R.drawable.widget_circle)
    }

    private fun song(context: Context, shape: Shape, r: RecognitionEntity?, art: android.graphics.Bitmap?) = RemoteViews(context.packageName, shape.layout).apply {
        val (title, artist) = lines(context, r)
        setTextViewText(R.id.rw_title, title)
        setTextViewText(R.id.rw_artist, artist)
        if (art != null) setImageViewBitmap(R.id.rw_art, art) else setImageViewResource(R.id.rw_art, R.drawable.ic_tile_recognize)
        setOnClickPendingIntent(R.id.rw_listen, listen(context))
        if (shape == Shape.CARD && Widgets.whiteLight(context)) setInt(R.id.rw_listen, "setBackgroundResource", R.drawable.widget_circle)
        setOnClickPendingIntent(
            R.id.rw_open,
            PendingIntent.getActivity(
                context, 21,
                Intent(context, com.prism.music.PrismActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse("prism://recognized"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        // Play only for a song found on YouTube Music.
        val videoId = r?.videoId?.takeIf { r.status == "found" }
        setViewVisibility(R.id.rw_play, if (videoId != null) View.VISIBLE else View.GONE)
        if (videoId != null && r != null) setOnClickPendingIntent(
            R.id.rw_play,
            PendingIntent.getBroadcast(
                context, 22,
                Intent(context, WidgetActionReceiver::class.java).setAction(ACTION_PLAY)
                    .putExtra("id", videoId).putExtra("title", r.title).putExtra("artist", r.artist).putExtra("cover", r.cover),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
    }

    /** The song the widget's Play button stands for. */
    fun songFrom(intent: Intent): Song? {
        val id = intent.getStringExtra("id") ?: return null
        return Song(id = id, title = intent.getStringExtra("title").orEmpty(), artists = listOf(ArtistRef(intent.getStringExtra("artist").orEmpty())), thumbnail = intent.getStringExtra("cover"))
    }
}
