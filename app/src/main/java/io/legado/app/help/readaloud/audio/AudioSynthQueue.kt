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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** B33.3c · 合成轨（落库目录 / 分配键 / 展示名） */
enum class SynthLane(val label: String, val folderRel: String, val assignKey: String) {
    SFX("音效", "sfx/拟音", "synthSfx"),
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

    /** 缺失上报入口（由四轨引擎回调；非挂起、不阻塞播放） */
    fun enqueue(kind: String, keyword: String) {
        val lane = SynthLane.ofKind(kind) ?: return
        val kw = keyword.trim()
        if (kw.isEmpty()) return
        scope.launch { enqueueInternal(lane, kw) }
    }

    fun release() {
        queue.close()
        worker.cancel()
    }

    // ------------------------------------------------------------ 入队与限流

    private suspend fun enqueueInternal(lane: SynthLane, kw: String) {
        ensureLoaded()
        val refs = runCatching { repo.queueRefs(lane.assignKey) }.getOrDefault(emptyList())
        lock.withLock {
            val key = keyOf(lane, kw)
            val now = now()
            entries[key]?.let { e ->
                when (e.status) {
                    "done" -> return
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
            val ck = chapterKey().ifBlank { "default" }
            val used = chapterCounts[ck] ?: 0
            if (used >= cap) {
                logSkipOnce(key, "${lane.label}「$kw」缺失（本章补缺已达上限 $cap）")
                return
            }
            val entry = Entry(lane, kw, "pending", updatedAt = now)
            entries[key] = entry
            chapterCounts[ck] = used + 1
            queue.trySend(entry)
            AppLog.putAudio("【合成】入队：${lane.label}「$kw」（本章 ${used + 1}/$cap）")
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
        val refs = runCatching { repo.queueRefs(task.lane.assignKey) }.getOrDefault(emptyList())
        if (refs.isEmpty()) {
            markFailed(task, "无可用合成模型")
            return
        }
        var lastError = ""
        refs.forEachIndexed { index, ref ->
            val provider = ref.provider
            val model = ref.model
            val head = if (index > 0) "换阵" else "开始"
            AppLog.putAudio("【合成】$head：${task.lane.label}「${task.keyword}」→ ${provider.name}/${model.name}")
            val result = AudioSynthClients.generate(provider, model, task.lane, task.keyword)
            val gen = result.getOrNull()
            if (gen != null) {
                try {
                    val rel = saveGeneratedAudio(
                        appContext, task.lane, task.keyword, gen.bytes,
                        source = "${provider.name}/${model.name}",
                        prompt = AudioSynthClients.promptFor(task.lane, task.keyword),
                    )
                    markDone(task, rel, "${provider.name}/${model.name}")
                    AppLog.putAudio("【合成】完成：${task.lane.label}「${task.keyword}」← ${provider.name}（${gen.detail}）")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    val msg = e.localizedMessage ?: "入库失败"
                    markFailed(task, msg)
                    AppLog.putAudio("【合成】入库失败：${task.lane.label}「${task.keyword}」（$msg）")
                }
                return
            }
            lastError = result.exceptionOrNull()?.localizedMessage ?: "未知错误"
            if (index < refs.lastIndex) {
                AppLog.putAudio("【合成】未中：${task.lane.label}「${task.keyword}」← ${provider.name}（$lastError）")
            }
        }
        val failMsg = lastError.ifBlank { "全部合成模型失败" }
        markFailed(task, failMsg)
        AppLog.putAudio(
            "【合成】失败：${task.lane.label}「${task.keyword}」→ $failMsg（冷却 ${FAIL_COOLDOWN_MS / 60_000} 分钟）"
        )
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

    private fun file(): File = File(TtsDirProvider.baseDir(appContext), "_store/audio_missing.json")

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

    private fun logSkipOnce(key: String, reason: String) {
        if (skipLogged.add(key)) AppLog.putAudio("【合成】跳过：$reason")
    }

    private fun now(): Long = System.currentTimeMillis()

    companion object {
        /** 失败冷却（30 分钟） */
        private const val FAIL_COOLDOWN_MS = 30 * 60 * 1000L

        /** 在途任务视为滞留的超时（服务重启兜底；超过后可重新入队） */
        private const val STALE_INFLIGHT_MS = 15 * 60 * 1000L

        /**
         * 「合成测试」：用真实派单链路生成一条音效（默认「铜铃轻响」）并入库。
         * 返回可直接展示的结果文案（成功含来源与落库路径）。
         */
        suspend fun selfTest(context: Context, keyword: String = "铜铃轻响"): String =
            withContext(Dispatchers.IO) {
                val app = context.applicationContext as Application
                val lane = SynthLane.SFX
                val repo = AiModelRepository(app)
                val refs = runCatching { repo.queueRefs(lane.assignKey) }.getOrDefault(emptyList())
                if (refs.isEmpty()) {
                    return@withContext "未分配「合成·${lane.label}」模型：请到 模型管理 → 分配 里添加"
                }
                var lastError = ""
                refs.forEachIndexed { index, ref ->
                    val started = System.currentTimeMillis()
                    val result = AudioSynthClients.generate(ref.provider, ref.model, lane, keyword)
                    val gen = result.getOrNull()
                    if (gen != null) {
                        val rel = runCatching {
                            saveGeneratedAudio(
                                app, lane, keyword, gen.bytes,
                                source = "${ref.provider.name}/${ref.model.name}",
                                prompt = AudioSynthClients.promptFor(lane, keyword),
                            )
                        }.getOrElse { "入库失败：${it.localizedMessage}" }
                        val sec = (System.currentTimeMillis() - started) / 1000.0
                        if (rel.startsWith("入库失败")) {
                            AppLog.putAudio("【合成·测试】入库失败：$rel")
                            return@withContext "❌ 已生成但${rel}"
                        }
                        AppLog.putAudio("【合成·测试】成功：$keyword ← ${ref.provider.name}（${sec}s）→ $rel")
                        return@withContext "✅ 已生成：${ref.provider.name}（${"%.1f".format(sec)}s）→ audio_lib/$rel"
                    }
                    lastError = result.exceptionOrNull()?.localizedMessage ?: "未知错误"
                    if (index < refs.lastIndex) {
                        AppLog.putAudio("【合成·测试】${ref.provider.name} 失败（$lastError），换下一个…")
                    }
                }
                AppLog.putAudio("【合成·测试】失败：$lastError")
                "❌ 合成失败：$lastError"
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
    prompt: String,
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
    File(out.parentFile, "$name.json").writeText(
        JSONObject().apply {
            put("keyword", keyword)
            put("lane", lane.name)
            put("source", source)
            put("prompt", prompt)
            put("bytes", bytes.size)
            put("createdAt", System.currentTimeMillis())
        }.toString()
    )
    runCatching { AudioLibrary.notifyFileAdded(context, out, AudioLibrary.SOURCE_GENERATED) }
    rel
}

internal fun sanitizeGeneratedName(keyword: String): String =
    keyword.replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]"), "_").trim().take(60).ifBlank { "未命名" }
