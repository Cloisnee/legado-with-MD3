package io.legado.app.data.repository

import android.app.Application
import com.github.jing332.compat.fs.TtsDirProvider
import com.github.jing332.tts.store.TtsConfigStore
import io.legado.app.domain.model.readaloud.VoiceGroupInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 朗读分析数据层（对照「角色管理」v36 忠实移植，数据根=<应用根>/data/）：
 *  - liebiao.json / cunfang.txt / characterRecords.json / fayinren.json / bare_words / special_words
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
    var usageCount: Int = 0,
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
)

class ReadAloudDataRepository(private val app: Application) {

    companion object {
        const val DEFAULT_BOOK = "默认"
        private val CHAPTER_MARKER = Regex("^\\[chapter:(\\d+)\\]\\s*$")
        private val EMO_HEAD = Regex("^(\\[\\[emo:[^\\]]*\\]\\])+")
    }

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

        if (readText(rootFile("fayinren.json")).isEmpty()) {
            writeText(rootFile("fayinren.json"), "[]")
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
                            usageCount = o.optInt("usageCount", 0),
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
                    put("usageCount", r.usageCount)
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

    // ---------------- 声线标签池（供声线选择弹窗） ----------------

    suspend fun loadFayinrenTags(): List<String> = withContext(Dispatchers.IO) {
        val arr = runCatching { JSONArray(readText(rootFile("fayinren.json"))) }
            .getOrDefault(JSONArray())
        buildList {
            for (i in 0 until arr.length()) {
                val v = arr.optString(i).trim()
                if (v.isNotEmpty()) add(v)
            }
        }
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

    private fun writeBookRev(book: String, chapter: Int) {
        writeText(
            bookFile(book, "book_rev.json"),
            JSONObject().apply {
                put("ts", System.currentTimeMillis())
                put("chapter", chapter)
            }.toString()
        )
    }

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
            buildList {
                for (i in start + 1 until end) {
                    val raw = lines[i]
                    if (raw.isBlank()) continue
                    val spk = speakerOf(raw)
                    val close = raw.indexOf('〗', 1)
                    val rest = if (spk != null && close != -1) raw.substring(close + 1) else raw
                    add(
                        ScriptLineRow(
                            absIndex = i,
                            speaker = spk.orEmpty(),
                            text = stripEmoRest(rest),
                        )
                    )
                }
            }
        }

    /** 修改某章若干行的说话标记；同步 chapter_cache 该章 scriptText；剔除该章合并账本 op；写 book_rev */
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
        // 剔除该章合并账本 op（人物记录不动）
        runCatching {
            val ops = readMergeLog(book)
            val kept = ops.filterNot { it.optInt("chapter", -1) == chapter }
            if (kept.size != ops.size) writeMergeLog(book, kept)
        }
        writeBookRev(book, chapter)
        true
    }

    /** 删除章节剧本：轻量=剧本+缓存+状态；回滚=另含 合并账本/人物逆向（用于"接续到末尾的连续章"） */
    suspend fun deleteChapterScripts(
        book: String,
        chapters: Set<Int>,
        rollback: Boolean,
    ): Boolean = withContext(Dispatchers.IO) {
        if (chapters.isEmpty()) return@withContext false
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
        // 缓存
        runCatching {
            val cacheFile = bookFile(book, "chapter_cache.$book.json")
            val raw = readText(cacheFile)
            if (raw.isNotEmpty()) {
                val cache = JSONObject(raw)
                val keys = buildList { val it = cache.keys(); while (it.hasNext()) add(it.next()) }
                keys.forEach { key ->
                    val ch = key.split("|").lastOrNull()?.toIntOrNull() ?: return@forEach
                    if (ch in chapters) cache.remove(key)
                }
                writeText(cacheFile, cache.toString())
            }
        }
        // 合并账本（回滚时剔除并做人物逆向）
        runCatching {
            val ops = readMergeLog(book)
            if (rollback) {
                val removed = ops.filter { it.optInt("chapter", -1) in chapters }
                val kept = ops.filterNot { it.optInt("chapter", -1) in chapters }
                if (removed.isNotEmpty()) {
                    writeMergeLog(book, kept)
                    rollbackCharacters(book, removed, chapters)
                }
            }
        }
        writeBookRev(book, chapters.minOrNull() ?: -1)
        true
    }

    /** 人物逆向：按被删合并账本回退别名/重建被合并角色，并按删除章清理出场数组 */
    private fun rollbackCharacters(
        book: String,
        removedOps: List<JSONObject>,
        deletedChapters: Set<Int>,
    ) {
        runCatching {
            val current = parseRecords(readText(bookFile(book, "shuming.$book.json"))).toMutableList()
            removedOps.forEach opLoop@{ op ->
                if (op.optString("status") != "active") return@opLoop
                val from = op.optString("from")
                val to = op.optString("to")
                if (from.isBlank() || to.isBlank() || from == to) return@opLoop
                val extra = mutableListOf<String>()
                op.optJSONArray("aliases")?.let { a ->
                    for (i in 0 until a.length()) {
                        a.optString(i).takeIf { it.isNotBlank() }?.let(extra::add)
                    }
                }
                current.firstOrNull { it.name == to }?.let { target ->
                    target.aliases = target.aliases.split("|")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
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
            val iter = current.iterator()
            while (iter.hasNext()) {
                val r = iter.next()
                val had = r.appearanceChapters.isNotEmpty()
                r.appearanceChapters.removeAll { it in deletedChapters }
                r.lastAppearanceChapter = r.appearanceChapters.maxOrNull() ?: -1
                r.appearanceCount = r.appearanceChapters.size
                if (had && r.appearanceChapters.isEmpty() && r.lastAppearanceChapter < 0) {
                    iter.remove()
                }
            }
            val json = recordsJson(current)
            writeText(bookFile(book, "shuming.$book.json"), json)
            writeText(rootFile("characterRecords.json"), json)
            writeText(rootFile("characterRecords_backup.json"), json)
        }
    }

    // ---------------- 配音前缀工具 ----------------

    fun expectedVoicePrefix(roletype: String, gender: String, age: String): String = when (roletype) {
        "特殊" -> if (gender == "女") "特殊女" else "特殊男"
        "路人" -> "路人$age"
        else -> age
    }

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
            if (ok && readText(rootFile("cunfang.txt")) == book) {
                ok = writeText(rootFile("characterRecords.json"), json)
                writeText(rootFile("characterRecords_backup.json"), json)
            }
            ok
        }

    /** 最近已分析章节（以剧本文件里的 [chapter:N] 标记为准；无则 -1）——连续性判定用 */
    suspend fun lastAnalyzedChapter(book: String): Int = withContext(Dispatchers.IO) {
        val txt = readText(bookFile(book, "all_clean_text_$book.txt"))
        if (txt.isEmpty()) return@withContext -1
        var max = -1
        for (line in txt.split("\n")) {
            val n = CHAPTER_MARKER.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            if (n > max) max = n
        }
        max
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

}