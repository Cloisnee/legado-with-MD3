package io.legado.app.help.readaloud.audio

/**
 * B33.4b · 合成提示词（AudioPrompts）——原 `promptFor` 并入本对象：
 *
 * - **desc 优先**：Ai 导演计划携带的生成描述（一句具体声音/氛围），缺失时回退关键词规范化；
 * - 平台模板按轨包装：Gen=方括号（防念长句）；Music=caption（纯器乐/无人声）；商汤=具体声音名。
 */
object AudioPrompts {

    /** 单次生成输入上限（防模型念长句/超长 caption） */
    private const val MAX_CHARS = 40

    /** Gen / Music 用（方括号 / caption 模板） */
    fun of(lane: SynthLane, keyword: String, desc: String = ""): String {
        val k = pickText(keyword, desc)
        return when (lane) {
            SynthLane.SFX -> "[$k]"
            SynthLane.AMB -> "[$k 环境]"
            SynthLane.BGM -> "$k 氛围，纯器乐配乐，无人声"
        }
    }

    /** 商汤 SFX 文本（官方要求「具体声音名称」，不要方括号；环境附「环境声」） */
    fun senseText(lane: SynthLane, keyword: String, desc: String = ""): String {
        val k = if (desc.isNotBlank()) cut(desc) else legacySense(keyword)
        if (lane == SynthLane.AMB && !k.contains("环境")) return "$k，环境声"
        return k
    }

    internal fun pickText(keyword: String, desc: String): String =
        if (desc.isNotBlank()) cut(desc) else legacyKeyword(keyword)

    /** 旧 promptFor 口径：仅去「音效/声效」尾缀（保留 声/音） */
    private fun legacyKeyword(keyword: String): String {
        val raw = keyword.trim()
        return raw.removeSuffix("音效").removeSuffix("声效").trim().ifBlank { raw }
    }

    /** 旧 senseTextFor 口径：补「声/音/响/语」之一（B33.3c-附2） */
    private fun legacySense(keyword: String): String {
        var k = legacyKeyword(keyword)
        val soundish = k.endsWith("声") || k.endsWith("音") || k.endsWith("响") || k.endsWith("语")
        if (!soundish) k = "${k}声"
        return k
    }

    private fun cut(s: String): String {
        var k = s.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
        k = k.removeSuffix("。").trim()
        if (k.length > MAX_CHARS) k = k.take(MAX_CHARS).trim()
        return k
    }
}
