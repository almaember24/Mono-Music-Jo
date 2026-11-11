package com.prism.music.data.innertube

import com.prism.music.data.model.AlbumItem
import com.prism.music.data.model.AlbumRef
import com.prism.music.data.model.ArtistItem
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.BrowseItem
import com.prism.music.data.model.Episode
import com.prism.music.data.model.MoodItem
import com.prism.music.data.model.PlaylistItem
import com.prism.music.data.model.PodcastItem
import com.prism.music.data.model.Shelf
import com.prism.music.data.model.Song
import com.prism.music.data.model.SongItem
import com.prism.music.data.model.parseDuration
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Converts YouTube Music renderer objects into app models. */
object Parser {
    private const val ARTIST = "MUSIC_PAGE_TYPE_ARTIST"
    private const val CHANNEL = "MUSIC_PAGE_TYPE_USER_CHANNEL"
    private const val ALBUM = "MUSIC_PAGE_TYPE_ALBUM"
    private const val PLAYLIST = "MUSIC_PAGE_TYPE_PLAYLIST"
    /** Artists in the signed-in library: browse id "MPLA" + the channel id. */
    private const val LIBRARY_ARTIST = "MUSIC_PAGE_TYPE_LIBRARY_ARTIST"
    /** A podcast show: browse id "MPSP" + its playlist id. */
    private const val PODCAST = "MUSIC_PAGE_TYPE_PODCAST_SHOW_DETAIL_PAGE"
    private const val EPISODE_VIDEO = "MUSIC_VIDEO_TYPE_PODCAST_EPISODE"
    /** Music the listener uploaded to YouTube Music themselves (#101). */
    private const val UPLOAD = "MUSIC_VIDEO_TYPE_PRIVATELY_OWNED_TRACK"

    /**
     * Whether a watch endpoint's type is a video: anything but YouTube's own songs ("ATV") and the listener's uploads,
     * which are songs too (#101: as "videos", "Songs, not videos" swapped an upload for YouTube's own, shorter, recording).
     */
    fun isVideoType(vt: String?): Boolean = vt != null && vt != "MUSIC_VIDEO_TYPE_ATV" && vt != UPLOAD

    /**
     * #383 (1.8.3): YouTube files some songs' videos as podcast episodes (Telugu film songs in a playlist: "Episode will
     * play next", no show, the singer linked as an artist). Read as episodes they had no artist, played with the episode
     * controls (15 s / 30 s) and were never swapped for their songs. A real episode names its show (linked); one that
     * names none is a song's video (its singer linked or not: "Mukesh" came as plain text).
     */
    @Suppress("UNUSED_PARAMETER")
    private fun songMarkedEpisode(show: JsonObject?, runs: List<JsonObject>): Boolean = show == null
    private val typeLabels = setOf(
        "Song", "Video", "Album", "Single", "EP", "Artist", "Playlist", "Episode", "Podcast", "Profile", "Station",
    )
    /**
     * YouTube answers in the phone's language ("노래 • 아이유 • 2026년", "Canción • IU • 3:13"), but Prism reads type labels
     * and years in English ("Song", "Single", "2026"). These are YouTube Music's own words for them, collected from its
     * answers in 20 languages; [canonical] puts the English back. Only a subtitle's first segment (the type) is matched,
     * so an album that happens to be called "Música" isn't touched.
     */
    private val localTypeWords: Map<String, String> = buildMap {
        fun add(english: String, vararg words: String) = words.forEach { put(it.lowercase(), english) }
        add("Song", "노래", "Canción", "Música", "Titre", "Titel", "Brano", "曲", "歌曲", "أغنية", "Композиция", "Şarkı", "Lagu", "गाना", "Bài hát", "เพลง", "Nummer", "Utwór", "Пісня", "Låt")
        add("Video", "동영상", "Vídeo", "Vidéo", "動画", "视频", "影片", "فيديو", "Видео", "वीडियो", "วิดีโอ", "Film", "Відео")
        add("Album", "앨범", "Álbum", "アルバム", "专辑", "專輯", "ألبوم", "Альбом", "Albüm", "एल्‍बम", "एल्बम", "Đĩa nhạc", "อัลบั้ม")
        add("Single", "싱글", "シングル", "单曲", "單曲", "أغنية منفردة", "Сингл", "सिंगल", "Đĩa đơn", "ซิงเกิล", "Singolo", "Singiel", "Singel")
        add("Artist", "아티스트", "Artista", "Artiste", "Künstler", "アーティスト", "音乐人", "藝人", "فنان", "Исполнитель", "Sanatçı", "Artis", "कलाकार", "Nghệ sĩ", "ศิลปิน", "Artiest", "Wykonawca", "Виконавець")
        add("Playlist", "재생목록", "Lista de reproducción", "プレイリスト", "播放列表", "播放清單", "قائمة تشغيل", "Плейлист", "Oynatma listesi", "प्लेलिस्ट", "Danh sách phát", "เพลย์ลิสต์", "Playlista", "Список відтворення", "Spellista")
        add("Episode", "에피소드", "Episodio", "Episódio", "Épisode", "Folge", "Puntata", "エピソード", "分集", "單集", "حلقة", "Выпуск", "Bölüm", "एपिसोड", "Tập podcast", "ตอน", "Aflevering", "Odcinek", "Серія", "Avsnitt")
        add("Podcast", "팟캐스트", "Pódcast", "ポッドキャスト", "播客", "بودكاست", "Подкаст", "पॉडकास्ट", "พอดแคสต์", "Podd")
        add("Profile", "프로필", "Perfil", "Profil", "Profilo", "プロフィール", "个人资料", "個人資料", "ملف شخصي", "Профиль", "प्रोफ़ाइल", "Hồ sơ", "โปรไฟล์", "Profiel", "Профіль")
    }
    private val localYear = Regex("^(\\d{4})\\s*[년年]$")

