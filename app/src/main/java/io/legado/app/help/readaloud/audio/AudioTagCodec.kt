package io.legado.app.help.readaloud.audio

/**
 * B33.4b · 剧本音频标签编解码（`[[a:amb=…]]` / `[[a:sfx=…@ms]]` / `[[a:bgm=情绪·强度·行数]]`）。
 *
 * - 写入：分析落盘时由渲染器追加到剧本行尾（不改正文）；
 * - 剥离：剧本解析时进 `ScriptLineRow.audio`、正文文本剥离（TTS/对齐/缓存一律不受影响）；
 * - 展示：剧本审查页 chips 文案（[chipsText]）。
 */
object AudioTagCodec {

    const val TYPE_AMB = "amb"
    const val TYPE_SFX = "sfx"
    const val TYPE_BGM = "bgm"

    /** `[[a:type=value]]`（value 内不含中括号） */
    val TAG_REGEX = Regex("\\[\\[a:([a-z]+)=([^\\[\\]]*)\\]\\]")

    data class Tag(val type: String, val value: String) {
        /** SFX 延迟毫秒（`名称@200` 的 @ 后半段；无则 0） */
        val delayMs: Long
            get() = if (type == TYPE_SFX) value.substringAfterLast('@', "").toLongOrNull() ?: 0L else 0L

        /** SFX 名称（去掉 @延迟 段） */
        val name: String
            get() = if (type == TYPE_SFX) value.substringBeforeLast('@') else value
    }

    fun parse(raw: String): List<Tag> =
        TAG_REGEX.findAll(raw).map { m -> Tag(m.groupValues[1], m.groupValues[2]) }.toList()

    /** 剥离全部音频标签（不改变其余文本） */
    fun strip(text: String): String =
        if (text.contains("[[a:")) TAG_REGEX.replace(text, "") else text

    /** 提取原文标签串（保持原样拼接；用于 ScriptLineRow.audio） */
    fun extractRaw(text: String): String =
        if (!text.contains("[[a:")) "" else TAG_REGEX.findAll(text).joinToString("") { it.value }

    fun build(type: String, value: String): String = "[[a:$type=$value]]"

    /** 组装一个段落的全部标签（顺序：环境 → 音效 → BGM） */
    fun renderLine(ambience: String?, sfx: List<String>, bgm: String?): String {
        val sb = StringBuilder()
        if (!ambience.isNullOrBlank()) sb.append(build(TYPE_AMB, ambience))
        sfx.forEach { v -> if (v.isNotBlank()) sb.append(build(TYPE_SFX, v)) }
        if (!bgm.isNullOrBlank()) sb.append(build(TYPE_BGM, bgm))
        return sb.toString()
    }

    /** sfx 标签值：名称[@延迟ms] */
    fun sfxValue(tag: String, delayMs: Long): String =
        if (delayMs > 0) "$tag@$delayMs" else tag

    /** 剧本审查页 chips 文案（单行） */
    fun chipsText(raw: String): String =
        parse(raw).joinToString("  ") { t ->
            when (t.type) {
                TYPE_AMB -> "环境·${t.value}"
                TYPE_BGM -> "BGM·${t.value}"
                else -> buildString {
                    append("音效·").append(t.name)
                    t.delayMs.takeIf { it > 0 }?.let { append('@').append(it) }
                }
            }
        }
}
