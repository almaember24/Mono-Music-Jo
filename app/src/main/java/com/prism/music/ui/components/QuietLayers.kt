package com.prism.music.ui.components

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

/**
 * Android 9 and older keep Compose's graphics layers as hidden views, and every time such a layer redraws Compose flips
 * its view's visibility. Android takes each flip for a change on screen and tells accessibility services: over a moving
 * background ~4 "window content changed" a second, on every tab, with nothing happening, so screen readers and
 * uiautomator never settled (1.7.9's check; stack seen in GraphicsViewLayer.record). What the screen says hasn't changed,
 * and Compose reports real changes its own way, so these are stopped here.
 */
private class QuietLayersFrame(context: Context) : FrameLayout(context) {
    override fun notifySubtreeAccessibilityStateChanged(child: View, source: View, changeType: Int) {
        if (isGraphicsLayerView(source.javaClass.name)) return
        super.notifySubtreeAccessibilityStateChanged(child, source, changeType)
    }
}

/** Compose's layer views and their container (Prism isn't minified, so the names hold). */
internal fun isGraphicsLayerView(className: String) = className.startsWith("androidx.compose.ui.graphics.layer.")

/** Android 10 has real render nodes for these layers: no views, nothing to stop. */
internal fun graphicsLayersAreViews(sdk: Int) = sdk < 29

/** Call right after setContent: puts [QuietLayersFrame] around the activity's ComposeView, on the Androids that need it. */
fun Activity.quietGraphicsLayers() {
    if (!graphicsLayersAreViews(android.os.Build.VERSION.SDK_INT)) return
    val content = findViewById<ViewGroup>(android.R.id.content) ?: return
    val compose = content.getChildAt(0) ?: return
    val full = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    content.removeView(compose)
    content.addView(QuietLayersFrame(this).apply { addView(compose, full) }, full)
}
