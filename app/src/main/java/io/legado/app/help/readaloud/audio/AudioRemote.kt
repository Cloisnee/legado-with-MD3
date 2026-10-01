package io.legado.app.help.readaloud.audio

import android.content.Context
import com.github.jing332.compat.fs.TtsDirProvider
import io.legado.app.help.http.await
import io.legado.app.help.http.okHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * B33.2b · 远程素材库（CNB 墨听/JRead 索引体系）。
 *
 * - 清单：`.../yinxiao/jread_audio_normalized/index.json`（packages → 各库索引）；
 * - 索引：`index_core.json`（3514）/ `index_horror_thriller_v1.json`（450）/ `index_adult_romance.json`（11，18+ 默认关）；
 * - 缓存：`_store/audio_remote/`（manifest + 各 pack 索引；网络失败回退旧缓存）；
 * - 下载：按条目 `url` 直链流式落库 → `audio_lib/<分类目录>/<中文名>.<ext>` + sidecar（libSource=remote）。
 */
object AudioRemoteCatalog {

    /** 清单来源：优先自家 fork（Cloisnee），失败回退上游 */
    private val MANIFEST_URLS = listOf(
        "https://cnb.cool/Cloisnee/yinpin/-/git/raw/master/yinxiao/jread_audio_normalized/index.json",
        "https://cnb.cool/applecabal/yinpin/-/git/raw/master/yinxiao/jread_audio_normalized/index.json",
    )

    data class RemotePack(
        val id: String = "",
        val label: String = "",
        val indexUrl: String = "",
        val soundCount: Int = 0,
        val defaultEnabled: Boolean = true,
    )

    data class RemoteSound(
        val soundId: String = "",
        val name: String = "",
        val aliases: List<String> = emptyList(),
        val category: String = "",
        val categoryName: String = "",
        val subType: String = "",
        val pack: String = "",
        val url: String = "",
        val assetPath: String = "",
        val sha256: String = "",
        val tags: List<String> = emptyList(),
    )

