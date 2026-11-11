package com.prism.music.ui.theme

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/** Player haptics, gated by the Haptics setting. */
class Haptics(private val view: View, private val enabled: () -> Boolean) {
    private fun buzz(constant: Int) {
        if (enabled()) view.performHapticFeedback(constant)
    }

    /** A button press: play/pause, skip, like, shuffle… */
    fun click() = buzz(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)

    /** A lighter tick, for toggles and small controls. */
    fun tick() = buzz(HapticFeedbackConstants.CLOCK_TICK)

    /** A swipe crossed the point where letting go changes the song. */
    fun threshold() = buzz(if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE else HapticFeedbackConstants.CONTEXT_CLICK)

    /** A swipe changed the song. */
    fun gestureEnd() = buzz(if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.GESTURE_END else HapticFeedbackConstants.CONTEXT_CLICK)
}

@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    val settings = LocalAppSettings.current
    val latest = androidx.compose.runtime.rememberUpdatedState(settings.haptics)
    return remember(view) { Haptics(view) { latest.value } }
}
