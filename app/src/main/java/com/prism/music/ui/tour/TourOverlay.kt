package com.prism.music.ui.tour

import com.prism.music.R
import com.prism.music.tr
import com.prism.music.trp
import android.content.Context
import com.prism.music.ui.components.tickWhileShown
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.prism.music.BuildConfig
import com.prism.music.data.Notifications
import com.prism.music.ui.components.PrismButton
import com.prism.music.ui.components.PrismOutlinedButton
import com.prism.music.ui.components.PrismTextButton
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.PrismIcons
import com.prism.music.ui.theme.PrismTokens
import kotlin.math.absoluteValue
import kotlinx.coroutines.launch

/** What a tour shows: [steps] after an opening page ([full]: the whole tour, else what's new since the last one). */
class TourPlan(val full: Boolean, val freshInstall: Boolean, val steps: List<TourStep>)

/**
 * The tour to show now, if any: the whole of it after a new install or the first time a release with tours runs
 * (Prism 1.7 for everyone), later only the steps of releases since the last tour seen. Null when there's nothing new.
 */
fun pendingTour(context: Context, returning: Boolean): TourPlan? {
    val seen = Tour.seenVersion(context)
    val steps = Tour.stepsAfter(seen)
    if (steps.isEmpty()) return null
    return TourPlan(Tour.isFullTour(seen), freshInstall = !returning, steps = steps)
}

/** The whole tour, from Settings → About → Take the tour. */
fun fullTour(returning: Boolean) = TourPlan(full = true, freshInstall = !returning, steps = Tour.steps)

/**
 * The tour: a page at a time over the app, swiped or stepped through with Next, skippable at any point. Finishing or
 * skipping it marks it seen for this version ([Tour.markSeen]).
 */
@Composable
fun TourOverlay(plan: TourPlan, onOpen: (String) -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val pages = plan.steps.size + 1
    val pager = rememberPagerState { pages }
    val scope = rememberCoroutineScope()
    fun finish() { Tour.markSeen(context); onDone() }
    BackHandler { if (pager.currentPage > 0) scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } else finish() }
    val shown by animateFloatAsState(1f, tween(320), label = "tourIn")

    Box(
        Modifier.fillMaxSize().graphicsLayer { alpha = shown }
            .background(Color(0xF20B0B0B))
            // Taps on the dimmed app behind do nothing while the tour is up.
            .clickable(remember { MutableInteractionSource() }, indication = null) {},
    ) {
        // The split light, faint, behind the cards.
        Box(
            Modifier.fillMaxSize().background(
                Brush.radialGradient(PrismTokens.spectrumColors.map { (_, c) -> c.copy(alpha = 0.10f) }, radius = 1400f),
            ),
        )
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (pager.currentPage == 0) "" else tr(R.string.of_2, pager.currentPage, pages - 1),
                    color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f).padding(start = 12.dp),
                )
                PrismTextButton(onClick = ::finish) { Text(tr(R.string.skip_tour), color = Color.White.copy(alpha = 0.8f)) }
            }
            HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { page ->
                val offset = ((pager.currentPage - page) + pager.currentPageOffsetFraction).absoluteValue.coerceIn(0f, 1f)
                Box(
                    Modifier.fillMaxSize().graphicsLayer { alpha = 1f - offset * 0.6f; val s = 1f - offset * 0.06f; scaleX = s; scaleY = s },
                    contentAlignment = Alignment.Center,
                ) {
                    if (page == 0) Opening(plan)
                    else StepPage(plan.steps[page - 1], onShowMe = { route -> Tour.markSeen(context); onDone(); onOpen(route) })
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    if (pager.currentPage > 0) PrismTextButton(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }) {
                        Text(tr(R.string.back), color = Color.White.copy(alpha = 0.8f))
                    }
                }
                Dots(pages, pager.currentPage)
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    val last = pager.currentPage == pages - 1
                    PrismButton(onClick = { if (last) finish() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }) {
                        Text(if (last) tr(R.string.done) else if (pager.currentPage == 0) tr(R.string.start) else tr(R.string.widget_next))
                    }
                }
            }
        }
    }
}