    /** A subtitle ("노래 • 아이유 • 2026년") with its type label and year in English ("Song • 아이유 • 2026"). */
    fun canonical(subtitle: String): String {
        if (subtitle.isEmpty()) return subtitle
        val parts = subtitle.split(" • ").toMutableList()
        localTypeWords[parts[0].trim().lowercase()]?.let { parts[0] = it }
        localYear.matchEntire(parts.last().trim())?.let { parts[parts.lastIndex] = it.groupValues[1] }
        return parts.joinToString(" • ")
    }

    private val durationRegex = Regex("^\\d{1,2}(:\\d{2}){1,2}$")
    private val playsRegex = Regex("^([\\d.,]+)\\s*([KMB])?\\s+plays?$", RegexOption.IGNORE_CASE)

    /** "54M plays" -> 54,000,000; null if [text] isn't a play count. */
    fun parsePlays(text: String): Long? {
        val m = playsRegex.matchEntire(text.trim()) ?: return null
        val n = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null
        val mult = when (m.groupValues[2].uppercase()) { "K" -> 1e3; "M" -> 1e6; "B" -> 1e9; else -> 1.0 }
        return (n * mult).toLong()
    }

    /**
     * A view count in a subtitle ("Black Gryph0n & Baasik • 52M views • 2:44"; a card's starts "Video • …"): the first
     * segment with a number after the [skip] leading ones (the type, the artist, whose name may hold digits), not the
     * length. Null when there's none.
     */
    internal fun countAfterArtist(subtitle: String, skip: Int): Long? =
        subtitle.split(" • ").drop(skip).map { it.trim() }.firstOrNull { it.any(Char::isDigit) && !durationRegex.matches(it) }
            ?.let(SearchRank::count)?.takeIf { it > 0 }

    /** A list row's play count (album tracks and top songs show one), if it has one. */
    fun playCount(r: JsonObject): Long? =
        r.arr("flexColumns")?.drop(1)?.firstNotNullOfOrNull { col ->
            parsePlays(col.obj("musicResponsiveListItemFlexColumnRenderer", "text").runs().joinToString("") { it.str("text") ?: "" })
        }

    private fun pageType(nav: JsonElement?): String? =
        nav.str("browseEndpoint", "browseEndpointContextSupportedConfigs", "browseEndpointContextMusicConfig", "pageType")

    private fun videoType(endpoint: JsonElement?): String? =
        endpoint.str("watchEndpointMusicSupportedConfigs", "watchEndpointMusicConfig", "musicVideoType")

    private fun isSeparator(t: String) = t.isBlank() || t.trim() == "•" || t.trim() == "&" || t.trim() == ","

    fun artistsFromRuns(runs: List<JsonObject>): List<ArtistRef> {
        val linked = runs.filter { pageType(it["navigationEndpoint"]) in setOf(ARTIST, CHANNEL) }.map {
            ArtistRef(it.str("text") ?: "", it.str("navigationEndpoint", "browseEndpoint", "browseId"))
        }
        if (linked.isNotEmpty()) return linked
        // Unlinked: "Artist • Album • 3:10" or "Song • Artist • 3:10"
        val segments = canonical(runs.joinToString("") { it.str("text") ?: "" }).split(" • ").map { it.trim() }
        val name = segments.firstOrNull { it.isNotBlank() && it !in typeLabels && !durationRegex.matches(it) && !it.contains(" views") && !playsRegex.matches(it) }
        return if (name != null) name.split(", ", " & ").map { ArtistRef(it) } else emptyList()
    }

