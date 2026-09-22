package io.legado.app.utils

/**
 * B19：章节「显示名」——面向外露显示的短名（与阅读器/书籍页一致）：
 *  1) 标题含「第…章/回/节…」编号 → 截取编号段（「第104章 雨夜来客」→「第104章」）；
 *  2) 无编号的短语（序章/前言/引子/楔子/番外…）→ 原样返回；
 *  3) 无标题 → 回退「第N+1章」。
 *
 * 注意：内部匹配一律使用 chapter index（0 基：[chapter:index]/缓存键/analyze_state），本对象只负责显示。
 */
object ChapterLabels {
    private val numbered = Regex("^第\\s*[0-9零〇一二三四五六七八九十百千万两IVXLCDMivxlcdm]+\\s*[章回节卷部篇集幕]")
    private val special = Regex("^(序章|序言|前言|引子|楔子|终章|尾声|后记|番外)")

    fun of(title: String?, chapterIndex: Int): String {
        val t = title?.trim().orEmpty()
        if (t.isEmpty()) return "第${chapterIndex + 1}章"
        numbered.find(t)?.let { m -> return m.value.replace(" ", "").replace("　", "") }
        special.find(t)?.let { return it.value }
        return t
    }
}
