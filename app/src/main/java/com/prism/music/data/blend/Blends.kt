package com.prism.music.data.blend

import com.prism.music.R
import com.prism.music.tr
import android.content.Context
import com.prism.music.AppContainer
import com.prism.music.data.db.BlendEntity
import com.prism.music.data.db.mainArtist
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.security.SecureRandom
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Blend: two people's tastes in one playlist, made on each phone from the taste codes they swap (by QR code, or as
 * text shared into Prism). No server is involved: the code itself carries everything, and only what [myCode] shows
 * the listener ([TasteCode]) is in it.
 */
class Blends(private val c: AppContainer) {
    private val prefs = c.app.getSharedPreferences("blend", Context.MODE_PRIVATE)

    /** This phone's random Blend id: tells friends' phones which Blend a new code belongs to, and nothing about anyone. */
    val myWho: String
        get() = prefs.getString(WHO, null) ?: ByteArray(9).also { SecureRandom().nextBytes(it) }
            .let { java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
            .also { prefs.edit().putString(WHO, it).apply() }

    /** The name friends see (the account's first name until changed). */
    var myName: String
        get() = prefs.getString(NAME, null) ?: c.displayName(first = true)
        set(v) { prefs.edit().putString(NAME, v.trim().take(30)).apply() }

    val blends: Flow<List<BlendEntity>> = c.db.blends().allFlow()

    /** A code shared into Prism (or scanned) waiting for the listener to say yes to it. */
    val pending = MutableStateFlow<String?>(null)

    /**
     * The listener's taste as a code: half a year of plays that count towards taste (excluded artists, playlists and
     * songs left out), their liked songs where plays are few, and their genres.
     */
    suspend fun myCode(): TasteCode {
        val now = System.currentTimeMillis()
        val since = now - 180L * 86_400_000
        val plays = c.db.plays()
        val excluded = c.exclusions
        val artists = plays.tasteTopArtists(since, now, 60)
            .map { it.artistName to it }
            .filter { (name, a) -> name.isNotBlank() && !excluded.isArtistExcluded(a.artistId, name) }
            .distinctBy { it.first.lowercase() }
            .take(30)
        val maxArtist = artists.maxOfOrNull { it.second.plays }?.toDouble() ?: 1.0
        val codeArtists = artists.map { (name, a) -> CodeArtist(a.artistId, name, weight(a.plays.toDouble(), maxArtist)) }

        val top = plays.tasteTopSongs(since, now, 120)
        val songs = ArrayList<CodeSong>()
        val maxSong = top.maxOfOrNull { it.plays }?.toDouble() ?: 1.0
        for (s in top) {
            if (s.songId.length != 11 || excluded.isSongExcluded(s.songId)) continue
            val artist = s.mainArtist
            if (artist.isBlank() || excluded.isArtistExcluded(s.artistId, artist)) continue
            songs += CodeSong(s.songId, s.title, artist, s.artistId, weight(s.plays.toDouble(), maxSong))
            if (songs.size >= 50) break
        }
        // Little played yet: liked songs fill in, as lightly played.
        if (songs.size < 50) for (l in c.db.songs().liked()) {
            if (songs.size >= 50) break
            if (l.id.length != 11 || songs.any { it.id == l.id } || excluded.isSongExcluded(l.id)) continue
            val artist = l.toSong().primaryArtist
            if (artist.isBlank() || excluded.isArtistExcluded(l.artistId, artist)) continue
            songs += CodeSong(l.id, l.title, artist, l.artistId, 5)
        }
        val genres = runCatching { c.taste.profile() }.getOrDefault(emptyMap())
            .entries.sortedByDescending { it.value }.take(8)
            .associate { it.key to (it.value * 100).roundToInt() }.filterValues { it > 0 }
        return TasteCode(myWho, myName.ifBlank { tr(R.string.a_prism_listener) }, codeArtists, songs, genres)
    }

    /** 1–99 on a log scale against the most played: a favourite played 200 times doesn't drown everything else. */
    private fun weight(n: Double, max: Double) = (99 * ln(1 + n) / ln(1 + max)).roundToInt().coerceIn(1, 99)

    sealed interface Import {
        data class Added(val who: String, val name: String, val updated: Boolean) : Import
        data object NotACode : Import
        data object Yours : Import
    }

    /**
     * Takes a friend's code: a new Blend, or their newer code for one there already. [mine]: this phone's code as the
     * friend got it (the QR code they scanned), so both phones make the very same Blend; else it's worked out now.
     */
    suspend fun import(text: String, mine: String? = null): Import {
        val code = BlendCode.findIn(text) ?: return Import.NotACode
        val friend = BlendCode.decode(code) ?: return Import.NotACode
        if (friend.who == myWho) return Import.Yours
        val old = c.db.blends().get(friend.who)
        val now = System.currentTimeMillis()
        val shown = mine?.let(BlendCode::findIn)?.takeIf { BlendCode.decode(it)?.who == myWho }
        val mineGiven = shown != null
        val mine = shown ?: BlendCode.encode(myCode())
        c.db.blends().put(BlendEntity(friend.who, friend.name, code, mine, old?.createdAt ?: now, now))
        // Sent back over Wi-Fi by the phone that scanned this one's code: it made the Blend from the same two.
        if (mineGiven) markOnBothPhones(friend.who)
        return Import.Added(friend.who, friend.name, old != null)
    }

    /** Both phones made this Blend from the same two codes ([BlendExchange]): nothing to send back. */
    fun onBothPhones(who: String) = prefs.getBoolean("both:$who", false)
    fun markOnBothPhones(who: String) = prefs.edit().putBoolean("both:$who", true).apply()

    suspend fun remove(who: String) {
        c.db.blends().delete(who)
        clearCover(who)
    }

    // ------------------------------------------------------------ Covers

    private val _coverStamp = MutableStateFlow(0)
    /** Moves on whenever a Blend's own cover is set or cleared (so pictures showing it reload). */
    val coverStamp: StateFlow<Int> = _coverStamp.asStateFlow()

    /** The picture the listener picked for the Blend with [who] (it may not exist: then Prism draws one). */
    fun coverFile(who: String): File = File(c.app.filesDir, "blends/" + who.filter { it.isLetterOrDigit() || it == '-' || it == '_' } + ".jpg")

    /** Uses a picture from the photo picker as the Blend's cover, cut square. Only this phone sees it. */
    suspend fun setCover(who: String, uri: android.net.Uri): Boolean =
        com.prism.music.data.Pictures.saveSquare(c.app, uri, coverFile(who)).also { if (it) _coverStamp.value++ }

    /** Back to the cover Prism draws. */
    fun clearCover(who: String) {
        if (coverFile(who).delete()) _coverStamp.value++
    }

    suspend fun get(who: String) = c.db.blends().get(who)

    /** The Blend worked out from what's stored (null if a code no longer reads). */
    fun blendOf(e: BlendEntity): Blend? {
        val friend = BlendCode.decode(e.friendCode) ?: return null
        val mine = BlendCode.decode(e.myCode) ?: return null
        return BlendMaths.blend(mine, friend)
    }

    /** A Blend song as a song to play (its cover from YouTube's own picture for it, until the player loads more). */
    fun songOf(s: CodeSong) = Song(
        id = s.id, title = s.title, artists = listOf(ArtistRef(s.artist, s.artistId)),
        thumbnail = "https://i.ytimg.com/vi/${s.id}/hqdefault.jpg",
    )

    private companion object {
        const val WHO = "who"
        const val NAME = "name"
    }
}
