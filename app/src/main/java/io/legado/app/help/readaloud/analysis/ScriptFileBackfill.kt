package io.legado.app.help.readaloud.analysis

import io.legado.app.data.repository.ScriptLineRow
import io.legado.app.domain.model.readaloud.CanonicalSpeechParagraph
import io.legado.app.domain.model.readaloud.ChapterSpeechSegment
import io.legado.app.domain.model.readaloud.SpeechIdentity
import io.legado.app.domain.model.readaloud.SpeechResolutionSource
import io.legado.app.domain.model.readaloud.SpeechRoleType

/**
 * B8.3 · 本地剧本文件回填（纯逻辑，供单测）。
 *
 * 把剧本行（`〖说话人〗[[emo:xx]]正文`，格式见 ReadAloudDataRepository.loadChapterScript）
 * 对齐回当前章节正文（[CanonicalSpeechParagraph] 位置空间），重建与播放/批量共用的
 * [ChapterSpeechSegment]（顺序 = 剧本行顺序，条目序号与批量音频缓存一致）。
 *
 * 对齐规则（双向唯一性校验）：
 *  - 正向：每行文本取「前一行结束之后的首个」精确匹配；
 *  - 反向：每行文本取「后一行开始之前的最末」精确匹配；
 *  - 两者完全一致才接受（保证正文未变且可唯一定位），否则返回 null、调用方回落快速链。
 */
internal object ScriptFileBackfill {

    /** 剧本文件里的旁白标记（与落盘渲染、批量合成口径一致） */
    const val NARRATOR_TAG = "旁白"

    data class AlignedRow(
        val paragraphIndex: Int,
        val start: Int,
        val end: Int,
        val chapterPosition: Int,
        val speaker: String,
        val text: String,
        val emotion: String,
    )

    private data class Match(val paraPos: Int, val start: Int, val end: Int)

    /** 对齐失败（正文已变 / 无法唯一定位 / 输入为空）返回 null */
    fun align(
        paragraphs: List<CanonicalSpeechParagraph>,
        rows: List<ScriptLineRow>,
    ): List<AlignedRow>? {
        if (paragraphs.isEmpty() || rows.isEmpty()) return null
        val forward = alignForward(paragraphs, rows) ?: return null
        val backward = alignBackward(paragraphs, rows) ?: return null
        forward.forEachIndexed { i, f ->
            // 空文本行不参与唯一性比较（零长度，无法也无须唯一定位）
            if (rows[i].text.isEmpty()) return@forEachIndexed
            val b = backward[i]
            if (f.paraPos != b.paraPos || f.start != b.start || f.end != b.end) return null
        }
        return forward.mapIndexed { i, m ->
            val p = paragraphs[m.paraPos]
            AlignedRow(
                paragraphIndex = p.index,
                start = m.start,
                end = m.end,
                chapterPosition = p.chapterPosition + m.start,
                speaker = rows[i].speaker,
                text = rows[i].text,
                emotion = rows[i].emotion,
            )
        }
    }

    /** 重建 segments；旁白（空说话人 / [NARRATOR_TAG]）无 characterName，其余按剧本名挂角色 */
    fun toSegments(
        aligned: List<AlignedRow>,
        bookUrl: String,
        chapterIndex: Int,
        analysisId: String,
    ): List<ChapterSpeechSegment> = aligned.map { a ->
        val isNarrator = a.speaker.isBlank() || a.speaker == NARRATOR_TAG
        ChapterSpeechSegment(
            id = SpeechIdentity.segmentId(analysisId, a.paragraphIndex, a.start, a.end),
            analysisId = analysisId,
            bookUrl = bookUrl,
            chapterIndex = chapterIndex,
            paragraphIndex = a.paragraphIndex,
            start = a.start,
            end = a.end,
            chapterPosition = a.chapterPosition,
            text = a.text,
            roleType = if (isNarrator) SpeechRoleType.Narrator else SpeechRoleType.Character,
            characterId = null,
            characterName = if (isNarrator) "" else a.speaker,
            emotion = a.emotion,
            confidence = 0f,
            source = SpeechResolutionSource.Local,
        )
    }

    /** 正向：每行取「前一行结束之后首个」匹配 */
    private fun alignForward(
        paragraphs: List<CanonicalSpeechParagraph>,
        rows: List<ScriptLineRow>,
    ): List<Match>? {
        val out = ArrayList<Match>(rows.size)
        var paraPos = 0
        var offset = 0
        rows.forEach { row ->
            val t = row.text
            if (t.isEmpty()) {
                out.add(Match(paraPos, offset, offset))
                return@forEach
            }
            var found: Match? = null
            var p = paraPos
            var from = offset
            while (p < paragraphs.size) {
                val idx = paragraphs[p].text.indexOf(t, from)
                if (idx >= 0) {
                    found = Match(p, idx, idx + t.length)
                    break
                }
                p++
                from = 0
            }
            val m = found ?: return null
            out.add(m)
            paraPos = m.paraPos
            offset = m.end
        }
        return out
    }

    /** 反向：每行取「后一行开始之前最末」匹配（末行上限 = 章节末尾） */
    private fun alignBackward(
        paragraphs: List<CanonicalSpeechParagraph>,
        rows: List<ScriptLineRow>,
    ): List<Match>? {
        val out = arrayOfNulls<Match>(rows.size)
        var ceilPara = paragraphs.lastIndex
        var ceilOffset = paragraphs.last().text.length
        for (i in rows.indices.reversed()) {
            val t = rows[i].text
            if (t.isEmpty()) {
                out[i] = Match(ceilPara, ceilOffset, ceilOffset)
                continue
            }
            var m: Match? = null
            var p = ceilPara
            while (p >= 0) {
                val text = paragraphs[p].text
                val maxStart = if (p == ceilPara) {
                    minOf(ceilOffset, text.length) - t.length
                } else {
                    text.length - t.length
                }
                val idx = lastIndexOfWithin(text, t, maxStart)
                if (idx >= 0) {
                    m = Match(p, idx, idx + t.length)
                    break
                }
                p--
            }
            val found = m ?: return null
            out[i] = found
            ceilPara = found.paraPos
            ceilOffset = found.start
        }
        val result = ArrayList<Match>(rows.size)
        out.forEach { result.add(it ?: return null) }
        return result
    }

    /** 文本中「起点 ≤ maxStart」的最末一次出现；越界或不存在返回 -1 */
    private fun lastIndexOfWithin(text: String, t: String, maxStart: Int): Int {
        if (maxStart < 0 || t.isEmpty()) return -1
        var best = -1
        var from = 0
        while (true) {
            val i = text.indexOf(t, from)
            if (i < 0 || i > maxStart) break
            best = i
            from = i + 1
        }
        return best
    }
}
