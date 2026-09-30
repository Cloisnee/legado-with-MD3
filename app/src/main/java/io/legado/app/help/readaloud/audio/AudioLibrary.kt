package io.legado.app.help.readaloud.audio

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * B33.2 · 音频素材库（registry 索引 + 解析链）。
 *
 * - 索引文件：`<数据根>/data/audio_lib/registry.json`（唯一索引，随库目录走，重启不丢）；
 * - 扫描：遍历 audio_lib 下音频文件，分类取目录（sfx/拟音 → 拟音；sfx/环境声 → 环境声；bgm → BGM）；
 * - 解析链（引擎 / 试听共用）：精确名 → 别名 → 包含匹配；未命中回退目录直扫（并自动补登）；
 * - 合成产物 / 示例素材落库时即时登记（[notifyFileAdded]），保证"落库即可用"；
 * - 预留（B33.2b）：zip 直读条目（zipRel/entry）与远程下载源。
 */
object AudioLibrary {

    const val SOURCE_LOCAL = "local"
    const val SOURCE_GENERATED = "generated"

    private val AUDIO_EXTS = setOf("mp3", "m4a", "wav", "ogg", "flac", "aac")

    data class AudioAsset(
        val name: String,
        val relPath: String,
        val category: String,
        val source: String = SOURCE_LOCAL,
        val aliases: List<String> = emptyList(),
        val tags: List<String> = emptyList(),
        val size: Long = 0L,
        val mtime: Long = 0L,
        val durationMs: Long = 0L,
        val loop: Boolean = false,
        val gain: Float = 1f,
        val hash: String = "",
        /** 预留（B33.2b zip 直读）：zip 文件相对路径 + 条目名 */
        val zipRel: String = "",
        val entry: String = "",
    ) {
        val id: String get() = if (zipRel.isBlank()) relPath else "$zipRel#$entry"
    }

    data class ScanResult(
        val total: Int,
        val added: Int,
        val removed: Int,
    )

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val saveLock = Mutex()

    /** 内存索引快照（null = 未加载；加载后整 Map 替换，保证跨线程读安全） */
    @Volatile
    private var index: Map<String, AudioAsset>? = null

    @Volatile
    private var loaded = false

    @Volatile
    private var loading = false

    // ------------------------------------------------------------ 查询

    suspend fun assets(context: Context): List<AudioAsset> {
        ensureLoaded(context.applicationContext)
        return index?.values?.toList().orEmpty()
    }

    fun fileOf(context: Context, asset: AudioAsset): File =
        File(TmDemoAssets.libRoot(context), asset.relPath)

    /** 引擎热路径：同步解析为文件（未加载时回退目录直扫） */
    fun resolveFile(context: Context, keyword: String): File? {
        if (index == null) warmUp(context)
        val snap = index
        if (snap != null) {
            val hit = lookup(snap.values, keyword)
            if (hit != null && hit.zipRel.isBlank()) {
                val f = fileOf(context, hit)
                if (f.isFile) return f
            }
        }
        // 兜底：目录直扫（覆盖尚未入册的新文件；命中后自动补登）
        val f = TmDemoAssets.findFile(context, keyword) ?: return null
        noteDiscovered(context, f)
        return f
    }

    /** 预热：后台加载一次（不阻塞播放链路） */
    fun warmUp(context: Context) {
        if (loaded || loading) return
        loading = true
        val appCtx = context.applicationContext
        io.launch {
            runCatching { ensureLoaded(appCtx) }
            loading = false
        }
    }

    suspend fun ensureLoaded(context: Context) {
        if (loaded) return
        lock.withLock {
            if (loaded) return
            withContext(Dispatchers.IO) {
                val f = registryFile(context)
                val parsed = if (f.isFile) {
                    parseRegistry(f.readText().removePrefix("\uFEFF"))
                } else {
                    null
                }
                index = parsed ?: scanInternal(context)
                if (parsed == null) persist(context, index.orEmpty().values)
            }
            loaded = true
        }
    }

