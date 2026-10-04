package io.legado.app.help.readaloud.audio

/**
 * P1 · 加词模式的分词（纯函数，可单测）：
 * 条目「匹配规则」关正则 = 加词模式；多词用 `|`、顿号、分号、逗号、换行、制表符分隔。
 */
internal fun splitWordList(raw: String): List<String> =
    raw.split('|', '、', ';', '；', ',', '，', '\n', '\r', '\t')
        .map { it.trim() }
        .filter { it.isNotBlank() }