package io.legado.app.help.readaloud.audio

import android.content.Context
import android.net.Uri
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
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * B33.2 · 音频素材库（registry 索引 + 解析链 + 库管理）。
 *
 * - 索引文件（B33.2c 聚合）：`<数据根>/data/audio_lib/_meta/{bgm,ambience,sfx}.json` 每类一份（无 sidecar、无 registry.json；旧文件忽略）；
 * - 条目模型：名称 / 分组 / 匹配规则（正则·字面 / 标题·正文）/ 标签描述 / 启用开关 / 音量·音速·音高 / 分类 / 来源；
 * - 解析链（引擎 / 试听共用）：精确名 → 别名 → 包含匹配（停用条目不参与）；
 *   未命中回退目录直扫（并自动补登）；
 * - 导入：单个/多个音频文件、zip 包（流式解包；zip 内部保留 sfx/、bgm/ 结构，其余落 `导入/`）；
 * - 导出：选中条目（音频 + `_audio_lib_meta.json` 规则与参数）打包 zip；
 * - 排序：注册表数组顺序 = 规范顺序（旧的在前）；置顶/置底/拖拽重排均落在该顺序上。
 */
object AudioLibrary {

    const val SOURCE_LOCAL = "local"
    const val SOURCE_GENERATED = "generated"
    const val SOURCE_REMOTE = "remote"

    private val AUDIO_EXTS = setOf("mp3", "m4a", "wav", "ogg", "flac", "aac")

    /** 解析负缓存 TTL（ms）：防热路径对未命中关键字重复深扫 */
    private const val MISS_TTL_MS = 30_000L

    data class AudioAsset(
        val name: String,
        val relPath: String,
        val category: String,
        val source: String = SOURCE_LOCAL,
        val aliases: List<String> = emptyList(),
        val size: Long = 0L,
        val mtime: Long = 0L,
        /** 编辑字段（B33.2 音频库 UI / B33.3e 规则实装） */
        val group: String = "",
        val pattern: String = "",
        /** 标签描述（原「替换为」更名：插入标签文案 / 合成描述，默认同素材名） */
        val tagDesc: String = "",
        /** 匹配规则是否按正则解释（关=字面包含） */
        val isRegex: Boolean = true,
        /** 匹配规则作用范围：章标题 */
        val scopeTitle: Boolean = false,
        /** 匹配规则作用范围：正文 */
        val scopeContent: Boolean = true,
        val enabled: Boolean = true,
        val volume: Float = 1f,
        val speed: Float = 1f,
        val pitch: Float = 1f,
        /** B33.3d：远程 soundId（sidecar 自带；规则层 → 素材直连） */
        val soundId: String = "",
        /** 预留（B33.2b zip 直读）：zip 文件相对路径 + 条目名 */
        val zipRel: String = "",
        val entry: String = "",
    ) {
        val id: String get() = if (zipRel.isBlank()) relPath else "$zipRel#$entry"
        val groupLabel: String get() = group.ifBlank { category }
    }

    data class ResolvedAsset(val asset: AudioAsset, val file: File)

    data class ScanResult(
        val total: Int,
        val added: Int,
        val removed: Int,
        /** B33.2c：同名同类被合并的重复条数 */
        val merged: Int = 0,
    )

    data class ImportSummary(val ok: Int, val skipped: Int, val fail: Int)

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val saveLock = Mutex()

    /** 解析负缓存（关键字 → 上次未命中时间；新文件落库 / 重扫时清除） */
    private val missCache = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** B33.2c：最近一次扫描合并的同名重复条数（rescan 用） */
    @Volatile
    private var lastMergeCount = 0

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

    fun metaByRelPath(relPath: String): AudioAsset? =
        index?.values?.firstOrNull { it.relPath == relPath }

    /** 当前索引中某相对路径的来源（""=未入册） */
    fun sourceOfRelPath(relPath: String): String =
        index?.values?.firstOrNull { it.relPath == relPath }?.source.orEmpty()

