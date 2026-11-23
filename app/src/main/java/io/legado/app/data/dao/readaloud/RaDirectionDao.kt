package io.legado.app.data.dao.readaloud

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import io.legado.app.data.entities.readaloud.RaDirectionAudioEntity
import io.legado.app.data.entities.readaloud.RaDirectionLineEntity
import io.legado.app.data.entities.readaloud.RaDirectionPlanEntity
import io.legado.app.data.entities.readaloud.RaDirectionSceneEntity

@Dao
interface RaDirectionDao {

    @Query("select * from ra_direction_plan where analysisId = :analysisId and module = :module limit 1")
    suspend fun plan(analysisId: String, module: String): RaDirectionPlanEntity?

    @Query("select * from ra_direction_plan where bookKey = :bookKey and chapterIndex = :chapterIndex and module = :module order by directorRevision desc limit 1")
    suspend fun latestPlan(bookKey: String, chapterIndex: Int, module: String): RaDirectionPlanEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlan(entity: RaDirectionPlanEntity)

    @Query("update ra_direction_plan set status = :status, failReason = :failReason, updatedAt = :updatedAt where id = :id")
    suspend fun updatePlanStatus(id: String, status: String, failReason: String, updatedAt: Long)

    @Query("update ra_direction_plan set status = 'STALE', updatedAt = :updatedAt where analysisId = :analysisId and module = 'PLAYBACK_BASE'")
    suspend fun stalePlaybackPlan(analysisId: String, updatedAt: Long)

    @Query("update ra_direction_plan set status = 'STALE', updatedAt = :updatedAt where bookKey = :bookKey and chapterIndex in (:chapterIndexes) and module = 'PLAYBACK_BASE'")
    suspend fun stalePlaybackPlans(bookKey: String, chapterIndexes: List<Int>, updatedAt: Long)

    @Query("select * from ra_direction_plan where bookKey = :bookKey and chapterIndex = :chapterIndex")
    suspend fun plansOfChapter(bookKey: String, chapterIndex: Int): List<RaDirectionPlanEntity>

    @Query("delete from ra_direction_plan where analysisId = :analysisId")
    suspend fun deletePlan(analysisId: String)

    @Transaction
    suspend fun deletePlanCompletely(planId: String) {
        deleteLines(planId)
        deleteAudio(planId)
        deleteScenes(planId)
        deletePlanById(planId)
    }

    @Query("delete from ra_direction_plan where id = :planId")
    suspend fun deletePlanById(planId: String)

    @Query("delete from ra_direction_plan where bookKey = :bookKey")
    suspend fun deletePlansOfBook(bookKey: String)

    @Query("select * from ra_direction_line where planId = :planId")
    suspend fun lines(planId: String): List<RaDirectionLineEntity>

    @Query("select * from ra_direction_line where planId = :planId and segmentId = :segmentId limit 1")
    suspend fun line(planId: String, segmentId: String): RaDirectionLineEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLines(entities: List<RaDirectionLineEntity>)

    @Query("delete from ra_direction_line where planId = :planId")
    suspend fun deleteLines(planId: String)

    @Query("select * from ra_direction_audio where planId = :planId order by idx")
    suspend fun audio(planId: String): List<RaDirectionAudioEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAudio(entities: List<RaDirectionAudioEntity>)

    @Query("delete from ra_direction_audio where planId = :planId")
    suspend fun deleteAudio(planId: String)

    @Query("select * from ra_direction_scene where planId = :planId order by fromPara")
    suspend fun scenes(planId: String): List<RaDirectionSceneEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertScenes(entities: List<RaDirectionSceneEntity>)

    @Query("delete from ra_direction_scene where planId = :planId")
    suspend fun deleteScenes(planId: String)

    @Query("delete from ra_direction_line where planId in (select id from ra_direction_plan where bookKey = :bookKey)")
    suspend fun deleteLinesOfBook(bookKey: String)

    @Query("delete from ra_direction_audio where planId in (select id from ra_direction_plan where bookKey = :bookKey)")
    suspend fun deleteAudioOfBook(bookKey: String)

    @Query("delete from ra_direction_scene where planId in (select id from ra_direction_plan where bookKey = :bookKey)")
    suspend fun deleteScenesOfBook(bookKey: String)

    @Transaction
    suspend fun savePlan(
        plan: RaDirectionPlanEntity,
        lines: List<RaDirectionLineEntity>,
        audio: List<RaDirectionAudioEntity>,
        scenes: List<RaDirectionSceneEntity>,
    ) {
        upsertPlan(plan)
        deleteLines(plan.id)
        if (lines.isNotEmpty()) upsertLines(lines)
        deleteAudio(plan.id)
        if (audio.isNotEmpty()) upsertAudio(audio)
        deleteScenes(plan.id)
        if (scenes.isNotEmpty()) upsertScenes(scenes)
    }

    @Query("delete from ra_scene where bookKey = :bookKey")
    suspend fun deleteScenesOfBookEntities(bookKey: String)

    @Query("select * from ra_scene where bookKey = :bookKey order by name")
    suspend fun sceneEntities(bookKey: String): List<io.legado.app.data.entities.readaloud.RaSceneEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSceneEntities(entities: List<io.legado.app.data.entities.readaloud.RaSceneEntity>)
}
