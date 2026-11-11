package com.prism.music.ui.theme

import android.content.res.Configuration
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalConfiguration

/** Width classes as Material's window size classes define them (600 / 840 dp). */
enum class WidthClass { COMPACT, MEDIUM, EXPANDED }

/**
 * The shape of the window Prism is drawn in, so screens can lay themselves out for phones, landscape, tablets and
 * foldables. Provided at the root as [LocalWindowLayout].
 */
@Immutable
data class WindowLayout(
    val widthDp: Int,
    val heightDp: Int,
    val landscape: Boolean,
    /** A foldable half-open like a laptop: the screen's top half stands up, the bottom half lies flat. */
    val tabletop: Boolean = false,
    /** The fold or hinge, in window pixels (null on flat screens or when it isn't separating anything). */
    val hinge: Rect? = null,
) {
    val width: WidthClass = when {
        widthDp >= 840 -> WidthClass.EXPANDED
        widthDp >= 600 -> WidthClass.MEDIUM
        else -> WidthClass.COMPACT
    }

    /** Short and wide: a phone on its side. */
    val shortLandscape: Boolean get() = landscape && heightDp < 480

    /** Tabs down the side (a navigation rail) instead of along the bottom. */
    val rail: Boolean get() = width != WidthClass.COMPACT

    /** The player shows the cover and controls beside lyrics / up next, instead of one above the other. */
    val twoPanePlayer: Boolean get() = !tabletop && ((landscape && width != WidthClass.COMPACT) || width == WidthClass.EXPANDED)

    /** A list and the page it opens fit side by side (Settings' hub and a page). */
    val listDetail: Boolean get() = width == WidthClass.EXPANDED

    companion object {
        val PHONE = WindowLayout(widthDp = 400, heightDp = 860, landscape = false)
    }
}

val LocalWindowLayout = staticCompositionLocalOf { WindowLayout.PHONE }

/**
 * Room the app's chrome takes along the start edge (the navigation rail on wide windows). Page backgrounds still
 * reach under it; [ScreenBackdrop] and the pages that draw their own keep their content clear of it.
 */
val LocalChromeStart = compositionLocalOf { 0.dp }

@Composable
fun rememberWindowLayout(): WindowLayout {
    val config = LocalConfiguration.current
    val info = currentWindowAdaptiveInfo()
    val posture = info.windowPosture
    // A separating fold only: the hinge of a book-style foldable lying flat doesn't split the content.
    val hinge = posture.hingeList.firstOrNull { it.isSeparating }?.bounds
    return WindowLayout(
        widthDp = config.screenWidthDp,
        heightDp = config.screenHeightDp,
        landscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE,
        tabletop = posture.isTabletop,
        hinge = hinge,
    )
}
