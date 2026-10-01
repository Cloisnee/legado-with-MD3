package io.legado.app.help.readaloud.audio

/**
 * B33.3d · 轻量 Aho-Corasick 多模式串匹配（纯 Kotlin、零依赖、可单测）。
 *
 * 用途：音效规则层「一次扫描命中全部关键词」——模式集 = CNB 意图 anchors（2400+ 条）；
 * 单行文本 O(n + 命中数)，替代「2219 条正则逐条 containsMatchIn」的暴力路径。
 */
class AhoCorasick(patterns: List<String>) {

    data class Match(val patternIndex: Int, val start: Int, val endExclusive: Int) {
        val length: Int get() = endExclusive - start
    }

    private class Node {
        val next = HashMap<Char, Int>(4)
        var fail = 0
        var out: IntArray = EMPTY
    }

    private val nodes = ArrayList<Node>()
    private val lengths = IntArray(patterns.size)

    init {
        nodes.add(Node())
        patterns.forEachIndexed { idx, raw ->
            val p = raw.trim()
            lengths[idx] = p.length
            if (p.isEmpty()) return@forEachIndexed
            var cur = 0
            for (ch in p) {
                cur = nodes[cur].next.getOrPut(ch) {
                    nodes.add(Node())
                    nodes.size - 1
                }
            }
            nodes[cur].out = nodes[cur].out.append(idx)
        }
        buildFailLinks()
    }

    val patternCount: Int get() = lengths.size

    /** 返回全部命中（按结束位置升序；同位置多个模式均返回） */
    fun matchAll(text: String): List<Match> {
        if (text.isEmpty() || patternCount == 0) return emptyList()
        val out = ArrayList<Match>()
        var cur = 0
        for (i in text.indices) {
            val ch = text[i]
            while (cur != 0 && ch !in nodes[cur].next) cur = nodes[cur].fail
            cur = nodes[cur].next[ch] ?: 0
            val outs = nodes[cur].out
            for (k in outs.indices) {
                val idx = outs[k]
                out.add(Match(idx, i + 1 - lengths[idx], i + 1))
            }
        }
        return out
    }

    private fun buildFailLinks() {
        val queue = java.util.ArrayDeque<Int>()
        nodes[0].next.values.forEach { child ->
            nodes[child].fail = 0
            queue.add(child)
        }
        while (queue.isNotEmpty()) {
            val r = queue.removeFirst()
            val failR = nodes[r].fail
            // 输出合并：并入失败链输出，扫描时无需回溯
            val failOut = nodes[failR].out
            if (failOut.isNotEmpty()) nodes[r].out = nodes[r].out.appendAll(failOut)
            nodes[r].next.forEach { (ch, c) ->
                var f = failR
                while (f != 0 && ch !in nodes[f].next) f = nodes[f].fail
                val via = nodes[f].next[ch]
                nodes[c].fail = if (via != null && via != c) via else 0
                queue.add(c)
            }
        }
    }

    private companion object {
        val EMPTY = IntArray(0)

        fun IntArray.append(v: Int): IntArray = copyOf(size + 1).also { it[size] = v }

        fun IntArray.appendAll(other: IntArray): IntArray =
            copyOf(size + other.size).also { other.copyInto(it, size) }
    }
}
