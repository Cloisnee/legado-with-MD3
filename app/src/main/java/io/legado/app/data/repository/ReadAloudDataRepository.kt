package io.legado.app.data.repository

import io.legado.app.utils.AliasTokens
import io.legado.app.utils.ChapterLabels
import android.app.Application
import com.github.jing332.compat.fs.TtsDirProvider
import io.legado.app.data.appDb
import com.github.jing332.tts.store.TtsConfigStore
import io.legado.app.domain.model.readaloud.VoiceBankRoleType
import io.legado.app.domain.model.readaloud.VoiceGroupInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 朗读分析数据层（对照「角色管理」v36 忠实移植，数据根=<应用根>/data/）：
 *  - liebiao.json / cunfang.txt / characterRecords.json / bare_words / special_words
 *  - books/<书名>/：shuming.<书名>.json（角色）、all_clean_text_<书名>.txt（剧本〖说话人〗+[chapter:N]）、
 *    chapter_cache.<书名>.json、merge_log.<书名>.json（合并账本）
 *  - 合并账本可回放；改名/合并=文本+缓存+账本三同步；FNV 行指纹防误伤。
 */
data class CharacterRecord(
    var name: String = "",
    var aliases: String = "",
    var roletype: String = "核心",
    var gender: String = "男",
    var age: String = "男青年",
    var voice: String = "",
    var lastAppearanceChapter: Int = -1,
    var appearanceCount: Int = 0,
    var appearanceChapters: MutableList<Int> = mutableListOf(),
)

data class JueseState(
    val bookList: List<String>,
    val currentBook: String,
    val records: List<CharacterRecord>,
)

data class RewriteResult(val replaced: Int, val chapters: List<Int>)
data class ReplayResult(
    val replaced: Int,
    val missed: Int,
    val chapters: List<Int>,
    val viaWords: List<String>,
)

data class ScriptLineRow(
    val absIndex: Int,
    val speaker: String,
    val text: String,
    /** 剧本行前缀 [[emo:xxx]] 的情绪（音频缓存键需要，与播放侧一致） */
    val emotion: String = "",
)

class ReadAloudDataRepository(private val app: Application) {

    companion object {
        const val DEFAULT_BOOK = "默认"
        private val CHAPTER_MARKER = Regex("^\\[chapter:(\\d+)\\]\\s*$")
        private val EMO_HEAD = Regex("^(\\[\\[emo:[^\\]]*\\]\\])+")
        private val EMO_VALUE = Regex("\\[\\[emo:([^\\]]*)\\]\\]")
    }

    /** 音频缓存仓库（books 数据目录同根：data/audio/<书名>/） */
    private val audioCache: ReadAloudAudioCacheRepository by lazy { ReadAloudAudioCacheRepository(app) }

    // ---------------- 路径 ----------------

    fun dataDir(): File = File(TtsDirProvider.baseDir(app), "data").apply { mkdirs() }

    private fun rootFile(name: String): File = File(dataDir(), name)

    private fun bookDir(book: String): File = File(dataDir(), "books/$book")

    private fun bookFile(book: String, name: String): File = File(bookDir(book), name)

    private fun readText(f: File): String = runCatching {
        if (!f.exists()) "" else f.readText().removePrefix("\uFEFF").trim()
    }.getOrDefault("")

    private fun writeText(f: File, s: String): Boolean = runCatching {
        f.parentFile?.mkdirs()
        f.writeText(s)
        true
    }.getOrDefault(false)

    // ---------------- 状态（initializePluginState 移植） ----------------

    suspend fun loadState(): JueseState = withContext(Dispatchers.IO) { initializeState() }

    private fun initializeState(): JueseState {
        val liebiao = runCatching { JSONArray(readText(rootFile("liebiao.json"))) }
            .getOrDefault(JSONArray())
        val bookList = mutableListOf<String>()
        for (i in 0 until liebiao.length()) {
            val n = liebiao.optString(i).trim()
            if (n.isNotEmpty() && n !in bookList) bookList.add(n)
        }
        if (DEFAULT_BOOK !in bookList) bookList.add(DEFAULT_BOOK)
        writeText(rootFile("liebiao.json"), JSONArray(bookList).toString())

        var current = readText(rootFile("cunfang.txt"))
        if (current.isEmpty() || current !in bookList) current = DEFAULT_BOOK
        writeText(rootFile("cunfang.txt"), current)

        val shumingText = readText(bookFile(current, "shuming.$current.json"))
        var records = parseRecords(shumingText)
        if (shumingText.isEmpty() || shumingText == "[]") {
            writeText(bookFile(current, "shuming.$current.json"), "[]")
        }

        val charRec = readText(rootFile("characterRecords.json"))
        if (charRec.isEmpty() || charRec == "[]") {
            writeText(rootFile("characterRecords.json"), recordsJson(records))
        } else {
            records = parseRecords(charRec)
        }

        return JueseState(bookList, current, records)
    }

    suspend fun switchBook(newBook: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val st = initializeState()
        val newName = newBook.trim()
        if (newName.isEmpty()) return@withContext false to "书名不能为空"
        if (newName == st.currentBook) return@withContext true to "已是当前书籍"
        writeText(bookFile(st.currentBook, "shuming.${st.currentBook}.json"), recordsJson(st.records))
        val text = readText(bookFile(newName, "shuming.$newName.json"))
        val records = if (text.isEmpty() || text == "[]") emptyList() else parseRecords(text)
        writeText(rootFile("cunfang.txt"), newName)
        writeText(rootFile("characterRecords.json"), recordsJson(records))
        val list = st.bookList.toMutableList()
        if (newName !in list) list.add(newName)
        writeText(rootFile("liebiao.json"), JSONArray(list).toString())
        true to "已切换到 $newName"
    }

    suspend fun saveRecords(book: String, records: List<CharacterRecord>): Boolean =
        withContext(Dispatchers.IO) {
            val json = recordsJson(records)
            var ok = writeText(rootFile("characterRecords.json"), json)
            if (ok) {
                ok = writeText(bookFile(book, "shuming.$book.json"), json)
                writeText(rootFile("characterRecords_backup.json"), json)
            }
            ok
        }

    // ---------------- 记录 parse / json ----------------