    /** 同步快照（规则层构建「用户自定规则」用） */
    fun snapshot(): List<AudioAsset> = index?.values?.toList().orEmpty()

    @Volatile
    private var bySidCache: Map<String, AudioAsset>? = null

    @Volatile
    private var bySidCacheSrc: Map<String, AudioAsset>? = null

    /** B33.3d：soundId → 素材（惰性构建，随 index 引用变化失效） */
    private fun assetBySoundId(soundId: String): AudioAsset? {
        val cur = index ?: return null
        if (bySidCacheSrc !== cur) {
            val map = HashMap<String, AudioAsset>()
            cur.values.forEach { a ->
                if (a.enabled && a.soundId.isNotBlank()) map.putIfAbsent(a.soundId, a)
            }
            bySidCache = map
            bySidCacheSrc = cur
        }
        return bySidCache?.get(soundId)
    }

    /** 规则层解析：按 soundId 直连已入库素材（sidecar 自带 soundId） */
    fun resolveBySoundId(context: Context, soundId: String): ResolvedAsset? {
        if (soundId.isBlank()) return null
        val asset = assetBySoundId(soundId) ?: return null
        if (asset.zipRel.isNotBlank()) return null
        val f = fileOf(context, asset)
        return if (f.isFile) ResolvedAsset(asset, f) else null
    }

