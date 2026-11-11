package com.prism.music

import android.view.LayoutInflater
import android.widget.TextView
import com.prism.music.data.db.RecognitionEntity
import com.prism.music.widget.RecognizeWidgets
import com.prism.music.widget.RecognizeWidgets.Shape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The Recognize widget (A7): which layout each size gets, what it says, and that its layouts inflate. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class RecognizeWidgetTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun shapeBySize() {
        assertEquals(Shape.BUTTON, RecognizeWidgets.shapeFor(57, 57)) // 1×1
        assertEquals(Shape.BUTTON, RecognizeWidgets.shapeFor(130, 57)) // 2×1
        assertEquals(Shape.ROW, RecognizeWidgets.shapeFor(250, 70)) // 4×1
        assertEquals(Shape.CARD, RecognizeWidgets.shapeFor(250, 150)) // 4×2
    }

    @Test fun linesForEachState() {
        assertEquals("Recognize a song" to "Tap the button to find out what's playing", RecognizeWidgets.lines(context, null))
        val pending = RecognitionEntity(at = 1, status = "pending", signature = "x")
        assertEquals("Recognize a song" to "Saved, will check when you're online", RecognizeWidgets.lines(context, pending))
        val found = RecognitionEntity(at = 2, status = "found", title = "Alright", artist = "Kendrick Lamar", videoId = "67vr-3kpX3Q")
        assertEquals("Alright" to "Kendrick Lamar", RecognizeWidgets.lines(context, found))
    }

    @Test fun layoutsInflateWithTheirViews() {
        val inflater = LayoutInflater.from(context)
        for (shape in Shape.entries) {
            val root = inflater.inflate(shape.layout, null)
            assertNotNull("${shape.name} has the button", root.findViewById(R.id.rw_listen))
            if (shape != Shape.BUTTON) {
                assertEquals("Recognize a song", root.findViewById<TextView>(R.id.rw_title).text.toString())
                assertNotNull(root.findViewById(R.id.rw_art))
                assertNotNull(root.findViewById(R.id.rw_play))
                assertNotNull(root.findViewById(R.id.rw_open))
            }
        }
    }
}
