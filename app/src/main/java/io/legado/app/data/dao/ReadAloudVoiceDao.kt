package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.legado.app.data.entities.ReadAloudVoiceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReadAloudVoiceDao {

    /** UI 列表：用户排除的不显示。 */
    @Query(
        "select * from read_aloud_voices where excluded = 0 " +
            "order by available desc, enabled desc, displayName collate nocase"
    )
    fun observeVoices(): Flow<List<ReadAloudVoiceEntity>>

    /**
     * 全量，含被排除的。
     *
     * 目录同步要靠它比对已有行并保留排除标记，所以这里不能过滤。
     */
    @Query("select * from read_aloud_voices order by available desc, enabled desc, displayName collate nocase")
    suspend fun getVoices(): List<ReadAloudVoiceEntity>

    /** 可选/自动选音候选：排除的不参与。 */
    @Query(
        "select * from read_aloud_voices where enabled = 1 and available = 1 and excluded = 0 " +
            "order by displayName collate nocase"
    )
    suspend fun getEnabledVoices(): List<ReadAloudVoiceEntity>

    @Query("select * from read_aloud_voices where id = :id limit 1")
    suspend fun getVoice(id: String): ReadAloudVoiceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertVoice(voice: ReadAloudVoiceEntity)

    @Delete
    suspend fun deleteVoice(voice: ReadAloudVoiceEntity)
}
