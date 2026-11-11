package com.prism.music.ui.screens

import com.prism.music.R
import com.prism.music.tr
import com.prism.music.trp
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.music.ui.components.PrismButton
import com.prism.music.ui.components.PrismOutlinedButton
import com.prism.music.ui.components.PrismTextButton
import com.prism.music.ui.theme.LocalAppSettings
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.PrismIcons
import com.prism.music.ui.theme.PrismTokens
import com.prism.music.ui.theme.drawPrismAvatar
import kotlinx.coroutines.launch

/**
 * The listener's picture, wherever Prism shows who they are: their Prism Account's picture, else (signed in) their
 * Google picture, else their initial on glass ringed in split light (with a Prism Account), else a person.
 */
@Composable
fun ProfilePicture(modifier: Modifier = Modifier, description: String = tr(R.string.your_account)) {
    val c = LocalContainer.current
    val s = LocalAppSettings.current
    val profile by c.account.profile.collectAsState()
    val p = profile
    BoxWithConstraints(
        modifier.clip(CircleShape).background(com.prism.music.ui.components.glassFill(1.4f))
            .border(if (p != null) 1.5.dp else 1.dp, if (p != null) PrismTokens.spectrumRing() else com.prism.music.ui.components.glassEdge(), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        val side = maxWidth
        when {
            p != null && p.picture > 0 && c.account.pictureFile.exists() -> coil3.compose.AsyncImage(
                coil3.request.ImageRequest.Builder(LocalContext.current).data(c.account.pictureFile)
                    .memoryCacheKey("account#${p.picture}").diskCacheKey("account#${p.picture}").build(),
                description, Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
            )
            p != null && com.prism.music.ui.theme.PrismAvatar.of(p.avatar) != null -> {
                val a = com.prism.music.ui.theme.PrismAvatar.of(p.avatar)!!
                AvatarArt(a, Modifier.fillMaxSize().clearAndSetSemantics { contentDescription = description })
            }
            p != null -> Text(
                p.name.trim().take(1).uppercase(), Modifier.clearAndSetSemantics { contentDescription = description },
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = (side.value * 0.42f).sp, fontWeight = FontWeight.Light),
            )
            s.isLoggedIn && s.accountAvatar.isNotBlank() -> coil3.compose.AsyncImage(s.accountAvatar, description, Modifier.fillMaxSize())
            else -> Icon(PrismIcons.Person, description, Modifier.size(side * 0.52f), tint = MaterialTheme.colorScheme.onSurface)
        }
    }
}

/** One of Prism's own profile pictures, drawn at any size (clip it to a circle). */
@Composable
fun AvatarArt(avatar: com.prism.music.ui.theme.PrismAvatar, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier) { drawPrismAvatar(avatar) }
}

/**
 * Prism's own pictures to pick from, after "Your photos": a row of small discs, the chosen one ringed in split light.
 */
