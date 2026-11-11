package com.prism.music.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Android 9's accessibility event storm: only Compose's layer views are kept quiet, and only where layers are views. */
class QuietLayersTest {
    @Test
    fun onlyLayerViewsAreStopped() {
        assertTrue(isGraphicsLayerView("androidx.compose.ui.graphics.layer.ViewLayer"))
        assertTrue(isGraphicsLayerView("androidx.compose.ui.graphics.layer.view.ViewLayerContainer"))
        // Compose's own view and what's embedded in pages (a sign-in page, a video) still speak.
        assertFalse(isGraphicsLayerView("androidx.compose.ui.platform.AndroidComposeView"))
        assertFalse(isGraphicsLayerView("android.webkit.WebView"))
        assertFalse(isGraphicsLayerView("android.view.TextureView"))
    }

    @Test
    fun onlyUpToAndroid9() {
        for (sdk in 26..28) assertTrue("sdk $sdk", graphicsLayersAreViews(sdk))
        for (sdk in 29..36) assertFalse("sdk $sdk", graphicsLayersAreViews(sdk))
    }
}
