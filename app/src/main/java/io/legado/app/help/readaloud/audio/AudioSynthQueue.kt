package io.legado.app.help.readaloud.audio

import android.app.Application
import android.content.Context
import com.github.jing332.compat.fs.TtsDirProvider
import io.legado.app.constant.AppLog
import io.legado.app.data.repository.AiModelRepository
import io.legado.app.domain.model.settings.ReadAloudSettings
import io.legado.app.help.readaloud.analysis.queueRefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** B33.3c · 合成轨（落库目录 / 分配键 / 展示名） */
enum class SynthLane(val label: String, val folderRel: String, val assignKey: String) {
    SFX("音效", "sfx/音效", "synthSfx"),
    AMB("环境", "sfx/环境声", "synthAmb"),
    BGM("BGM", "bgm", "synthBgm");

    companion object {
        fun ofKind(kind: String): SynthLane? = entries.firstOrNull { it.label == kind }
    }
}

/**
 * B33.3c · 缺失音频自动合成队列（串行补缺 worker）。
 *
 * 口径（《B33 施工方案》§8.7）：
 *  - 触发：四轨解析素材缺失时由 [AudioLaneEngine] 上报（缺失自动合成默认开）；
 *  - 限流：串行；每章 ≤ 上限（默认 10，0=关闭）；同词只补一次；失败冷却 30 分钟；
 *  - 派单：按「模型分配 → 合成·音效 / 合成·BGM / 合成·环境底噪」队列顺序逐模型尝试，失败自动换下一个；
 *  - 落库：`data/audio_lib/<轨目录>/<中文关键词>.mp3`（原子落盘 + .json sidecar + 音频日志）；
 *  - 状态：`_store/audio_missing.json`（pending / running / done / failed + 来源）。
 */
