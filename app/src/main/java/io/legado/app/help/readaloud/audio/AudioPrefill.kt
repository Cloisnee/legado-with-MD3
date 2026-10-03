package io.legado.app.help.readaloud.audio

/**
 * B34.2b · 导演预插标记（③ · 纯逻辑）：
 *  - [markersFor]：某片段的本地词典建议 → 导演输入内联标记（〔音效建议：…〕）；
 *  - [PlanSeed] + [buildRulesPlan]：兜底章——本地建议（已换算为剧本行锚点+句内比例）
 *    编译为「章节计划」（source=rules），供首章/非连续/未设置Ai/AI失败·超时在落库时点写入剧本。
 */
object AudioPrefill {

    private const val MAX_SFX = 80
    private const val MAX_AMB = 12
    private const val MAX_BGM = 8

    /** 兜底计划种子（由管线把 段/片段 锚点换算为剧本行号=segPara + 句内比例） */
    data class PlanSeed(
        val segPara: Int,
        val lane: SynthLane,
        val label: String,
        val ratio: Float = 0f,
    )

    fun laneLabel(lane: SynthLane): String = when (lane) {
        SynthLane.SFX -> "音效"
        SynthLane.AMB -> "环境"
        SynthLane.BGM -> "BGM"
    }

    /** 某片段建议 → 内联标记串（顺序=建议顺序；空白名跳过） */
    fun markersFor(suggestions: List<AudioLaneScan.Suggestion>): String =
        suggestions.filter { it.label.isNotBlank() }
            .joinToString("") { sug -> "〔${laneLabel(sug.lane)}建议：${sug.label}〕" }

    /** 兜底计划：种子 → 章节计划（source=rules；按剧本行号排序；同轨上限） */
    fun buildRulesPlan(seeds: List<PlanSeed>): AudioPlan {
        val amb = ArrayList<AudioPlanItem>()
        val sfx = ArrayList<AudioPlanItem>()
        val bgm = ArrayList<AudioPlanItem>()
        seeds.forEach { seed ->
            if (seed.label.isBlank() || seed.segPara <= 0) return@forEach
            when (seed.lane) {
                SynthLane.AMB -> amb += AudioPlanItem(seed.segPara, AudioTagCodec.TYPE_AMB, tag = seed.label)
                SynthLane.BGM -> bgm += AudioPlanItem(seed.segPara, AudioTagCodec.TYPE_BGM, tag = seed.label)
                else -> sfx += AudioPlanItem(
                    para = seed.segPara,
                    type = AudioTagCodec.TYPE_SFX,
                    tag = seed.label,
                    posRatio = seed.ratio,
                )
            }
        }
        return AudioPlan(
            source = "rules",
            ambience = amb.sortedBy { it.para }.take(MAX_AMB),
            bgm = bgm.sortedBy { it.para }.take(MAX_BGM),
            sfx = sfx.sortedBy { it.para }.take(MAX_SFX),
        )
    }
}
