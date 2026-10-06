package io.legado.app.help.readaloud.audio

import android.content.Context
import com.github.jing332.compat.fs.TtsDirProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * P1.6.2+（第二刀）· 云端上传账本：记录「哪些条目上传过云端词网」与「上传时的名字/词快照」，
 * 供音频库卡片显示「已上传 / 待同步」标签。
 *
 * - 轻量指纹键 = `"size|mtime"`（重命名不改文件指纹，可跨改名追踪；文件被替换则失联、按名兜底）；
 *   查询先按指纹、再按名字。
 * - 状态：pending（已提交待回执）/ confirmed（回执已回）；两者都显示「已上传」；
 *   名字或词集与快照不一致 → 「待同步」；无记录 → 不显示。
 * - 存储：`_store/cloud_uploads.json`；fail-open、内存缓存（O(1) 查询）。
 */
object CloudUploadLedger {

    private class Entry {
        var name: String = ""
        var words: List<String> = emptyList()
        var batch: String = ""
        var ts: Long = 0L
        var status: String = "pending"
        var size: Long = 0L
        var mtime: Long = 0L
    }

    /** 上传提交的快照记录 */
    data class Record(val name: String, val words: List<String>, val size: Long, val mtime: Long)

    private val lock = Any()
    private val byKey = ConcurrentHashMap<String, Entry>()
    private val byName = ConcurrentHashMap<String, Entry>()
    private var loaded = false

    private fun file(context: Context): File =
        File(TtsDirProvider.baseDir(context), "_store/cloud_uploads.json")

    private fun keyOf(size: Long, mtime: Long): String = "$size|$mtime"

    private fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            loaded = true
            runCatching {
                val f = file(context)
                if (!f.isFile) return
                val root = JSONObject(f.readText().removePrefix("\uFEFF"))
                val arr = root.optJSONArray("entries") ?: return
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val e = Entry().apply {
                        name = o.optString("name")
                        words = o.optJSONArray("words")?.let { wa ->
                            (0 until wa.length()).map { wa.optString(it) }
                        }.orEmpty()
                        batch = o.optString("batch")
                        ts = o.optLong("ts")
                        status = o.optString("status", "confirmed")
                        size = o.optLong("size")
                        mtime = o.optLong("mtime")
                    }
                    byKey[keyOf(e.size, e.mtime)] = e
                    byName[e.name] = e
                }
            }
        }
    }

    /** 上传成功后记账（batch=云端批次号；records=实际提交的条目快照） */
    fun record(context: Context, batch: String, records: List<Record>) {
        if (batch.isBlank() || records.isEmpty()) return
        ensureLoaded(context)
        synchronized(lock) {
            runCatching {
                val now = System.currentTimeMillis()
                records.forEach { r ->
                    val e = Entry().apply {
                        name = r.name
                        words = r.words
                        this.batch = batch
                        ts = now
                        status = "pending"
                        size = r.size
                        mtime = r.mtime
                    }
                    byKey[keyOf(e.size, e.mtime)] = e
                    byName[e.name] = e
                }
                save(context)
            }
        }
    }

    /** 回执到达：本批 pending → confirmed */
    fun confirm(context: Context, batch: String) {
        if (batch.isBlank()) return
        ensureLoaded(context)
        synchronized(lock) {
            runCatching {
                var changed = false
                byKey.values.forEach { e ->
                    if (e.batch == batch && e.status == "pending") {
                        e.status = "confirmed"
                        changed = true
                    }
                }
                if (changed) save(context)
            }
        }
    }

    /**
     * 卡片状态文案："" 未上传 / "已上传" / "待同步"。
     * 注意：words = 当前词集（非正则词模式的词）
     */
    fun statusText(
        context: Context,
        name: String,
        words: List<String>,
        size: Long,
        mtime: Long,
    ): String {
        if (name.isBlank()) return ""
        ensureLoaded(context)
        val e = byKey[keyOf(size, mtime)] ?: byName[name] ?: return ""
        return if (e.name != name || e.words.sorted() != words.sorted()) "待同步" else "已上传"
    }

    private fun save(context: Context) {
        runCatching {
            val f = file(context)
            f.parentFile?.mkdirs()
            val arr = JSONArray()
            byKey.values.distinct().forEach { e ->
                arr.put(
                    JSONObject().apply {
                        put("name", e.name)
                        put("words", JSONArray(e.words))
                        put("batch", e.batch)
                        put("ts", e.ts)
                        put("status", e.status)
                        put("size", e.size)
                        put("mtime", e.mtime)
                    }
                )
            }
            val root = JSONObject().apply {
                put("version", 1)
                put("entries", arr)
            }
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(f)) {
                tmp.copyTo(f, overwrite = true)
                tmp.delete()
            }
        }
    }
}