    private fun albumFromRuns(runs: List<JsonObject>): AlbumRef? =
        runs.firstOrNull { pageType(it["navigationEndpoint"]) == ALBUM }?.let {
            AlbumRef(it.str("text") ?: "", it.str("navigationEndpoint", "browseEndpoint", "browseId"))
        }

    fun parseItem(wrapper: JsonElement?): BrowseItem? {
        wrapper.obj("musicResponsiveListItemRenderer")?.let { return responsive(it) }
        wrapper.obj("musicTwoRowItemRenderer")?.let { return twoRow(it) }
        wrapper.obj("musicNavigationButtonRenderer")?.let { return mood(it) }
        wrapper.obj("musicMultiRowListItemRenderer")?.let { return episode(it)?.song?.let(::SongItem) }
        wrapper.obj("playlistPanelVideoRenderer")?.let { return panelVideo(it)?.let(::SongItem) }
        return null
    }

    private fun mood(r: JsonObject): MoodItem? {
        val title = r.obj("buttonText").text() ?: return null
        val id = r.str("clickCommand", "browseEndpoint", "browseId") ?: return null
        val color = r.str("solid", "leftStripeColor")?.toLongOrNull() ?: 0xFF787878
        return MoodItem(id, title, r.str("clickCommand", "browseEndpoint", "params"), color)
    }

    fun responsive(r: JsonObject): BrowseItem? {
        val cols = r.arr("flexColumns")?.map { it.obj("musicResponsiveListItemFlexColumnRenderer", "text").runs() }
            ?: return null
        val title = cols.getOrNull(0)?.joinToString("") { it.str("text") ?: "" }?.takeIf { it.isNotBlank() } ?: return null
        val thumb = r.obj("thumbnail").bestThumbnail()
        val watch = r.obj("overlay", "musicItemThumbnailOverlayRenderer", "content", "musicPlayButtonRenderer", "playNavigationEndpoint", "watchEndpoint")
            ?: cols[0].firstOrNull()?.obj("navigationEndpoint", "watchEndpoint")
        val videoId = r.str("playlistItemData", "videoId") ?: watch.str("videoId")
        val restRuns = cols.drop(1).flatten()
        val browse = r.obj("navigationEndpoint")

        // A podcast episode (search's Episodes): "Sep 30 • The Show", the show linked.
        val episodeShow = restRuns.firstOrNull { pageType(it["navigationEndpoint"]) == PODCAST }
        if (videoId != null && videoType(watch) == EPISODE_VIDEO && !songMarkedEpisode(episodeShow, restRuns)) {
            val show = episodeShow
            return SongItem(
                Song(
                    id = videoId, title = title,
                    artists = listOfNotNull(show?.str("text")?.let { ArtistRef(it) }),
                    thumbnail = thumb, isEpisode = true,
                    podcastId = show?.str("navigationEndpoint", "browseEndpoint", "browseId"),
                )
            )
        }
        if (videoId != null && pageType(browse) == null) {
            val duration = r.arr("fixedColumns")?.firstOrNull()
                ?.obj("musicResponsiveListItemFixedColumnRenderer", "text").text()
                ?: restRuns.map { it.str("text") ?: "" }.lastOrNull { durationRegex.matches(it.trim()) }
            // #383: a song YouTube files as an "episode" counts as the video it is ([isVideoType]), so it can be swapped.
            val vt = videoType(watch)
            val explicit = r.arr("badges")?.any {
                it.str("musicInlineBadgeRenderer", "icon", "iconType") == "MUSIC_EXPLICIT_BADGE"
            } == true
            // How often it's been played: a song row's own column ("189M plays", "7963만회 재생"), a video's views in
            // its subtitle after the artist ("Jack Johnson • 120M views • 3:08").
            val plays = cols.drop(2).map { c -> c.joinToString("") { it.str("text") ?: "" } }.firstOrNull { it.any(Char::isDigit) }?.let(SearchRank::count)
                ?: if (!isVideoType(vt)) null else countAfterArtist(cols.getOrNull(1)?.joinToString("") { it.str("text") ?: "" }.orEmpty(), skip = 1)
            return SongItem(
                Song(
                    id = videoId,
                    title = title,
                    artists = artistsFromRuns(cols.getOrNull(1) ?: emptyList()),
                    album = albumFromRuns(restRuns),
                    durationSec = parseDuration(duration),
                    thumbnail = thumb,
                    isVideo = isVideoType(vt), isUpload = vt == UPLOAD,
                    explicit = explicit,
                ),
                plays = plays?.takeIf { it > 0 },
            )
        }
        val browseId = browse.str("browseEndpoint", "browseId") ?: return null
        val subtitle = canonical(restRuns.joinToString("") { it.str("text") ?: "" })
        return when (pageType(browse)) {
            ALBUM -> AlbumItem(browseId, title, subtitle, thumb)
            ARTIST, CHANNEL -> ArtistItem(browseId, title, subtitle, thumb)
            LIBRARY_ARTIST -> ArtistItem(browseId.removePrefix("MPLA"), title, subtitle, thumb)
            PLAYLIST -> PlaylistItem(browseId.removePrefix("VL"), title, subtitle, thumb)
            PODCAST -> PodcastItem(browseId, title, subtitle, thumb)
            else -> null
        }
    }