    private fun parseRecords(raw: String): List<CharacterRecord> {
        if (raw.isEmpty() || raw == "[]") return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val chapters = mutableListOf<Int>()
                    o.optJSONArray("appearanceChapters")?.let { ac ->
                        for (k in 0 until ac.length()) {
                            val v = ac.optInt(k, Int.MIN_VALUE)
                            if (v != Int.MIN_VALUE) chapters.add(v)
                        }
                    }
                    add(
                        CharacterRecord(
                            name = o.optString("name"),
                            aliases = o.optString("aliases"),
                            roletype = o.optString("roletype").ifBlank { "核心" },
                            gender = o.optString("gender").ifBlank { "男" },
                            age = o.optString("age").ifBlank { "男青年" },
                            voice = o.optString("voice"),
                            lastAppearanceChapter = o.optInt("lastAppearanceChapter", -1),
                            appearanceCount = o.optInt("appearanceCount", 0),
                            appearanceChapters = chapters,
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun recordsJson(records: List<CharacterRecord>): String {
        val arr = JSONArray()
        records.forEach { r ->
            arr.put(
                JSONObject().apply {
                    put("name", r.name)
                    put("aliases", r.aliases)
                    put("roletype", r.roletype)
                    put("gender", r.gender)
                    put("age", r.age)
                    put("voice", r.voice)
                    put("lastAppearanceChapter", r.lastAppearanceChapter)
                    put("appearanceCount", r.appearanceCount)
                    val chaptersArr = JSONArray()
                    r.appearanceChapters.forEach { chaptersArr.put(it) }
                    put("appearanceChapters", chaptersArr)
                }
            )
        }
        return arr.toString()
    }

    // ---------------- 合并账本 ----------------

    private fun readMergeLog(book: String): MutableList<JSONObject> {
        val raw = readText(bookFile(book, "merge_log.$book.json"))
        if (raw.isEmpty()) return mutableListOf()
        return runCatching {
            val arr = JSONObject(raw).optJSONArray("ops") ?: JSONArray()
            buildList {
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { add(JSONObject(it.toString())) }
                }
            }.toMutableList()
        }.getOrDefault(mutableListOf())
    }

    private fun writeMergeLog(book: String, ops: List<JSONObject>) {
        val opsArr = JSONArray()
        ops.forEach { opsArr.put(it) }
        writeText(
            bookFile(book, "merge_log.$book.json"),
            JSONObject().apply { put("ops", opsArr) }.toString()
        )
    }

    private fun appendMergeOpInternal(book: String, op: JSONObject) {
        val lines = op.optJSONArray("lines") ?: JSONArray()
        if (lines.length() == 0) return
        val ops = readMergeLog(book)
        val chapter = op.optInt("chapter")
        val from = op.optString("from")
        val to = op.optString("to")
        val hit = ops.indexOfFirst {
            it.optString("status") == "active" &&
                it.optInt("chapter") == chapter &&
                it.optString("from") == from &&
                it.optString("to") == to
        }
        if (hit >= 0) ops[hit] = op else ops.add(op)
        writeMergeLog(book, ops)
    }

    /**
     * B23：脚本自动合并捕获（对齐 1.4.x `mergeLogCapture`）——快速命中/回跳命中/长文本命中各写一条
     * 'm' 凭据：chapter=命中章、from=合并方显示名、to=主名、aliases=随行别名、lines=本章台词行指纹。
     * 与改名/手动合并的 'u' 账分工：回滚逆 'm'（保留区同对跳过）、编辑保存只清本章 'm'、释放回放按行指纹回写。
     */
    suspend fun captureAutoMergeOp(
        book: String,
        chapter: Int,
        from: String,
        to: String,
        aliases: List<String>,
        via: String,
        lines: List<Pair<Int, String>>,
    ) = withContext(Dispatchers.IO) {
        if (book.isBlank() || from.isBlank() || to.isBlank() || from == to) return@withContext
        runCatching {
            val arr = JSONArray()
            lines.forEach { (i, text) ->
                arr.put(
                    JSONObject().apply {
                        put("i", i)
                        put("h", fnv1a(text))
                        put("len", text.length)
                    }
                )
            }
            if (arr.length() == 0) return@runCatching
            val ts = System.currentTimeMillis()
            appendMergeOpInternal(
                book,
                JSONObject().apply {
                    put("id", "m${ts}_c${chapter}_${fnv1a(from)}")
                    put("ts", ts)
                    put("chapter", chapter)
                    put("from", from)
                    put("to", to)
                    put("aliases", JSONArray().apply { aliases.filter { it.isNotBlank() }.forEach { put(it) } })
                    put("via", via)
                    put("lines", arr)
                    put("status", "active")
                },
            )
        }
    }

    suspend fun renameMergeLogTokens(book: String, oldName: String, newName: String) =
        withContext(Dispatchers.IO) {
            if (book.isBlank() || oldName.isBlank() || newName.isBlank() || oldName == newName) return@withContext
            val ops = readMergeLog(book)
            var changed = false
            ops.forEach { o ->
                if (o.optString("to") == oldName) { o.put("to", newName); changed = true }
                if (o.optString("from") == oldName) { o.put("from", newName); changed = true }
            }
            if (changed) writeMergeLog(book, ops)
        }

    /** 该主名在合并账本中的随行别名（status=active 的 from 匹配） */
    suspend fun collectMergeExtraAliases(book: String, alias: String): List<String> =
        withContext(Dispatchers.IO) {
            val out = LinkedHashSet<String>()
            readMergeLog(book).forEach { o ->
                if (o.optString("status") != "active") return@forEach
                val from = o.optString("from")
                if (!(from == alias || from.startsWith("$alias【第"))) return@forEach
                val als = o.optJSONArray("aliases") ?: return@forEach
                for (i in 0 until als.length()) {
                    val t = als.optString(i).trim()
                    if (t.isNotEmpty() && t != alias) out.add(t)
                }
            }
            out.toList()
        }

    // ---------------- 标记改写引擎（FNV 行指纹） ----------------

    private fun fnv1a(s: String): String {
        var h = 0x811c9dc5.toInt()
        for (c in s) {
            h = h xor c.code
            h += (h shl 1) + (h shl 4) + (h shl 7) + (h shl 8) + (h shl 24)
        }
        return "%08x".format(h)
    }

    private fun stripEmoRest(s: String): String = s.replace(EMO_HEAD, "")

    private fun speakerOf(line: String): String? {
        if (!line.startsWith("〖")) return null
        val e = line.indexOf('〗', 1)
        return if (e == -1) null else line.substring(1, e)
    }

    private data class LineRec(val i: Int, val h: String, val len: Int)

    private fun buildChapterLineMap(book: String, from: String): Map<Int, MutableList<LineRec>>? {
        val txt = readText(bookFile(book, "all_clean_text_$book.txt"))
        if (txt.isEmpty()) return null
        val lines = txt.split("\n")
        val map = LinkedHashMap<Int, MutableList<LineRec>>()
        var cur = -1
        var markerIdx = -1
        for (idx in lines.indices) {
            val m = CHAPTER_MARKER.find(lines[idx])
            if (m != null) {
                cur = m.groupValues[1].toIntOrNull() ?: continue
                markerIdx = idx
                continue
            }
            if (cur < 0 || speakerOf(lines[idx]) != from) continue
            val close = lines[idx].indexOf('〗', 1)
            val rest = if (close != -1) lines[idx].substring(close + 1) else ""
            val stripped = stripEmoRest(rest)
            map.getOrPut(cur) { mutableListOf() }
                .add(LineRec(idx - markerIdx - 1, fnv1a(stripped), stripped.length))
        }
        return map
    }

    private fun mutateBookLines(
        book: String,
        byChapter: Map<Int, List<LineRec>>,
        mutate: (String, String) -> String?,
    ): Pair<Int, Int> {
        val f = bookFile(book, "all_clean_text_$book.txt")
        val txt = readText(f)
        if (txt.isEmpty()) return 0 to 0
        val lines = txt.split("\n").toMutableList()
        val chapterStarts = HashMap<Int, Int>()
        lines.forEachIndexed { idx, l ->
            CHAPTER_MARKER.find(l)?.let { chapterStarts[it.groupValues[1].toIntOrNull() ?: return@let] = idx }
        }
        var replaced = 0
        var missed = 0
        for ((ch, list) in byChapter) {
            val start = chapterStarts[ch]
            if (start == null) {
                missed += list.size
                continue
            }
            for (rec in list) {
                val idx = start + 1 + rec.i
                if (idx !in lines.indices) {
                    missed++
                    continue
                }
                val line = lines[idx]
                if (speakerOf(line) == null) {
                    missed++
                    continue
                }
                val close = line.indexOf('〗', 1)
                val rest = if (close != -1) line.substring(close + 1) else ""
                if (fnv1a(stripEmoRest(rest)) != rec.h) {
                    missed++
                    continue
                }
                val nl = mutate(line, rest)
                if (nl == null) {
                    missed++
                    continue
                }
                lines[idx] = nl
                replaced++
            }
        }
        if (replaced > 0) writeText(f, lines.joinToString("\n"))
        return replaced to missed
    }

    private fun mutateCacheLines(
        book: String,
        byChapter: Map<Int, List<LineRec>>,
        mutate: (String, String) -> String?,
    ): Int {
        val f = bookFile(book, "chapter_cache.$book.json")
        val raw = readText(f)
        if (raw.isEmpty()) return 0
        return runCatching {
            val cache = JSONObject(raw)
            var total = 0
            for ((ch, list) in byChapter) {
                val keys = buildList { val it = cache.keys(); while (it.hasNext()) add(it.next()) }
                for (key in keys) {
                    val parts = key.split("|")
                    if (parts.size < 2 || parts.last() != ch.toString()) continue
                    val c = cache.optJSONObject(key) ?: continue
                    val scriptText = c.optString("scriptText")
                    if (scriptText.isEmpty()) continue
                    val body = scriptText.split("\n").toMutableList()
                    var changed = 0
                    for (rec in list) {
                        if (rec.i !in body.indices) continue
                        val line = body[rec.i]
                        if (speakerOf(line) == null) continue
                        val close = line.indexOf('〗', 1)
                        val rest = if (close != -1) line.substring(close + 1) else ""
                        if (fnv1a(stripEmoRest(rest)) != rec.h) continue
                        val nl = mutate(line, rest) ?: continue
                        body[rec.i] = nl
                        changed++
                    }
                    if (changed > 0) {
                        c.put("scriptText", body.joinToString("\n"))
                        total += changed
                    }
                }
            }
            if (total > 0) writeText(f, cache.toString())
            total
        }.getOrDefault(0)
    }

    /** 全量改名（改主名/合并跟随共用）；writeLog=true 记合并账本（可回放） */
    suspend fun rewriteMarkersAll(
        book: String,
        from: String,
        to: String,
        writeLog: Boolean,
    ): RewriteResult = withContext(Dispatchers.IO) {
        if (book.isBlank() || from.isBlank() || to.isBlank() || from == to) {
            return@withContext RewriteResult(0, emptyList())
        }
        val map = buildChapterLineMap(book, from)
            ?: return@withContext RewriteResult(0, emptyList())
        val (replaced, _) = mutateBookLines(book, map) { _, rest -> "〖$to〗$rest" }
        mutateCacheLines(book, map) { _, rest -> "〖$to〗$rest" }
        if (writeLog) {
            val ts = System.currentTimeMillis()
            for ((ch, list) in map) {
                appendMergeOpInternal(
                    book,
                    JSONObject().apply {
                        put("id", "u${ts}_c${ch}_${fnv1a(from)}")
                        put("ts", ts)
                        put("chapter", ch)
                        put("from", from)
                        put("to", to)
                        put("via", "")
                        put(
                            "lines",
                            JSONArray().apply {
                                list.forEach { r ->
                                    put(
                                        JSONObject().apply {
                                            put("i", r.i)
                                            put("h", r.h)
                                            put("len", r.len)
                                        }
                                    )
                                }
                            }
                        )
                        put("status", "active")
                    }
                )
            }
        }
        RewriteResult(replaced, map.keys.sorted())
    }

    /** 释放回放：按合并账本把该别名的台词精确改回 releaseName；账本标记 released */
    suspend fun replayMergeOps(book: String, alias: String): ReplayResult =
        withContext(Dispatchers.IO) {
            val rawOps = readMergeLog(book)
            val matched = mutableListOf<Int>()
            val byChapter = LinkedHashMap<Int, MutableList<LineRec>>()
            val via = LinkedHashSet<String>()
            rawOps.forEachIndexed { i, o ->
                if (o.optString("status") != "active") return@forEachIndexed
                val from = o.optString("from")
                if (!(from == alias || from.startsWith("$alias【第"))) return@forEachIndexed
                matched += i
                val ch = o.optInt("chapter", -1)
                val list = o.optJSONArray("lines") ?: JSONArray()
                for (k in 0 until list.length()) {
                    val r = list.optJSONObject(k) ?: continue
                    byChapter.getOrPut(ch) { mutableListOf() }
                        .add(LineRec(r.optInt("i"), r.optString("h"), r.optInt("len")))
                }
                o.optString("via").takeIf { it.isNotEmpty() }?.let { via += it }
            }
            if (matched.isEmpty()) {
                return@withContext ReplayResult(0, 0, emptyList(), emptyList())
            }
            val (replaced, missed) = mutateBookLines(book, byChapter) { line, rest ->
                if (speakerOf(line) == alias) line else "〖$alias〗$rest"
            }
            mutateCacheLines(book, byChapter) { line, rest ->
                if (speakerOf(line) == alias) line else "〖$alias〗$rest"
            }
            rawOps.forEachIndexed { i, o ->
                if (i in matched) {
                    o.put("status", "released")
                    o.put("releasedAt", System.currentTimeMillis())
                }
            }
            writeMergeLog(book, rawOps)
            ReplayResult(replaced, missed, byChapter.keys.sorted(), via.toList())
        }

    // ---------------- 词库（入库） ----------------

    /** 追加词库（bare_words.json / special_words.json），去重；返回新增数 */
    suspend fun appendWordLibrary(fileName: String, words: List<String>): Int =
        withContext(Dispatchers.IO) {
            val f = rootFile(fileName)
            val lib = runCatching { JSONArray(readText(f)) }.getOrDefault(JSONArray())
            val existing = LinkedHashSet<String>()
            for (i in 0 until lib.length()) {
                val v = lib.optString(i).trim()
                if (v.isNotEmpty()) existing.add(v)
            }
            var added = 0
            words.forEach { w ->
                val t = w.trim()
                if (t.isNotEmpty() && existing.add(t)) added++
            }
            if (added > 0) writeText(f, JSONArray(existing.toList()).toString())
            added
        }

    suspend fun loadActiveVoiceBanks(): List<String> = withContext(Dispatchers.IO) {
        runCatching {
            val f = File(TtsDirProvider.baseDir(app), "_store/readaloud_ext.json")
            if (!f.exists()) return@runCatching emptyList()
            val arr = JSONObject(f.readText().removePrefix("\uFEFF"))
                .optJSONArray("activeVoiceBanks") ?: return@runCatching emptyList()
            buildList {
                for (i in 0 until arr.length()) {
                    val v = arr.optString(i).trim()
                    if (v.isNotEmpty()) add(v)
                }
            }
        }.getOrDefault(emptyList())
    }

    /** 标签池 = 仅"已选中"声线池（配置列表勾选的分组）内的标签；未选池返回空 */
    suspend fun loadVoiceTagPool(): List<String> = withContext(Dispatchers.IO) {
        val banks = loadActiveVoiceBanks().toSet()
        if (banks.isEmpty()) return@withContext emptyList()
        val out = LinkedHashSet<String>()
        runCatching {
            val voices = TtsConfigStore.loadVoices(app)
            for (g in 0 until voices.length()) {
                val grp = voices.optJSONObject(g) ?: continue
                val gname = grp.optJSONObject("group")?.optString("name").orEmpty()
                if (gname !in banks) continue
                val list = grp.optJSONArray("list") ?: continue
                for (i in 0 until list.length()) {
                    val cfg = list.optJSONObject(i)?.optJSONObject("config") ?: continue
                    val tag = cfg.optJSONObject("speechRule")?.optString("tag").orEmpty().trim()
                    if (tag.isNotEmpty()) out.add(tag)
                }
            }
        }
        out.toList()
    }

    // ---------------- UI 状态持久化 ----------------

    private fun uiStateFile(): File = File(dataDir(), "ui_state.json")

    suspend fun loadCharacterFilter(): String = withContext(Dispatchers.IO) {
        runCatching {
            JSONObject(readText(uiStateFile())).optString("characterFilter")
        }.getOrNull().orEmpty().ifBlank { "全部" }
    }

    suspend fun saveCharacterFilter(value: String) = withContext(Dispatchers.IO) {
        runCatching {
            val o = runCatching { JSONObject(readText(uiStateFile())) }
                .getOrElse { JSONObject() }
            o.put("characterFilter", value)
            writeText(uiStateFile(), o.toString())
        }
        Unit
    }

    /** 最近朗读的书名（朗读会话启动时记录，B10.5·Q4） */
    suspend fun loadRecentReadBook(): String = withContext(Dispatchers.IO) {
        runCatching { JSONObject(readText(uiStateFile())).optString("recentReadBook") }
            .getOrDefault("")
    }

    /** 记录最近朗读的书名（ui_state.json；B10.5·Q4） */
    suspend fun setRecentReadBook(book: String) = withContext(Dispatchers.IO) {
        val name = book.trim()
        if (name.isEmpty()) return@withContext
        runCatching {
            val o = runCatching { JSONObject(readText(uiStateFile())) }
                .getOrElse { JSONObject() }
            o.put("recentReadBook", name)
            writeText(uiStateFile(), o.toString())
        }
        Unit
    }

    /**
     * Q4：把「当前书」自动切到最近朗读的书（书籍管理/角色管理非嵌入入口调用）。
     * 仅在 最近朗读 ≠ 当前书 且仍在书架索引（liebiao）内时切换；switchBook 同步 cunfang 与根镜像。
     */
    suspend fun syncBookToRecentRead(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val recent = loadRecentReadBook()
            if (recent.isBlank()) return@runCatching false
            val st = loadState()
            if (recent == st.currentBook || recent !in st.bookList) return@runCatching false
            switchBook(recent).first
        }.onFailure {
            if (it is CancellationException) throw it
        }.getOrDefault(false)
    }

    /** 章节号列表（从剧本 [chapter:N] 标记读取） */
    suspend fun loadChapters(book: String): List<Int> = withContext(Dispatchers.IO) {
        val txt = readText(bookFile(book, "all_clean_text_$book.txt"))
        if (txt.isEmpty()) return@withContext emptyList()
        buildList {
            txt.split("\n").forEach { l ->
                CHAPTER_MARKER.find(l)?.groupValues?.get(1)?.toIntOrNull()?.let { if (it !in this) add(it) }
            }
        }.sorted()
    }

    // ---------------- 书籍管理：剧本读取 / 修改 / 删除 ----------------

    /** 读取某章的剧本行（absIndex=整书文件行号，供修改定位） */
    suspend fun loadChapterScript(book: String, chapter: Int): List<ScriptLineRow> =
        withContext(Dispatchers.IO) {
            val txt = readText(bookFile(book, "all_clean_text_$book.txt"))
            if (txt.isEmpty()) return@withContext emptyList()
            val lines = txt.split("\n")
            var start = -1
            var end = lines.size
            for (i in lines.indices) {
                val m = CHAPTER_MARKER.find(lines[i]) ?: continue
                val ch = m.groupValues[1].toIntOrNull() ?: continue
                if (ch == chapter) {
                    start = i
                } else if (start >= 0 && i > start) {
                    end = i
                    break
                }
            }
            if (start < 0) return@withContext emptyList()
            parseScriptLines(lines.subList(start + 1, end), base = start + 1)
        }

    /**
     * B8.3 回填 / Q3 换源复用：某章剧本文本候选（按可用性排序，去重）。
     *  ① `all_clean_text_<书>.txt` 的 [chapter:N] 段（与批量合成同一枚举，条目序号一致）；
     *  ② `chapter_cache.<书>.json` 精确键 `bookUrl|chapter`（state=success）；
     *  ③ 同章任意键（换源后 URL 漂移：旧源键仍在，由调用方做文本对齐校验后复用）。
     * 调用方逐个做 ScriptFileBackfill.align，首个对齐成功者胜出。
     */
    suspend fun loadChapterScriptCandidates(
        book: String,
        bookUrl: String,
        chapter: Int,
    ): List<List<ScriptLineRow>> = withContext(Dispatchers.IO) {
        if (book.isBlank()) return@withContext emptyList()
        val out = ArrayList<List<ScriptLineRow>>()
        val seen = HashSet<String>()
        fun addRows(rows: List<ScriptLineRow>) {
            if (rows.isEmpty()) return
            val sig = rows.joinToString("|") { "${it.speaker}:${it.text}:${it.emotion}" }.hashCode().toString()
            if (seen.add(sig)) out.add(rows)
        }
        addRows(loadChapterScript(book, chapter))
        val cache = runCatching { JSONObject(readText(bookFile(book, "chapter_cache.$book.json"))) }.getOrNull()
        if (cache != null) {
            fun rowsOf(key: String): List<ScriptLineRow>? {
                val entry = cache.optJSONObject(key) ?: return null
                if (entry.optString("state") != "success") return null
                val scriptText = entry.optString("scriptText")
                if (scriptText.isBlank()) return null
                return parseScriptLines(scriptText.split("\n"), base = 0)
            }
            if (bookUrl.isNotBlank()) rowsOf("$bookUrl|$chapter")?.let { addRows(it) }
            val keys = cache.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (k.substringAfterLast('|', "").toIntOrNull() == chapter) {
                    rowsOf(k)?.let { addRows(it) }
                }
            }
        }
        out
    }

