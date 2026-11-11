package com.prism.music

import android.graphics.Color
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import androidx.test.core.app.ApplicationProvider
import com.prism.music.widget.WidgetButtons
import com.prism.music.widget.WidgetOptions
import com.prism.music.widget.WidgetStyle
import com.prism.music.widget.Widgets
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Reddit, 1.7.9 ("a setting to change THIS color?", the Prism widget's play button): a widget's own settings pick the
 * play button's and progress line's color. The widget is built as the launcher gets it (RemoteViews) and applied.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class WidgetButtonColorTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun applied(style: WidgetStyle, o: WidgetOptions) =
        runBlocking { Widgets.preview(context, style, o) }.apply(context, FrameLayout(context))

    @Test fun yourColorTintsThePlayButtonAndProgress() {
        val blue = 0xFF2E7DFF.toInt()
        val v = applied(WidgetStyle.PRISM, WidgetOptions(buttons = WidgetButtons.COLOR, buttonColor = blue))
        val play = v.findViewById<ImageView>(R.id.widget_play)
        assertEquals(blue, play.backgroundTintList?.defaultColor)
        assertEquals(blue, v.findViewById<ProgressBar>(R.id.widget_progress).progressTintList?.defaultColor)
        // White on a deep color, dark on a pale one.
        assertNotEquals(null, play.colorFilter)
        val white = applied(WidgetStyle.PRISM, WidgetOptions(buttons = WidgetButtons.WHITE)).findViewById<ImageView>(R.id.widget_play)
        assertEquals(Color.WHITE, white.backgroundTintList?.defaultColor)
    }

    @Test fun asBeforeByDefault() {
        val play = applied(WidgetStyle.PRISM, WidgetOptions()).findViewById<ImageView>(R.id.widget_play)
        assertEquals(null, play.backgroundTintList)
    }
}
