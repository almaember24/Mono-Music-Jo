package com.prism.music.ui.components

import com.prism.music.R
import com.prism.music.tr
import com.prism.music.ui.theme.PrismIcons
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.prism.music.data.model.Song
import com.prism.music.data.model.hiRes
import com.prism.music.ui.theme.LocalContainer
import kotlinx.coroutines.launch

/** Something to share: a song, album, playlist or artist, or one of Prism's own lists. */
data class ShareTarget(
    val title: String,
    val subtitle: String,
    /** "Song", "Album", "Playlist", "Artist"… shown over the title on the card. */
    val kind: String,
    val art: String?,
    /** The music.youtube.com link; null for lists that only exist in Prism (Liked songs, Downloads). */
    val url: String?,
    /** Artist photos are round. */
    val round: Boolean = false,
    /** For lists without a link: shared as a track list instead. */
    val songs: List<Song> = emptyList(),
    /** An owned playlist's id and visibility, so a private one can be made shareable from here. */
    val playlistId: String? = null,
    val privacy: String? = null,
)

/**
 * Share as the big streaming apps do: a story card (the picture, the title, where to listen)
 * shown as it'll look, plus the link to copy or send. Prism's own lists share as a card and a
 * track list. A private playlist's link only works for its owner, so that's said and fixable here.
 */
@Composable
fun ShareSheet(target: ShareTarget, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val card = rememberGraphicsLayer()
    var privacy by remember { mutableStateOf(target.privacy) }
    var changing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val frost = rememberFrost(target.art)
    val palette = rememberArtPalette(target.art)
    // The link as sent: YouTube Music's, or YouTube's when the listener picked that (Reddit R9).
    val link = target.url?.let { if (com.prism.music.ui.theme.LocalAppSettings.current.shareYoutubeLinks) com.prism.music.data.ShareLinks.asYouTube(it) else it }
    val message = listOf(target.title, target.subtitle).filter { it.isNotBlank() }.joinToString(" · ")

    fun shareImage(chooserTitle: String) {
        if (busy) return
        busy = true
        scope.launch {
            val text = link?.let { "$message\n$it" } ?: message
            if (!shareLayer(context, card, "prism-share.png", text, chooserTitle)) {
                Toast.makeText(context, tr(R.string.couldn_t_make_the_picture_to), Toast.LENGTH_SHORT).show()
            }
            busy = false
        }
    }

    PrismSheet(onDismiss = onDismiss) { close ->
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(tr(R.string.share_2, target.kind.lowercase()), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))

            // The card, as it'll be sent. It's recorded at 1080 px wide whatever its size here.
            Box(
                Modifier.width(212.dp).aspectRatio(9f / 16f)
                    .shadow(18.dp, RoundedCornerShape(22.dp))
                    .recordInto(card),
            ) { ShareCard(target, frost, palette) }

            if (target.url != null && privacy == "PRIVATE") PrivateNotice(changing) {
                changing = true
                scope.launch {
                    val ok = runCatching { c.ytm.editPlaylist(target.playlistId!!, privacy = "UNLISTED") }.isSuccess
                    changing = false
                    if (ok) {
                        privacy = "UNLISTED"
                        c.library.refreshCollections()
                        Toast.makeText(context, tr(R.string.anyone_with_the_link_can_now), Toast.LENGTH_SHORT).show()
                    } else Toast.makeText(context, tr(R.string.couldn_t_change_who_can_see), Toast.LENGTH_SHORT).show()
                }
            }

            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                if (target.url != null) {
                    ShareAction(PrismIcons.Copy, tr(R.string.copy_link)) {
                        copy(context, link!!)
                        close {}
                    }
                    ShareAction(PrismIcons.Link, tr(R.string.send_link)) {
                        close { sendText(context, "$message\n$link", tr(R.string.share_2, target.kind.lowercase())) }
                    }
                } else if (target.songs.isNotEmpty()) {
                    ShareAction(PrismIcons.Queue, tr(R.string.track_list)) {
                        close { sendText(context, trackList(target), tr(R.string.share_2, target.title)) }
                    }
                }
                ShareAction(PrismIcons.Photo, if (busy) tr(R.string.preparing) else tr(R.string.share_image)) { shareImage(tr(R.string.share_2, target.kind.lowercase())) }
                ShareAction(PrismIcons.More, tr(R.string.more)) {
                    close { sendText(context, link?.let { "$message\n$it" } ?: trackList(target), tr(R.string.share_2, target.kind.lowercase())) }
                }
            }
            if (target.url == null) Text(
                tr(R.string.lives_in_prism_so_there_s, target.title),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                modifier = Modifier.padding(start = 28.dp, end = 28.dp, top = 14.dp),
            )
        }
    }
}

