package io.legado.app.domain.gateway

import io.legado.app.domain.model.readaloud.v4.RaAnalysis
import io.legado.app.domain.model.readaloud.v4.RaChapterBuild
import io.legado.app.domain.model.readaloud.v4.RaEvidence
import io.legado.app.domain.model.readaloud.v4.RaLocalSpeaker
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

    suspend fun latestAnalysisForSource(bookKey: String, sourceKey: String, chapterIndex: Int): RaAnalysis?

    suspend fun saveAnalysisBundle(
        analysis: RaAnalysis,
        segments: List<RaSegment>,
        localSpeakers: List<RaLocalSpeaker>,
        evidence: List<RaEvidence>,
        build: RaChapterBuild?,
    )

    suspend fun segments(analysisId: String): List<RaSegment>

    suspend fun segmentsOfChapter(bookKey: String, sourceKey: String, chapterIndex: Int): List<RaSegment>

    suspend fun lineCounts(bookKey: String, sourceKey: String): Map<Int, Int>

    suspend fun localSpeakers(analysisId: String): List<RaLocalSpeaker>

    suspend fun evidence(analysisId: String): List<RaEvidence>

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
