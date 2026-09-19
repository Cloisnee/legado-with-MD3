package io.legado.app.ui.book.readaloud.cache

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.constant.AppLog
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import splitties.init.appCtx
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TtsCacheViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(TtsCacheUiState())
    val uiState = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<TtsCacheEffect>(extraBufferCapacity = 16)
    val effects = _effects.asSharedFlow()

    private val ttsCacheDir: File?
        get() {
            val baseDir = appCtx.externalCacheDir ?: appCtx.cacheDir
            return File(baseDir, "httpTTS").takeIf { it.exists() }
        }

    private val textIndexFile: File?
        get() = ttsCacheDir?.let { File(it, "tts_cache_index.json") }

    init {
        loadCache()
        // 实时日志：AppLog 每次写入都会推送新快照（升序），日志页边播边刷
        viewModelScope.launch {
            AppLog.logsFlow.collect { entries ->
                val mapped = entries.map { entry ->
                    TtsLogEntryUi(
                        id = entry.id,
                        timestamp = entry.timestamp,
                        message = entry.message,
                        hasError = entry.throwable != null,
                        fullContent = entry.throwable
                            ?.let { t -> "${entry.message}\n${t.stackTraceToString()}" }
                            ?: entry.message,
                        category = entry.category,
                    )
                }
                _uiState.update { it.copy(logs = mapped.toImmutableList()) }
            }
        }
    }

    fun onIntent(intent: TtsCacheIntent) {
        when (intent) {
            TtsCacheIntent.LoadCache -> loadCache()
            is TtsCacheIntent.SelectTab -> _uiState.update {
                it.copy(
                    activeTab = intent.tab,
                    selectedIds = persistentSetOf(),
                    expandedIds = persistentSetOf(),
                )
            }

            is TtsCacheIntent.SetSearchMode -> _uiState.update { it.copy(isSearch = intent.isSearch) }
            is TtsCacheIntent.SetSearchKey -> _uiState.update { it.copy(searchKey = intent.key) }
            is TtsCacheIntent.ToggleSelection -> _uiState.update { state ->
                state.copy(
                    selectedIds = (if (intent.id in state.selectedIds) {
                        state.selectedIds - intent.id
                    } else {
                        state.selectedIds + intent.id
                    }).toImmutableSet()
                )
            }

            is TtsCacheIntent.SetSelection ->
                _uiState.update { it.copy(selectedIds = intent.ids.toImmutableSet()) }

            is TtsCacheIntent.ToggleExpand -> _uiState.update { state ->
                state.copy(
                    expandedIds = (if (intent.id in state.expandedIds) {
                        state.expandedIds - intent.id
                    } else {
                        state.expandedIds + intent.id
                    }).toImmutableSet()
                )
            }

            is TtsCacheIntent.DeleteFile -> deleteFile(intent.name)
            TtsCacheIntent.ClearAll -> clearAll()
            TtsCacheIntent.ShowClearAllDialog ->
                _uiState.update { it.copy(activeDialog = TtsCacheDialog.ClearAll) }

            TtsCacheIntent.ShowClearLogsDialog ->
                _uiState.update { it.copy(activeDialog = TtsCacheDialog.ClearLogs) }

            TtsCacheIntent.ClearLogs -> {
                AppLog.clear()
                _effects.tryEmit(TtsCacheEffect.ShowToast("朗读日志已清空"))
            }

            TtsCacheIntent.DismissDialog ->
                _uiState.update { it.copy(activeDialog = null) }

            is TtsCacheIntent.ShowFileDetail -> _uiState.update {
                it.copy(
                    detailTitle = intent.name,
                    detailContent = buildString {
                        append("分片文字:\n${intent.text}\n\n")
                        append("文件大小: ${formatSize(intent.sizeBytes)}\n")
                        append("创建时间: ${detailDateFormat.format(Date(intent.lastModified))}\n")
                        append("文件名: ${intent.name}.mp3")
                    },
                    showDetail = true,
                )
            }

            TtsCacheIntent.DismissDetail -> _uiState.update { it.copy(showDetail = false) }
        }
    }

    private fun loadCache() {
        viewModelScope.launch(Dispatchers.IO) {
            val dir = ttsCacheDir
            if (dir == null) {
                _uiState.update {
                    it.copy(loading = false, files = persistentListOf(), totalSizeBytes = 0)
                }
                return@launch
            }
            val index = loadTextIndex()
            val files = dir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".mp3") }
                ?.sortedByDescending { it.lastModified() }
                ?.map { file ->
                    val baseName = file.nameWithoutExtension
                    val text = index[baseName].orEmpty()
                    TtsCacheFileUi(
                        name = baseName,
                        text = text,
                        sizeBytes = file.length(),
                        lastModified = file.lastModified(),
                    )
                } ?: emptyList()
            val totalSize = files.sumOf { it.sizeBytes }
            _uiState.update {
                it.copy(
                    loading = false,
                    files = files.toImmutableList(),
                    totalSizeBytes = totalSize,
                )
            }
        }
    }

    private fun loadTextIndex(): Map<String, String> {
        return try {
            val file = textIndexFile ?: return emptyMap()
            if (!file.exists()) return emptyMap()
            val json = file.readText()
            val map = mutableMapOf<String, String>()
            // Simple JSON parsing: {"filename":"text",...}
            val regex = Regex("\"([^\"]+)\":\"([^\"]*)\"")
            regex.findAll(json).forEach { match ->
                map[match.groupValues[1]] = match.groupValues[2]
                    .replace("\\n", "\n")
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
            }
            map
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun deleteFile(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val dir = ttsCacheDir ?: return@launch
            val file = File(dir, "$name.mp3")
            if (file.exists() && file.delete()) {
                removeFromTextIndex(name)
                _effects.tryEmit(TtsCacheEffect.ShowToast("已删除"))
                loadCache()
            }
        }
    }

    private fun clearAll() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(activeDialog = null) }
            val dir = ttsCacheDir
            if (dir != null && dir.exists()) {
                dir.listFiles()?.forEach { it.delete() }
            }
            textIndexFile?.delete()
            _effects.tryEmit(TtsCacheEffect.ShowToast("缓存已清除"))
            loadCache()
        }
    }

    private fun removeFromTextIndex(filename: String) {
        try {
            val file = textIndexFile ?: return
            if (!file.exists()) return
            val index = loadTextIndex().toMutableMap()
            index.remove(filename)
            writeTextIndex(index)
        } catch (_: Exception) {
        }
    }

    private fun writeTextIndex(index: Map<String, String>) {
        try {
            val file = textIndexFile ?: return
            val json = buildString {
                append("{")
                index.entries.forEachIndexed { i, (key, value) ->
                    if (i > 0) append(",")
                    val escaped = value
                        .replace("\\", "\\\\")
                        .replace("\"", "\\\"")
                        .replace("\n", "\\n")
                    append("\"$key\":\"$escaped\"")
                }
                append("}")
            }
            file.writeText(json)
        } catch (_: Exception) {
        }
    }

    companion object {
        private val detailDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

        fun formatSize(bytes: Long): String {
            return when {
                bytes < 1024 -> "$bytes B"
                bytes < 1024 * 1024 -> "${bytes / 1024} KB"
                else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
            }
        }
    }
}

private fun <T> List<T>.toImmutableList(): ImmutableList<T> =
    persistentListOf<T>().builder().apply { addAll(this@toImmutableList) }.build()

private fun <T> Set<T>.toImmutableSet(): ImmutableSet<T> =
    persistentSetOf<T>().builder().apply { addAll(this@toImmutableSet) }.build()