    /**
     * Q3 换源自愈：为当前 bookUrl 补写该章缓存条目（已存在则不动），
     * 使「换源后复用」的章节状态落到最新 URL 键，后续读取走精确命中。
     */
    suspend fun ensureChapterCacheForUrl(
        book: String,
        bookUrl: String,
        chapter: Int,
        scriptText: String,
    ) {
        if (book.isBlank() || bookUrl.isBlank() || scriptText.isBlank()) return
        withContext(Dispatchers.IO) {
            runCatching {
                val cacheFile = bookFile(book, "chapter_cache.$book.json")
                val cache = runCatching { JSONObject(readText(cacheFile)) }.getOrDefault(JSONObject())
                val key = "$bookUrl|$chapter"
                if (cache.has(key)) return@runCatching
                cache.put(
                    key,
                    JSONObject().apply {
                        put("state", "success")
                        put("scriptText", scriptText)
                    },
                )
                writeText(cacheFile, cache.toString())
            }
        }
    }

    /** 书名解析（bookUrl → Room 书表）；B8.3 回填定位 books/<书名>/ 目录用 */
    suspend fun loadBookName(bookUrl: String): String = withContext(Dispatchers.IO) {
        if (bookUrl.isBlank()) return@withContext ""
        runCatching { appDb.bookDao.getBook(bookUrl)?.name.orEmpty() }.getOrDefault("")
    }

