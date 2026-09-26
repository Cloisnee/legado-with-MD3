package io.legado.app.help.readaloud.analysis

/**
 * B31：第1阶段「逐项 T/F 判定」的解析与组装（纯逻辑，可单测）。
 *
 * 协议：AI 对每个片段判定 T（是话语）/F（旁白）；输入、输出均按段编号顺序一一对应：
 *   {"段落":[{"段号":1,"判定":["F","F","F","T"]},…]}
 *
 * 组装规则（甲方定稿 2026-09-26）：
 *  - F 串 → 旁白（不产生话语）；
 *  - T 且为成对符号块（引号包裹）→ 独立成一条话语，不与相邻合并；
 *  - 其他 T → 按完整句子收：以 。！？ 结尾即收；否则到段尾也收；，、…— 继续接续；
 *  - 保险丝：以「：」结尾的 T → 丢弃（转旁白）；纯符号 T → 丢弃（转旁白）。
 */
internal object SpeechSelectionAssembly {

    data class Unit(val start: Int, val end: Int, val text: String)
    data class ParaUnits(val index: Int, val units: List<Unit>)
    data class Span(val para: Int, val start: Int, val end: Int)
    data class Result(
        val spans: List<Span>,
        val narratorUnits: Int,
        val fusedColon: Int,
        val fusedSymbol: Int,
    )

    private val COLON_ENDERS = setOf('：', ':')
    private val SENTENCE_ENDERS = setOf('。', '！', '？', '!', '?')

    private fun endsWith(c: Char?, set: Set<Char>): Boolean = c != null && c in set

    /** 组装：段落+片段+判定 → 话语区间（顺序=段落顺序、片段顺序） */
    fun assemble(paragraphs: List<ParaUnits>, flagsByPara: Map<Int, BooleanArray>): Result {
        val spans = ArrayList<Span>()
        var narrator = 0
        var fusedColon = 0
        var fusedSymbol = 0
        paragraphs.forEach { p ->
            val units = p.units
            val flags = flagsByPara[p.index] ?: BooleanArray(units.size)
            var i = 0
            while (i < units.size) {
                val unit = units[i]
                if (i >= flags.size || !flags[i]) {
                    narrator++
                    i++
                    continue
                }
                // 保险丝：：结尾 / 纯符号 → 转旁白
                if (endsWith(unit.text.lastOrNull(), COLON_ENDERS)) {
                    fusedColon++
                    narrator++
                    i++
                    continue
                }
                if (unit.text.none { it.isLetterOrDigit() }) {
                    fusedSymbol++
                    narrator++
                    i++
                    continue
                }
                if (QuoteSpeechRules.isWrappedBlock(unit.text)) {
                    // 成对符号块：独立成条（不与任何相邻合并）
                    spans.add(Span(p.index, unit.start, unit.end))
                    i++
                    continue
                }
                // 普通片段：按完整句子收（。！？ 或段尾收；否则接续）
                var j = i
                while (true) {
                    val cur = units[j]
                    if (endsWith(cur.text.lastOrNull(), SENTENCE_ENDERS)) break
                    if (j == units.size - 1) break
                    val nxt = j + 1
                    val nxtOk = nxt < flags.size && flags[nxt] &&
                        !endsWith(units[nxt].text.lastOrNull(), COLON_ENDERS) &&
                        units[nxt].text.any { it.isLetterOrDigit() } &&
                        !QuoteSpeechRules.isWrappedBlock(units[nxt].text)
                    if (!nxtOk) break
                    j++
                }
                spans.add(Span(p.index, units[i].start, units[j].end))
                i = j + 1
            }
        }
        return Result(spans, narrator, fusedColon, fusedSymbol)
    }

    /** 解析 AI 判定（宽容）：["T","F",…] / "TFFT" / [true,false,…]；长度不符/非法值 → null */
    fun parseFlags(raw: Any?, expect: Int): BooleanArray? {
        when (raw) {
            null -> return if (expect == 0) BooleanArray(0) else null
            is String -> {
                val s = raw.filter { it == 'T' || it == 'F' || it == 't' || it == 'f' }
                if (s.length != expect) return null
                return BooleanArray(expect) { s[it] == 'T' || s[it] == 't' }
            }
            is List<*> -> {
                if (raw.size != expect) return null
                val out = BooleanArray(expect)
                raw.forEachIndexed { idx, v ->
                    val b = when (v) {
                        is Boolean -> v
                        is Number -> v.toInt() != 0
                        is String -> when (v.trim().uppercase()) {
                            "T", "TRUE", "是", "1" -> true
                            "F", "FALSE", "否", "0" -> false
                            else -> return null
                        }
                        else -> return null
                    }
                    out[idx] = b
                }
                return out
            }
            else -> return null
        }
    }
}