    /**
     * A podcast episode row (a show's page, "Popular episodes"): the title, the show it's from
     * ("secondTitle", linked), "663K views • 1d ago", its length ("2 hr 54 min") and description.
     */
    fun episode(r: JsonObject, show: PodcastItem? = null): Episode? {
        val watch = r.obj("onTap", "watchEndpoint")
            ?: r.obj("overlay", "musicItemThumbnailOverlayRenderer", "content", "musicPlayButtonRenderer", "playNavigationEndpoint", "watchEndpoint")
        val id = watch.str("videoId") ?: return null
        val title = r.obj("title").text() ?: return null
        val showRun = r.obj("secondTitle").runs().firstOrNull { pageType(it["navigationEndpoint"]) == PODCAST }
        val progress = r.obj("playbackProgress", "musicPlaybackProgressRenderer")
        val subtitle = r.obj("subtitle").runs().map { it.str("text") ?: "" }.filter { !isSeparator(it) }
        return Episode(
            song = Song(
                id = id,
                title = title,
                artists = listOfNotNull((showRun?.str("text") ?: show?.title)?.let { ArtistRef(it) }),
                durationSec = parseDuration(progress.obj("durationText").runs().lastOrNull().str("text")),
                thumbnail = r.obj("thumbnail").bestThumbnail() ?: show?.thumbnail,
                isEpisode = true,
                podcastId = showRun?.str("navigationEndpoint", "browseEndpoint", "browseId") ?: show?.id,
            ),
            // The date, without the view count.
            date = subtitle.lastOrNull { !it.contains("view", ignoreCase = true) },
            description = r.obj("description").runs().joinToString("") { it.str("text") ?: "" }.takeIf { it.isNotBlank() },
            progress = (progress.str("playbackProgressPercentage")?.toFloatOrNull() ?: 0f) / 100f,
            played = progress.obj("playedText") != null && (progress.str("playbackProgressPercentage")?.toFloatOrNull() ?: 0f) >= 99f,
        )
    }

    /**
     * The "Top result" card of YouTube Music's unfiltered search (`musicCardShelfRenderer`): an artist, album,
     * playlist or song. It's YouTube's own best guess, good at abbreviations and typos ("adtr", "taylor swfit").
     */
    fun card(r: JsonObject): BrowseItem? {
        val titleRun = r.obj("title").runs().firstOrNull() ?: return null
        val title = r.obj("title").text()?.takeIf { it.isNotBlank() } ?: return null
        val subtitleRuns = r.obj("subtitle").runs()
        val subtitle = canonical(subtitleRuns.joinToString("") { it.str("text") ?: "" })
        val thumb = r.obj("thumbnail").bestThumbnail()
        val nav = titleRun.obj("navigationEndpoint") ?: r.obj("onTap")
        nav.obj("watchEndpoint")?.let { watch ->
            val id = watch.str("videoId") ?: return null
            val vt = videoType(watch)
            if (vt == EPISODE_VIDEO) return null
            return SongItem(
                Song(
                    id = id, title = title,
                    artists = artistsFromRuns(subtitleRuns),
                    album = albumFromRuns(subtitleRuns),
                    durationSec = parseDuration(subtitleRuns.map { it.str("text") ?: "" }.lastOrNull { durationRegex.matches(it.trim()) }),
                    thumbnail = thumb,
                    isVideo = isVideoType(vt), isUpload = vt == UPLOAD,
                ),
                // A video card's views ("Video • Black Gryph0n & Baasik • 52M views • 2:44"); a song card says none.
                plays = if (isVideoType(vt)) countAfterArtist(subtitle, skip = 2) else null,
            )
        }
        val browseId = nav.str("browseEndpoint", "browseId") ?: return null
        return when (pageType(nav)) {
            ALBUM -> AlbumItem(browseId, title, subtitle, thumb)
            ARTIST, CHANNEL -> ArtistItem(browseId, title, subtitle, thumb)
            PLAYLIST -> PlaylistItem(browseId.removePrefix("VL"), title, subtitle, thumb)
            else -> null
        }
    }