    private val http by lazy {
        okHttpClient.newBuilder()
            .callTimeout(300, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    private val loadLock = Mutex()
    private val indexLock = Mutex()

    @Volatile
    private var manifestCache: List<RemotePack>? = null

    private val indexCache = HashMap<String, List<RemoteSound>>()

    private fun remoteDir(context: Context): File =
        File(TtsDirProvider.baseDir(context), "_store/audio_remote")

    /** 远程目录（packages 清单）：优先内存 → 磁盘缓存 → 远端；forceRefresh 强制网络（失败回退缓存） */
    suspend fun manifest(context: Context, forceRefresh: Boolean = false): List<RemotePack> =
        withContext(Dispatchers.IO) {
            manifestCache?.takeIf { !forceRefresh }?.let { return@withContext it }
            loadLock.withLock {
                manifestCache?.takeIf { !forceRefresh }?.let { return@withLock it }
                val f = File(remoteDir(context), "manifest.json")
                val text = if (!forceRefresh && f.isFile) {
                    f.readText().removePrefix("\uFEFF")
                } else {
                    val fresh = runCatching { fetchManifestText() }.getOrElse { e ->
                        if (f.isFile) f.readText().removePrefix("\uFEFF") else throw e
                    }
                    runCatching {
                        f.parentFile?.mkdirs()
                        f.writeText(fresh)
                    }
                    fresh
                }
                val packs = parseManifest(text).orEmpty()
                manifestCache = packs
                packs
            }
        }

    /** 某个 pack 的音效索引：内存 → 磁盘缓存 → 远端下载；onStatus 用于 UI 提示 */
    suspend fun sounds(
        context: Context,
        pack: RemotePack,
        forceRefresh: Boolean = false,
        onStatus: (String) -> Unit = {},
    ): List<RemoteSound> = withContext(Dispatchers.IO) {
        indexLock.withLock {
            if (!forceRefresh) {
                indexCache[pack.id]?.let { return@withLock it }
            }
            val f = File(remoteDir(context), "index_${pack.id}.json")
            val text = if (!forceRefresh && f.isFile) {
                f.readText().removePrefix("\uFEFF")
            } else {
                onStatus(
                    if (f.isFile) "正在刷新索引…"
                    else "首次载入索引（约 ${pack.soundCount} 条，稍候）…"
                )
                val fresh = runCatching { fetchText(pack.indexUrl) }.getOrElse { e ->
                    if (f.isFile) f.readText().removePrefix("\uFEFF") else throw e
                }
                runCatching {
                    val tmp = File(f.parentFile, f.name + ".tmp")
                    tmp.writeText(fresh)
                    if (!tmp.renameTo(f)) {
                        tmp.copyTo(f, overwrite = true)
                        tmp.delete()
                    }
                }
                fresh
            }
            val list = parseIndex(text).orEmpty()
            indexCache[pack.id] = list
            list
        }
    }

    /** 下载一条远程音效并落库（已存在则直接复用并登记） */
    suspend fun download(
        context: Context,
        sound: RemoteSound,
        onProgress: (Long) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        val root = TmDemoAssets.libRoot(context)
        val name = fileNameOf(sound)
        val out = File(File(root, folderOf(sound)), name)
        if (out.isFile && out.length() > 0L) {
            AudioLibrary.notifyFileAdded(context, out, AudioLibrary.SOURCE_REMOTE)
            return@withContext out
        }
        out.parentFile?.mkdirs()
        val tmp = File(out.parentFile, "$name.part")
        val req = Request.Builder()
            .url(sound.url)
            .header("User-Agent", "legado-audio-remote")
            .build()
        val resp = http.newCall(req).await()
        require(resp.isSuccessful) { "HTTP ${resp.code}" }
        val body = resp.body ?: error("空响应")
        var downloaded = 0L
        var lastReport = 0L
        body.byteStream().use { input ->
            tmp.outputStream().use { output ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    output.write(buf, 0, n)
                    downloaded += n
                    if (downloaded - lastReport >= 256 * 1024) {
                        lastReport = downloaded
                        runCatching { onProgress(downloaded) }
                    }
                }
            }
        }
        if (downloaded <= 0L) {
            runCatching { tmp.delete() }
            error("下载为空")
        }
        if (!tmp.renameTo(out)) {
            tmp.copyTo(out, overwrite = true)
            tmp.delete()
        }
        runCatching {
            File(out.parentFile, out.nameWithoutExtension + ".json").writeText(
                JSONObject().apply {
                    put("libSource", AudioLibrary.SOURCE_REMOTE)
                    put("soundId", sound.soundId)
                    put("pack", sound.pack)
                    put("name", sound.name)
                    put("category", sound.category)
                    put("categoryName", sound.categoryName)
                    put("subType", sound.subType)
                    put("assetPath", sound.assetPath)
                    put("sha256", sound.sha256)
                    put("createdAt", System.currentTimeMillis())
                }.toString()
            )
        }
        AudioLibrary.notifyFileAdded(context, out, AudioLibrary.SOURCE_REMOTE)
        out
    }

    // ------------------------------------------------------------ 纯函数（可单测）

    internal fun parseManifest(text: String): List<RemotePack>? = runCatching {
        val root = JSONObject(text)
        val arr = root.optJSONArray("packages") ?: JSONArray()
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id")
                if (id.isBlank()) continue
                add(
                    RemotePack(
                        id = id,
                        label = o.optString("label").ifBlank { id },
                        indexUrl = o.optString("indexUrl"),
                        soundCount = o.optInt("soundCount", 0),
                        defaultEnabled = o.optBoolean("defaultEnabled", false),
                    )
                )
            }
        }
    }.getOrNull()

    internal fun parseIndex(text: String): List<RemoteSound>? = runCatching {
        val root = JSONObject(text)
        val arr = root.optJSONArray("sounds") ?: JSONArray()
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("soundId")
                if (id.isBlank()) continue
                val primary = o.optString("legacyName").ifBlank { o.optString("sourceOriginalName") }
                add(
                    RemoteSound(
                        soundId = id,
                        name = primary.ifBlank { id },
                        aliases = (
                            o.optJSONArray("aliases").toStringList() +
                                o.optJSONArray("legacyNames").toStringList()
                            ).distinct(),
                        category = o.optString("category"),
                        categoryName = o.optString("categoryName"),
                        subType = o.optString("subType"),
                        pack = o.optString("pack"),
                        url = o.optString("url"),
                        assetPath = o.optString("assetPath"),
                        sha256 = o.optString("sha256"),
                        tags = o.optJSONArray("tags").toStringList(),
                    )
                )
            }
        }
    }.getOrNull()

    private val FOLDER_BY_CATEGORY = mapOf(
        "scene" to "sfx/环境声",
        "emotion" to "sfx/主观声",
        "strong_sfx" to "sfx/硬音效",
        "micro_sfx" to "sfx/拟音",
        "medium_sfx" to "sfx/拟音",
        "transition" to "sfx/拟音",
    )

    private val FOLDER_BY_CATEGORY_NAME = mapOf(
        "环境声" to "sfx/环境声",
        "戏内声源" to "sfx/戏内声源",
        "主观恐惧" to "sfx/主观声",
        "人体反应" to "sfx/拟音",
        "恐怖器物" to "sfx/硬音效",
        "灵异鬼怪" to "sfx/主观声",
        "民俗心理" to "sfx/主观声",
        "事件链" to "sfx/拟音",
    )

    /** 落库目录：优先按包内中文分类名，其次按英文 category，兜底 拟音 */
    internal fun folderOf(sound: RemoteSound): String =
        FOLDER_BY_CATEGORY_NAME[sound.categoryName]
            ?: FOLDER_BY_CATEGORY[sound.category]
            ?: "sfx/拟音"

    /** 落库文件名：中文名纯净文件名 + 源扩展名（mp3/wav…） */
    internal fun fileNameOf(sound: RemoteSound): String {
        val base = sanitizeGeneratedName(sound.name.ifBlank { sound.soundId })
        val ext = sound.assetPath.substringAfterLast('.', "mp3").lowercase()
            .takeIf { it in setOf("mp3", "wav", "m4a", "ogg", "flac", "aac") } ?: "mp3"
        return "$base.$ext"
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

    // ------------------------------------------------------------ 内部

    private suspend fun fetchManifestText(): String {
        var last: Throwable? = null
        for (url in MANIFEST_URLS) {
            runCatching { return fetchText(url) }.onFailure { last = it }
        }
        throw last ?: IllegalStateException("远程清单不可用")
    }

    private suspend fun fetchText(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "legado-audio-remote")
            .build()
        val resp = http.newCall(req).await()
        require(resp.isSuccessful) { "HTTP ${resp.code}" }
        return resp.body?.string() ?: error("空响应")
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until length()) {
            val s = optString(i)
            if (s.isNotBlank()) out.add(s)
        }
        return out
    }
}

/** B33.2b · 远程下载队列（串行；状态供库页/远程页观察） */
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
