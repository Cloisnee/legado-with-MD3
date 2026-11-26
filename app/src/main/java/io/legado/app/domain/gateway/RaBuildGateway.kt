package io.legado.app.domain.gateway

import io.legado.app.domain.model.readaloud.v4.RaAnalysis
import io.legado.app.domain.model.readaloud.v4.RaAttributeObservation
import io.legado.app.domain.model.readaloud.v4.RaChapterBuild
import io.legado.app.domain.model.readaloud.v4.RaEvidence
import io.legado.app.domain.model.readaloud.v4.RaLocalMention
import io.legado.app.domain.model.readaloud.v4.RaLocalSpeaker
import io.legado.app.domain.model.readaloud.v4.RaRelationObservation
import io.legado.app.domain.model.readaloud.v4.RaSegment
import io.legado.app.domain.model.readaloud.v4.RaSegmentOverride

interface RaBuildGateway {

    suspend fun getBuild(bookKey: String, sourceKey: String, chapterIndex: Int): RaChapterBuild?

    suspend fun builds(bookKey: String, sourceKey: String): List<RaChapterBuild>

    suspend fun upsertBuild(build: RaChapterBuild)

    suspend fun getAnalysis(
        bookKey: String,
        sourceKey: String,
        chapterIndex: Int,
        contentHash: String,
        resolverVersion: String,
    ): RaAnalysis?

    suspend fun analysisById(id: String): RaAnalysis?

    /** 换源复用：同一 bookKey + 正文哈希 + 解析版本的已收敛分析（可来自任意 sourceKey）。 */
    suspend fun analysisByContentHash(
        bookKey: String,
        chapterIndex: Int,
        contentHash: String,
        resolverVersion: String,
    ): RaAnalysis?

    suspend fun latestAnalysisForSource(bookKey: String, sourceKey: String, chapterIndex: Int): RaAnalysis?

    suspend fun saveAnalysisBundle(
        analysis: RaAnalysis,
        segments: List<RaSegment>,
        localSpeakers: List<RaLocalSpeaker>,
        localMentions: List<RaLocalMention>,
        evidence: List<RaEvidence>,
        build: RaChapterBuild?,
    )

    /** 同一事务写入章节分析与属性/关系观察；默认退化为普通 bundle。 */
    suspend fun saveAnalysisBundleWithObservations(
        analysis: RaAnalysis,
        segments: List<RaSegment>,
        localSpeakers: List<RaLocalSpeaker>,
        localMentions: List<RaLocalMention>,
        evidence: List<RaEvidence>,
        build: RaChapterBuild?,
        attributes: List<RaAttributeObservation>,
        relations: List<RaRelationObservation>,
    ) {
        saveAnalysisBundle(analysis, segments, localSpeakers, localMentions, evidence, build)
    }

    suspend fun segments(analysisId: String): List<RaSegment>

    suspend fun segmentsOfChapter(bookKey: String, sourceKey: String, chapterIndex: Int): List<RaSegment>

    suspend fun lineCounts(bookKey: String, sourceKey: String): Map<Int, Int>

    suspend fun localSpeakers(analysisId: String): List<RaLocalSpeaker>

    suspend fun localMentions(analysisId: String): List<RaLocalMention>

    suspend fun evidence(analysisId: String): List<RaEvidence>

    suspend fun evidenceOfCharacter(bookKey: String, characterId: String, limit: Int = 6): List<RaEvidence>

    suspend fun overrides(bookKey: String, chapterIndex: Int): List<RaSegmentOverride>

    suspend fun updateLocalSpeakerResolution(
        localSpeakerId: String,
        characterId: String?,
        resolution: String,
    )

    suspend fun updateSegmentSpeaker(segmentId: String, characterId: String?, surfaceName: String)

    suspend fun markSegmentNarrator(segmentId: String)

    suspend fun saveOverride(
        bookKey: String,
        chapterIndex: Int,
        paragraphIndex: Int,
        textHash: String,
        characterId: String?,
        narrator: Boolean,
    )

    suspend fun deleteChapters(bookKey: String, sourceKey: String, chapterIndexes: List<Int>)

    suspend fun deleteBook(bookKey: String)
}