    fun twoRow(r: JsonObject): BrowseItem? {
        val title = r.obj("title").text() ?: return null
        val subtitleRuns = r.obj("subtitle").runs()
        val subtitle = canonical(subtitleRuns.joinToString("") { it.str("text") ?: "" })
        val thumb = r.obj("thumbnailRenderer").bestThumbnail()
        val nav = r.obj("navigationEndpoint")
        nav.obj("watchEndpoint")?.let { w ->
            val id = w.str("videoId")
            val pid = w.str("playlistId")
            // A station ("Station • Bring Me The Horizon, Oasis…"): a radio seeded by a song, not the song itself.
            // Song cards carry their own radio ("RDAMVM" + the song) or album; anything else that's a radio is the station.
            val kind = subtitle.substringBefore(" • ").trim()
            // A card that says it's a song or a video is one, whatever radio it starts (#266: Speed dial showed no songs).
            val songCard = id != null && (kind.equals("Song", true) || kind.equals("Video", true))
            val station = pid != null && !songCard && (
                id == null || kind.equals("Station", true) || kind.equals("Mix", true) ||
                    (pid.startsWith("RD") && !pid.startsWith("RDAMVM"))
                )
            if (pid != null && station) {
                return PlaylistItem(pid, title, subtitle, thumb, isMix = true, seedVideoId = id, params = w.str("params"))
            }
            id ?: return null
            val show = subtitleRuns.firstOrNull { pageType(it["navigationEndpoint"]) == PODCAST }
            val episode = videoType(w) == EPISODE_VIDEO && !songMarkedEpisode(show, subtitleRuns)
            val vt = videoType(w)
            if (episode) {
                return SongItem(
                    Song(
                        id = id, title = title, artists = listOfNotNull(show?.str("text")?.let { ArtistRef(it) }),
                        thumbnail = thumb, isEpisode = true, podcastId = show?.str("navigationEndpoint", "browseEndpoint", "browseId"),
                    )
                )
            }
            return SongItem(
                Song(
                    id = id, title = title, artists = artistsFromRuns(subtitleRuns),
                    album = albumFromRuns(subtitleRuns), thumbnail = thumb,
                    isVideo = isVideoType(vt), isUpload = vt == UPLOAD,
                )
            )
        }
        nav.obj("watchPlaylistEndpoint")?.let { w ->
            val pid = w.str("playlistId") ?: return null
            return PlaylistItem(pid, title, subtitle, thumb, isMix = true, params = w.str("params"))
        }
        val browseId = nav.str("browseEndpoint", "browseId") ?: return null
        return when (pageType(nav)) {
            ALBUM -> AlbumItem(
                browseId, title, subtitle, thumb,
                playlistId = r.str("thumbnailOverlay", "musicItemThumbnailOverlayRenderer", "content", "musicPlayButtonRenderer", "playNavigationEndpoint", "watchPlaylistEndpoint", "playlistId"),
            )
            ARTIST, CHANNEL -> ArtistItem(browseId, title, subtitle, thumb)
            LIBRARY_ARTIST -> ArtistItem(browseId.removePrefix("MPLA"), title, subtitle, thumb)
            PODCAST -> PodcastItem(browseId, title, subtitle, thumb)
            else -> if (browseId.startsWith("VL") || browseId.startsWith("RD") || browseId.startsWith("PL")) {
                PlaylistItem(browseId.removePrefix("VL"), title, subtitle, thumb)
            } else null
        }
    }

    fun panelVideo(r: JsonObject): Song? {
        val id = r.str("videoId") ?: return null
        val byline = r.obj("longBylineText").runs()
        val vt = videoType(r.obj("navigationEndpoint", "watchEndpoint"))
        return Song(
            id = id,
            title = r.obj("title").text() ?: return null,
            artists = artistsFromRuns(byline),
            album = albumFromRuns(byline),
            durationSec = parseDuration(r.obj("lengthText").text()),
            thumbnail = r.obj("thumbnail").bestThumbnail(),
            isVideo = isVideoType(vt), isUpload = vt == UPLOAD,
        )
    }