    /** 重新扫描（库管理页）：zip 登记条目原样保留（B33.2b 起维护） */
    suspend fun rescan(context: Context): ScanResult = withContext(Dispatchers.IO) {
        lock.withLock {
            val old = index ?: emptyMap()
            val fresh = LinkedHashMap(scanInternal(context))
            old.values.filter { it.zipRel.isNotBlank() }
                .filter { File(TmDemoAssets.libRoot(context), it.zipRel).isFile }
                .forEach { z -> fresh[z.id] = z }
            index = fresh
            persist(context, fresh.values)
            loaded = true
            ScanResult(
                total = fresh.size,
                added = fresh.keys.count { it !in old },
                removed = old.keys.count { it !in fresh },
            )
        }
    }

    /** 合成产物 / 示例素材落库登记（未加载时跳过，等首扫接管） */
    fun notifyFileAdded(context: Context, file: File, source: String = SOURCE_LOCAL) {
        val cur = index ?: return
        if (!file.isFile) return
        val root = TmDemoAssets.libRoot(context)
        val rel = runCatching { file.relativeTo(root).path.replace(File.separatorChar, '/') }
            .getOrNull()?.takeIf { it.isNotBlank() && !it.startsWith("..") } ?: return
        val asset = AudioAsset(
            name = file.nameWithoutExtension,
            relPath = rel,
            category = categoryOf(rel),
            source = source,
            size = file.length(),
            mtime = file.lastModified(),
        )
        val existing = cur[asset.id]
        if (existing != null && existing.size == asset.size &&
            existing.mtime == asset.mtime && existing.source == asset.source
        ) {
            return
        }
        index = cur + (asset.id to asset)
        scheduleSave(context.applicationContext)
    }

