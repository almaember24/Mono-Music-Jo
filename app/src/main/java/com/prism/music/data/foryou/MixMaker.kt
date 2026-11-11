package com.prism.music.data.foryou

import com.prism.music.data.model.ArtistItem
import com.prism.music.data.model.Song
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * How a mix is made (1.7.9), apart from where its data comes from, so a week of mixes can be tried in a test:
 * 1. **Familiar**: songs the listener knows that fit the mix, drawn by weighted chance ([Variety.sample]) from all of
 *    them, each held back while it rests ([Variety.rest]: heard lately, on repeat, served in the last few days'
 *    mixes). Before, it was the same top thirty every day.
 * 2. **New**: found from a few of today's familiar picks (so the seeds change with them) through three ways at once:
 *    YouTube Music's radio of the song, its Related tab ("You might also like") and its similar artists' top songs. A
 *    song several of them agree on counts more. Nothing played in the last 90 days, nothing turned down
 *    ([RecFeedback]), and artists whose recommendations get skipped count for less. Drawn by chance as well, best
 *    weighted, so two days' new songs aren't the same.
 * 3. **Order**: [Variety.arrange]: new songs spread through it at [Recipe.freshShare], each artist a few times at most
 *    and never twice running, the mix's own artists taking turns.
 */
class MixMaker(private val sources: Sources) {
    interface Sources {
        suspend fun radio(seed: Song): List<Song>
        suspend fun related(seed: Song): com.prism.music.data.model.Related
        suspend fun artistTop(artist: ArtistItem): List<Song>
    }

    /** A song the listener knows, with what [Variety.rest] needs. */
    data class Known(
        val song: Song,
        /** How likely they are to want it now (PlayLikelihood), 0..1. */
        val score: Double,
        val playsLately: Int = 0,
        val hoursSinceLast: Double? = null,
        val servedDaysAgo: Int? = null,
    )

    /** What the maker knows of the listener, built once per run. */
    class Taste(
        val known: List<Known>,
        /** Songs (and same-song keys, [LocalRadio.sameSong]) played in the last 90 days: never offered as new. */
        val played: Set<String>,
        /** Left out of recommendations (disliked, excluded, turned down). [known] = the listener knows it. */
        val refused: (Song, Boolean) -> Boolean,
        /** 0..0.8: how much an artist's recommended songs get skipped ([RecFeedback.artistDoubt]). */
        val artistDoubt: (String) -> Double = { 0.0 },
        /** Days since a song was last offered, for new songs not to be offered again straight away. */
        val servedDaysAgo: (String) -> Int? = { null },
    )

    data class Recipe(
        val id: String,
        val size: Int,
        /** Share of new songs, 0..1. */
        val freshShare: Double,
        /** How many of the day's familiar picks seed the search for new songs. */
        val seeds: Int = 3,
        val perArtist: Int = 4,
        /** Similar artists' top songs looked at, per seed. */
        val similarArtists: Int = 2,
    )

    data class Made(val songs: List<Song>, val familiar: List<Song>, val fresh: List<Song>, val seeds: List<Song>)

    /** A mix of [recipe] from the known songs [fits] picks, made the same all of [day]. */
    suspend fun make(recipe: Recipe, taste: Taste, day: String, fits: (Known) -> Boolean): Made {
        val seed = Variety.daySeed(day, recipe.id)
        val candidates = taste.known.filter { fits(it) && usable(it.song) && !taste.refused(it.song, true) }
            .distinctBy { LocalRadio.sameSong(it.song) }
        val wantFamiliar = (recipe.size * (1 - recipe.freshShare)).toInt() + recipe.size / 10
        // Sharpness 1.5: favourites still come up more often than songs heard once, just not every time.
        val familiar = Variety.sample(candidates, wantFamiliar, seed, sharpness = 1.5) {
            it.score * Variety.rest(it.playsLately, it.hoursSinceLast, it.servedDaysAgo)
        }.map { it.song }
        // Seeds: today's picks, taken in turn across artists, so new songs come from around the whole mix.
        val seeds = Variety.roundRobin(familiar.ifEmpty { candidates.map { it.song } }) { it.primaryArtist }.take(recipe.seeds)
        val mixArtists = candidates.map { it.song.primaryArtist.lowercase() }.toSet()
        val fresh = discover(seeds, recipe, taste, mixArtists, seed, exclude = familiar.map { LocalRadio.sameSong(it) }.toSet())
        val songs = Variety.arrange(familiar, fresh, recipe.size, recipe.freshShare, recipe.perArtist) { it.primaryArtist.lowercase() }
        return Made(songs, familiar, fresh, seeds)
    }

    /**
     * New songs for [seeds]: every way's answers weighed together (a song found by several seeds or ways counts more,
     * earlier in a list counts more), then drawn by weighted chance.
     */
    suspend fun discover(
        seeds: List<Song>, recipe: Recipe, taste: Taste, mixArtists: Set<String>, seed: Long, exclude: Set<String>,
        /** How many to draw: by default what the recipe's share of new songs needs. */
        want: Int = (recipe.size * recipe.freshShare).toInt() + recipe.size / 10,
    ): List<Song> {
        val weight = HashMap<String, Double>()
        val byId = HashMap<String, Song>()
        fun add(list: List<Song>, base: Double) = list.forEachIndexed { rank, s ->
            weight.merge(s.id, base / (1.0 + rank / 8.0), Double::plus)
            byId.putIfAbsent(s.id, s)
        }
        val gate = Semaphore(3)
        val found = coroutineScope {
            seeds.map { s ->
                async {
                    val radio = gate.withPermit { runCatching { sources.radio(s) }.getOrDefault(emptyList()) }
                    val related = gate.withPermit { runCatching { sources.related(s) }.getOrNull() }
                    val similar = related?.artists.orEmpty()
                        .filter { it.title.lowercase() !in mixArtists }
                        .take(recipe.similarArtists)
                        .map { a -> gate.withPermit { runCatching { sources.artistTop(a) }.getOrDefault(emptyList()) } }
                    Triple(radio.filter { it.id != s.id }, related?.songs.orEmpty(), similar)
                }
            }.awaitAll()
        }
        for ((radio, related, similar) in found) {
            add(radio, 1.0)
            add(related, 1.2)
            similar.forEach { add(it.take(6), 0.9) }
        }
        val pool = byId.values.filter { s ->
            usable(s) && s.id !in taste.played && LocalRadio.sameSong(s) !in taste.played && LocalRadio.sameSong(s) !in exclude &&
                !taste.refused(s, false)
        }.distinctBy { LocalRadio.sameSong(it) }
        return Variety.sample(pool, want, seed + 1, sharpness = 1.3) { s ->
            val w = weight[s.id] ?: 0.0
            val artist = s.primaryArtist.lowercase()
            // Someone the mix is about: welcome, a little more than a stranger.
            val near = if (artist in mixArtists) 1.15 else 1.0
            // Offered a day or two ago and not taken up: something else first.
            val served = taste.servedDaysAgo(s.id)?.let { d -> if (d <= 1) 0.25 else if (d <= 3) 0.6 else 1.0 } ?: 1.0
            w * near * served * (1.0 - taste.artistDoubt(s.primaryArtist))
        }
    }

    private fun usable(s: Song) = !s.isVideo && !s.isLive && !s.isEpisode && !s.isLocal && (s.durationSec == 0 || s.durationSec in 60..900)
}
