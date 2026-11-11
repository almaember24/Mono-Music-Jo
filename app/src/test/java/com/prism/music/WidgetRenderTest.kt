package com.prism.music

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Inflates Prism's own home-screen widgets (as the launcher would, without RemoteViews) over a wallpaper-like
 * gradient, part-way through a song, onto one sheet: app/build/widget-previews/widgets.png. Look at it after
 * changing a widget layout or its drawables.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "xxhdpi")
class WidgetRenderTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val density get() = context.resources.displayMetrics.density

    private fun render(layout: Int, wDp: Int, hDp: Int, white: Boolean = false): Bitmap {
        val w = (wDp * density).toInt()
        val h = (hDp * density).toInt()
        val host = FrameLayout(context)
        val v = LayoutInflater.from(context).inflate(layout, host, false)
        v.findViewById<TextView?>(R.id.widget_title)?.text = "Blinding Lights"
        v.findViewById<TextView?>(R.id.widget_artist)?.text = "The Weeknd"
        v.findViewById<ProgressBar?>(R.id.widget_progress)?.progress = 420
        v.findViewById<ProgressBar?>(R.id.widget_progress_white)?.progress = 420
        if (white) {
            // What Widgets.views does in White light.
            v.findViewById<View?>(R.id.widget_progress_white)?.let { white ->
                white.visibility = View.VISIBLE
                v.findViewById<View>(R.id.widget_progress).visibility = View.GONE
            }
            v.findViewById<View?>(R.id.widget_play)?.takeIf { layout != R.layout.widget_disc }?.setBackgroundResource(R.drawable.widget_circle)
            v.findViewById<View?>(R.id.rw_listen)?.takeIf { layout == R.layout.widget_recognize }?.setBackgroundResource(R.drawable.widget_circle)
            v.findViewById<View?>(R.id.rw_disc)?.setBackgroundResource(R.drawable.widget_circle)
        }
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        return bmp
    }

    @Test
    fun prismWidgets() = sheet("widgets.png", white = false)

    /** The same widgets in White light (Appearance → Color → Style). */
    @Test
    fun whiteLightWidgets() = sheet("widgets-white.png", white = true)

    private fun sheet(name: String, white: Boolean) {
        val shots = listOf(
            render(R.layout.widget_prism, 320, 150, white),
            render(R.layout.widget_strip, 320, 64, white),
            render(R.layout.widget_cover, 150, 150, white),
            render(R.layout.widget_disc, 150, 150, white),
            render(R.layout.widget_recognize, 320, 110, white),
            render(R.layout.widget_recognize_small, 80, 80, white),
        )
        val gap = (16 * density).toInt()
        val width = shots.maxOf { it.width } + gap * 2
        val height = shots.sumOf { it.height + gap } + gap
        val sheet = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(sheet)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(), intArrayOf(0xFF2B3A55.toInt(), 0xFF4E3B5C.toInt(), 0xFF1E2A30.toInt()), null, Shader.TileMode.CLAMP)
        })
        var y = gap
        for (s in shots) {
            c.drawBitmap(s, gap.toFloat(), y.toFloat(), null)
            y += s.height + gap
        }
        val out = File("build/widget-previews").apply { mkdirs() }
        File(out, name).outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
        check(shots.all { it.getPixel(it.width / 2, it.height / 2) != Color.TRANSPARENT }) { "a widget drew nothing" }
    }
}