    /** 引擎热路径：同步解析为「条目 + 文件」（停用条目不参与；未加载时回退目录直扫） */
    fun resolve(context: Context, keyword: String): ResolvedAsset? {
        val kw = keyword.trim()
        if (kw.isEmpty()) return null
        if (index == null) warmUp(context)
        index?.let { snap ->
            val hit = lookup(snap.values.filter { it.enabled }, kw)
            if (hit != null && hit.zipRel.isBlank()) {
                val f = fileOf(context, hit)
                if (f.isFile) return ResolvedAsset(hit, f)
            }
        }
        // 负缓存：TTL 内不重复深扫（合成/下载落库会即时清除）
        val now = System.currentTimeMillis()
        val lastMiss = missCache[kw]
        if (lastMiss != null && now - lastMiss < MISS_TTL_MS) return null
        // 兜底：目录直扫（覆盖尚未入册的新文件；命中后自动补登）
        val f = TmDemoAssets.findFile(context, kw) ?: run {
            missCache[kw] = now
            return null
        }
        val asset = registerFile(context, f, SOURCE_LOCAL) ?: AudioAsset(
            name = f.nameWithoutExtension,
            relPath = relOf(context, f),
            category = categoryOf(relOf(context, f)),
            source = SOURCE_LOCAL,
        )
        missCache.remove(kw)
        return ResolvedAsset(asset, f)
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
                val merged = LinkedHashMap<String, AudioAsset>()
                var anyMeta = false
                listOf("BGM", "环境声", "音效").forEach { cat ->
                    val f = File(metaDir(context), categoryFileName(cat))
                    if (f.isFile) {
                        anyMeta = true
                        parseRegistry(f.readText().removePrefix("\uFEFF"))?.let { merged.putAll(it) }
                    }
                }
                if (anyMeta) {
                    index = merged
                } else {
                    // 无聚合文件：一次性导入旧 registry.json（若有），否则全量扫描；随后按类别拆分落盘
                    val legacy = legacyRegistryFile(context)
                    val parsedLegacy = if (legacy.isFile) {
                        parseRegistry(legacy.readText().removePrefix("\uFEFF"))
                    } else {
                        null
                    }
                    index = parsedLegacy ?: scanInternal(context, emptyMap())
                    persist(context, index.orEmpty().values)
                }
            }
            loaded = true
        }
    }

    /** 重新扫描（库管理页）：保留编辑字段（同名同路径合并），zip 登记条目原样保留 */
    suspend fun rescan(context: Context): ScanResult = withContext(Dispatchers.IO) {
        lock.withLock {
            val old = index ?: emptyMap()
            val fresh = LinkedHashMap(scanInternal(context, old))
            old.values.filter { it.zipRel.isNotBlank() }
                .filter { File(TmDemoAssets.libRoot(context), it.zipRel).isFile }
                .forEach { z -> fresh[z.id] = z }
            index = fresh
            persist(context, fresh.values)
            missCache.clear()
            loaded = true
            ScanResult(
                total = fresh.size,
                added = fresh.keys.count { it !in old },
                removed = old.keys.count { it !in fresh },
                merged = lastMergeCount,
            )
        }
    }

    /** 合成产物 / 示例素材 / 导入落库登记（未加载时跳过，等首扫接管） */
    fun notifyFileAdded(
        context: Context,
        file: File,
        source: String = SOURCE_LOCAL,
        soundId: String = "",
        aliases: List<String> = emptyList(),
    ) {
        missCache.clear()
        registerFile(context, file, source, soundId, aliases)
    }

    /** B33.3d/2c：规则表就绪后回填（名称→soundId 反查 + soundId→名称/别名），只补不覆盖 */
    suspend fun backfillFromRules(
        context: Context,
        namesOf: (String) -> List<String>,
        sidOfName: (String) -> String = { "" },
    ): Int {
        ensureLoaded(context)
        return withContext(Dispatchers.IO) {
            lock.withLock {
                val cur = index ?: return@withLock 0
                var changed = 0
                val next = LinkedHashMap<String, AudioAsset>(cur.size)
                cur.values.forEach { a ->
                    var soundId = a.soundId
                    var aliases = a.aliases
                    if (soundId.isBlank()) {
                        soundId = runCatching { sidOfName(a.name) }.getOrDefault("")
                    }
                    if (aliases.isEmpty() && soundId.isNotBlank()) {
                        aliases = runCatching { namesOf(soundId) }.getOrDefault(emptyList())
                            .asSequence()
                            .filter { it.isNotBlank() && it != a.name }
                            .distinct()
                            .take(12)
                            .toList()
                    }
                    if (soundId != a.soundId || aliases != a.aliases) {
                        next[a.id] = a.copy(soundId = soundId, aliases = aliases)
                        changed++
                    } else {
                        next[a.id] = a
                    }
                }
                if (changed > 0) {
                    index = next
                    persist(context, next.values)
                    missCache.clear()
                }
                changed
            }
        }
    }

    /** 更新单个条目（编辑保存：名称 / 分组 / 规则 / 参数等） */
    suspend fun updateAsset(context: Context, asset: AudioAsset): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            val cur = index ?: return@withLock false
            if (asset.id !in cur) return@withLock false
            index = cur + (asset.id to asset)
            persist(context, index.orEmpty().values)
            missCache.clear()
            true
        }
    }

    /** 批量启用/停用 */
    suspend fun setEnabled(context: Context, ids: Set<String>, enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext false
        lock.withLock {
            val cur = index ?: return@withLock false
            index = cur.mapValues { (k, v) -> if (k in ids) v.copy(enabled = enabled) else v }
            persist(context, index.orEmpty().values)
            true
        }
    }

    /** B33.2c · 批量移动分组（group 字段；空串=默认（同分类）） */
    suspend fun setGroup(context: Context, ids: Set<String>, group: String): Boolean = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext false
        lock.withLock {
            val cur = index ?: return@withLock false
            var changed = false
            val next = cur.mapValues { (k, v) ->
                if (k in ids && v.group != group) {
                    changed = true
                    v.copy(group = group)
                } else {
                    v
                }
            }
            if (!changed) return@withLock false
            index = next
            persist(context, next.values)
            true
        }
    }

    /** 批量删除（文件 + sidecar + 索引登记；删除失败的文件保留登记） */
    suspend fun removeAssets(context: Context, ids: Set<String>): Int = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext 0
        lock.withLock {
            val cur = index ?: return@withLock 0
            val removedIds = HashSet<String>()
            ids.forEach { id ->
                val asset = cur[id] ?: return@forEach
                if (asset.zipRel.isNotBlank()) {
                    removedIds.add(id)
                    return@forEach
                }
                val f = fileOf(context, asset)
                val ok = if (f.isFile) f.delete() else true
                if (ok) {
                    runCatching {
                        File(f.parentFile, f.nameWithoutExtension + ".json")
                            .takeIf { it.isFile }?.delete()
                    }
                    removedIds.add(id)
                }
            }
            if (removedIds.isEmpty()) return@withLock 0
            val next = cur.filterKeys { it !in removedIds }
            index = next
            persist(context, next.values)
            missCache.clear()
            removedIds.size
        }
    }

    /** 拖拽重排：给定 id 集合按新相对顺序落到其在规范序中的原位置（与替换净化 moveOrder 对齐） */
    suspend fun reorder(context: Context, orderedIds: List<String>): Boolean = withContext(Dispatchers.IO) {
        if (orderedIds.isEmpty()) return@withContext false
        lock.withLock {
            val cur = index ?: return@withLock false
            val idSet = orderedIds.toSet()
            val values = cur.values.toMutableList()
            val positions = values.withIndex().filter { it.value.id in idSet }.map { it.index }
            if (positions.size != orderedIds.size) return@withLock false
            orderedIds.forEachIndexed { i, id ->
                cur[id]?.let { values[positions[i]] = it }
            }
            val next = LinkedHashMap<String, AudioAsset>()
            values.forEach { next[it.id] = it }
            index = next
            persist(context, next.values)
            true
        }
    }

    /** 置顶（保持所选内部相对顺序） */
    suspend fun moveTop(context: Context, ids: Set<String>): Boolean = moveEdges(context, ids, toTop = true)

    /** 置底 */
    suspend fun moveBottom(context: Context, ids: Set<String>): Boolean = moveEdges(context, ids, toTop = false)

    private suspend fun moveEdges(context: Context, ids: Set<String>, toTop: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            if (ids.isEmpty()) return@withContext false
            lock.withLock {
                val cur = index ?: return@withLock false
                val moved = cur.values.filter { it.id in ids }
                val rest = cur.values.filter { it.id !in ids }
                val values = if (toTop) moved + rest else rest + moved
                val next = LinkedHashMap<String, AudioAsset>()
                values.forEach { next[it.id] = it }
                index = next
                persist(context, next.values)
                true
            }
        }

    // ------------------------------------------------------------ 导入 / 导出

    /** 导入音频：单个/多个音频文件或 zip 包（zip 内保留 sfx/、bgm/ 结构，其余落 `导入/`） */
    suspend fun importAudio(context: Context, uris: List<Uri>): ImportSummary = withContext(Dispatchers.IO) {
        val root = TmDemoAssets.libRoot(context)
        var ok = 0
        var skipped = 0
        var fail = 0
        uris.forEach { uri ->
            val displayName = displayNameOf(context, uri)
            if (displayName.endsWith(".zip", ignoreCase = true)) {
                val r = runCatching { importZip(context, root, uri) }
                    .getOrElse { ImportSummary(0, 0, 1) }
                ok += r.ok
                skipped += r.skipped
                fail += r.fail
            } else {
                val ext = displayName.substringAfterLast('.', "").lowercase()
                if (ext !in AUDIO_EXTS) {
                    fail++
                    return@forEach
                }
                val base = sanitizeGeneratedName(displayName.substringBeforeLast('.'))
                val out = File(File(root, "导入"), "$base.$ext")
                if (out.isFile && out.length() > 0L) {
                    skipped++
                    return@forEach
                }
                runCatching {
                    out.parentFile?.mkdirs()
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        out.outputStream().use { output -> input.copyTo(output) }
                    } ?: error("无法读取")
                    if (out.length() <= 0L) error("空文件")
                    ok++
                }.onFailure {
                    runCatching { out.delete() }
                    fail++
                }
            }
        }
        if (ok > 0) runCatching { rescan(context) }
        ImportSummary(ok, skipped, fail)
    }

    private suspend fun importZip(context: Context, root: File, uri: Uri): ImportSummary {
        var ok = 0
        var skipped = 0
        var fail = 0
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input.buffered()).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    val name = entry.name.replace('\\', '/')
                    val ext = name.substringAfterLast('.', "").lowercase()
                    val isAudio = !entry.isDirectory && ext in AUDIO_EXTS &&
                        !name.startsWith("__MACOSX/") && !name.substringAfterLast('/').startsWith(".")
                    if (isAudio) {
                        val base = sanitizeGeneratedName(name.substringAfterLast('/').substringBeforeLast('.'))
                        val rel = when {
                            name.startsWith("sfx/") || name.startsWith("bgm/") -> {
                                val safe = name.split('/').filter { it.isNotBlank() && it != ".." }
                                if (safe.size >= 2) safe.dropLast(1).joinToString("/") + "/$base.$ext" else "导入/$base.$ext"
                            }
                            else -> "导入/$base.$ext"
                        }
                        val out = File(root, rel)
                        if (out.isFile && out.length() > 0L) {
                            skipped++
                        } else {
                            val done = runCatching {
                                out.parentFile?.mkdirs()
                                out.outputStream().use { zis.copyTo(it) }
                                if (out.length() <= 0L) error("空文件")
                                true
                            }.getOrElse {
                                runCatching { out.delete() }
                                false
                            }
                            if (done) ok++ else fail++
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        } ?: return ImportSummary(0, 0, 1)
        return ImportSummary(ok, skipped, fail)
    }

    /** 导出选中条目：音频 + `_audio_lib_meta.json`（含规则与音量等调整参数） */
    suspend fun exportZip(context: Context, assets: List<AudioAsset>, out: OutputStream): Int =
        withContext(Dispatchers.IO) {
            var n = 0
            ZipOutputStream(out.buffered()).use { zos ->
                assets.forEach { a ->
                    if (a.zipRel.isNotBlank()) return@forEach
                    val f = fileOf(context, a)
                    if (!f.isFile) return@forEach
                    runCatching {
                        zos.putNextEntry(ZipEntry("audio/${a.relPath}"))
                        f.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                        n++
                    }
                }
                val meta = JSONObject().apply {
                    put("version", 1)
                    put("exportedAt", System.currentTimeMillis())
                    put("items", JSONArray().apply {
                        assets.forEach { a ->
                            put(JSONObject().apply {
                                put("name", a.name)
                                put("relPath", a.relPath)
                                put("category", a.category)
                                put("group", a.group)
                                put("pattern", a.pattern)
                                put("tagDesc", a.tagDesc)
                                put("isRegex", a.isRegex)
                                put("scopeTitle", a.scopeTitle)
                                put("scopeContent", a.scopeContent)
                                put("enabled", a.enabled)
                                put("volume", a.volume.toDouble())
                                put("speed", a.speed.toDouble())
                                put("pitch", a.pitch.toDouble())
                            })
                        }
                    })
                }
                zos.putNextEntry(ZipEntry("_audio_lib_meta.json"))
                zos.write(meta.toString().toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
            n
        }

    // ------------------------------------------------------------ 纯函数（可单测）

    internal fun lookup(assets: Collection<AudioAsset>, keyword: String): AudioAsset? {
        val kw = keyword.trim()
        if (kw.isEmpty()) return null
        assets.firstOrNull { it.name == kw }?.let { return it }
        assets.firstOrNull { a -> a.aliases.any { it.equals(kw, ignoreCase = true) } }?.let { return it }
        return assets.firstOrNull { it.name.contains(kw, ignoreCase = true) }
    }

    /** B33.3e 分类收敛：bgm/→BGM；sfx/环境声/→环境声；其余（含导入）→音效 */
    internal fun categoryOf(relPath: String): String {
        val parts = relPath.split('/')
        return when {
            parts.getOrNull(0) == "bgm" -> "BGM"
            parts.getOrNull(0) == "sfx" && parts.getOrNull(1) == "环境声" -> "环境声"
            else -> "音效"
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
                size = o.optLong("size", 0L),
                mtime = o.optLong("mtime", 0L),
                group = o.optString("group"),
                pattern = o.optString("pattern"),
                tagDesc = o.optString("tagDesc"),
                isRegex = o.optBoolean("isRegex", true),
                scopeTitle = o.optBoolean("scopeTitle", false),
                scopeContent = o.optBoolean("scopeContent", true),
                enabled = o.optBoolean("enabled", true),
                volume = o.optDouble("volume", 1.0).toFloat(),
                speed = o.optDouble("speed", 1.0).toFloat(),
                pitch = o.optDouble("pitch", 1.0).toFloat(),
                soundId = o.optString("soundId"),
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
                put("size", a.size)
                put("mtime", a.mtime)
                if (a.group.isNotBlank()) put("group", a.group)
                if (a.pattern.isNotBlank()) put("pattern", a.pattern)
                if (a.tagDesc.isNotBlank()) put("tagDesc", a.tagDesc)
                if (!a.isRegex) put("isRegex", false)
                if (a.scopeTitle) put("scopeTitle", true)
                if (!a.scopeContent) put("scopeContent", false)
                if (!a.enabled) put("enabled", false)
                if (a.volume != 1f) put("volume", a.volume.toDouble())
                if (a.speed != 1f) put("speed", a.speed.toDouble())
                if (a.pitch != 1f) put("pitch", a.pitch.toDouble())
                if (a.soundId.isNotBlank()) put("soundId", a.soundId)
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

    private fun metaDir(context: Context): File =
        File(TmDemoAssets.libRoot(context), "_meta")

    private fun categoryFileName(category: String): String = when (category) {
        "BGM" -> "bgm.json"
        "环境声" -> "ambience.json"
        else -> "sfx.json"
    }

    /** 旧版 registry.json（仅一次性导入兜底；用户可删除） */
    private fun legacyRegistryFile(context: Context): File =
        File(TmDemoAssets.libRoot(context), "registry.json")

    private fun relOf(context: Context, file: File): String =
        runCatching { file.relativeTo(TmDemoAssets.libRoot(context)).path.replace(File.separatorChar, '/') }
            .getOrDefault(file.name)

    /** B33.2c：无 sidecar 后，按名称在规则声音表反查（soundId + 别名），尽力恢复联动 */
    private fun recoverByName(name: String): Pair<String, List<String>> {
        val data = AudioRuleStore.current() ?: return "" to emptyList()
        val sid = runCatching { data.soundIdByName(name) }.getOrDefault("")
        if (sid.isBlank()) return "" to emptyList()
        val aliases = data.namesOfSound(sid)
            .asSequence()
            .filter { it.isNotBlank() && it != name }
            .take(8)
            .toList()
        return sid to aliases
    }

    private fun scanInternal(context: Context, old: Map<String, AudioAsset>): Map<String, AudioAsset> {
        val root = TmDemoAssets.libRoot(context)
        root.mkdirs()
        val prevByRel = old.values.associateBy { it.relPath }
        val map = LinkedHashMap<String, AudioAsset>()
        walkAudio(root).forEach { f ->
            val rel = f.relativeTo(root).path.replace(File.separatorChar, '/')
            val prev = prevByRel[rel]
            val recovered = if (prev == null) recoverByName(f.nameWithoutExtension) else "" to emptyList()
            val asset = (prev ?: AudioAsset(
                name = f.nameWithoutExtension,
                relPath = rel,
                category = categoryOf(rel),
                source = SOURCE_LOCAL,
            )).copy(
                size = f.length(),
                mtime = f.lastModified(),
                soundId = prev?.soundId?.takeIf { it.isNotBlank() } ?: recovered.first,
                aliases = prev?.aliases?.takeIf { it.isNotEmpty() } ?: recovered.second,
            )
            map[asset.id] = asset
        }
        // B33.2c：同名同类去重合并（保留首个；并集 soundId/别名；不删除文件）
        val byKey = HashMap<String, String>()
        val deduped = LinkedHashMap<String, AudioAsset>()
        var mergedCount = 0
        map.values.forEach { a ->
            val key = "${a.category}|${a.name}"
            val keptId = byKey[key]
            if (keptId == null) {
                byKey[key] = a.id
                deduped[a.id] = a
            } else {
                mergedCount++
                val kept = deduped[keptId] ?: return@forEach
                deduped[keptId] = kept.copy(
                    soundId = kept.soundId.ifBlank { a.soundId },
                    aliases = (kept.aliases + a.aliases).distinct(),
                )
            }
        }
        lastMergeCount = mergedCount
        return deduped
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

    private fun registerFile(
        context: Context,
        file: File,
        source: String,
        soundId: String = "",
        aliases: List<String> = emptyList(),
    ): AudioAsset? {
        val cur = index ?: return null
        if (!file.isFile) return null
        val root = TmDemoAssets.libRoot(context)
        val rel = runCatching { file.relativeTo(root).path.replace(File.separatorChar, '/') }
            .getOrNull()?.takeIf { it.isNotBlank() && !it.startsWith("..") } ?: return null
        val existing = cur.values.firstOrNull { it.relPath == rel }
        if (existing != null) {
            // B33.4 前置：远程版本优先——远程下载可覆盖「合成产物」的来源与别名
            val upgradedSource = if (source == SOURCE_REMOTE && existing.source != SOURCE_REMOTE) {
                SOURCE_REMOTE
            } else {
                existing.source
            }
            val updated = existing.copy(
                size = file.length(),
                mtime = file.lastModified(),
                soundId = existing.soundId.ifBlank { soundId },
                aliases = (existing.aliases + aliases).distinct(),
                source = upgradedSource,
            )
            if (updated == existing) return existing
            index = cur + (updated.id to updated)
            scheduleSave(context.applicationContext)
            return updated
        }
        val recovered = if (soundId.isBlank()) recoverByName(file.nameWithoutExtension) else "" to emptyList()
        val asset = AudioAsset(
            name = file.nameWithoutExtension,
            relPath = rel,
            category = categoryOf(rel),
            source = source.ifBlank { SOURCE_LOCAL },
            size = file.length(),
            mtime = file.lastModified(),
            soundId = soundId.ifBlank { recovered.first },
            aliases = aliases.takeIf { it.isNotEmpty() } ?: recovered.second,
        )
        index = cur + (asset.id to asset)
        scheduleSave(context.applicationContext)
        return asset
    }

    private fun displayNameOf(context: Context, uri: Uri): String {
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) {
                    val n = c.getString(idx)
                    if (!n.isNullOrBlank()) return n
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/').orEmpty().ifBlank { "未命名" }
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
            val dir = metaDir(context)
            dir.mkdirs()
            val grouped = LinkedHashMap<String, MutableList<AudioAsset>>()
            assets.forEach { a ->
                grouped.getOrPut(categoryFileName(a.category)) { mutableListOf() }.add(a)
            }
            listOf("bgm.json", "ambience.json", "sfx.json").forEach { name ->
                val f = File(dir, name)
                val tmp = File(dir, "$name.tmp")
                tmp.writeText(serializeRegistry(grouped[name].orEmpty()))
                if (!tmp.renameTo(f)) {
                    tmp.copyTo(f, overwrite = true)
                    tmp.delete()
                }
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
