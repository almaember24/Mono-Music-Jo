package com.prism.music

import com.prism.music.data.innertube.Parser
import com.prism.music.data.innertube.SearchRank
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** YouTube answers in the phone's language; Prism reads its type labels, years and audiences in any of them. */
class LocalizedLabelsTest {
    @Test fun typeLabelsAndYearsComeBackInEnglish() {
        assertEquals("Single • 아이유 • 2026", Parser.canonical("싱글 • 아이유 • 2026년"))
        assertEquals("Song • 3:13", Parser.canonical("Canción • 3:13"))
        assertEquals("Album • 宇多田ヒカル • 2016", Parser.canonical("アルバム • 宇多田ヒカル • 2016年"))
        assertEquals("Playlist • Cyworld Music Channel", Parser.canonical("재생목록 • Cyworld Music Channel"))
        assertEquals("Video • IU", Parser.canonical("Vídeo • IU"))
        // Only the first segment is a type: an album called "Música" stays.
        assertEquals("Song • Artist • Música • 3:10", Parser.canonical("Canción • Artist • Música • 3:10"))
        assertEquals("Single • IU • 2026", Parser.canonical("Single • IU • 2026"))
    }

    @Test fun audiencesInOtherLanguages() {
        assertEquals(444_000_000L, SearchRank.audience("Artist • 444M monthly audience"))
        assertEquals(3_550L, SearchRank.audience("Artist • 3.55K subscribers"))
        assertEquals(72_500_000L, SearchRank.audience("아티스트 • 월간 시청자 7250만명"))
        assertEquals(65_000_000L, SearchRank.audience("Künstler • 65 Mio. Zuschauer/Monat"))
        assertEquals(72_500_000L, SearchRank.audience("Artis • 72,5 jt audiens bulanan"))
        assertEquals(65_000_000L, SearchRank.audience("ศิลปิน • มีผู้ชมรายเดือน 65 ล้าน คน"))
        assertEquals(789_000L, SearchRank.audience("Artist • 789 000 lyssnare per månad"))
        assertEquals(789_000L, SearchRank.audience("Исполнитель • 789 тыс. слушателей в месяц"))
        assertEquals(65_000_000L, SearchRank.audience("Artista • 65 mi ouvintes mensais"))
        assertTrue(SearchRank.audience("Artista • 65 M usuarios mensuales") >= 1_000_000)
        assertEquals(65_000_000L, SearchRank.audience("कलाकार • हर महीने 6.5 क॰ दर्शक"))
    }
}
