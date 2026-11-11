package com.prism.music.ui.tv

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.prism.music.R
import com.prism.music.tr
import com.prism.music.ui.screens.QrCode
import com.prism.music.ui.theme.PrismIcons

/**
 * First run (TV): welcome, then "Sign in with your phone" (the phone's Prism sends the account over the encrypted
 * home-network link, after the listener confirms on the phone) or "Continue without an account". The TV's language
 * is Prism's. Focus opens on "Sign in with your phone".
 */
@Composable
fun TvWelcomeScreen(onPhone: () -> Unit, onSkip: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Box(modifier.fillMaxSize().testTag(TAG_WELCOME), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size(180.dp))
            Text(tr(R.string.welcome_to_prism), style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.SemiBold), color = scheme.onBackground)
            Spacer(Modifier.height(10.dp))
            Text(tr(R.string.tv_welcome_line), style = MaterialTheme.typography.titleLarge, color = scheme.onSurfaceVariant, textAlign = TextAlign.Center)
            Spacer(Modifier.height(40.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                TvButton(tr(R.string.tv_sign_in_with_phone), onPhone, Modifier.tvItem("$TAG_WELCOME/phone", entry = true), icon = PrismIcons.Phone, primary = true)
                TvButton(tr(R.string.tv_continue_without_account), onSkip, Modifier.tvItem("$TAG_WELCOME/skip"))
            }
        }
    }
}

/**
 * Pairing a phone (TV): the code the phone enters (and a QR code the phone's camera can read instead), with the
 * steps beside it. Used for signing in with the phone and for "Use your phone as a remote". Focus is on Cancel; the
 * screen moves on by itself when the phone confirms.
 */
@Composable
fun TvPairScreen(
    title: String, code: String, link: String, tvName: String, status: String, onCancel: () -> Unit, modifier: Modifier = Modifier,
    /** Signing in: what to tap on the phone once paired, and how the library comes over too. */
    signIn: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    Row(modifier.fillMaxSize().padding(horizontal = 96.dp, vertical = 40.dp).testTag(TAG_PAIR), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.SemiBold), color = scheme.onBackground)
            Spacer(Modifier.height(20.dp))
            Step(1, tr(R.string.tv_pair_step_open))
            // Where on the phone, in its own words (1.8.1, Reddit: "I had to stop and think about how to finish"): the
            // line under the player's controls, a plain tap; then Remote beside this TV.
            Step(2, tr(R.string.tv_pair_step_where, tvName))
            Step(3, tr(R.string.tv_pair_step_code))
            if (signIn) Step(4, tr(R.string.tv_pair_step_sign_in))
            Spacer(Modifier.height(16.dp))
            Text(code.chunked(3).joinToString(" "), style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = 6.sp), color = scheme.onBackground, modifier = Modifier.testTag("$TAG_PAIR/code"))
            Spacer(Modifier.height(8.dp))
            Text(status, style = MaterialTheme.typography.titleMedium, color = scheme.primary)
            Spacer(Modifier.height(20.dp))
            TvButton(tr(R.string.cancel), onCancel, Modifier.tvItem("$TAG_PAIR/cancel", entry = true))
        }
        Spacer(Modifier.width(48.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(280.dp).background(Color.White, RoundedCornerShape(18.dp)).padding(18.dp)) { QrCode(link, Modifier.fillMaxSize()) }
            Spacer(Modifier.height(14.dp))
            Text(tr(R.string.tv_pair_qr), style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.width(280.dp))
        }
    }
}

@Composable
private fun Step(n: Int, text: String) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).tvGlass(CircleShape), contentAlignment = Alignment.Center) {
            Text("$n", style = MaterialTheme.typography.titleMedium, color = scheme.onSurface)
        }
        Spacer(Modifier.width(16.dp))
        Text(text, style = MaterialTheme.typography.titleMedium, color = scheme.onBackground)
    }
}

const val TAG_WELCOME = "tv_welcome"
const val TAG_PAIR = "tv_pair"
