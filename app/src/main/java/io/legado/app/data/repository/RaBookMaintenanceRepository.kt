package io.legado.app.data.repository

import androidx.room.withTransaction
import com.google.gson.Gson
import io.legado.app.data.AppDatabase
import io.legado.app.data.dao.readaloud.RaBuildDao
import io.legado.app.data.dao.readaloud.RaCharacterDao
import io.legado.app.data.entities.readaloud.RaAttributeObsEntity
import io.legado.app.data.entities.readaloud.RaBookIdentityEntity
import io.legado.app.data.entities.readaloud.RaChapterAnalysisEntity
import io.legado.app.data.entities.readaloud.RaChapterBuildEntity
import io.legado.app.data.entities.readaloud.RaCharacterAliasEntity
import io.legado.app.data.entities.readaloud.RaCharacterEntity
import io.legado.app.data.entities.readaloud.RaCharacterRedirectEntity
import io.legado.app.data.entities.readaloud.RaEvidenceEntity
import io.legado.app.data.entities.readaloud.RaIdentityDecisionEntity
import io.legado.app.data.entities.readaloud.RaIdentityLinkEntity
import io.legado.app.data.entities.readaloud.RaLocalMentionEntity
import io.legado.app.data.entities.readaloud.RaLocalSpeakerEntity
import io.legado.app.data.entities.readaloud.RaRelationObsEntity
import io.legado.app.data.entities.readaloud.RaSegmentEntity
import io.legado.app.data.entities.readaloud.RaSegmentOverrideEntity
import io.legado.app.data.entities.readaloud.RaVoiceProfileEntity
import io.legado.app.domain.gateway.RaBuildGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

/**
 * 书籍级维护：删除章节分析 / 删除整书数据 / 人物出场重算 / 分析数据 JSON 导入导出。
 * 音频缓存与 Room 数据同一集合删除；不触碰书架中的 Book。
 */