    /** 删除素材（文件 + sidecar + 索引登记） */
    suspend fun removeAsset(context: Context, asset: AudioAsset): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            val cur = index ?: return@withLock false
            var ok = asset.zipRel.isNotBlank() // zip 条目（2b 前）仅移除登记
            if (asset.zipRel.isBlank()) {
                val f = fileOf(context, asset)
                if (f.isFile) ok = f.delete()
                runCatching {
                    File(f.parentFile, f.nameWithoutExtension + ".json")
                        .takeIf { it.isFile }?.delete()
                }
            }
            if (ok) {
                val next = cur - asset.id
                index = next
                persist(context, next.values)
            }
            ok
        }
    }

    // ------------------------------------------------------------ 纯函数（可单测）

    internal fun lookup(assets: Collection<AudioAsset>, keyword: String): AudioAsset? {
        val kw = keyword.trim()
        if (kw.isEmpty()) return null
        assets.firstOrNull { it.name == kw }?.let { return it }
        assets.firstOrNull { a -> a.aliases.any { it.equals(kw, ignoreCase = true) } }?.let { return it }
        return assets.firstOrNull { it.name.contains(kw, ignoreCase = true) }
    }

    internal fun categoryOf(relPath: String): String {
        val parts = relPath.split('/')
        return when {
            parts.isEmpty() -> "其他"
            parts[0] == "bgm" -> "BGM"
            parts[0] == "sfx" && parts.size >= 3 -> parts[1]
            parts[0] == "sfx" -> "音效"
            else -> "其他"
        }
    }

    internal fun parseRegistry(text: String): Map<String, AudioAsset>? = runCatching {
        val root = JSONObject(text)
        val arr = root.optJSONArray("assets") ?: JSONArray()
        val map = LinkedHashMap<String, AudioAsset>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val rel = o.optString("relPath")
            if (rel.isBlank()) continue
            val asset = AudioAsset(
                name = o.optString("name").ifBlank { rel.substringAfterLast('/') },
                relPath = rel,
                category = o.optString("category").ifBlank { categoryOf(rel) },
                source = o.optString("source").ifBlank { SOURCE_LOCAL },
                aliases = o.optJSONArray("aliases").toStringList(),
                tags = o.optJSONArray("tags").toStringList(),
                size = o.optLong("size", 0L),
                mtime = o.optLong("mtime", 0L),
                durationMs = o.optLong("durationMs", 0L),
                loop = o.optBoolean("loop", false),
                gain = o.optDouble("gain", 1.0).toFloat(),
                hash = o.optString("hash"),
                zipRel = o.optString("zipRel"),
                entry = o.optString("entry"),
            )
            map[asset.id] = asset
        }
        map
    }.getOrNull()

    internal fun serializeRegistry(assets: Collection<AudioAsset>): String {
        val arr = JSONArray()
        assets.forEach { a ->
            arr.put(JSONObject().apply {
                put("name", a.name)
                put("relPath", a.relPath)
                put("category", a.category)
                put("source", a.source)
                if (a.aliases.isNotEmpty()) put("aliases", JSONArray(a.aliases))
                if (a.tags.isNotEmpty()) put("tags", JSONArray(a.tags))
                put("size", a.size)
                put("mtime", a.mtime)
                if (a.durationMs > 0) put("durationMs", a.durationMs)
                if (a.loop) put("loop", true)
                if (a.gain != 1f) put("gain", a.gain.toDouble())
                if (a.hash.isNotBlank()) put("hash", a.hash)
                if (a.zipRel.isNotBlank()) put("zipRel", a.zipRel)
                if (a.entry.isNotBlank()) put("entry", a.entry)
            })
        }
        return JSONObject().apply {
            put("version", 1)
            put("savedAt", System.currentTimeMillis())
            put("assets", arr)
        }.toString()
    }

    // ------------------------------------------------------------ 内部

    private fun registryFile(context: Context): File =
        File(TmDemoAssets.libRoot(context), "registry.json")

    private fun scanInternal(context: Context): Map<String, AudioAsset> {
        val root = TmDemoAssets.libRoot(context)
        root.mkdirs()
        val map = LinkedHashMap<String, AudioAsset>()
        walkAudio(root).forEach { f ->
            val rel = f.relativeTo(root).path.replace(File.separatorChar, '/')
            val asset = AudioAsset(
                name = f.nameWithoutExtension,
                relPath = rel,
                category = categoryOf(rel),
                source = if (File(f.parentFile, f.nameWithoutExtension + ".json").isFile) {
                    SOURCE_GENERATED
                } else {
                    SOURCE_LOCAL
                },
                size = f.length(),
                mtime = f.lastModified(),
            )
            map[asset.id] = asset
        }
        return map
    }

    private fun walkAudio(dir: File, depth: Int = 0): Sequence<File> {
        if (depth > 5) return emptySequence()
        return dir.listFiles().orEmpty().asSequence().flatMap { f ->
            when {
                f.isDirectory -> walkAudio(f, depth + 1)
                f.isFile && f.extension.lowercase() in AUDIO_EXTS -> sequenceOf(f)
                else -> emptySequence()
            }
        }
    }

    private fun noteDiscovered(context: Context, f: File) {
        val cur = index ?: return
        val root = TmDemoAssets.libRoot(context)
        val rel = runCatching { f.relativeTo(root).path.replace(File.separatorChar, '/') }
            .getOrNull()?.takeIf { it.isNotBlank() && !it.startsWith("..") } ?: return
        if (cur.values.any { it.relPath == rel }) return
        notifyFileAdded(
            context,
            f,
            if (File(f.parentFile, f.nameWithoutExtension + ".json").isFile) {
                SOURCE_GENERATED
            } else {
                SOURCE_LOCAL
            },
        )
    }

    private fun scheduleSave(context: Context) {
        val appCtx = context.applicationContext
        io.launch {
            saveLock.withLock {
                val snap = index ?: return@withLock
                persist(appCtx, snap.values)
            }
        }
    }

    private fun persist(context: Context, assets: Collection<AudioAsset>) {
        runCatching {
            val f = registryFile(context)
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(serializeRegistry(assets))
            if (!tmp.renameTo(f)) {
                tmp.copyTo(f, overwrite = true)
                tmp.delete()
            }
        }
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
