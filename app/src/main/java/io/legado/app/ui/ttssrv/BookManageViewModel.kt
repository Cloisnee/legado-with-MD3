package io.legado.app.ui.ttssrv

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.constant.AppLog
import io.legado.app.data.repository.RaBookMaintenanceRepository
import io.legado.app.data.repository.ReadAloudCatalogRepository
import io.legado.app.data.repository.ReadAloudVoicePoolRepository
import io.legado.app.domain.gateway.RaBuildGateway
import io.legado.app.domain.gateway.RaCharacterGateway
import io.legado.app.domain.gateway.RaDirectionGateway
import io.legado.app.domain.model.readaloud.SpeechRoleType
import io.legado.app.domain.model.readaloud.v4.RaBookSummary
import io.legado.app.domain.model.readaloud.v4.RaCharacterView
import io.legado.app.domain.model.readaloud.v4.RaScriptLineRow
import io.legado.app.help.readaloud.analysis.AnalysisSchedulerV3
import io.legado.app.utils.ChapterLabels
import io.legado.app.utils.MD5Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.context.GlobalContext

data class BookManageUiState(
    val books: List<RaBookSummary> = emptyList(),
    val currentBookKey: String = "",
    val currentBookUrl: String = "",
    val currentSourceKey: String = "",
    val records: List<RaCharacterView> = emptyList(),
    val chapters: List<Int> = emptyList(),
    val selectedChapter: Int? = null,
    val lines: List<RaScriptLineRow> = emptyList(),
    val chapterTitles: Map<Int, String> = emptyMap(),
    val loading: Boolean = false,
) {
    val currentBookName: String
        get() = books.firstOrNull { it.bookKey == currentBookKey }?.name ?: "默认"
}

class BookManageViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(BookManageUiState())
    val uiState = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val effects = _effects.asSharedFlow()

    private val catalog by lazy { GlobalContext.get().get<ReadAloudCatalogRepository>() }
    private val raBuild by lazy { GlobalContext.get().get<RaBuildGateway>() }
    private val raDirection by lazy { GlobalContext.get().get<RaDirectionGateway>() }
    private val characters by lazy { GlobalContext.get().get<RaCharacterGateway>() }
    private val maintenance by lazy { GlobalContext.get().get<RaBookMaintenanceRepository>() }
    private val voicePool by lazy { GlobalContext.get().get<ReadAloudVoicePoolRepository>() }
    private val app by lazy { GlobalContext.get().get<Context>() as Application }

    private var externalBookUrl: String? = null
    private var embedded = false
    private var initialChapter: Int? = null

    fun configure(externalBookUrl: String?, embedded: Boolean, initialChapter: Int?) {
        this.externalBookUrl = externalBookUrl
        this.embedded = embedded
        this.initialChapter = initialChapter
    }

    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true) }
            val list = catalog.booksWithData()
            var key = if (externalBookUrl != null) {
                catalog.bookKeyByBookUrl(externalBookUrl!!)
            } else {
                catalog.currentBookKey()
            }
            if (key.isNullOrBlank()) key = list.firstOrNull()?.bookKey
            val bookKey = key.orEmpty()
            if (bookKey.isNotBlank() && catalog.currentBookKey() != bookKey && externalBookUrl == null) {
                catalog.setCurrentBookKey(bookKey)
            }
            val summary = list.firstOrNull { it.bookKey == bookKey }
            val bookUrl = externalBookUrl ?: summary?.bookUrl.orEmpty()
            val sourceKey = summary?.sourceKey ?: catalog.sourceKeyByBookUrl(bookUrl).orEmpty()
            val records = if (bookKey.isBlank()) emptyList() else characters.characters(bookKey)
            val lineCounts = if (bookKey.isBlank()) emptyMap() else raBuild.lineCounts(bookKey, sourceKey)
            val chapters = lineCounts.keys.sorted()
            val chapterTitles = catalog.chapterTitles(bookUrl)
            val previous = _uiState.value.selectedChapter
            val selected = when {
                embedded && initialChapter != null -> initialChapter
                previous != null && previous in chapters -> previous
                else -> chapters.lastOrNull()
            }
            _uiState.update {
                it.copy(
                    books = list,
                    currentBookKey = bookKey,
                    currentBookUrl = bookUrl,
                    currentSourceKey = sourceKey,
                    records = records,
                    chapters = chapters,
                    chapterTitles = chapterTitles,
                    selectedChapter = selected,
                    loading = false,
                )
            }
            reloadLines()
        }
    }

    fun selectBook(bookKey: String) {
        if (bookKey == _uiState.value.currentBookKey) return
        viewModelScope.launch {
            catalog.setCurrentBookKey(bookKey)
            _uiState.update { it.copy(selectedChapter = null) }
            load()
        }
    }

    fun selectChapter(chapter: Int) {
        _uiState.update { it.copy(selectedChapter = chapter) }
        viewModelScope.launch { reloadLines() }
    }

    fun chLabel(chapter: Int): String = ChapterLabels.of(_uiState.value.chapterTitles[chapter], chapter)

    fun tailMin(): Int {
        val sorted = _uiState.value.chapters.sorted()
        if (sorted.isEmpty()) return 0
        var tail = sorted.last()
        for (i in sorted.size - 2 downTo 0) {
            if (sorted[i] == sorted[i + 1] - 1) tail = sorted[i] else break
        }
        return tail
    }

    private suspend fun reloadLines() {
        val state = _uiState.value
        val ch = state.selectedChapter
        if (ch == null || state.currentBookKey.isBlank()) {
            _uiState.update { it.copy(lines = emptyList()) }
            return
        }
        val segments = raBuild.segmentsOfChapter(state.currentBookKey, state.currentSourceKey, ch)
        val analysis = raBuild.getBuild(state.currentBookKey, state.currentSourceKey, ch)?.analysisId
            ?.let { raBuild.analysisById(it) }
            ?: raBuild.latestAnalysisForSource(state.currentBookKey, state.currentSourceKey, ch)
        val directionLines = analysis?.let { item ->
            raDirection.plan(item.id, "PLAYBACK_BASE")?.let { plan -> raDirection.lines(plan.id) }
        }.orEmpty().associateBy { it.segmentId }
        val charactersRaw = characters.charactersRaw(state.currentBookKey).associateBy { it.id }
        val finalIdCache = HashMap<String, String>()
        suspend fun displayNameOf(characterId: String?): String {
            if (characterId.isNullOrBlank()) return ""
            val finalId = finalIdCache.getOrPut(characterId) { characters.finalCharacterId(characterId) }
            return charactersRaw[finalId]?.displayName.orEmpty()
        }
        val rows = segments.mapIndexed { index, segment ->
            val displayName = if (segment.roleType == SpeechRoleType.Narrator) {
                "旁白"
            } else {
                displayNameOf(segment.characterId).ifBlank { segment.surfaceName.ifBlank { "未识别" } }
            }
            RaScriptLineRow(
                segmentId = segment.id,
                absIndex = index,
                paragraphIndex = segment.paragraphIndex,
                speaker = displayName,
                characterId = segment.characterId,
                text = segment.text,
                emotion = directionLines[segment.id]?.emotionTag.orEmpty(),
            )
        }
        _uiState.update { it.copy(lines = rows) }
    }

    /** 保存说话人修改；只处理实际改动的行。返回保存数量。 */
    suspend fun savePending(
        pending: Map<Int, String>,
        pendingIds: Map<Int, String>,
    ): Int {
        val state = _uiState.value
        val ch = state.selectedChapter ?: return 0
        if (pending.isEmpty() || state.currentBookKey.isBlank()) return 0
        var saved = 0
        pending.forEach { (lineIndex, speakerName) ->
            val line = state.lines.getOrNull(lineIndex) ?: return@forEach
            val name = speakerName.trim()
            if (name.isBlank()) return@forEach
            val isNarrator = name == "旁白"
            val newCharacterId = if (isNarrator) null else pendingIds[lineIndex]?.takeIf { it.isNotBlank() }
            if (!isNarrator && newCharacterId.isNullOrBlank()) return@forEach
            if (name == line.speaker && newCharacterId == line.characterId) return@forEach
            if (isNarrator) {
                raBuild.markSegmentNarrator(line.segmentId)
                raBuild.saveOverride(
                    bookKey = state.currentBookKey,
                    chapterIndex = ch,
                    paragraphIndex = line.paragraphIndex,
                    textHash = MD5Utils.md5Encode16(line.text),
                    characterId = null,
                    narrator = true,
                )
                saved++
            } else {
                raBuild.updateSegmentSpeaker(line.segmentId, newCharacterId, name)
                raBuild.saveOverride(
                    bookKey = state.currentBookKey,
                    chapterIndex = ch,
                    paragraphIndex = line.paragraphIndex,
                    textHash = MD5Utils.md5Encode16(line.text),
                    characterId = newCharacterId,
                    narrator = false,
                )
                saved++
            }
        }
        raBuild.getBuild(state.currentBookKey, state.currentSourceKey, ch)?.analysisId
            ?.let { raDirection.markPlaybackStale(it) }
        if (saved > 0) reloadLines()
        return saved
    }

    suspend fun deleteChapters(target: Int, rollback: Boolean) {
        val state = _uiState.value
        val set = if (rollback) state.chapters.filter { it >= target }.toSet() else setOf(target)
        maintenance.deleteChapters(
            state.currentBookKey,
            state.currentSourceKey,
            state.currentBookName,
            set,
            rollback,
        )
        _uiState.update { it.copy(selectedChapter = null) }
        load()
    }

    suspend fun deleteBook(bookKey: String) {
        val name = _uiState.value.books.firstOrNull { it.bookKey == bookKey }?.name.orEmpty()
        maintenance.deleteBook(bookKey, name)
        catalog.setCurrentBookKey("")
        _uiState.update { it.copy(selectedChapter = null) }
        load()
    }

    suspend fun reanalyze(chapter: Int) {
        val url = externalBookUrl ?: _uiState.value.currentBookUrl
        if (url.isBlank()) {
            _effects.tryEmit("无法定位该书数据（先朗读一次以建立章节缓存）")
            return
        }
        runCatching {
            val scheduler: AnalysisSchedulerV3 = GlobalContext.get().get()
            scheduler.enqueueChapter(url, chapter, force = true)
        }.onFailure { AppLog.put("重析入队失败: ${it.localizedMessage}", it) }
        _effects.tryEmit("已提交重新分析（${chLabel(chapter)}）")
    }

    suspend fun setVoice(characterId: String, voice: String) {
        val entryId = runCatching {
            voicePool.loadActivePoolTagEntries()[voice]?.first.orEmpty()
        }.getOrDefault("")
        characters.setDefaultVoice(characterId, voice, entryId)
        _uiState.update { it.copy(records = characters.characters(it.currentBookKey)) }
        _effects.tryEmit("声线已更新：$voice")
    }

    /** 新增人物并返回 (id, 最终主名)；失败返回 null。 */
    suspend fun createCharacter(
        name: String,
        roleType: String,
        gender: String,
        voice: String,
        chapter: Int,
    ): Pair<String, String>? {
        val state = _uiState.value
        val finalName = if (roleType == "路人" && !name.contains("【第")) {
            "$name【第${chapter + 1}章】"
        } else {
            name
        }
        val created = characters.ensureCharacter(
            bookKey = state.currentBookKey,
            displayName = finalName,
            roleType = roleType,
            gender = gender,
            ageBand = when {
                roleType == "特殊" -> "系统"
                gender == "女" -> "女青年"
                else -> "男青年"
            },
            chapterIndex = chapter,
            aliases = emptyList(),
            status = "ACTIVE",
            aliasSource = "user",
            attributeSource = "user",
        )
        if (voice.isNotBlank()) {
            val entryId = runCatching {
                voicePool.loadActivePoolTagEntries()[voice]?.first.orEmpty()
            }.getOrDefault("")
            characters.setDefaultVoice(created.id, voice, entryId)
        }
        _uiState.update { it.copy(records = characters.characters(it.currentBookKey)) }
        _effects.tryEmit("已新增人物：$finalName")
        return created.id to finalName
    }

    suspend fun exportBooks(names: List<String>, uri: Uri): Int = withContext(Dispatchers.IO) {
        runCatching {
            app.contentResolver.openOutputStream(uri)?.use { out ->
                maintenance.exportBooksJson(names, out)
            } ?: -1
        }.getOrDefault(-1)
    }

    suspend fun importBooks(uri: Uri): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        runCatching {
            app.contentResolver.openInputStream(uri)?.use { maintenance.importBooksJson(it) }
                ?: (false to "读取文件失败")
        }.getOrElse { false to "导入失败：${it.localizedMessage ?: it.javaClass.simpleName}" }
    }
}