class AudioSynthQueue(
    private val appContext: Context,
    private val scope: CoroutineScope,
    /** 实时读取最新设置（每章上限等） */
    private val settings: () -> ReadAloudSettings,
    /** 当前章节键（bookUrl|chapterIndex），每章计数用 */
    private val chapterKey: () -> String,
) {

    private val app: Application = appContext.applicationContext as Application
    private val repo = AiModelRepository(app)

    private class Entry(
        val lane: SynthLane,
        val keyword: String,
        var status: String,
        var attempts: Int = 0,
        var lastError: String = "",
        var fileRel: String = "",
        var source: String = "",
        var updatedAt: Long = 0L,
        /** B33.4-前置：条目归属章（bookUrl|chapterIndex；预合成章用显式键） */
        var chapterKey: String = "",
        /** B33.4b：生成描述（Ai 导演产物；合成提示词 desc 优先） */
        var desc: String = "",
    )

    private val lock = Mutex()
    private val saveLock = Mutex()
    private val loadLock = Mutex()

    private val entries = HashMap<String, Entry>()      // "SFX|关键词"
    private val chapterCounts = HashMap<String, Int>()  // 章节键 → 已入队次数
    private val skipLogged = HashSet<String>()

    private val queue = Channel<Entry>(Channel.UNLIMITED)
    private var loaded = false
    private val worker: Job

    init {
        worker = scope.launch(Dispatchers.IO) {
            ensureLoaded()
            for (entry in queue) {
                try {
                    process(entry)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    markFailed(entry, e.localizedMessage ?: e.javaClass.simpleName)
                }
            }
        }
    }

    /** 缺失上报入口（由四轨引擎/预合成闭环回调；非挂起、不阻塞播放）；chapterKey=预合成章显式键；desc=生成描述 */
    fun enqueue(kind: String, keyword: String, chapterKey: String? = null, desc: String = "") {
        val lane = SynthLane.ofKind(kind) ?: return
        val kw = keyword.trim()
        if (kw.isEmpty()) return
        scope.launch { enqueueInternal(lane, kw, chapterKey, desc) }
    }

    /** P1.4：同步注册版入队（预合成闸门用；返回后条目已登记，等待终态不会空转） */
    suspend fun enqueueAwait(kind: String, keyword: String, chapterKey: String? = null, desc: String = "") {
        val lane = SynthLane.ofKind(kind) ?: return
        val kw = keyword.trim()
        if (kw.isEmpty()) return
        enqueueInternal(lane, kw, chapterKey, desc)
    }

    fun release() {
        queue.close()
        worker.cancel()
    }

    // ------------------------------------------------------------ 入队与限流

    private suspend fun enqueueInternal(lane: SynthLane, kw: String, ckOverride: String? = null, desc: String = "") {
        ensureLoaded()
        val refs = runCatching { repo.queueRefs(lane.assignKey) }.getOrDefault(emptyList())
        lock.withLock {
            val key = keyOf(lane, kw)
            val now = now()
            entries[key]?.let { e ->
                when (e.status) {
                    "done" -> {
                        // B33.4-前置：done 但文件已被删（清库重来/手动清理）→ 视同缺失，重新走补缺链
                        val f = if (e.fileRel.isNotBlank()) {
                            runCatching { File(TmDemoAssets.libRoot(appContext), e.fileRel) }.getOrNull()
                        } else null
                        if (f != null && f.isFile && f.length() > 0L) return
                    }
                    "pending", "running" -> if (now - e.updatedAt < STALE_INFLIGHT_MS) return
                    "failed" -> if (now - e.updatedAt < FAIL_COOLDOWN_MS) return
                }
            }
            if (refs.isEmpty()) {
                logSkipOnce(key, "${lane.label}「$kw」缺失，但未分配「合成·${lane.label}」模型")
                return
            }
            val cap = settings().alChapterSynthCap
            if (cap <= 0) {
                logSkipOnce(key, "${lane.label}「$kw」缺失（自动补缺已关闭）")
                return
            }
            val ck = (ckOverride ?: chapterKey()).ifBlank { "default" }
            val used = chapterCounts[ck] ?: 0
            if (used >= cap) {
                logSkipOnce(key, "${lane.label}「$kw」缺失（本章补缺已达上限 $cap）")
                return
            }
            val entry = Entry(lane, kw, "pending", updatedAt = now, chapterKey = ck, desc = desc)
            entries[key] = entry
            chapterCounts[ck] = used + 1
            queue.trySend(entry)
        }
        save()
    }

    // ------------------------------------------------------------ 串行 worker

    private suspend fun process(task: Entry) {
        lock.withLock {
            task.status = "running"
            task.attempts += 1
            task.updatedAt = now()
        }
        save()
        // P1.2：补缺链第二环——词网直连（免费；不需要合成模型）
        val remoteFile = runCatching {
            val net = AudioNetStore.lookup(task.keyword)
            if (net != null) AudioNetStore.fetchAsset(appContext, net) else null
        }.getOrNull()
        if (remoteFile != null) {
            val rel = runCatching {
                remoteFile.relativeTo(TmDemoAssets.libRoot(appContext)).path.replace(File.separatorChar, '/')
            }.getOrDefault(remoteFile.name)
            markDone(task, rel, "远程库")
            return
        }
        val refs = runCatching { repo.queueRefs(task.lane.assignKey) }.getOrDefault(emptyList())
        if (refs.isEmpty()) {
            markFailed(task, "无可用合成模型")
            return
        }
        var lastError = ""
        refs.forEachIndexed { index, ref ->
            val provider = ref.provider
            val model = ref.model
            val result = AudioSynthClients.generate(provider, model, task.lane, task.keyword, task.desc)
            val gen = result.getOrNull()
            if (gen != null) {
                try {
                    val rel = saveGeneratedAudio(
                        appContext, task.lane, task.keyword, gen.bytes,
                        source = "${provider.name}/${model.name}",
                    )
                    markDone(task, rel, "${provider.name}/${model.name}")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    val msg = e.localizedMessage ?: "入库失败"
                    markFailed(task, msg)
                }
                return
            }
            lastError = result.exceptionOrNull()?.localizedMessage ?: "未知错误"
        }
        val failMsg = lastError.ifBlank { "全部合成模型失败" }
        markFailed(task, failMsg)
    }

    private suspend fun markDone(task: Entry, fileRel: String, source: String) {
        lock.withLock {
            task.status = "done"
            task.fileRel = fileRel
            task.source = source
            task.lastError = ""
            task.updatedAt = now()
        }
        save()
    }

    private suspend fun markFailed(task: Entry, message: String) {
        lock.withLock {
            task.status = "failed"
            task.lastError = message
            task.updatedAt = now()
        }
        save()
    }

    // ------------------------------------------------------------ 状态持久化

    private fun file(): File = missingFile(appContext)

    private suspend fun ensureLoaded() {
        if (loaded) return
        loadLock.withLock {
            if (loaded) return
            withContext(Dispatchers.IO) { load() }
            loaded = true
        }
    }

    private fun load() {
        runCatching {
            val f = file()
            if (!f.isFile) return
            val root = JSONObject(f.readText().removePrefix("\uFEFF"))
            val entriesObj = root.optJSONObject("entries") ?: JSONObject()
            entriesObj.keys().forEach { key ->
                val o = entriesObj.optJSONObject(key) ?: return@forEach
                val lane = SynthLane.entries.firstOrNull { it.name == o.optString("lane") } ?: return@forEach
                entries[key] = Entry(
                    lane = lane,
                    keyword = o.optString("keyword"),
                    status = o.optString("status", "pending"),
                    attempts = o.optInt("attempts", 0),
                    lastError = o.optString("lastError"),
                    fileRel = o.optString("file"),
                    source = o.optString("source"),
                    updatedAt = o.optLong("updatedAt", 0L),
                    chapterKey = o.optString("chapterKey"),
                    desc = o.optString("desc"),
                )
            }
            val chaptersObj = root.optJSONObject("chapters") ?: JSONObject()
            chaptersObj.keys().forEach { k -> chapterCounts[k] = chaptersObj.optInt(k, 0) }
        }
    }

    private suspend fun save() {
        val json = lock.withLock { buildJsonLocked() }
        saveLock.withLock {
            withContext(Dispatchers.IO) {
                runCatching {
                    val f = file()
                    f.parentFile?.mkdirs()
                    val tmp = File(f.parentFile, f.name + ".tmp")
                    tmp.writeText(json)
                    if (!tmp.renameTo(f)) {
                        tmp.copyTo(f, overwrite = true)
                        tmp.delete()
                    }
                }
            }
        }
    }

    private fun buildJsonLocked(): String {
        val root = JSONObject()
        root.put("version", 1)
        root.put("savedAt", now())
        val entriesObj = JSONObject()
        entries.forEach { (key, e) ->
            entriesObj.put(key, JSONObject().apply {
                put("lane", e.lane.name)
                put("keyword", e.keyword)
                put("status", e.status)
                put("attempts", e.attempts)
                put("lastError", e.lastError)
                put("file", e.fileRel)
                put("source", e.source)
                put("updatedAt", e.updatedAt)
                if (e.chapterKey.isNotBlank()) put("chapterKey", e.chapterKey)
                if (e.desc.isNotBlank()) put("desc", e.desc)
            })
        }
        root.put("entries", entriesObj)
        val chaptersObj = JSONObject()
        chapterCounts.forEach { (k, v) -> chaptersObj.put(k, v) }
        root.put("chapters", chaptersObj)
        return root.toString()
    }

    // ------------------------------------------------------------ 工具

    private fun keyOf(lane: SynthLane, keyword: String): String = "${lane.name}|$keyword"

    /** B33.4-前置：某章是否仍有在途条目（pending/running） */
    private fun chapterBusy(chapterKey: String): Boolean =
        entries.values.any { it.chapterKey == chapterKey && (it.status == "pending" || it.status == "running") }

    /** M1-A：等待某章全部合成条目到终态（done/failed；不设上限；音效一次成/败即终态） */
    suspend fun awaitChapterTerminal(chapterKey: String) {
        if (chapterKey.isBlank()) return
        while (lock.withLock { chapterBusy(chapterKey) }) delay(500)
    }

    /** M1-B：某关键词条目是否仍在途（pending/running）。未入队/已终态 = false（不等待） */
    fun entryInFlight(lane: SynthLane, keyword: String): Boolean = runCatching {
        when (entries["${lane.name}|$keyword"]?.status) {
            "pending", "running" -> true
            else -> false
        }
    }.getOrDefault(false)

    /** M1-B：等待一组关键词全部离开在途态（不设上限）；返回等待毫秒 */
    suspend fun awaitKeywordsTerminal(lane: SynthLane, keywords: Collection<String>): Long {
        val keys = keywords.filter { it.isNotBlank() }.distinct()
        if (keys.isEmpty()) return 0L
        val t0 = System.currentTimeMillis()
        while (keys.any { entryInFlight(lane, it) }) delay(500)
        return System.currentTimeMillis() - t0
    }

    /** 某条目当前状态（""=未入队；供预合成总结统计） */
    fun entryStatus(lane: SynthLane, keyword: String): String =
        runCatching { entries["${lane.name}|$keyword"]?.status.orEmpty() }.getOrDefault("")

    /** 日志 v3：跳过原因仅记录去重集合（散行静默），由章节总结统一呈现 */
    private fun logSkipOnce(key: String, reason: String) {
        skipLogged.add(key)
    }

    private fun now(): Long = System.currentTimeMillis()

    companion object {
        /** 失败冷却（30 分钟） */
        private const val FAIL_COOLDOWN_MS = 30 * 60 * 1000L

        /** 在途任务视为滞留的超时（服务重启兜底；超过后可重新入队） */
        private const val STALE_INFLIGHT_MS = 15 * 60 * 1000L

        // ---------------- B33.2c · 缺失清单（管理面板用） ----------------

        /** 读取 `_store/audio_missing.json` 全部条目 */
        suspend fun missingRows(context: Context): List<AudioMissingRow> = withContext(Dispatchers.IO) {
            runCatching {
                val f = missingFile(context)
                if (!f.isFile) return@runCatching emptyList()
                val entries = JSONObject(f.readText().removePrefix("\uFEFF")).optJSONObject("entries")
                    ?: return@runCatching emptyList()
                buildList {
                    val keys = entries.keys()
                    while (keys.hasNext()) {
                        val o = entries.optJSONObject(keys.next()) ?: continue
                        add(
                            AudioMissingRow(
                                lane = o.optString("lane"),
                                keyword = o.optString("keyword"),
                                status = o.optString("status"),
                                source = o.optString("source"),
                                lastError = o.optString("lastError").trim(),
                                updatedAt = o.optLong("updatedAt"),
                            )
                        )
                    }
                }
            }.getOrDefault(emptyList())
        }

        /** 重试：失败/完成 → pending、清冷却（回放命中章节即可再入队补缺） */
        suspend fun retryMissing(context: Context, lane: String, keyword: String): Boolean =
            editMissing(context) { entries ->
                val o = entries.optJSONObject("$lane|$keyword") ?: return@editMissing false
                o.put("status", "pending")
                o.put("updatedAt", 0L)
                o.put("lastError", "")
                true
            }

        /** 移除缺失记录 */
        suspend fun removeMissing(context: Context, lane: String, keyword: String): Boolean =
            editMissing(context) { entries -> entries.remove("$lane|$keyword") != null }

        private fun missingFile(context: Context): File =
            File(TtsDirProvider.baseDir(context), "_store/audio_missing.json")

        /** 原子编辑条目表（返回 false=未变更不落盘） */
        private suspend fun editMissing(
            context: Context,
            mutate: (JSONObject) -> Boolean,
        ): Boolean = withContext(Dispatchers.IO) {
            runCatching {
                val f = missingFile(context)
                if (!f.isFile) return@runCatching false
                val root = JSONObject(f.readText().removePrefix("\uFEFF"))
                val entries = root.optJSONObject("entries") ?: JSONObject()
                if (!mutate(entries)) return@runCatching false
                val tmp = File(f.parentFile, f.name + ".tmp")
                tmp.writeText(root.toString())
                if (!tmp.renameTo(f)) {
                    tmp.copyTo(f, overwrite = true)
                    tmp.delete()
                }
                true
            }.getOrDefault(false)
        }
    }
}

