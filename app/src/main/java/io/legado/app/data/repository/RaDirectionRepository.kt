package io.legado.app.data.repository

import io.legado.app.data.dao.readaloud.RaDirectionDao
import io.legado.app.data.entities.readaloud.RaDirectionAudioEntity
import io.legado.app.data.entities.readaloud.RaDirectionLineEntity
import io.legado.app.data.entities.readaloud.RaDirectionPlanEntity
import io.legado.app.domain.gateway.RaDirectionAudio
import io.legado.app.domain.gateway.RaDirectionGateway
import io.legado.app.domain.gateway.RaDirectionLine
import io.legado.app.domain.gateway.RaDirectionPlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RaDirectionRepository(
    private val dao: RaDirectionDao,
) : RaDirectionGateway {

    override suspend fun plan(analysisId: String, module: String): RaDirectionPlan? =
        withContext(Dispatchers.IO) {
            dao.plan(analysisId, module)?.toDomain()
        }

    override suspend fun latestPlan(bookKey: String, chapterIndex: Int, module: String): RaDirectionPlan? =
        withContext(Dispatchers.IO) {
            dao.latestPlan(bookKey, chapterIndex, module)?.toDomain()
        }

    override suspend fun savePlan(
        plan: RaDirectionPlan,
        lines: List<RaDirectionLine>,
        audio: List<RaDirectionAudio>,
    ) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        dao.savePlan(
            plan = plan.toEntity(now),
            lines = lines.map {
                RaDirectionLineEntity(
                    planId = plan.id,
                    segmentId = it.segmentId,
                    emotionTag = it.emotionTag,
                    instruction = it.instruction,
                    voiceStateHint = it.voiceStateHint,
                    createdAt = now,
                    updatedAt = now,
                )
            },
            audio = audio.map {
                RaDirectionAudioEntity(
                    planId = plan.id,
                    idx = it.idx,
                    type = it.type,
                    para = it.para,
                    frag = it.frag,
                    posRatio = it.posRatio,
                    tag = it.tag,
                    desc = it.desc,
                    delayMs = it.delayMs,
                    hold = it.hold,
                    theme = it.theme,
                    scene = it.scene,
                    mood = it.mood,
                    speed = it.speed,
                    anchor = it.anchor,
                    sceneKey = it.sceneKey,
                    createdAt = now,
                    updatedAt = now,
                )
            },
            scenes = emptyList(),
        )
    }

    override suspend fun lines(planId: String): List<RaDirectionLine> = withContext(Dispatchers.IO) {
        dao.lines(planId).map {
            RaDirectionLine(
                segmentId = it.segmentId,
                emotionTag = it.emotionTag,
                instruction = it.instruction,
                voiceStateHint = it.voiceStateHint,
            )
        }
    }

    override suspend fun audio(planId: String): List<RaDirectionAudio> = withContext(Dispatchers.IO) {
        dao.audio(planId).map {
            RaDirectionAudio(
                idx = it.idx,
                type = it.type,
                para = it.para,
                frag = it.frag,
                posRatio = it.posRatio,
                tag = it.tag,
                desc = it.desc,
                delayMs = it.delayMs,
                hold = it.hold,
                theme = it.theme,
                scene = it.scene,
                mood = it.mood,
                speed = it.speed,
                anchor = it.anchor,
                sceneKey = it.sceneKey,
            )
        }
    }

    override suspend fun markPlaybackStale(analysisId: String) = withContext(Dispatchers.IO) {
        dao.stalePlaybackPlan(analysisId, System.currentTimeMillis())
    }
}

private fun RaDirectionPlanEntity.toDomain() = RaDirectionPlan(
    id = id,
    analysisId = analysisId,
    bookKey = bookKey,
    chapterIndex = chapterIndex,
    module = module,
    status = status,
    directorRevision = directorRevision,
    identityRevisionSnapshot = identityRevisionSnapshot,
    origin = origin,
    failReason = failReason,
)

private fun RaDirectionPlan.toEntity(now: Long) = RaDirectionPlanEntity(
    id = id,
    analysisId = analysisId,
    bookKey = bookKey,
    chapterIndex = chapterIndex,
    module = module,
    status = status,
    directorRevision = directorRevision,
    identityRevisionSnapshot = identityRevisionSnapshot,
    origin = origin,
    failReason = failReason,
    createdAt = now,
    updatedAt = now,
)
