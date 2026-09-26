package io.legado.app.help.readaloud.analysis

/**
 * 本地话语判定规则 v2 + 文本切块（甲方定版 2026-09-19，复刻自研朗读脚本并修订版）。
 *
 * 话语判定（成对包裹区 S）：
 *  a) S 前有内容、且前导显著字符为标点（含 ：，。！？；、—…）→ 话语（如 张三，“123。”）；
 *  b) S 在段首（前无实义内容）→ 话语（b 后有内容 / c 前后皆无，两情形均收；如 “123。”张三。 / 独占一行）；
 *     · 例外：段首 + 短词 + 无句末标点 + 后方紧跟定义类词（是/的/等/字/词…）→ 视为术语定义，拒；
 *  d) S 前为实义字符（非标点）时，仅当：
 *     (i) 前置 ≤24 字窗口内出现说话动词，且其位置不低于比喻连接词（就像/好像/仿佛…）、无俗语框架（俗话说/所谓…）；
 *     或 (ii) S 后无实义内容（句尾独立）且无比喻/俗语框架；
 *     → 话语；否则拒（嵌引“他就像‘死了’一般”、俗语夹杂、术语场景均拒）。
 *  · 引用内容须含实义字符（汉字/字母/数字），纯标点/空白拒；未闭合引号按同规则以段尾为界。
 */
object QuoteSpeechRules {

    /** 话语判定用包裹对："" '' 「」 『』 【】 */
    private val JUDGE_PAIRS: Map<Char, Char> = mapOf(
        '\u201C' to '\u201D',
        '\u2018' to '\u2019',
        '\u300C' to '\u300D',
        '\u300E' to '\u300F',
        '\u3010' to '\u3011',
    )

    /** 切块用成对符号（复刻脚本 QUOTE_PAIRS：成对块整体成片，不切开） */
    private val SPLIT_PAIRS: Map<Char, Char> = buildMap {
        put('\u201C', '\u201D'); put('\u2018', '\u2019')
        put('\u300C', '\u300D'); put('\u300E', '\u300F')
        put('\uFF08', '\uFF09'); put('\u3010', '\u3011')
        put('\uFF5B', '\uFF5D'); put('\u3014', '\u3015')
        put('\uFF3B', '\uFF3D'); put('\u300A', '\u300B')
        put('\u3008', '\u3009'); put('\u3016', '\u3017')
    }

    /** 切块断片符号：脚本 [，。！？；：…] 基础上补 —（破折号） */
    private val CHUNK_FLUSH: Set<Char> = setOf(
        '\uFF0C', '\u3002', '\uFF01', '\uFF1F', '\uFF1B', '\uFF1A', '\u2026', '\u2014',
    )

    /** 前导显著标点集（a 类判定用；含各成对符号与常见标点） */
    private val LEAD_PUNCTS: Set<Char> = setOf(
        '，', '。', '！', '？', '；', '：', '、', '—', '…', '·', '－', '-',
        ',', '.', '!', '?', ';', ':', '"',
        '“', '”', '‘', '’', '「', '」', '『', '』', '【', '】',
        '（', '）', '《', '》', '〈', '〉', '〔', '〕', '｛', '｝', '［', '］', '〖', '〗',
        '\u3000', ' ',
    )

    private val SENTENCE_ENDERS: Set<Char> = setOf('。', '！', '？', '…', '—')

    private val SPEECH_VERBS = listOf(
        "说道", "说", "问道", "问", "答道", "答", "喊道", "喊", "叫道", "叫", "喝道", "喝",
        "笑道", "笑", "哭道", "哭", "吼道", "吼", "骂道", "骂", "念道", "念", "念叨",
        "嘟囔", "嘀咕", "喃喃", "自语", "开口", "嘱咐", "吩咐", "低声道", "沉声道", "怒声道",
    )

    /** 俗语/引用框架：出现即否决 d 类判定 */
    private val IDIOM_FRAMES = listOf(
        "俗话说", "常言道", "古人云", "有道是", "所谓", "可以说", "也就是说", "换言之", "即是说", "就是说", "民谚", "老话",
    )

    /** 比喻/命名连接词：位置不低于说话动词时否决 */
    private val SIMILE_WORDS = listOf(
        "就像", "好像", "仿佛", "如同", "宛如", "恰似", "好比", "似的", "像", "似",
    )

    /** 定义类后接词（段首术语守卫） */
    private val DEFINITION_AFTER: Set<Char> = setOf('是', '的', '等', '即', '指', '称', '叫', '字', '词', '句', '话', '意')

    private const val HINT_WINDOW = 24

    /** 切块单元 [start, end) */
    data class SplitUnit(val start: Int, val end: Int)