/** 原子落盘 + sidecar（合成产物与库同构命名：中文纯净文件名，不加前缀） */
internal suspend fun saveGeneratedAudio(
    context: Context,
    lane: SynthLane,
    keyword: String,
    bytes: ByteArray,
    source: String,
): String = withContext(Dispatchers.IO) {
    val root = TmDemoAssets.libRoot(context)
    val name = sanitizeGeneratedName(keyword)
    val rel = "${lane.folderRel}/$name.mp3"
    val out = File(root, rel)
    out.parentFile?.mkdirs()
    if (!out.isFile || out.length() == 0L) {
        val tmp = File(out.parentFile, "$name.mp3.part")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(out)) {
            tmp.copyTo(out, overwrite = true)
            tmp.delete()
        }
    }
    // B33.2c：不再写 sidecar；来源由登记入口统一写入 _meta/<类>.json
    runCatching { AudioLibrary.notifyFileAdded(context, out, AudioLibrary.SOURCE_GENERATED) }
    rel
}

internal fun sanitizeGeneratedName(keyword: String): String =
    keyword.replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]"), "_").trim().take(60).ifBlank { "未命名" }

/** B33.2c · 缺失清单行（lane=队列枚举名 AMB/SFX/BGM；供管理面板） */
data class AudioMissingRow(
    val lane: String,
    val keyword: String,
    val status: String,
    val source: String,
    val lastError: String,
    val updatedAt: Long,
)