@Composable
private fun PrivateNotice(changing: Boolean, onMakeShareable: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(scheme.surfaceContainerHighest).padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(PrismIcons.Lock, null, Modifier.size(20.dp), tint = scheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text(
            tr(R.string.this_playlist_is_private_so_its),
            Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
        )
        if (changing) com.prism.music.ui.components.PrismSpinner(Modifier.padding(horizontal = 14.dp).size(18.dp), stroke = 2.dp)
        else PrismTextButton(onClick = onMakeShareable) { Text(tr(R.string.make_unlisted)) }
    }
}

@Composable
private fun ShareAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier.width(78.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(52.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(24.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The story card: the picture on its own frost, what it is, and where to listen. Always light-on-dark. */
@Composable
private fun ShareCard(target: ShareTarget, frost: FrostImage?, palette: ArtPalette?) {
    val settle = palette?.settle(dark = true) ?: Color(0xFF171717)
    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(22.dp)).background(settle)) {
        frost?.let {
            val painter = remember(it) { BitmapPainter(it.image) }
            Image(
                painter, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(1.4f) }),
            )
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.18f), 0.55f to Color.Black.copy(alpha = 0.32f), 1f to Color.Black.copy(alpha = 0.66f))))
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.weight(1f))
            val shape = if (target.round) CircleShape else RoundedCornerShape(10.dp)
            Box(Modifier.fillMaxWidth(0.72f).aspectRatio(1f).shadow(16.dp, shape).clip(shape).background(Color.White.copy(alpha = 0.08f))) {
                if (target.art != null) AsyncImage(hiRes(target.art, 900), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            Spacer(Modifier.height(16.dp))
            Text(
                target.kind.uppercase(), color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.18.em, fontWeight = FontWeight.Bold, fontSize = 8.sp),
            )
            Spacer(Modifier.height(3.dp))
            Text(
                target.title, color = Color.White, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, lineHeight = 20.sp),
            )
            if (target.subtitle.isNotBlank()) Text(
                target.subtitle, color = Color.White.copy(alpha = 0.78f), textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (target.url != null) (if (com.prism.music.ui.theme.LocalAppSettings.current.shareYoutubeLinks && com.prism.music.data.ShareLinks.asYouTube(target.url) != target.url) tr(R.string.listen_on_youtube) else tr(R.string.listen_on_youtube_music)) else com.prism.music.data.model.songCount(target.songs.size), color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
            )
            Spacer(Modifier.height(2.dp))
            Text("PRISM", color = Color.White, style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.4.em, fontWeight = FontWeight.Black, fontSize = 9.sp))
        }
    }
}

private fun trackList(t: ShareTarget): String = buildString {
    append(t.title)
    if (t.subtitle.isNotBlank()) append(" · ").append(t.subtitle)
    append('\n')
    t.songs.take(50).forEachIndexed { i, s -> append("${i + 1}. ${s.title} — ${s.artistText}\n") }
    if (t.songs.size > 50) append(tr(R.string.and_more, t.songs.size - 50))
}.trim()

private fun sendText(context: Context, text: String, title: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, title))
}

private fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(tr(R.string.link), text))
    // Android 13 and up confirm copies themselves.
    if (Build.VERSION.SDK_INT < 33) Toast.makeText(context, tr(R.string.link_copied), Toast.LENGTH_SHORT).show()
}

/** The share target for a song. */
fun Song.shareTarget() = ShareTarget(title, artistText, when { isEpisode -> tr(R.string.episode); isVideo -> tr(R.string.video); else -> tr(R.string.song) }, thumbnail, com.prism.music.data.ShareLinks.song(id))

/** The share target for a card ([art]: the picture shown for it, e.g. a custom playlist cover). */
fun com.prism.music.data.model.BrowseItem.shareTarget(art: String? = thumbnail): ShareTarget {
    val parts = subtitle.split(" • ").map { it.trim() }.filter { it.isNotBlank() }
    val url = com.prism.music.data.ShareLinks.of(this)
    return when (this) {
        is com.prism.music.data.model.SongItem -> song.shareTarget()
        is com.prism.music.data.model.AlbumItem -> {
            // YouTube's kinds as Prism reads them (English, Parser.canonical) or already in Prism's language; shown translated.
            val found = parts.firstOrNull { it in com.prism.music.data.model.RELEASE_KINDS || it == tr(R.string.album) || it == tr(R.string.single) }
            val kind = found?.let { f -> com.prism.music.data.model.kindName(f) ?: f } ?: tr(R.string.album)
            ShareTarget(title, parts.filter { it != found && !it.matches(Regex("\\d{4}")) }.joinToString(", "), kind, art, url)
        }
        is com.prism.music.data.model.ArtistItem -> ShareTarget(title, parts.filter { it != "Artist" }.joinToString(" · "), tr(R.string.artist), art, url, round = true)
        is com.prism.music.data.model.PodcastItem -> ShareTarget(title, subtitle, tr(R.string.podcast), art, url)
        else -> ShareTarget(title, parts.filter { it != "Playlist" }.joinToString(" · "), tr(R.string.playlist), art, url)
    }
}
