package io.legado.app.help.readaloud.audio

/**
 * B34.2 · 导演预插标记（③ · 纯逻辑）：
 *  - [markersFor]：某段的本地词典建议 → 导演输入内联标记（〔音效建议：…〕）；
 *  - [buildRulesPlan]：全章建议 → 兜底「章节计划」（source=rules，含 posRatio），
 *    供首章/非连续/未设置Ai/AI失败·超时章在落库时点写入剧本。
 */
object AudioPrefill {

    private const val MAX_SFX = 80
    private const val MAX_AMB = 12
    private const val MAX_BGM = 8

    fun laneLabel(lane: SynthLane): String = when (lane) {
        SynthLane.SFX -> "音效"
        SynthLane.AMB -> "环境"
        SynthLane.BGM -> "BGM"
    }

    /** 某段建议 → 内联标记串（顺序=建议顺序；空白名跳过） */
    fun markersFor(suggestions: List<AudioLaneScan.Suggestion>): String =
        suggestions.filter { it.label.isNotBlank() }
            .joinToString("") { sug -> "〔${laneLabel(sug.lane)}建议：${sug.label}〕" }

    /** 全章建议 → 兜底计划（段序号=列表下标+1，与导演输入编号一致） */
    fun buildRulesPlan(suggestions: List<List<AudioLaneScan.Suggestion>>): AudioPlan {
        val amb = ArrayList<AudioPlanItem>()
        val sfx = ArrayList<AudioPlanItem>()
        val bgm = ArrayList<AudioPlanItem>()
        suggestions.forEachIndexed { i, list ->
            val para = i + 1
            list.forEach { sug ->
                if (sug.label.isBlank()) return@forEach
                when (sug.lane) {
                    SynthLane.AMB -> amb += AudioPlanItem(para, AudioTagCodec.TYPE_AMB, tag = sug.label)
                    SynthLane.BGM -> bgm += AudioPlanItem(para, AudioTagCodec.TYPE_BGM, tag = sug.label)
                    else -> sfx += AudioPlanItem(
                        para = para,
                        type = AudioTagCodec.TYPE_SFX,
                        tag = sug.label,
                        posRatio = sug.ratio,
                    )
                }
            }
        }
        return AudioPlan(
            source = "rules",
            ambience = amb.take(MAX_AMB),
            bgm = bgm.take(MAX_BGM),
            sfx = sfx.take(MAX_SFX),
        )
    }
}