    /** 剧本行解析（all_clean_text 章节段 / chapter_cache.scriptText 共用；base=首个元素对应的绝对行号） */
    private fun parseScriptLines(lines: List<String>, base: Int): List<ScriptLineRow> = buildList {
        lines.forEachIndexed { i, raw ->
            if (raw.isBlank()) return@forEachIndexed
            val spk = speakerOf(raw)
            val close = raw.indexOf('〗', 1)
            val rest = if (spk != null && close != -1) raw.substring(close + 1) else raw
            add(
                ScriptLineRow(
                    absIndex = base + i,
                    speaker = spk.orEmpty(),
                    text = stripEmoRest(rest),
                    emotion = EMO_VALUE.find(rest)?.groupValues?.getOrNull(1).orEmpty(),
                )
            )
        }
    }

    /** 修改某章若干行的说话标记；同步 chapter_cache 该章 scriptText；剔除该章合并账本 op */
    suspend fun rewriteChapterSpeakers(
        book: String,
        chapter: Int,
        changes: Map<Int, String>,
    ): Boolean = withContext(Dispatchers.IO) {
        if (changes.isEmpty()) return@withContext false
        val f = bookFile(book, "all_clean_text_$book.txt")
        val txt = readText(f)
        if (txt.isEmpty()) return@withContext false
        val lines = txt.split("\n").toMutableList()
        var start = -1
        var end = lines.size
        for (i in lines.indices) {
            val m = CHAPTER_MARKER.find(lines[i]) ?: continue
            val ch = m.groupValues[1].toIntOrNull() ?: continue
            if (ch == chapter) {
                start = i
            } else if (start >= 0 && i > start) {
                end = i
                break
            }
        }
        if (start < 0) return@withContext false
        var changed = 0
        changes.forEach chgLoop@{ (absIdx, newSpk) ->
            if (absIdx <= start || absIdx >= end) return@chgLoop
            val line = lines.getOrNull(absIdx) ?: return@chgLoop
            val close = line.indexOf('〗', 1)
            if (!line.startsWith("〖") || close == -1) return@chgLoop
            lines[absIdx] = "〖$newSpk〗" + line.substring(close + 1)
            changed++
        }
        if (changed == 0) return@withContext false
        writeText(f, lines.joinToString("\n"))
        // 同步 chapter_cache 该章 scriptText
        runCatching {
            val cacheFile = bookFile(book, "chapter_cache.$book.json")
            val raw = readText(cacheFile)
            if (raw.isNotEmpty()) {
                val cache = JSONObject(raw)
                val keys = buildList { val it = cache.keys(); while (it.hasNext()) add(it.next()) }
                var t = 0
                keys.forEach keyLoop@{ key ->
                    val parts = key.split("|")
                    if (parts.size < 2 || parts.last() != chapter.toString()) return@keyLoop
                    val c = cache.optJSONObject(key) ?: return@keyLoop
                    val script = c.optString("scriptText")
                    if (script.isEmpty()) return@keyLoop
                    val body = script.split("\n").toMutableList()
                    changes.forEach chg2@{ (absIdx, newSpk) ->
                        val rel = absIdx - (start + 1)
                        if (rel < 0 || rel >= body.size) return@chg2
                        val line = body[rel]
                        val close = line.indexOf('〗', 1)
                        if (!line.startsWith("〖") || close == -1) return@chg2
                        body[rel] = "〖$newSpk〗" + line.substring(close + 1)
                        t++
                    }
                    c.put("scriptText", body.joinToString("\n"))
                }
                if (t > 0) writeText(cacheFile, cache.toString())
            }
        }
        // 剔除该章「自动合并凭据」op（对齐 1.4.x：只清本章 'm'；改名/手动合并账不随编辑清除；人物记录不动）
        runCatching {
            val ops = readMergeLog(book)
            val kept = ops.filterNot {
                it.optInt("chapter", -1) == chapter && it.optString("id").startsWith("m")
            }
            if (kept.size != ops.size) writeMergeLog(book, kept)
        }
        true
    }