class RaBookMaintenanceRepository(
    private val database: AppDatabase,
    private val buildDao: RaBuildDao,
    private val characterDao: RaCharacterDao,
    private val audioCache: ReadAloudAudioCacheRepository,
    private val catalog: ReadAloudCatalogRepository,
    private val buildGateway: RaBuildGateway,
) {

    data class ExportPackage(
        val schemaVersion: Int = 3,
        val books: List<BookData> = emptyList(),
    )

    data class BookData(
        val bookKey: String,
        val identities: List<RaBookIdentityEntity> = emptyList(),
        val builds: List<RaChapterBuildEntity> = emptyList(),
        val analyses: List<RaChapterAnalysisEntity> = emptyList(),
        val segments: List<RaSegmentEntity> = emptyList(),
        val localSpeakers: List<RaLocalSpeakerEntity> = emptyList(),
        val localMentions: List<RaLocalMentionEntity> = emptyList(),
        val evidence: List<RaEvidenceEntity> = emptyList(),
        val characters: List<RaCharacterEntity> = emptyList(),
        val aliases: List<RaCharacterAliasEntity> = emptyList(),
        val attributes: List<RaAttributeObsEntity> = emptyList(),
        val relations: List<RaRelationObsEntity> = emptyList(),
        val links: List<RaIdentityLinkEntity> = emptyList(),
        val decisions: List<RaIdentityDecisionEntity> = emptyList(),
        val redirects: List<RaCharacterRedirectEntity> = emptyList(),
        val voiceProfiles: List<RaVoiceProfileEntity> = emptyList(),
        val overrides: List<RaSegmentOverrideEntity> = emptyList(),
    )

    suspend fun deleteChapters(
        bookKey: String,
        sourceKey: String,
        bookName: String,
        chapterIndexes: Collection<Int>,
        rollback: Boolean,
    ) = withContext(Dispatchers.IO) {
        if (chapterIndexes.isEmpty()) return@withContext
        val floor = chapterIndexes.min()
        val known = buildGateway.builds(bookKey, sourceKey).map { it.chapterIndex }
        val targets = if (rollback) {
            (known.filter { it >= floor } + chapterIndexes).distinct().sorted()
        } else {
            chapterIndexes.toList()
        }
        buildGateway.deleteChapters(bookKey, sourceKey, targets)
        val audioTargets = if (rollback) {
            audioCache.listChapters(bookName).filter { it >= floor }
        } else {
            chapterIndexes.toList()
        }
        audioTargets.forEach { audioCache.deleteChapter(bookName, it) }
        database.withTransaction { refreshCharacters(bookKey) }
    }

    suspend fun deleteBook(bookKey: String, bookName: String) = withContext(Dispatchers.IO) {
        buildGateway.deleteBook(bookKey)
        if (bookName.isNotBlank()) audioCache.deleteBook(bookName)
    }

    suspend fun exportBooksJson(books: List<String>, out: OutputStream): Int = withContext(Dispatchers.IO) {
        val summaries = catalog.booksWithData()
        val selected = books.mapNotNull { name ->
            summaries.firstOrNull { it.name == name || it.bookKey == name }
        }.distinctBy { it.bookKey }
        if (selected.isEmpty()) return@withContext 0
        val packages = selected.map { summary -> exportBook(summary.bookKey) }
        out.bufferedWriter().use { writer ->
            writer.write(Gson().toJson(ExportPackage(books = packages)))
        }
        packages.size
    }

    suspend fun importBooksJson(input: InputStream): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        runCatching {
            val text = input.bufferedReader().use { it.readText() }
            val packageData = Gson().fromJson(text, ExportPackage::class.java)
                ?: return@runCatching false to "JSON 为空"
            if (packageData.schemaVersion != 3) {
                return@runCatching false to "不支持的 schemaVersion=${packageData.schemaVersion}"
            }
            var imported = 0
            database.withTransaction {
                packageData.books.forEach { book ->
                    importBook(book)
                    imported++
                }
            }
            true to "已导入 $imported 本书的分析数据"
        }.getOrElse {
            false to "导入失败：${it.localizedMessage ?: it.javaClass.simpleName}"
        }
    }

    private suspend fun exportBook(bookKey: String): BookData {
        val characters = characterDao.allCharacters(bookKey)
        val redirects = characterDao.redirectsOfBook(bookKey)
        return BookData(
            bookKey = bookKey,
            identities = buildDao.identitiesOf(bookKey),
            builds = buildDao.allBuildsOf(bookKey),
            analyses = buildDao.allAnalysesOf(bookKey),
            segments = buildDao.allSegmentsOf(bookKey),
            localSpeakers = buildDao.localSpeakersOfBook(bookKey),
            localMentions = buildDao.localMentionsOfBook(bookKey),
            evidence = buildDao.evidenceOfBook(bookKey),
            characters = characters,
            aliases = characterDao.aliasesOfBook(bookKey),
            attributes = characterDao.attributesOfBook(bookKey),
            relations = characterDao.relationsOfBook(bookKey),
            links = characterDao.linksOfBook(bookKey),
            decisions = characterDao.decisionsOfBook(bookKey),
            redirects = redirects,
            voiceProfiles = characterDao.voiceProfilesOfBook(bookKey),
            overrides = buildDao.overridesOfBook(bookKey),
        )
    }

    private suspend fun importBook(book: BookData) {
        book.identities.forEach { buildDao.upsertIdentity(it) }
        book.builds.forEach { buildDao.upsertBuild(it) }
        book.analyses.forEach { buildDao.upsertAnalysis(it) }
        if (book.segments.isNotEmpty()) buildDao.upsertSegments(book.segments)
        if (book.localSpeakers.isNotEmpty()) buildDao.upsertLocalSpeakers(book.localSpeakers)
        if (book.localMentions.isNotEmpty()) buildDao.upsertLocalMentions(book.localMentions)
        if (book.evidence.isNotEmpty()) buildDao.upsertEvidence(book.evidence)
        book.characters.forEach { characterDao.upsertCharacter(it) }
        if (book.aliases.isNotEmpty()) characterDao.upsertAliases(book.aliases)
        book.attributes.forEach { characterDao.insertAttribute(it) }
        book.relations.forEach { characterDao.insertRelation(it) }
        book.links.forEach { characterDao.upsertLink(it) }
        book.decisions.forEach { characterDao.insertDecision(it) }
        book.redirects.forEach { characterDao.upsertRedirect(it) }
        book.voiceProfiles.forEach { characterDao.upsertVoiceProfile(it) }
        book.overrides.forEach { buildDao.upsertOverride(it) }
    }

    private suspend fun refreshCharacters(bookKey: String) {
        characterDao.allCharacters(bookKey).forEach { character ->
            val aggregate = buildDao.appearanceOf(character.id)
            if (aggregate.lineCount <= 0) {
                characterDao.deleteRedirectsForCharacter(character.id)
                characterDao.deleteLinksForCharacter(bookKey, character.id)
                characterDao.deleteDecisionsByChosenCharacter(character.id)
                characterDao.deleteVoiceProfilesOfCharacter(character.id)
                characterDao.deleteAliasesOfCharacter(character.id)
                characterDao.deleteAttributes(character.id)
                characterDao.deleteRelations(character.id)
                characterDao.deleteCharacter(character.id)
            } else {
                val first = aggregate.firstChapter ?: character.firstChapter
                val last = aggregate.lastChapter ?: character.lastChapter
                characterDao.refreshAppearance(
                    id = character.id,
                    firstChapter = first,
                    lastChapter = last,
                    appearanceCount = aggregate.chapterCount,
                    updatedAt = System.currentTimeMillis(),
                )
            }
        }
    }
}