@Composable
private fun Opening(plan: TourPlan) {
    val (title, body) = when {
        !plan.full -> tr(R.string.what_s_new_in_prism, BuildConfig.VERSION_NAME) to
            trp(R.plurals.n_things_to_know, plan.steps.size)
        plan.freshInstall -> tr(R.string.welcome_to_prism) to tr(R.string.your_music_refracted_here_s_a)
        else -> tr(R.string.welcome_to_the_new_prism) to tr(R.string.prism_is_redrawn_from_the_ground, BuildConfig.VERSION_NAME)
    }
    PageColumn {
        Halo(PrismIcons.Spatial, big = true)
        Spacer(Modifier.height(30.dp))
        Text(title, color = Color.White, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(12.dp))
        Text(body, color = Color.White.copy(alpha = 0.78f), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(18.dp))
        Text(tr(R.string.you_can_take_it_again_from), color = Color.White.copy(alpha = 0.5f), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
    }
}

@Composable
private fun StepPage(step: TourStep, onShowMe: (String) -> Unit) {
    val c = LocalContainer.current
    val context = LocalContext.current
    PageColumn {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Halo(step.icon, big = true)
            if (step.extras.isNotEmpty()) Column(Modifier.padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                step.extras.forEach { Halo(it, big = false) }
            }
        }
        Spacer(Modifier.height(26.dp))
        Text(step.title, color = Color.White, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(10.dp))
        Text(step.body, color = Color.White.copy(alpha = 0.78f), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        if (step.points.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color.White.copy(alpha = 0.07f))
                    .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(20.dp)).padding(horizontal = 18.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                step.points.forEachIndexed { i, p ->
                    Row(verticalAlignment = Alignment.Top) {
                        Box(Modifier.padding(top = 7.dp).size(7.dp).clip(CircleShape).background(PrismTokens.hue(i % 7)))
                        Spacer(Modifier.width(12.dp))
                        Text(p, color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        if (step.kind == TourStep.Kind.NOTIFICATIONS) {
            Spacer(Modifier.height(22.dp))
            var allowed by remember { mutableStateOf(Notifications.allowed(context)) }
            // After Android's prompt answers, look again (it shows over the app, and Prism resumes under it).
            val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
            LaunchedEffect(lifecycle) { lifecycle.tickWhileShown(700) { allowed = Notifications.allowed(context) } }
            if (allowed) Text(tr(R.string.notifications_are_on), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyMedium)
            else if (Build.VERSION.SDK_INT >= 33) PrismOutlinedButton(onClick = {
                context.getSharedPreferences("prompts", Context.MODE_PRIVATE).edit().putBoolean("notifications_asked", true).apply()
                c.notificationPrompt?.invoke()
            }) { Text(tr(R.string.allow_notifications), color = Color.White) }
            else Text(tr(R.string.turn_them_on_in_android_s), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyMedium)
        }
        step.showMe?.let { route ->
            Spacer(Modifier.height(18.dp))
            PrismOutlinedButton(onClick = { onShowMe(route) }) { Text(tr(R.string.show_me), color = Color.White) }
        }
    }
}

/** A page's content: a phone's width, centred, scrolling if a small screen needs it. */
@Composable
private fun PageColumn(content: @Composable () -> Unit) {
    Column(
        Modifier.widthIn(max = 460.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

/** An icon on frosted glass ringed with the split light. */
@Composable
private fun Halo(icon: ImageVector, big: Boolean) {
    val size = if (big) 112.dp else 46.dp
    Box(
        Modifier.size(size).clip(CircleShape).background(Color.White.copy(alpha = 0.08f))
            .border(if (big) 2.dp else 1.5.dp, PrismTokens.spectrumRing(), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(if (big) 48.dp else 22.dp))
    }
}

@Composable
private fun Dots(count: Int, current: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        // Many pages: a short run around the current one, so the dots never crowd the buttons.
        val shown = if (count <= 9) 0 until count else (current - 4).coerceIn(0, count - 9).let { it until it + 9 }
        for (i in shown) Box(
            Modifier.size(if (i == current) 8.dp else 6.dp).clip(CircleShape)
                .background(if (i == current) Color.White else Color.White.copy(alpha = 0.3f)),
        )
    }
}