    /**
     * 删除章节剧本：轻量=剧本+缓存+音频+DB 记录；回滚=另含 合并账本/人物逆向（用于"接续到末尾的连续章"）。
     * B22：回滚统一按「≥ min(chapters) 连续尾段」口径清理（缓存/账本/人物与剧本同一集合）；
     *      人物出场逆向不再以「合并账本非空」为门控（无 op 也必须执行，对齐 1.4.x rollbackChaptersFrom）；
     *      并把 analyze_state 分析前沿回退到删除区间之前的最大保留章（连续判定不再指向已回滚章节）。
     * B25：清理集合补上「DB 分析/分段 + 音频缓存」——原缺失导致点朗读时「缓存命中→存量补写」复活已回滚章节。
     */
    suspend fun deleteChapterScripts(
        book: String,
        chapters: Set<Int>,
        rollback: Boolean,
    ): Boolean = withContext(Dispatchers.IO) {
        if (chapters.isEmpty()) return@withContext false
        val floor = chapters.minOrNull() ?: return@withContext false
        // B25：候选 bookUrl（当前书名 + 表内可归属旧键）；与 deleteBookAssets 同口径，兼容换源遗留
        val urls = linkedSetOf<String>()
        runCatching {
            appDb.bookDao.getBookByName(book)?.bookUrl?.takeIf { it.isNotBlank() }?.let(urls::add)
        }
        runCatching {
            val tableUrls = appDb.chapterSpeechDao.distinctAnalysisBookUrls() +
                    appDb.chapterSpeechDao.distinctSegmentBookUrls()
            tableUrls.distinct().forEach { u ->
                if (u.isNotBlank() && appDb.bookDao.getBook(u)?.name == book) urls.add(u)
            }
        }
        val f = bookFile(book, "all_clean_text_$book.txt")
        val txt = readText(f)
        var changed = false
        if (txt.isNotEmpty()) {
            val lines = txt.split("\n")
            val kept = mutableListOf<String>()
            var cur = -1
            lines.forEach lineLoop@{ l ->
                val m = CHAPTER_MARKER.find(l)
                if (m != null) {
                    cur = m.groupValues[1].toIntOrNull() ?: -1
                    if (cur in chapters) {
                        changed = true
                        return@lineLoop
                    }
                }
                if (cur in chapters) {
                    changed = true
                    return@lineLoop
                }
                kept.add(l)
            }
            if (changed) writeText(f, kept.joinToString("\n"))
        }
        // 缓存（回滚=≥floor 全清；轻量=所列章节）
        runCatching {
            val cacheFile = bookFile(book, "chapter_cache.$book.json")
            val raw = readText(cacheFile)
            if (raw.isNotEmpty()) {
                val cache = JSONObject(raw)
                val keys = buildList { val it = cache.keys(); while (it.hasNext()) add(it.next()) }
                keys.forEach { key ->
                    val ch = key.split("|").lastOrNull()?.toIntOrNull() ?: return@forEach
                    val hit = if (rollback) ch >= floor else ch in chapters
                    if (hit) {
                        key.substringBefore('|').takeIf { it.isNotBlank() }?.let(urls::add)
                        cache.remove(key)
                    }
                }
                writeText(cacheFile, cache.toString())
            }
        }
        // 合并账本 + 人物逆向（回滚剔除 ≥floor 的 op；人物出场清理独立于账本是否存在）
        runCatching {
            val ops = readMergeLog(book)
            if (rollback) {
                val removed = ops.filter { it.optInt("chapter", -1) >= floor }
                val kept = ops.filterNot { it.optInt("chapter", -1) >= floor }
                if (removed.isNotEmpty()) writeMergeLog(book, kept)
                rollbackCharacters(book, removed, kept, floor)
            }
        }
        // B25：DB 清零（分析/分段）＋音频缓存清理（轻量=所列章节；回滚=≥floor）——
        // 与剧本/缓存/账本/人物同一集合；原缺失导致点朗读时「缓存命中 → 存量补写」复活已回滚章节。
        runCatching {
            urls.forEach { u ->
                if (rollback) {
                    appDb.chapterSpeechDao.deleteSegmentsFrom(u, floor)
                    appDb.chapterSpeechDao.deleteAnalysesFrom(u, floor)
                } else {
                    chapters.forEach { ch -> appDb.chapterSpeechDao.deleteChapter(u, ch) }
                }
            }
        }
        runCatching {
            audioCache.bookDir(book).listFiles()
                ?.mapNotNull { it.name.toIntOrNull() }
                ?.map { it - 1 } // 音频目录名 = chapterIndex + 1
                ?.filter { ch -> if (rollback) ch >= floor else ch in chapters }
                ?.forEach { ch -> audioCache.deleteChapter(book, ch) }
        }
        // B17 分析前沿回退：回滚后前沿 = 保留区最大章（原值指向 ≥floor 的已回滚章节会破坏后续「连续」判定）
        if (rollback) {
            runCatching {
                val stateFile = bookFile(book, "analyze_state.$book.json")
                if (stateFile.exists()) {
                    val curState = runCatching { JSONObject(readText(stateFile)).optInt("lastChapter", -1) }
                        .getOrDefault(-1)
                    var maxKept = -1
                    readText(f).split("\n").forEach { l ->
                        CHAPTER_MARKER.find(l)?.groupValues?.get(1)?.toIntOrNull()?.let { c ->
                            if (c < floor && c > maxKept) maxKept = c
                        }
                    }
                    if (curState > maxKept) {
                        writeText(
                            stateFile,
                            JSONObject().apply {
                                put("lastChapter", maxKept)
                                put("updatedAt", System.currentTimeMillis())
                            }.toString(),
                        )
                    }
                }
            }
        }
        true
    }