@Composable
private fun AvatarPicker(selected: String?, photoChosen: Boolean, onPhotos: () -> Unit, onPick: (com.prism.music.ui.theme.PrismAvatar) -> Unit) {
    androidx.compose.foundation.lazy.LazyRow(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item("photos") {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp)) {
                Box(
                    Modifier.size(56.dp).clip(CircleShape).background(com.prism.music.ui.components.glassFill(1.6f))
                        .border(if (photoChosen) 2.dp else 1.dp, if (photoChosen) PrismTokens.spectrumRing() else com.prism.music.ui.components.glassEdge(), CircleShape)
                        .clickable(onClickLabel = tr(R.string.pick_a_photo)) { onPhotos() },
                    contentAlignment = Alignment.Center,
                ) { Icon(PrismIcons.Photo, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurface) }
                Text(tr(R.string.photos), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
            }
        }
        items(com.prism.music.ui.theme.PrismAvatar.entries.size) { i ->
            val a = com.prism.music.ui.theme.PrismAvatar.entries[i]
            val on = a.id == selected
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp)) {
                Box(
                    Modifier.size(56.dp).then(if (on) Modifier.border(2.dp, PrismTokens.spectrumRing(), CircleShape).padding(4.dp) else Modifier)
                        .clip(CircleShape).clickable(onClickLabel = a.label) { onPick(a) },
                ) { AvatarArt(a, Modifier.fillMaxSize()) }
                Text(
                    a.label, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                    color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/**
 * Making or changing the Prism Account: a picture (from the photo picker) and a name, kept on this phone. With
 * [onboarding] it's a step of setting up (after the welcome screen), with "Skip" beside "Make my account". Otherwise
 * it's the account's own page: change it, copy a YouTube Music library into Prism, or remove it (the music stays).
 */
@Composable
fun PrismAccountScreen(onboarding: Boolean, bottomPadding: Dp, onDone: () -> Unit) {
    val c = LocalContainer.current
    val s = LocalAppSettings.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val profile by c.account.profile.collectAsState()
    var name by remember { mutableStateOf(profile?.name ?: s.accountName.trim()) }
    var removing by remember { mutableStateOf(false) }
    var bringing by remember { mutableStateOf<String?>(null) }
    // A picture picked before the account exists waits until it's made.
    var pendingPicture by remember { mutableStateOf<android.net.Uri?>(null) }
    var pendingAvatar by remember { mutableStateOf<com.prism.music.ui.theme.PrismAvatar?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        if (c.account.profile.value == null) { pendingPicture = uri; pendingAvatar = null }
        else scope.launch { if (!c.account.setPicture(uri)) Toast.makeText(context, tr(R.string.couldn_t_use_that_picture), Toast.LENGTH_SHORT).show() }
    }
    fun pickPicture() = pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    fun save() {
        if (name.isBlank()) { Toast.makeText(context, tr(R.string.type_a_name_first), Toast.LENGTH_SHORT).show(); return }
        val made = c.account.profile.value == null
        c.account.create(name)
        scope.launch {
            pendingPicture?.let { c.account.setPicture(it) }
            pendingAvatar?.let { c.account.setAvatar(it.id) }
            pendingPicture = null
            pendingAvatar = null
            if (made) Toast.makeText(context, tr(R.string.your_prism_account_is_ready), Toast.LENGTH_SHORT).show()
            if (made || onboarding) onDone()
        }
    }

    if (removing) AlertDialog(
        onDismissRequest = { removing = false },
        title = { Text(tr(R.string.remove_your_prism_account)) },
        text = { Text(tr(R.string.only_your_name_and_picture_go)) },
        confirmButton = { PrismTextButton({ removing = false; c.account.delete(); onDone() }) { Text(tr(R.string.remove)) } },
        dismissButton = { PrismTextButton({ removing = false }) { Text(tr(R.string.keep_it)) } },
    )

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).then(if (onboarding) Modifier.statusBarsPadding().navigationBarsPadding() else Modifier)
            .padding(bottom = bottomPadding + 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (!onboarding) Row(Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp)) {
            com.prism.music.ui.components.RoundAction(PrismIcons.ArrowBack, tr(R.string.back), glass = true) { onDone() }
        }
        Column(Modifier.widthIn(max = 480.dp).fillMaxWidth().padding(horizontal = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(if (onboarding) 48.dp else 8.dp))
            Text(
                if (profile == null) tr(R.string.your_prism_account) else profile!!.name,
                style = com.prism.music.ui.components.pageTitleStyle(), textAlign = TextAlign.Center,
            )
            Text(
                tr(R.string.who_you_are_in_prism_kept),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp),
            )
            Spacer(Modifier.height(28.dp))
            // The picture: tap to pick one.
            Box(Modifier.size(132.dp).clip(CircleShape).clickable(onClickLabel = tr(R.string.pick_a_picture)) { pickPicture() }, contentAlignment = Alignment.Center) {
                val pending = pendingPicture
                val pendingArt = pendingAvatar
                if (pending != null) coil3.compose.AsyncImage(
                    pending, tr(R.string.your_picture), Modifier.fillMaxSize().clip(CircleShape).border(1.5.dp, PrismTokens.spectrumRing(), CircleShape), contentScale = ContentScale.Crop,
                ) else if (pendingArt != null) AvatarArt(pendingArt, Modifier.fillMaxSize().clip(CircleShape))
                else if (profile != null) ProfilePicture(Modifier.fillMaxSize())
                else Box(
                    Modifier.fillMaxSize().clip(CircleShape).background(com.prism.music.ui.components.glassFill(1.4f)).border(1.5.dp, PrismTokens.spectrumRing(), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (name.isNotBlank()) Text(name.trim().take(1).uppercase(), style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Light))
                    else Icon(PrismIcons.Photo, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val hasPicture = (profile?.picture ?: 0) > 0 || profile?.avatar != null || pendingPicture != null || pendingAvatar != null
            Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp, bottom = 8.dp)) {
                Text(tr(R.string.pick_a_picture), style = MaterialTheme.typography.titleSmall)
                if (hasPicture) PrismTextButton({ pendingPicture = null; pendingAvatar = null; c.account.clearPicture() }) { Text(tr(R.string.use_my_initial)) }
            }
        }
        // Full width, so the row of pictures runs to the screen's edges.
        AvatarPicker(
            selected = pendingAvatar?.id ?: profile?.avatar.takeIf { pendingPicture == null },
            photoChosen = pendingPicture != null || (pendingAvatar == null && (profile?.picture ?: 0) > 0),
            onPhotos = { pickPicture() },
        ) { a ->
            if (profile == null) { pendingAvatar = a; pendingPicture = null } else c.account.setAvatar(a.id)
        }
        Column(Modifier.widthIn(max = 480.dp).fillMaxWidth().padding(horizontal = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(18.dp))
            com.prism.music.ui.components.PrismTextField(
                name, { name = it.take(40) }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(tr(R.string.your_name)) },
            )
            Text(
                tr(R.string.home_greets_you_by_it_blend),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp, start = 4.dp),
            )
            Spacer(Modifier.height(22.dp))
            val changed = profile == null || name.trim() != profile?.name
            PrismButton({ save() }, Modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = changed || pendingPicture != null || pendingAvatar != null) {
                Text(if (profile == null) tr(R.string.make_my_account) else tr(R.string.save))
            }
            if (onboarding) PrismTextButton(onDone, Modifier.fillMaxWidth().padding(top = 4.dp)) { Text(tr(R.string.skip_for_now)) }

            if (profile != null && !onboarding) {
                Spacer(Modifier.height(32.dp))
                Column(Modifier.fillMaxWidth().clip(com.prism.music.ui.theme.LocalUi.current.card).background(com.prism.music.ui.components.glassFill(1.2f)).padding(18.dp)) {
                    com.prism.music.ui.components.Eyebrow(tr(R.string.on_this_phone))
                    Spacer(Modifier.height(8.dp))
                    val lists by c.prismLibrary.lists.collectAsState()
                    val albums by c.prismLibrary.albums.collectAsState()
                    val liked by c.library.liked.collectAsState(initial = emptyList())
                    val stars by c.artistPrefs.favorites.collectAsState()
                    Text(
                        listOf(
                            trp(R.plurals.n_liked_songs, liked.size),
                            trp(R.plurals.n_playlists, lists.size),
                            trp(R.plurals.n_saved_albums, albums.size),
                            trp(R.plurals.n_starred_artists, stars.size),
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (s.isLoggedIn) {
                        Spacer(Modifier.height(14.dp))
                        Text(tr(R.string.your_youtube_music_library), style = MaterialTheme.typography.titleSmall)
                        Text(
                            tr(R.string.copy_its_playlists_saved_albums_and_all),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
                        )
                        val at = profile?.broughtAt ?: 0
                        PrismOutlinedButton({
                            if (bringing != null) return@PrismOutlinedButton
                            bringing = tr(R.string.starting)
                            scope.launch {
                                val r = runCatching { c.account.bringOver { bringing = it } }.getOrNull()
                                bringing = null
                                Toast.makeText(
                                    context,
                                    if (r == null) tr(R.string.couldn_t_reach_youtube_music_try)
                                    else tr(R.string.copied_playlists_albums_and_artists, trp(R.plurals.n_playlists, r.playlists), trp(R.plurals.n_albums, r.albums), trp(R.plurals.n_artists, r.artists)) + if (r.failed > 0) trp(R.plurals.n_playlists_couldn_t_be_read, r.failed) else "",
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        }, Modifier.fillMaxWidth(), enabled = bringing == null) {
                            if (bringing != null) { com.prism.music.ui.components.PrismSpinner(Modifier.size(16.dp), stroke = 2.dp); Spacer(Modifier.width(10.dp)); Text(bringing!!) }
                            else Text(if (at > 0) tr(R.string.copy_again) else tr(R.string.copy_into_prism))
                        }
                        if (at > 0) Text(
                            tr(R.string.last_copied, java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(at))),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
                Spacer(Modifier.height(18.dp))
                PrismTextButton({ removing = true }) { Text(tr(R.string.remove_prism_account), color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}
