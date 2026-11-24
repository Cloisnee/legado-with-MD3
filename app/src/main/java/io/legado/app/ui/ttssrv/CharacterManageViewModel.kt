package io.legado.app.ui.ttssrv

import android.app.Application
import android.content.Context
import android.media.MediaPlayer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.ReadAloudCatalogRepository
import io.legado.app.data.repository.ReadAloudVoicePoolRepository
import io.legado.app.data.repository.TtsServerCenterRepository
import io.legado.app.data.repository.WordLibraryRepository
import io.legado.app.domain.gateway.RaCharacterGateway
import io.legado.app.domain.model.readaloud.v4.RaBookSummary
import io.legado.app.domain.model.readaloud.v4.RaCharacterView
import io.legado.app.utils.AliasTokens
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

data class CharacterManageUiState(
    val books: List<RaBookSummary> = emptyList(),
    val currentBookKey: String = "",
    val records: List<RaCharacterView> = emptyList(),
    val roleFilter: String = "全部",
    val loading: Boolean = false,
) {
    val currentBookName: String
        get() = books.firstOrNull { it.bookKey == currentBookKey }?.name ?: "默认"
}

/** UI 已算好的终态（主名后缀、类型、年龄），ViewModel 只负责写库。 */
data class CharacterEditInput(
    val characterId: String,
    val name: String,
    val aliases: String,
    val roleType: String,
    val gender: String,
    val ageBand: String,
    val voice: String,
)

class CharacterManageViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(CharacterManageUiState())
    val uiState = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val effects = _effects.asSharedFlow()

    private val catalog by lazy { GlobalContext.get().get<ReadAloudCatalogRepository>() }
    private val characters by lazy { GlobalContext.get().get<RaCharacterGateway>() }
    private val wordLibrary by lazy { GlobalContext.get().get<WordLibraryRepository>() }
    private val voicePool by lazy { GlobalContext.get().get<ReadAloudVoicePoolRepository>() }
    private val app by lazy { GlobalContext.get().get<Context>() as Application }
    private val centerRepo by lazy { TtsServerCenterRepository(app) }
    private val player by lazy { MediaPlayer() }

    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true) }
            val list = catalog.booksWithData()
            var key = catalog.currentBookKey()
            if (key.isBlank() || list.none { it.bookKey == key }) {
                key = list.firstOrNull()?.bookKey.orEmpty()
            }
            if (key.isNotBlank() && catalog.currentBookKey() != key) {
                catalog.setCurrentBookKey(key)
            }
            val records = if (key.isBlank()) emptyList() else characters.characters(key)
            _uiState.update {
                it.copy(
                    books = list,
                    currentBookKey = key,
                    records = records,
                    roleFilter = catalog.characterFilter(),
                    loading = false,
                )
            }
        }
    }

    fun selectBook(bookKey: String) {
        if (bookKey == _uiState.value.currentBookKey) return
        viewModelScope.launch {
            catalog.setCurrentBookKey(bookKey)
            load()
        }
    }

    fun setRoleFilter(filter: String) {
        viewModelScope.launch {
            catalog.setCharacterFilter(filter)
            _uiState.update { it.copy(roleFilter = filter) }
        }
    }

    fun saveEdit(input: CharacterEditInput) {
        val target = _uiState.value.records.firstOrNull { it.id == input.characterId } ?: return
        viewModelScope.launch {
            runCatching {
                if (target.name != input.name) {
                    characters.updateDisplayName(target.id, input.name)
                }
                characters.updateAttributes(target.id, input.roleType, input.gender, input.ageBand)
                characters.replaceAliases(
                    bookKey = _uiState.value.currentBookKey,
                    characterId = target.id,
                    names = AliasTokens.of(input.aliases).filter { it != input.name },
                    chapterIndex = target.lastAppearanceChapter.coerceAtLeast(0),
                    source = "user",
                )
                if (input.voice != target.voice) {
                    val entryId = runCatching {
                        voicePool.loadActivePoolTagEntries()[input.voice]?.first.orEmpty()
                    }.getOrDefault("")
                    characters.setDefaultVoice(target.id, input.voice, entryId)
                }
            }.onSuccess {
                _effects.tryEmit("已保存角色信息（剧本文本未改写）")
                load()
            }.onFailure {
                _effects.tryEmit("保存失败：${it.localizedMessage ?: "未知错误"}")
            }
        }
    }

    fun deleteCharacters(ids: List<String>) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            runCatching { characters.deleteCharacters(ids) }
                .onSuccess {
                    _effects.tryEmit("已删除 ${ids.size} 个角色")
                    load()
                }
                .onFailure { _effects.tryEmit("删除失败：${it.localizedMessage ?: "未知错误"}") }
        }
    }

    fun mergeCharacters(followerIds: List<String>, targetId: String) {
        if (followerIds.isEmpty()) {
            _effects.tryEmit("请标记至少一个要合并的角色")
            return
        }
        viewModelScope.launch {
            runCatching {
                followerIds.forEach { follower ->
                    characters.mergeCharacters(follower, targetId, "用户手动合并")
                }
            }.onSuccess {
                _effects.tryEmit("角色合并成功（台词按新人物显示，文本未改写）")
                load()
            }.onFailure { _effects.tryEmit("合并失败：${it.localizedMessage ?: "未知错误"}") }
        }
    }

    fun releaseAlias(
        parentId: String,
        alias: String,
        selectedVoice: String,
        gender: String,
        roleType: String,
        ageBand: String,
    ) {
        val parent = _uiState.value.records.firstOrNull { it.id == parentId } ?: return
        viewModelScope.launch {
            runCatching {
                characters.replaceAliases(
                    bookKey = _uiState.value.currentBookKey,
                    characterId = parent.id,
                    names = parent.aliasList().filterNot { it == alias },
                    chapterIndex = parent.lastAppearanceChapter.coerceAtLeast(0),
                    source = "user",
                )
                val voicePrefix = voicePool.voiceAgePrefix(selectedVoice)
                val age = if (voicePrefix.isNotEmpty() && !voicePrefix.contains("特殊") &&
                    !voicePrefix.contains("路人")
                ) {
                    voicePrefix
                } else {
                    if (roleType == "特殊") "系统" else ageBand
                }
                val created = characters.ensureCharacter(
                    bookKey = _uiState.value.currentBookKey,
                    displayName = alias,
                    roleType = "核心",
                    gender = gender,
                    ageBand = age,
                    chapterIndex = parent.lastAppearanceChapter.coerceAtLeast(0),
                    aliases = emptyList(),
                    status = "ACTIVE",
                    aliasSource = "user",
                    attributeSource = "user",
                )
                val entryId = runCatching {
                    voicePool.loadActivePoolTagEntries()[selectedVoice]?.first.orEmpty()
                }.getOrDefault("")
                characters.setDefaultVoice(created.id, selectedVoice, entryId)
            }.onSuccess {
                _effects.tryEmit("已释放并固定：$alias → $selectedVoice")
                load()
            }.onFailure { _effects.tryEmit("释放失败：${it.localizedMessage ?: "未知错误"}") }
        }
    }

    fun setVoice(characterId: String, voice: String) {
        viewModelScope.launch {
            runCatching {
                val entryId = runCatching {
                    voicePool.loadActivePoolTagEntries()[voice]?.first.orEmpty()
                }.getOrDefault("")
                characters.setDefaultVoice(characterId, voice, entryId)
            }.onSuccess {
                _effects.tryEmit("声线已更新：$voice")
                load()
            }.onFailure { _effects.tryEmit("声线更新失败：${it.localizedMessage ?: "未知错误"}") }
        }
    }

    fun appendBareWords(words: List<String>) =
        appendWordLibrary(WordLibraryRepository.BARE_WORDS_FILE, words)

    fun appendSpecialWords(words: List<String>) =
        appendWordLibrary(WordLibraryRepository.SPECIAL_WORDS_FILE, words)

    fun appendWordLibrary(file: String, words: List<String>) {
        viewModelScope.launch {
            runCatching { wordLibrary.append(file, words) }
                .onSuccess { count -> _effects.tryEmit("已加入词库（新增 $count 个）") }
                .onFailure { _effects.tryEmit("入库失败：${it.localizedMessage ?: "未知错误"}") }
        }
    }

    fun audition(voiceTag: String, text: String) {
        if (voiceTag.isBlank()) {
            _effects.tryEmit("该角色未设置声线")
            return
        }
        viewModelScope.launch {
            val active = runCatching { voicePool.loadActiveVoiceBanks().toSet() }.getOrDefault(emptySet())
            val groups = runCatching { centerRepo.loadGroups() }.getOrDefault(emptyList())
            val entry = groups
                .filter { it.name in active }
                .flatMap { it.entries }
                .firstOrNull { it.tag == voiceTag }
            if (entry == null) {
                _effects.tryEmit(
                    if (active.isEmpty()) {
                        "未选择声线池：请到 我的→朗读→引擎与音色→配置列表 选中声线池后再试听"
                    } else {
                        "当前已选声线池中没有标签「$voiceTag」"
                    },
                )
                return@launch
            }
            val out = runCatching {
                if (entry.id != 0L) {
                    centerRepo.auditionByEntry(entry.groupId, entry.id, text)
                } else {
                    centerRepo.auditionDetailed(entry.tagRuleId, voiceTag, text)
                }
            }.getOrNull()
            val path = out?.path
            if (out?.ok == true && !path.isNullOrBlank()) {
                runCatching {
                    player.reset()
                    player.setDataSource(path)
                    player.prepare()
                    player.start()
                }.onSuccess {
                    _effects.tryEmit("播放中")
                }.onFailure {
                    _effects.tryEmit("播放失败：${it.localizedMessage}")
                }
            } else {
                _effects.tryEmit("试听失败：${out?.message?.ifBlank { "合成失败" } ?: "合成失败"}")
            }
        }
    }

    override fun onCleared() {
        runCatching { player.release() }
        super.onCleared()
    }
}