    /** 人物逆向：按被删合并账本回退别名/重建被合并角色，并按回滚下界清理出场（≥floor 全清，对齐 1.4.x c<X 保留口径） */
    private fun rollbackCharacters(
        book: String,
        removedOps: List<JSONObject>,
        keptOps: List<JSONObject>,
        floor: Int,
    ) {
        runCatching {
            val current = parseRecords(readText(bookFile(book, "shuming.$book.json"))).toMutableList()
            MergeRollbackCore.reverseOps(
                current,
                removedOps = removedOps.map(::mergeOpViewOf),
                keptOps = keptOps.map(::mergeOpViewOf),
            )
            MergeRollbackCore.purgeAppearances(current, floor)
            val json = recordsJson(current)
            writeText(bookFile(book, "shuming.$book.json"), json)
            // 根镜像仅在本书为「当前书」时同步（与 saveBookRecords / 原版 writeBookCharacters 同款门控）
            syncRootMirror(book, json)
        }
    }

    /** 合并账本 op → 逆向核心视图（纯函数入参） */
    private fun mergeOpViewOf(op: JSONObject): MergeRollbackCore.OpView =
        MergeRollbackCore.OpView(
            id = op.optString("id"),
            status = op.optString("status"),
            from = op.optString("from"),
            to = op.optString("to"),
            aliases = op.optJSONArray("aliases")?.let { a ->
                buildList {
                    for (i in 0 until a.length()) {
                        a.optString(i).takeIf { it.isNotBlank() }?.let(::add)
                    }
                }
            } ?: emptyList(),
        )

    /**
     * 删除书籍全套资产（B10.3·U7）：
     *  ① 剧本目录 books/<书名>/（含合并账本）+ ② 音频缓存 audio/<书名>/
     *  ③ DB 按书数据：分析 / 分段 / 绑定（含换源遗留旧 bookUrl 键）
     *  ④ 书架索引 liebiao.json；若为当前书 → cunfang 切回「默认」并同步根镜像。
     * 不动书架本体；「默认」不可删除。
     */
    suspend fun deleteBookAssets(book: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val name = book.trim()
        if (name.isEmpty()) return@withContext false to "书名不能为空"
        if (name == DEFAULT_BOOK) return@withContext false to "「默认」不可删除"
        val st = initializeState()
        // 候选 bookUrl：章节缓存键（留住换源遗留）+ Room 当前 url + 表内可归属旧键
        val urls = linkedSetOf<String>()
        runCatching {
            val cache = JSONObject(readText(bookFile(name, "chapter_cache.$name.json")))
            val keys = cache.keys()
            while (keys.hasNext()) {
                val u = keys.next().substringBefore('|')
                if (u.isNotBlank()) urls.add(u)
            }
        }
        runCatching {
            appDb.bookDao.getBookByName(name)?.bookUrl?.takeIf { it.isNotBlank() }?.let(urls::add)
        }
        runCatching {
            val tableUrls = appDb.chapterSpeechDao.distinctAnalysisBookUrls() +
                    appDb.chapterSpeechDao.distinctSegmentBookUrls()
            tableUrls.distinct().forEach { u ->
                if (u.isNotBlank() && appDb.bookDao.getBook(u)?.name == name) urls.add(u)
            }
        }
        // 当前书 → 回退「默认」（同步根镜像）
        if (st.currentBook == name) {
            val defText = readText(bookFile(DEFAULT_BOOK, "shuming.$DEFAULT_BOOK.json"))
            val defRecords = if (defText.isEmpty() || defText == "[]") emptyList() else parseRecords(defText)
            writeText(rootFile("cunfang.txt"), DEFAULT_BOOK)
            writeText(rootFile("characterRecords.json"), recordsJson(defRecords))
        }
        // 书架索引（分析侧 liebiao，不动书架本体）
        writeText(rootFile("liebiao.json"), JSONArray(st.bookList.filterNot { it == name }).toString())
        // 目录清零
        val dir = bookDir(name)
        if (dir.exists()) dir.deleteRecursively()
        audioCache.deleteBook(name)
        // DB 清零（逐 bookUrl：分析 / 分段 / 绑定）
        urls.forEach { u ->
            appDb.chapterSpeechDao.deleteBookSegments(u)
            appDb.chapterSpeechDao.deleteBookAnalyses(u)
            appDb.readAloudVoiceDao.deleteBindingsByBookUrl(u)
        }
        true to "已删除「$name」全部数据"
    }

    // ---------------- 书籍资产导出 / 导入（B10.4.2·U10） ----------------

    /**
     * 导出所选书籍资产为 zip 流：打包 `books/<书名>/` 目录树（角色记录 / 剧本 / 章节缓存 / 合并账本；
     * 不含音频）。返回实际导出的书籍数。
     */
    suspend fun exportBooksZip(books: List<String>, out: OutputStream): Int = withContext(Dispatchers.IO) {
        var count = 0
        ZipOutputStream(BufferedOutputStream(out)).use { zos ->
            books.distinct().forEach { book ->
                val dir = bookDir(book)
                if (!dir.isDirectory) return@forEach
                var wrote = false
                dir.walkTopDown().forEach { f ->
                    if (!f.isFile) return@forEach
                    val rel = f.relativeTo(dir).path.replace(File.separatorChar, '/')
                    zos.putNextEntry(ZipEntry("books/$book/$rel"))
                    f.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                    wrote = true
                }
                if (wrote) count++
            }
        }
        count
    }

