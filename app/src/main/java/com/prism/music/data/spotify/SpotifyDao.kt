package com.prism.music.data.spotify

import androidx.room.Dao
import androidx.room.Query

/**
 * What the Spotify connection asks of the imported plays (1.8.1): no table of its own (live plays are imported plays
 * from "spotify_live"), only the questions that keep a play from being counted twice.
 */
@Dao
interface SpotifyDao {
    /**
     * When a song was played in a data download's history ("spotify"), between two times: by its name, or by its Spotify
     * URI (the extended history names a feature or a compilation's song by the album's artist, so its name can differ).
     */
    @Query("SELECT timestamp FROM imported_plays WHERE origin = 'spotify' AND (songKey = :key OR sourceId = :uri) AND timestamp BETWEEN :from AND :to")
    suspend fun downloadTimes(key: String, uri: String, from: Long, to: Long): List<Long>

    /**
     * Takes away live plays a data download brought later: the same song (by name or Spotify URI) within [slackMs] of the
     * play's time, or of its end (whichever Spotify's two clocks name).
     */
    @Query(
        """DELETE FROM imported_plays WHERE origin = 'spotify_live' AND EXISTS (
               SELECT 1 FROM imported_plays d WHERE d.origin = 'spotify'
               AND (d.songKey = imported_plays.songKey OR d.sourceId = imported_plays.sourceId)
               AND d.timestamp BETWEEN imported_plays.timestamp - :slackMs AND imported_plays.timestamp + imported_plays.playedMs + :slackMs)"""
    )
    suspend fun dropLiveTwins(slackMs: Long): Int

    @Query("SELECT COUNT(*) FROM imported_plays WHERE origin = 'spotify_live'")
    suspend fun livePlays(): Int
}
