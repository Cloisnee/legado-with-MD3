package io.legado.app.help.readaloud.analysis.v4

import org.json.JSONObject

/**
 * 批量阶段2 的 NDJSON 增量解析器（03 §5.2）。
 *
 * 输入是 AI 流式返回的分片文本，按行切分：
 *  - `{"type":"entities",...}` 必须最先出现；
 *  - `{"type":"chapter","ch":92,...}` 每章一行，允许边到边提交；
 *  - `{"type":"end",...}` 结束标记；
 *  - ``` 围栏行忽略；坏行只记录，不影响后续行。
 *
 * [resetAttempt] 在每次模型重试前调用：清空本次尝试的缓冲与标志，但保留已提交章节。
 * [finish] 返回已提交/缺失章节、是否收到 end、是否需要整体 JSON 回退。
 */
class NdjsonChapterStream(
    private val requestedChapters: Set<Int> = emptySet(),
    private val onEntities: (JSONObject) -> Unit = {},
    private val onChapter: (Int, JSONObject) -> Unit = { _, _ -> },
) {

    data class Result(
        val committed: Set<Int>,
        val missing: Set<Int>,
        val sawEnd: Boolean,
        val needsFallback: Boolean,
        val badLines: Int,
        val raw: String,
    )

    private val buf = StringBuilder()
    private val whole = StringBuilder()
    private val committed = LinkedHashSet<Int>()
    private var sawEntities = false
    private var sawEnd = false
    private var needsFallback = false
    private var badLines = 0
    private var chapterSeen = false

    fun feed(delta: String) {
        if (delta.isEmpty()) return
        whole.append(delta)
        buf.append(delta)
        var newline = buf.indexOf("\n")
        while (newline >= 0) {
            val line = buf.substring(0, newline)
            buf.delete(0, newline + 1)
            handleLine(line)
            newline = buf.indexOf("\n")
        }
    }

    /** 每次模型尝试开始前调用；已提交章节跨尝试保留。 */
    fun resetAttempt() {
        buf.clear()
        whole.clear()
        sawEntities = false
        sawEnd = false
        needsFallback = false
        chapterSeen = false
    }

    fun finish(): Result {
        if (buf.isNotBlank()) {
            val line = buf.toString()
            buf.clear()
            handleLine(line)
        }
        if (chapterSeen && !sawEntities) needsFallback = true
        return Result(
            committed = committed.toSet(),
            missing = requestedChapters - committed,
            sawEnd = sawEnd,
            needsFallback = needsFallback,
            badLines = badLines,
            raw = whole.toString(),
        )
    }

    /**
     * 整体 JSON 回退（03 §3.3）：`{"entities":[...],"chapters":[...]}`。
     * 已提交章节跳过；成功解析返回 true。
     */
    fun consumeWholeJson(raw: String): Boolean {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return false
        val root = runCatching { JSONObject(raw.substring(start, end + 1)) }.getOrNull() ?: return false
        root.optJSONArray("entities")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let(onEntities)
            }
            sawEntities = true
        }
        val chapters = root.optJSONArray("chapters") ?: return false
        for (i in 0 until chapters.length()) {
            val obj = chapters.optJSONObject(i) ?: continue
            val ch = obj.optInt("ch", -1)
            if (ch <= 0 || ch in committed) continue
            if (commit(ch, obj)) committed.add(ch)
        }
        return true
    }

    private fun handleLine(rawLine: String) {
        val line = rawLine.trim()
        if (line.isEmpty()) return
        if (line.startsWith("```")) return
        val obj = runCatching { JSONObject(line) }.getOrNull()
        if (obj == null) {
            badLines++
            return
        }
        when (obj.optString("type").trim().lowercase()) {
            "entities" -> {
                if (chapterSeen || committed.isNotEmpty()) {
                    needsFallback = true
                    return
                }
                sawEntities = true
                onEntities(obj)
            }
            "chapter" -> {
                chapterSeen = true
                if (!sawEntities) {
                    needsFallback = true
                    return
                }
                val ch = obj.optInt("ch", -1)
                if (ch <= 0 || ch in committed) return
                if (commit(ch, obj)) committed.add(ch)
            }
            "end" -> sawEnd = true
            else -> badLines++
        }
    }

    private fun commit(ch: Int, obj: JSONObject): Boolean = runCatching {
        onChapter(ch, obj)
        true
    }.getOrDefault(false)
}