    /** 导入书籍资产 zip：解包 `books/<书名>/…` 到数据目录（覆盖同名文件）并追加 liebiao；返回导入结论 */
    suspend fun importBooksZip(input: InputStream): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        runCatching {
            val names = linkedSetOf<String>()
            var files = 0
            ZipInputStream(BufferedInputStream(input)).use { zis ->
                while (true) {
                    val e = zis.nextEntry ?: break
                    val name = e.name.replace('\\', '/')
                    if (e.isDirectory || !name.startsWith("books/")) {
                        zis.closeEntry()
                        continue
                    }
                    val parts = name.split('/')
                    // 防目录穿越：books/<书名>/<相对路径>，且不含 . / ..
                    if (parts.size < 3 || parts.any { it.isBlank() || it == ".." || it == "." }) {
                        zis.closeEntry()
                        continue
                    }
                    val book = parts[1]
                    val rel = parts.drop(2).joinToString("/")
                    val target = File(bookDir(book), rel)
                    target.parentFile?.mkdirs()
                    target.outputStream().use { zis.copyTo(it) }
                    names.add(book)
                    files++
                    zis.closeEntry()
                }
            }
            if (files == 0) return@runCatching false to "压缩包内没有可导入的书籍数据"
            names.forEach { runCatching { ensureBookInList(it) } }
            true to "已导入 ${names.size} 本书（${files} 个文件）"
        }.getOrElse { false to "导入失败：${it.localizedMessage ?: it.javaClass.simpleName}" }
    }

    // ---------------- 配音前缀工具 ----------------

    fun expectedVoicePrefix(roletype: String, gender: String, age: String): String =
        VoiceBankRoleType.tagPrefix(roletype, gender, age)

    /** 从标签提取前缀（用于"音色与性别/年龄不符"判断） */
    fun voiceAgePrefix(voice: String): String {
        val m = Regex("^(路人)?(男童|女童|少年|少女|男青年|女青年|男中年|女中年|男老年|女老年|特殊男|特殊女)").find(voice)
        return m?.value.orEmpty()
    }

    // ---------------- 分析管线（V3）数据接口 ----------------

    /** 读取指定书籍的角色记录（优先 books/<书名>/shuming.<书名>.json；当前书缺文件时回退根镜像） */
    suspend fun loadBookRecords(book: String): List<CharacterRecord> = withContext(Dispatchers.IO) {
        val perBook = parseRecords(readText(bookFile(book, "shuming.$book.json")))
        if (perBook.isNotEmpty()) return@withContext perBook
        val current = readText(rootFile("cunfang.txt"))
        if (current == book) parseRecords(readText(rootFile("characterRecords.json"))) else emptyList()
    }

    /** 保存指定书籍的角色记录（只写本书文件；当前书时同步根镜像+备份，避免踩“当前书”语义） */
    suspend fun saveBookRecords(book: String, records: List<CharacterRecord>): Boolean =
        withContext(Dispatchers.IO) {
            val json = recordsJson(records)
            var ok = writeText(bookFile(book, "shuming.$book.json"), json)
            if (ok && readText(rootFile("cunfang.txt")).trim() == book) {
                ok = syncRootMirror(book, json)
            }
            ok
        }

    /** B24：当前书时同步根镜像（characterRecords.json + 备份）；非当前书返回 false（调用方自行决定是否关心）。 */
    private fun syncRootMirror(book: String, json: String): Boolean {
        if (readText(rootFile("cunfang.txt")).trim() != book) return false
        val ok = writeText(rootFile("characterRecords.json"), json)
        writeText(rootFile("characterRecords_backup.json"), json)
        return ok
    }

    /**
     * B17：最近一次「完成解析」的章节（0 基；-1=无）——连续性判定用。
     * 语义：顺读接续（最近解析章 == 上一章）→ true；跳读/回跳 → false。
     * 由 新分析（管线完成）/ 回填复用 / 缓存命中 三条路经 [markChapterResolved] 推进。
     * （取代旧实现：以 all_clean_text 的 [chapter:N] 最大标记为准——旧残留会干扰判定）
     */
    suspend fun lastResolvedChapter(book: String): Int = withContext(Dispatchers.IO) {
        if (book.isBlank()) return@withContext -1
        runCatching {
            val f = bookFile(book, "analyze_state.$book.json")
            if (!f.exists()) -1 else JSONObject(readText(f)).optInt("lastChapter", -1)
        }.getOrDefault(-1)
    }

    /**
     * B17：标记某章完成解析（三条路共用；落盘 <书>/analyze_state.<书>.json）。
     * B27：单调推进——仅当 [chapterIndex] 大于当前前沿才写。播放期窗口/扫掠任务为乱序执行
     *   （旧章的缓存命中任务会夹在新章任务之间），原实现会把「最近完成解析章」倒写回旧值，
     *   导致下一章误判「不连续」（跳读 → 第1阶段降级本地规则）。回滚的前沿回退不经本函数
     *   （deleteChapterScripts 直写），不受此约束。
     */
    suspend fun markChapterResolved(book: String, chapterIndex: Int) {
        if (book.isBlank()) return
        withContext(Dispatchers.IO) {
            runCatching {
                val f = bookFile(book, "analyze_state.$book.json")
                val o = runCatching { JSONObject(readText(f)) }.getOrDefault(JSONObject())
                if (chapterIndex <= o.optInt("lastChapter", -1)) return@runCatching
                o.put("lastChapter", chapterIndex)
                o.put("updatedAt", System.currentTimeMillis())
                writeText(f, o.toString())
            }
        }
    }

    /** 读取「已选中」声线库分组（activeVoiceBanks ↔ voices.json；tags 保持列表顺序=配置列表显示顺序，首=置顶） */
    suspend fun loadActiveVoiceGroups(): List<VoiceGroupInfo> = withContext(Dispatchers.IO) {
        val banks = loadActiveVoiceBanks().toSet()
        if (banks.isEmpty()) return@withContext emptyList()
        val out = ArrayList<VoiceGroupInfo>()
        runCatching {
            val voices = TtsConfigStore.loadVoices(app)
            for (g in 0 until voices.length()) {
                val grp = voices.optJSONObject(g) ?: continue
                val info = grp.optJSONObject("group") ?: continue
                val name = info.optString("name")
                if (name.isBlank() || name !in banks) continue
                val tags = ArrayList<String>()
                val list = grp.optJSONArray("list") ?: continue
                for (i in 0 until list.length()) {
                    val tag = list.optJSONObject(i)?.optJSONObject("config")
                        ?.optJSONObject("speechRule")?.optString("tag").orEmpty().trim()
                    if (tag.isNotEmpty()) tags.add(tag)
                }
                if (tags.isEmpty()) continue
                out.add(
                    VoiceGroupInfo(
                        name = name,
                        roleType = info.optString("roleType").trim(),
                        tags = tags,
                    ),
                )
            }
        }
        out
    }


    // ---------------- 分析管线（V3）文件产物 ----------------

    /**
     * 写入/替换 某章剧本（[chapter:N] 标记+行内容；重析=原地替换旧段）；同步 chapter_cache。
     * B22：写回按章号升序重组（对齐 1.4.x globalLibSerialize「按章号升序，保证追加时按顺序存储」；
     * 跳章/回跳补析不再把新段追加到文件尾造成乱序——顺带自愈历史乱序）。
     */
    suspend fun saveChapterScript(
        book: String,
        chapter: Int,
        bookUrl: String,
        scriptText: String,
    ): Boolean = withContext(Dispatchers.IO) {
        if (book.isBlank() || scriptText.isBlank()) return@withContext false
        runCatching {
            val f = bookFile(book, "all_clean_text_$book.txt")
            val raw = if (f.exists()) readText(f) else ""
            val (prefix, sections) = ScriptSectionStore.parse(raw)
            sections[chapter] = scriptText.split("\n").filter { it.isNotEmpty() }.toMutableList()
            writeText(f, ScriptSectionStore.serialize(prefix, sections))
            // 章节缓存（键=bookUrl|chapter；与既有同步逻辑同构）
            val cacheFile = bookFile(book, "chapter_cache.$book.json")
            val cache = runCatching { JSONObject(readText(cacheFile)) }.getOrDefault(JSONObject())
            val key = "$bookUrl|$chapter"
            cache.put(
                key,
                JSONObject().apply {
                    put("state", "success")
                    put("scriptText", scriptText)
                },
            )
            writeText(cacheFile, cache.toString())
            true
        }.getOrDefault(false)
    }

    /** 各章剧本行数（一次读文件；音频管理页统计「已合成/总数」用） */
    suspend fun loadChapterLineCounts(book: String): Map<Int, Int> = withContext(Dispatchers.IO) {
        val txt = readText(bookFile(book, "all_clean_text_$book.txt"))
        if (txt.isEmpty()) return@withContext emptyMap()
        val counts = mutableMapOf<Int, Int>()
        var current: Int? = null
        txt.split("\n").forEach { l ->
            val m = CHAPTER_MARKER.find(l)
            if (m != null) {
                current = m.groupValues[1].toIntOrNull()
                current?.let { counts.putIfAbsent(it, 0) }
            } else if (l.isNotBlank() && current != null) {
                counts[current!!] = (counts[current!!] ?: 0) + 1
            }
        }
        counts
    }

    /** 章节标题（书名 → chapter_cache 的 bookUrl → Room 章节表） */
    suspend fun loadChapterTitles(book: String): Map<Int, String> = withContext(Dispatchers.IO) {
        val url = loadBookUrl(book)
        if (url.isEmpty()) return@withContext emptyMap()
        runCatching {
            appDb.bookChapterDao.getChapterList(url).associate { it.index to it.title }
        }.getOrDefault(emptyMap())
    }

    /** B19：章节显示名（标题截到「章」；无标题回退 第N+1章）。仅用于外露显示；内部匹配仍用 index。 */
    suspend fun chapterLabelOf(book: String, chapterIndex: Int): String =
        runCatching {
            val title = if (book.isBlank()) null else loadChapterTitles(book)[chapterIndex]
            ChapterLabels.of(title, chapterIndex)
        }.getOrDefault("第${chapterIndex + 1}章")

    /** 作者（书名 → Room 书表） */
    suspend fun loadBookAuthor(book: String): String = withContext(Dispatchers.IO) {
        runCatching { appDb.bookDao.getBookByName(book)?.author.orEmpty() }.getOrDefault("")
    }

    /** 该书当前 bookUrl：优先 Room 书表（换源后为最新源 url，B10.5·Q3），未收录再回退 chapter_cache 键扫描 */
    suspend fun loadBookUrl(book: String): String = withContext(Dispatchers.IO) {
        runCatching { appDb.bookDao.getBookByName(book)?.bookUrl.orEmpty() }
            .getOrDefault("")
            .takeIf { it.isNotBlank() }
            ?.let { return@withContext it }
        val cacheFile = bookFile(book, "chapter_cache.$book.json")
        val cache = runCatching { JSONObject(readText(cacheFile)) }.getOrDefault(JSONObject())
        val keys = cache.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val url = k.substringBefore('|')
            if (url.isNotBlank() && url != k) return@withContext url
        }
        ""
    }

    /** 确保书籍在书架索引（liebiao.json；仅追加，不改变当前书） */
    suspend fun ensureBookInList(book: String): Boolean = withContext(Dispatchers.IO) {
        val name = book.trim()
        if (name.isEmpty()) return@withContext false
        runCatching {
            val f = rootFile("liebiao.json")
            val arr = runCatching { JSONArray(readText(f)) }.getOrDefault(JSONArray())
            for (i in 0 until arr.length()) if (arr.optString(i).trim() == name) return@withContext false
            arr.put(name)
            writeText(f, arr.toString())
            true
        }.getOrDefault(false)
    }

    /** 剧本文件是否已含该章（[chapter:N] 标记）——存量补写判断用 */
    suspend fun hasChapterScript(book: String, chapter: Int): Boolean = withContext(Dispatchers.IO) {
        val txt = readText(bookFile(book, "all_clean_text_$book.txt"))
        if (txt.isEmpty()) return@withContext false
        txt.split("\n").any { l ->
            CHAPTER_MARKER.find(l)?.groupValues?.get(1)?.toIntOrNull() == chapter
        }
    }
}

