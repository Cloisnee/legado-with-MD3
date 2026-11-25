package io.legado.app.help.readaloud.analysis

import io.legado.app.domain.model.readaloud.v4.RaChapterState

/**
 * 后台批次选择（02 §7.3，纯函数）。
 *
 * 可分析 = CACHED / STALE 且不在前台窗口、未被占用。
 * 先向锚点之后找第一个可分析章并向后连续扩展；没有则向窗口之前找，
 * 再向前连续扩展。前向批保留靠近锚点的前段，后向批保留靠近锚点的后段。
 */
object BatchSelector {

    fun nextBatch(
        states: Map<Int, RaChapterState>,
        anchor: Int,
        fgWindow: IntRange,
        budgetLeft: Int,
        maxBatch: Int,
        chapterOutputTokens: Map<Int, Int> = emptyMap(),
        outputTokenCap: Int = Int.MAX_VALUE,
        occupied: Set<Int> = emptySet(),
    ): List<Int>? {
        if (budgetLeft <= 0 || maxBatch <= 0 || states.isEmpty()) return null
        val maxIndex = states.keys.maxOrNull() ?: return null

        fun analyzable(chapter: Int): Boolean =
            states[chapter].let { it == RaChapterState.CACHED || it == RaChapterState.STALE } &&
                chapter !in fgWindow &&
                chapter !in occupied

        // 前向：从 max(anchor, fgWindow.last) + 1 起
        var start = maxOf(anchor, fgWindow.last) + 1
        while (start <= maxIndex && !analyzable(start)) start++
        if (start <= maxIndex) {
            val run = ArrayList<Int>()
            var chapter = start
            while (chapter <= maxIndex && analyzable(chapter)) {
                run.add(chapter)
                chapter++
            }
            return trim(run, budgetLeft, maxBatch, chapterOutputTokens, outputTokenCap, keepTail = false)
        }

        // 后向：从 fgWindow.first - 1 起
        var end = minOf(fgWindow.first - 1, maxIndex)
        while (end >= 0 && !analyzable(end)) end--
        if (end >= 0) {
            val run = ArrayList<Int>()
            var chapter = end
            while (chapter >= 0 && analyzable(chapter)) {
                run.add(0, chapter)
                chapter--
            }
            return trim(run, budgetLeft, maxBatch, chapterOutputTokens, outputTokenCap, keepTail = true)
        }
        return null
    }

    private fun trim(
        run: List<Int>,
        budgetLeft: Int,
        maxBatch: Int,
        chapterOutputTokens: Map<Int, Int>,
        outputTokenCap: Int,
        keepTail: Boolean,
    ): List<Int>? {
        val maxLen = minOf(run.size, budgetLeft, maxBatch)
        if (maxLen <= 0) return null
        var limited = if (keepTail) run.takeLast(maxLen) else run.take(maxLen)

        if (chapterOutputTokens.isNotEmpty() && outputTokenCap < Int.MAX_VALUE) {
            val ordered = if (keepTail) limited.asReversed() else limited
            var total = 0
            val allowed = ArrayList<Int>()
            for (chapter in ordered) {
                val tokens = chapterOutputTokens[chapter] ?: 0
                if (total + tokens > outputTokenCap) break
                total += tokens
                allowed.add(chapter)
            }
            if (allowed.isEmpty()) return null
            limited = if (keepTail) allowed.asReversed() else allowed
        }
        return limited.sorted()
    }
}