    /** All carousel / list / grid shelves found in a browse response. */
    fun shelves(root: JsonElement): List<Shelf> {
        val out = mutableListOf<Shelf>()
        val sections = root.findAll("sectionListRenderer").flatMap { it.arr("contents") ?: emptyList() } +
            root.findAll("sectionListContinuation").flatMap { it.arr("contents") ?: emptyList() }
        val nodes = if (sections.isNotEmpty()) sections else listOf(root)
        for (node in nodes) {
            node.obj("musicCarouselShelfRenderer")?.let { s ->
                val header = s.obj("header", "musicCarouselShelfBasicHeaderRenderer")
                val title = header.obj("title").text() ?: return@let
                val items = s.arr("contents")?.mapNotNull { parseItem(it) } ?: emptyList()
                if (items.isNotEmpty()) out += Shelf(
                    title = title,
                    strapline = header.obj("strapline").text(),
                    items = items,
                    moreBrowseId = header.str("moreContentButton", "buttonRenderer", "navigationEndpoint", "browseEndpoint", "browseId")
                        ?: header.obj("title").runs().firstOrNull().str("navigationEndpoint", "browseEndpoint", "browseId"),
                    moreParams = header.str("moreContentButton", "buttonRenderer", "navigationEndpoint", "browseEndpoint", "params"),
                    // Song rows, not cards: "Listen again" / "Speed dial" (your recent plays) also come a few per
                    // column (numItemsPerColumn), but as cards, and were taken for Quick picks.
                    rows = s.arr("contents")?.firstOrNull().obj("musicResponsiveListItemRenderer") != null,
                    stacked = (s.str("numItemsPerColumn")?.toIntOrNull() ?: 1) > 1,
                )
            }
            node.obj("musicShelfRenderer")?.let { s ->
                val items = s.arr("contents")?.mapNotNull { parseItem(it) } ?: emptyList()
                if (items.isNotEmpty()) out += Shelf(
                    title = s.obj("title").text() ?: "",
                    items = items,
                    moreBrowseId = s.str("bottomEndpoint", "browseEndpoint", "browseId")
                        ?: s.obj("title").runs().firstOrNull().str("navigationEndpoint", "browseEndpoint", "browseId"),
                    moreParams = s.str("bottomEndpoint", "browseEndpoint", "params"),
                )
            }
            node.obj("gridRenderer")?.let { g ->
                val items = g.arr("items")?.mapNotNull { parseItem(it) } ?: emptyList()
                if (items.isNotEmpty()) out += Shelf(
                    title = g.obj("header", "gridHeaderRenderer", "title").text() ?: "",
                    items = items,
                    grid = true,
                )
            }
        }
        return out
    }

    fun continuation(root: JsonElement?): String? =
        root.findFirst("nextContinuationData").str("continuation")
            ?: root.findFirst("nextRadioContinuationData").str("continuation")
            ?: root.findFirst("continuationCommand").str("token")

    // ---------------------------------------------------------------- The listener's uploads (#332, #101)
    //
    // Written from ytmusicapi's own reading of these answers (sigma67/ytmusicapi at 488dbf3: mixins/uploads.py,
    // parsers/uploads.py, parsers/library.py, parsers/albums.py; MIT): NOT from an answer seen by Prism. No account with
    // uploads was at hand when this was built, so anything unexpected gives nothing rather than a wrong item, and
    // [YouTubeMusic]'s callers log what they saw.

    /** An uploaded artist's page: "FEmusic_library_privately_owned_artist_detail…". */
    fun isUploadArtist(id: String?) = id != null && id.contains("privately_owned_artist")

    /** An uploaded album's page: "FEmusic_library_privately_owned_release_detail…". */
    fun isUploadAlbum(id: String?) = id != null && id.contains("privately_owned_release")

    private fun flexColumn(r: JsonObject, i: Int): List<JsonObject> =
        r.arr("flexColumns")?.getOrNull(i).obj("musicResponsiveListItemFlexColumnRenderer", "text").runs()

