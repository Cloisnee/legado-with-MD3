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

/** 当前版本发布、只保留历史，或同 ID 已有更新结果。 */
enum class RaBuildPublication { PUBLISHED, HISTORY_ONLY, IGNORED }

interface RaBuildGateway {

    suspend fun getBuild(bookKey: String, sourceKey: String, chapterIndex: Int): RaChapterBuild?

    suspend fun builds(bookKey: String, sourceKey: String): List<RaChapterBuild>

    /** 只替换读取时的同一条状态（expected=null 表示尚无记录）。 */
    suspend fun transitionBuild(expected: RaChapterBuild?, next: RaChapterBuild): Boolean

    /** 只载入同次模型响应里仍是当前版本的已就绪章，供分次收敛复用。 */
    suspend fun readyBatchAnalyses(bookKey: String, sourceKey: String, batchIds: Set<String>): List<RaAnalysis>

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

    /** 分析包、有效指针、用户覆盖和观察在同一事务中发布。 */
    suspend fun saveAnalysisBundle(
        analysis: RaAnalysis,
        segments: List<RaSegment>,
        localSpeakers: List<RaLocalSpeaker>,
        localMentions: List<RaLocalMention>,
        evidence: List<RaEvidence>,
        build: RaChapterBuild,
        attributes: List<RaAttributeObservation> = emptyList(),
        relations: List<RaRelationObservation> = emptyList(),
        expectedExtraction: RaChapterBuild? = null,
    ): RaBuildPublication

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
