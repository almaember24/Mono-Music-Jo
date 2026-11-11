package com.prism.music.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * One of the listener's own labels on a song ("gym", "rainy day"; 1.8.3). A song can have many; a tag is its name, as
 * first typed (the store reuses a name that differs only in case). Kept on the phone, in backups, never sent anywhere.
 */
@Entity(tableName = "song_tags", primaryKeys = ["tag", "songId"], indices = [Index("songId")])
data class SongTagEntity(
    val tag: String,
    val songId: String,
    val addedAt: Long,
)

/** A tag in Library: how many songs it has, the newest one's cover, and when one was last added. */
data class TagSummary(val tag: String, val songs: Int, val thumbnail: String?, val lastAt: Long)

/** How many of some songs carry a tag. */
data class TagHold(val tag: String, val n: Int)

/** A tagged song with its tag, for backups. */
data class TaggedRow(
    val tag: String,
    val addedAt: Long,
    @androidx.room.Embedded val song: SongEntity,
)

private const val SUMMARIES = """SELECT t.tag AS tag, COUNT(*) AS songs, MAX(t.addedAt) AS lastAt,
           (SELECT s.thumbnail FROM song_tags t2 JOIN songs s ON s.id = t2.songId
            WHERE t2.tag = t.tag AND s.thumbnail IS NOT NULL ORDER BY t2.addedAt DESC LIMIT 1) AS thumbnail
           FROM song_tags t GROUP BY t.tag ORDER BY lastAt DESC"""

@Dao
abstract class TagDao {
    @Query(SUMMARIES)
    abstract fun summariesFlow(): Flow<List<TagSummary>>

    @Query(SUMMARIES)
    abstract suspend fun summaries(): List<TagSummary>

    @Query("SELECT tag FROM song_tags WHERE songId = :songId ORDER BY tag COLLATE NOCASE")
    abstract fun tagsOfFlow(songId: String): Flow<List<String>>

    @Query("SELECT tag AS tag, COUNT(*) AS n FROM song_tags WHERE songId IN (:ids) GROUP BY tag")
    abstract suspend fun holds(ids: List<String>): List<TagHold>

    /** A tag's songs, the newest tagged first. */
    @Query("SELECT s.* FROM song_tags t JOIN songs s ON s.id = t.songId WHERE t.tag = :tag ORDER BY t.addedAt DESC")
    abstract fun songsFlow(tag: String): Flow<List<SongEntity>>

    @Query("SELECT DISTINCT tag FROM song_tags")
    abstract suspend fun names(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun add(rows: List<SongTagEntity>): List<Long>

    @Query("DELETE FROM song_tags WHERE tag = :tag AND songId IN (:ids)")
    abstract suspend fun remove(tag: String, ids: List<String>): Int

    @Query("DELETE FROM song_tags WHERE tag = :tag")
    abstract suspend fun delete(tag: String)

    @Query("UPDATE OR IGNORE song_tags SET tag = :to WHERE tag = :from")
    protected abstract suspend fun renameRows(from: String, to: String)

    /** [from]'s songs go to [to] (joining it when it's already a tag); [from] is gone after. */
    @Transaction
    open suspend fun rename(from: String, to: String) {
        renameRows(from, to)
        if (from != to) delete(from)
    }

    @Query("SELECT t.tag AS tag, t.addedAt AS addedAt, s.* FROM song_tags t JOIN songs s ON s.id = t.songId ORDER BY t.addedAt")
    abstract suspend fun allWithSongs(): List<TaggedRow>

    @Query("DELETE FROM song_tags")
    protected abstract suspend fun clear()

    /** Every tag replaced by [rows] (a restore). */
    @Transaction
    open suspend fun replaceAll(rows: List<SongTagEntity>) {
        clear()
        rows.chunked(500).forEach { add(it) }
    }
}