    /**
     * An uploaded song's row. Its id is where ytmusicapi reads it, the row's "Add to queue" (the rows don't always say
     * it anywhere else), with the usual places as a fallback. A row with no menu isn't a song: the "Shuffle all" that can
     * head the list.
     */
    fun uploadSong(r: JsonObject, album: AlbumRef? = null): Song? {
        val menu = r.arr("menu", "menuRenderer", "items") ?: return null
        val id = menu.firstNotNullOfOrNull { it.str("menuServiceItemRenderer", "serviceEndpoint", "queueAddEndpoint", "queueTarget", "videoId") }
            ?: r.str("playlistItemData", "videoId")
            ?: r.str("overlay", "musicItemThumbnailOverlayRenderer", "content", "musicPlayButtonRenderer", "playNavigationEndpoint", "watchEndpoint", "videoId")
            ?: return null
        val title = flexColumn(r, 0).joinToString("") { it.str("text") ?: "" }.takeIf { it.isNotBlank() } ?: return null
        val artists = flexColumn(r, 1).mapNotNull { run ->
            val name = run.str("text")?.trim().orEmpty()
            if (isSeparator(name)) null else ArtistRef(name, run.str("navigationEndpoint", "browseEndpoint", "browseId"))
        }
        val albumRun = flexColumn(r, 2).firstOrNull()
        return Song(
            id = id,
            title = title,
            artists = artists,
            album = albumRun?.str("text")?.takeIf { it.isNotBlank() }?.let { AlbumRef(it, albumRun.str("navigationEndpoint", "browseEndpoint", "browseId")) } ?: album,
            durationSec = parseDuration(r.arr("fixedColumns")?.firstOrNull().obj("musicResponsiveListItemFixedColumnRenderer", "text").text()),
            thumbnail = r.obj("thumbnail").bestThumbnail(),
            explicit = r.arr("badges")?.any { it.str("musicInlineBadgeRenderer", "icon", "iconType") == "MUSIC_EXPLICIT_BADGE" } == true,
            isUpload = true,
        )
    }

    /** An uploaded album's card ("Album • Coldplay • 2014"); its page id is on its title. */
    fun uploadAlbum(r: JsonObject): AlbumItem? {
        val id = r.obj("title").runs().firstOrNull().str("navigationEndpoint", "browseEndpoint", "browseId")
            ?: r.str("navigationEndpoint", "browseEndpoint", "browseId") ?: return null
        val title = r.obj("title").text()?.takeIf { it.isNotBlank() } ?: return null
        val playlist = r.arr("menu", "menuRenderer", "items")?.firstNotNullOfOrNull {
            it.str("menuNavigationItemRenderer", "navigationEndpoint", "watchPlaylistEndpoint", "playlistId")
        }
        return AlbumItem(id, title, canonical(r.obj("subtitle").text() ?: ""), r.obj("thumbnailRenderer").bestThumbnail(), playlist)
    }

    /** An uploaded artist's row ("12 songs" under the name). */
    fun uploadArtist(r: JsonObject): ArtistItem? {
        val id = r.str("navigationEndpoint", "browseEndpoint", "browseId") ?: return null
        val name = flexColumn(r, 0).joinToString("") { it.str("text") ?: "" }.takeIf { it.isNotBlank() } ?: return null
        return ArtistItem(id, name, flexColumn(r, 1).joinToString("") { it.str("text") ?: "" }, r.obj("thumbnail").bestThumbnail())
    }

    /**
     * One page of uploaded songs. [rows]: how many rows the answer had, so a shape Prism can't read tells from an empty list.
     * [entities]: each song's id to delete it by (1.8.3, D49), where its row's menu had one.
     */
    class UploadRows<T>(val items: List<T>, val rows: Int, val next: String?, val entities: Map<String, String> = emptyMap())

    /**
     * What an uploaded song is deleted by (1.8.3, D49): the entity id in its row's "Delete" confirm, where ytmusicapi
     * reads it (parsers/uploads.py: the menu's last item, confirmDialogEndpoint → musicDeletePrivatelyOwnedEntityCommand).
     */
    fun uploadEntityId(r: JsonObject): String? =
        r.arr("menu", "menuRenderer", "items")?.firstNotNullOfOrNull { it.findFirst("musicDeletePrivatelyOwnedEntityCommand").str("entityId") }

    fun uploadSongRows(root: JsonElement, album: AlbumRef? = null): UploadRows<Song> {
        val rows = root.findAll("musicResponsiveListItemRenderer")
        val entities = HashMap<String, String>()
        val songs = rows.mapNotNull { r -> uploadSong(r, album)?.also { s -> uploadEntityId(r)?.let { entities[s.id] = it } } }.distinctBy { it.id }
        return UploadRows(songs, rows.size, continuation(root), entities)
    }

