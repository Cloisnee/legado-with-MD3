package io.legado.app.service.readaloud

/**
 * 送进引擎的那一句文本，及其「引擎下标 → 正文绝对下标」的还原。
 *
 * 本移植版没有标记删改：两份下标天然一致，[storedOf] 即 base 平移。朗读进度、
 * 翻页判定与「从半句接着念」的续念偏移共用这一份换算。
 */
class Spoken(val text: String, private val storedEnd: Int, private val map: (Int) -> Int) {

    companion object {
        fun passThrough(text: String, base: Int = 0) =
            Spoken(text, base + text.length) { (base + it).coerceIn(base, base + text.length) }
    }

    /** 引擎回传的下标 → 正文（含标记）的绝对下标。 */
    fun storedOf(spokenIndex: Int): Int = map(spokenIndex).coerceIn(0, storedEnd)
}
