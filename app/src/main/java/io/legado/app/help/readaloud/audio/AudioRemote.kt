package io.legado.app.help.readaloud.audio

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * P1.2 · 远程素材库（单链版）：数据源 = 词网 [AudioNetStore]（`声效/index/`）。
 *
 * 旧「音效与背景声」pack 体系（CNB 缓存 + 钉 SHA 旧索引）全部退役；
 * 浏览 / 搜索 / 下载均走新声效库索引——库管理「远程素材库」页即新版词网视图。
 */
object AudioRemoteCatalog {

    data class RemotePack(
        val id: String = "core",
        val label: String = "声效库",
        val indexUrl: String = "",
        val soundCount: Int = 0,
        val defaultEnabled: Boolean = true,
        val rulesUrl: String = "",
    )

    data class RemoteSound(
        val soundId: String = "",
        val name: String = "",
        val aliases: List<String> = emptyList(),
        val category: String = "",
        val categoryName: String = "",
        val subType: String = "",
        val pack: String = "core",
        val url: String = "",
        val assetPath: String = "",
        val sha256: String = "",
        val tags: List<String> = emptyList(),
    )

    /** 清单：单一「声效库」（数量=词网资产数） */
    suspend fun manifest(context: Context, forceRefresh: Boolean = false): List<RemotePack> {
        AudioNetStore.ensureLoaded(context)
        return listOf(RemotePack(soundCount = AudioNetStore.assetCount))
    }

    /** 索引：词网快照 → 远程条目（onStatus 兼容旧签名） */
    suspend fun sounds(
        context: Context,
        pack: RemotePack,
        forceRefresh: Boolean = false,
        onStatus: (String) -> Unit = {},
    ): List<RemoteSound> = withContext(Dispatchers.IO) {
        AudioNetStore.ensureLoaded(context)
        AudioNetStore.snapshot().map { a ->
            RemoteSound(
                soundId = a.id,
                name = a.name,
                aliases = a.aliases,
                category = a.lane,
                categoryName = laneNameOf(a.lane),
                pack = pack.id,
                url = AudioNetStore.urlOf(a.file),
                assetPath = a.file,
            )
        }
    }

    /** 下载一条远程音效并落库（落库名=规范名） */
    suspend fun download(
        context: Context,
        sound: RemoteSound,
        onProgress: (Long) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        val asset = AudioNetStore.NetAsset(
            id = sound.soundId.ifBlank { sound.name },
            name = sound.name,
            lane = sound.category,
            file = sound.assetPath,
            adult = sound.category == "adult",
        )
        AudioNetStore.fetchAsset(context, asset, onProgress) ?: error("下载失败：${sound.name}")
    }

    /** 搜索：精确（名/别名）→ 前缀 → 包含，保持原始顺序去重 */
    internal fun search(
        list: List<RemoteSound>,
        query: String,
        limit: Int = Int.MAX_VALUE,
    ): List<RemoteSound> {
        val q = query.trim()
        if (q.isEmpty()) return list.take(limit)
        val exact = ArrayList<RemoteSound>()
        val prefix = ArrayList<RemoteSound>()
        val contain = ArrayList<RemoteSound>()
        for (s in list) {
            when {
                s.name == q || s.aliases.any { it == q } -> exact.add(s)
                s.name.startsWith(q) || s.aliases.any { it.startsWith(q) } -> prefix.add(s)
                s.name.contains(q, true) || s.aliases.any { it.contains(q, true) } -> contain.add(s)
            }
            if (exact.size + prefix.size + contain.size >= limit) break
        }
        return (exact + prefix + contain).take(limit)
    }

    internal fun laneNameOf(lane: String): String = when (lane.lowercase()) {
        "bgm" -> "BGM"
        "amb" -> "环境声"
        "adult" -> "ADULT"
        else -> "音效"
    }
}

/**
 * 远程下载队列（库管理「远程素材库」页用）：并入词网下载落库。
 */
object AudioRemoteDownloader {

    sealed interface State {
        data object Queued : State
        data class Running(val bytes: Long) : State
        data object Done : State
        data class Failed(val message: String) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<Pair<Context, AudioRemoteCatalog.RemoteSound>>(Channel.UNLIMITED)

    private val _states = MutableStateFlow<Map<String, State>>(emptyMap())
    val states: StateFlow<Map<String, State>> = _states

    init {
        scope.launch {
            for ((context, sound) in queue) {
                runCatching { process(context.applicationContext, sound) }
            }
        }
    }

    fun enqueue(context: Context, sound: AudioRemoteCatalog.RemoteSound) {
        val cur = _states.value[sound.soundId]
        if (cur is State.Queued || cur is State.Running || cur is State.Done) return
        _states.value = _states.value + (sound.soundId to State.Queued)
        queue.trySend(context.applicationContext to sound)
    }

    private suspend fun process(context: Context, sound: AudioRemoteCatalog.RemoteSound) {
        _states.value = _states.value + (sound.soundId to State.Running(0L))
        runCatching {
            AudioRemoteCatalog.download(context, sound) { bytes ->
                _states.value = _states.value + (sound.soundId to State.Running(bytes))
            }
        }.onSuccess {
            _states.value = _states.value + (sound.soundId to State.Done)
        }.onFailure { e ->
            _states.value = _states.value + (
                sound.soundId to State.Failed(e.localizedMessage ?: "下载失败")
                )
        }
    }
}