    fun uploadAlbumCards(root: JsonElement): UploadRows<AlbumItem> {
        val rows = root.findAll("musicTwoRowItemRenderer")
        return UploadRows(rows.mapNotNull(::uploadAlbum), rows.size, continuation(root))
    }

    fun uploadArtistRows(root: JsonElement): UploadRows<ArtistItem> {
        val rows = root.findAll("musicResponsiveListItemRenderer")
        return UploadRows(rows.mapNotNull(::uploadArtist), rows.size, continuation(root))
    }

    private val uploadYear = Regex("^(19|20)\\d{2}$")

    /** An uploaded album's page as ytmusicapi reads it: `musicDetailHeaderRenderer`, or the newer responsive header. */
    fun uploadAlbumPage(browseId: String, root: JsonElement): com.prism.music.data.model.CollectionPage {
        val header = root.findFirst("musicDetailHeaderRenderer") ?: root.findFirst("musicResponsiveHeaderRenderer")
        val title = header.obj("title").text()?.takeIf { it.isNotBlank() }
        val runs = header.obj("subtitle").runs()
        // "Album • Coldplay • 2014": the kind first, then every other run an artist or the year.
        val named = runs.drop(1).mapNotNull { run -> run.str("text")?.trim()?.takeIf { !isSeparator(it) }?.let { it to run } }
        val year = named.firstOrNull { (t, _) -> uploadYear.matches(t) }?.first
        val artists = named.filter { (t, _) -> !uploadYear.matches(t) && !durationRegex.matches(t) }
            .map { (t, run) -> ArtistRef(t, run.str("navigationEndpoint", "browseEndpoint", "browseId")) }
            .ifEmpty { artistsFromRuns(header.obj("straplineTextOne").runs()) }
        val ref = title?.let { AlbumRef(it, browseId) }
        val rows = uploadSongRows(root, ref)
        val kind = canonical(runs.firstOrNull().str("text")?.trim().orEmpty()).ifBlank { "Album" }
        val thumb = header.obj("thumbnail").bestThumbnail()
        return com.prism.music.data.model.CollectionPage(
            id = browseId,
            kind = com.prism.music.data.model.CollectionKind.ALBUM,
            title = title ?: rows.items.firstNotNullOfOrNull { it.album?.title } ?: "Album",
            subtitle = artists.joinToString(", ") { it.name },
            secondSubtitle = listOfNotNull(kind, year).joinToString(" • "),
            thumbnail = thumb,
            songs = rows.items.map { s -> s.copy(thumbnail = s.thumbnail ?: thumb, artists = s.artists.ifEmpty { artists }) },
            continuation = rows.next,
            playlistId = header.findFirst("watchPlaylistEndpoint").str("playlistId") ?: header.findFirst("watchEndpoint").str("playlistId"),
            artists = artists,
        )
    }

    /** An uploaded artist's page: YouTube sends only their songs, so their albums are read from those. */
    fun uploadArtistPage(browseId: String, root: JsonElement, songs: List<Song>): com.prism.music.data.model.ArtistPage {
        val header = root.findFirst("musicImmersiveHeaderRenderer") ?: root.findFirst("musicVisualHeaderRenderer")
            ?: root.findFirst("musicDetailHeaderRenderer") ?: root.findFirst("musicResponsiveHeaderRenderer")
        val name = header.obj("title").text()?.takeIf { it.isNotBlank() }
            ?: songs.firstNotNullOfOrNull { s -> s.artists.firstOrNull { it.id == browseId }?.name }
            ?: songs.firstOrNull()?.primaryArtist?.takeIf { it.isNotBlank() }
            ?: "Artist"
        val albums = songs.filter { it.album?.id != null }.groupBy { it.album!!.id!! }.map { (id, list) ->
            AlbumItem(id, list.first().album!!.title, name, list.first().thumbnail)
        }
        return com.prism.music.data.model.ArtistPage(
            id = browseId,
            name = name,
            thumbnail = header.obj("thumbnail").bestThumbnail() ?: header.obj("foregroundThumbnail").bestThumbnail() ?: songs.firstNotNullOfOrNull { it.thumbnail },
            description = null,
            subscribers = null,
            shelves = listOfNotNull(
                Shelf("Songs", items = songs.map(::SongItem)).takeIf { songs.isNotEmpty() },
                Shelf("Albums", items = albums).takeIf { albums.isNotEmpty() },
            ),
            radioPlaylistId = null,
            shufflePlaylistId = null,
        )
    }
}
