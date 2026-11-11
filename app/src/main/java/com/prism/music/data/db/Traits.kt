package com.prism.music.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * What's been measured of a song (1.8.3), in one place where it can be filtered: its tempo, key, loudness and energy.
 * Filled whenever a song is scanned (Mix's whole-song scan, DJ mode's quick scan; [com.prism.music.data.SongTraits]),
 * and copied once from the scans' own prefs, which Mix and DJ mode keep reading as before.
 */
@Entity(tableName = "song_traits", indices = [Index("bpm"), Index("energy")])
data class SongTraitsEntity(
    @PrimaryKey val songId: String,
    /** Beats per minute; null with no beat found. */
    val bpm: Double?,
    /** The beat is clear enough to go by ([com.prism.music.playback.Tempo.steady]). */
    val bpmSure: Boolean,
    /** [com.prism.music.playback.MusicKey.encode]; null with no clear key. */
    val key: String?,
    /** The key on the Camelot wheel ("8A"), for filtering. */
    val camelot: String?,
    /** Integrated loudness, LUFS. */
    val lufs: Float?,
    /** 0 (calm) … 1 (energetic): [com.prism.music.playback.Energy]. */
    val energy: Float?,
    /** Measured over the whole song (true) or from DJ mode's three stretches of it (false; the whole song's wins). */
    val whole: Boolean,
    val measuredAt: Long,
)

@Dao
interface TraitsDao {
    @Query("SELECT * FROM song_traits WHERE songId = :id")
    suspend fun get(id: String): SongTraitsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(e: SongTraitsEntity)

    /** Rows for songs that have none yet (the copy from the scans' prefs never replaces a newer measurement). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun putMissing(e: List<SongTraitsEntity>)

    @Query("DELETE FROM song_traits WHERE songId = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM song_traits")
    suspend fun count(): Int

    @Query("SELECT songId FROM song_traits")
    suspend fun ids(): List<String>
}
