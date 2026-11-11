package com.prism.music.ui.tv

import android.service.dreams.DreamService
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.prism.music.R
import com.prism.music.container
import com.prism.music.ui.theme.LocalContainer
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/**
 * Prism's system screensaver (1.8.0, plan §2), where the TV (or phone) lets the listener pick one: the ambient screen
 * while music plays, and otherwise the Prism mark and the time, drifting (burn-in safe).
 */
class PrismDream : DreamService(), LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry

    override fun onCreate() {
        super.onCreate()
        saved.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        val c = applicationContext.container
        setContentView(ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@PrismDream)
            setViewTreeSavedStateRegistryOwner(this@PrismDream)
            setContent { CompositionLocalProvider(LocalContainer provides c) { TvTheme { DreamContent() } } }
        })
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        registry.currentState = Lifecycle.State.RESUMED
    }

    override fun onDreamingStopped() {
        registry.currentState = Lifecycle.State.CREATED
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        registry.currentState = Lifecycle.State.DESTROYED
        super.onDetachedFromWindow()
    }
}

/** The screensaver's view: what's playing as the ambient screen, else the mark and the time. */
@Composable
fun DreamContent() {
    val c = LocalContainer.current
    val song by c.player.currentSong.collectAsState()
    if (song != null) { AmbientNow(c, Modifier.fillMaxSize().testTag(TAG_DREAM)); return }
    val clock by produceState(System.currentTimeMillis()) { while (true) { value = System.currentTimeMillis(); delay(10_000) } }
    val drift = ambientDrift(clock)
    Box(Modifier.fillMaxSize().tvBackdrop(null, Color(0xFF050505)).testTag(TAG_DREAM), contentAlignment = Alignment.Center) {
        Column(Modifier.offset(drift.x * 3, drift.y * 3), horizontalAlignment = Alignment.CenterHorizontally) {
            Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size(160.dp))
            Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(clock)), style = MaterialTheme.typography.displayMedium, color = Color(0xFFD9D9D9).copy(alpha = 0.7f))
        }
    }
}

const val TAG_DREAM = "tv_dream"