    /** 段落内所有「判定为话语」的包裹区（闭区间，含引号本身；含未闭合的段尾引号） */
    fun quoteSpans(text: String): List<IntRange> {
        val spans = ArrayList<IntRange>()
        var i = 0
        while (i < text.length) {
            val close = JUDGE_PAIRS[text[i]]
            if (close == null) {
                i++
                continue
            }
            val open = text[i]
            var j = i + 1
            var found = -1
            while (j < text.length) {
                if (text[j] == close) {
                    found = j
                    break
                }
                if (text[j] == open) {
                    // 同类嵌套：跳到内层闭合之后
                    val innerEnd = matchEnd(text, j, open, close)
                    j = if (innerEnd < 0) text.length else innerEnd
                    continue
                }
                j++
            }
            if (found >= 0) {
                if (isUtterance(text, i, found)) spans.add(i..found)
                i = found + 1
            } else {
                if (isUtterance(text, i, text.length - 1)) spans.add(i..(text.length - 1))
                i = text.length
            }
        }
        return spans
    }

    /** 段落 → 有序单元：成对符号块整体成片；裸文字在 [，。！？；：…—] 处断片（标点随片尾）；纯标点片并入前片 */
    fun splitToUnits(text: String): List<SplitUnit> {
        val units = ArrayList<SplitUnit>()
        var bufStart = 0
        var i = 0
        fun flush(end: Int) {
            if (end <= bufStart) return
            val piece = text.substring(bufStart, end)
            if (!hasSubstantive(piece) && units.isNotEmpty()) {
                // 纯标点片并入上一片末尾
                val last = units.removeAt(units.size - 1)
                units.add(SplitUnit(last.start, end))
            } else {
                units.add(SplitUnit(bufStart, end))
            }
        }
        while (i < text.length) {
            val close = SPLIT_PAIRS[text[i]]
            if (close != null) {
                flush(i)
                val j = text.indexOf(close, i + 1)
                val end = if (j == -1) text.length else j + 1
                units.add(SplitUnit(i, end))
                i = end
                bufStart = i
            } else {
                i++
                if (CHUNK_FLUSH.contains(text[i - 1])) {
                    flush(i)
                    bufStart = i
                }
            }
        }
        flush(text.length)
        return units
    }

    /** B31：是否为「成对符号块」（首尾恰为一对成对符号；阶段1组装：引号包裹的话语独立成条） */
    fun isWrappedBlock(text: String): Boolean {
        if (text.length < 2) return false
        val close = SPLIT_PAIRS[text.first()] ?: return false
        return text.last() == close
    }

    /** B31：显示用——去掉首尾成对符号（未闭合则只去前引号；非包裹块原样返回） */
    fun unwrapOuterBlock(text: String): String {
        if (text.length < 2) return text
        val close = SPLIT_PAIRS[text.first()] ?: return text
        val body = text.substring(1)
        return if (body.isNotEmpty() && body.last() == close) body.substring(0, body.length - 1) else body
    }

    // ---------------- 内部 ----------------

    private fun matchEnd(text: String, start: Int, open: Char, close: Char): Int {
        var depth = 1
        var j = start + 1
        while (j < text.length) {
            val c = text[j]
            if (c == open) {
                depth++
            } else if (c == close) {
                depth--
                if (depth == 0) return j + 1
            }
            j++
        }
        return -1
    }

    private fun hasSubstantive(s: String): Boolean = s.any { it.isLetterOrDigit() }

    private fun isUtterance(text: String, first: Int, last: Int): Boolean {
        val innerStart = (first + 1).coerceAtMost(text.length)
        val innerEnd = last.coerceAtMost(text.length)
        val inner = if (innerEnd > innerStart) text.substring(innerStart, innerEnd) else ""
        if (!hasSubstantive(inner)) return false

        var p = first - 1
        while (p >= 0 && (text[p] == ' ' || text[p] == '\u3000')) p--
        val hasBefore = p >= 0
        val prevChar = if (hasBefore) text[p] else null

        // b/c：段首（前无实义内容）
        if (!hasBefore) {
            val after = nextSignificant(text, last + 1)
            if (after != null && DEFINITION_AFTER.contains(after.first) &&
                inner.length <= 6 && !inner.any { it in SENTENCE_ENDERS }
            ) return false
            return true
        }

        // a：前导为标点
        if (prevChar != null && LEAD_PUNCTS.contains(prevChar)) return true

        // d：前为实义字符
        val windowStart = (p + 1 - HINT_WINDOW).coerceAtLeast(0)
        val window = text.substring(windowStart, p + 1)
        if (IDIOM_FRAMES.any { window.contains(it) }) return false
        val verbIdx = SPEECH_VERBS.map { window.lastIndexOf(it) }.max()
        val simileIdx = SIMILE_WORDS.map { window.lastIndexOf(it) }.max()
        if (verbIdx >= 0 && (simileIdx < 0 || verbIdx > simileIdx)) return true
        val after = nextSignificant(text, last + 1)
        return after == null && simileIdx < 0
    }

    private fun nextSignificant(text: String, from: Int): Pair<Char, Int>? {
        var i = from
        while (i < text.length) {
            val c = text[i]
            if (c != ' ' && c != '\u3000' && !LEAD_PUNCTS.contains(c)) return c to i
            i++
        }
        return null
    }
}