/**
 * B22：剧本文件段落存储（[chapter:N] 标记切段）。
 * 原版 1.4.x 以「按章号升序」序列化落库（globalLibSerialize：保证追加时按顺序存储）——
 * 移植版此前缺失该不变量：跳章/回跳补析会把新段追加到文件尾，文件顺序随分析顺序漂移。
 * 本对象把解析/重组抽成纯函数（单测直测），写入时统一升序重组（顺带自愈历史乱序）。
 */
internal object ScriptSectionStore {

    private val MARKER = Regex("^\\[chapter:(\\d+)\\]\\s*$")

    /** 解析：prefix=首个标记之前的行（正常为空）；sections=章号→段内行（同章多标记合并，内容不丢）。 */
    fun parse(content: String): Pair<List<String>, LinkedHashMap<Int, MutableList<String>>> {
        val prefix = mutableListOf<String>()
        val sections = LinkedHashMap<Int, MutableList<String>>()
        if (content.isEmpty()) return prefix to sections
        var cur = -1
        for (l in content.split("\n")) {
            val idx = MARKER.find(l)?.groupValues?.get(1)?.toIntOrNull()
            if (idx != null) {
                cur = idx
                sections.getOrPut(cur) { mutableListOf() }
                continue
            }
            if (cur >= 0) sections[cur]!!.add(l) else prefix.add(l)
        }
        return prefix to sections
    }

    /** 升序重组写回：prefix + 各段 [chapter:N]+行（段尾空行归一，段间无空行——与 1.4.x 序列化同款）。 */
    fun serialize(prefix: List<String>, sections: Map<Int, List<String>>): String {
        val out = ArrayList<String>()
        out.addAll(prefix)
        sections.toSortedMap().forEach { (ch, body) ->
            out.add("[chapter:$ch]")
            var end = body.size
            while (end > 0 && body[end - 1].isBlank()) end--
            for (i in 0 until end) out.add(body[i])
        }
        return out.joinToString("\n")
    }
}

/**
 * B23：合并账本『逆向』核心（纯函数，app 单测直测）。
 * 对齐 1.4.x/角色管理 v50 回滚语义：
 *  - 'm'（脚本自动合并凭据）：①记录名==from 且别名含 to → 改名回退（升级/吸收回退）；
 *    ②任一带 from（或凭据随行别名）的记录 → 剥离这些别名；不新建记录。
 *  - 'u'（移植版改名/手动合并账本）：维持既有可逆语义（剥离 + 被并走记录缺失时重建）。
 *  - keepPairs：保留区仍持有同一 from|to 对 → 跳过该逆向（"并入章节比回滚点更早 → 保留别名"）。
 *  - 出场清理：≥floor 全清；清空即删记录。
 */
internal object MergeRollbackCore {

    data class OpView(
        val id: String,
        val status: String,
        val from: String,
        val to: String,
        val aliases: List<String>,
    )

    fun reverseOps(
        current: MutableList<CharacterRecord>,
        removedOps: List<OpView>,
        keptOps: List<OpView>,
    ) {
        val keptPairs = HashSet<String>()
        keptOps.forEach { op ->
            if (op.from.isNotBlank() && op.to.isNotBlank()) keptPairs.add("${op.from}|${op.to}")
        }
        for (op in removedOps.asReversed()) {
            if (op.status != "active") continue
            val from = op.from
            val to = op.to
            if (from.isBlank() || to.isBlank() || from == to) continue
            if (keptPairs.contains("$from|$to")) continue
            val extra = op.aliases.filter { it.isNotBlank() }
            if (op.id.startsWith("m")) {
                val named = current.firstOrNull { it.name == from }
                if (named != null) {
                    val ts = AliasTokens.of(named.aliases)
                    if (to in ts) {
                        named.name = to
                        named.aliases = ts.filterNot { it == to }.joinToString("|")
                    }
                } else {
                    val holder = current.firstOrNull { r ->
                        val ts = AliasTokens.of(r.aliases)
                        from in ts || extra.any { it in ts }
                    }
                    if (holder != null) {
                        holder.aliases = AliasTokens.of(holder.aliases)
                            .filterNot { it == from || it in extra }
                            .joinToString("|")
                    }
                }
            } else {
                current.firstOrNull { it.name == to }?.let { target ->
                    target.aliases = AliasTokens.of(target.aliases)
                        .filterNot { it == from || it in extra }
                        .joinToString("|")
                }
                if (current.none { it.name == from }) {
                    current.add(
                        CharacterRecord(
                            name = from,
                            aliases = extra.joinToString("|"),
                            roletype = if (from.contains("【第")) "路人" else "核心",
                            gender = "男",
                            age = "男青年",
                        )
                    )
                }
            }
        }
    }

    fun purgeAppearances(current: MutableList<CharacterRecord>, floor: Int) {
        val iter = current.iterator()
        while (iter.hasNext()) {
            val r = iter.next()
            val had = r.appearanceChapters.isNotEmpty()
            r.appearanceChapters.removeAll { it >= floor }
            r.lastAppearanceChapter = r.appearanceChapters.maxOrNull() ?: -1
            r.appearanceCount = r.appearanceChapters.size
            if (had && r.appearanceChapters.isEmpty() && r.lastAppearanceChapter < 0) {
                iter.remove()
            }
        }
    }
}
