package io.legado.app.domain.model.readaloud

/**
 * 朗读情绪 → 插件桥字段（`ttsrv.tts.emotion`）的下发口径。
 *
 * 分析词表固定十个词（见 SpeechAnalysisPipelineV3.EMOTIONS）：平静/愉快/悲伤/愤怒/恐惧/
 * 惊讶/厌恶/严肃/温柔/鄙夷。旁白行没有情绪（空串），「平静」是中性默认——两者都不下发，
 * 让插件走它自己的默认；其余情绪原样带给插件（插件按自家词表映射）。
 */
object ReadAloudEmotion {

    /** 分析词表里的中性默认词；不下发。 */
    const val NEUTRAL = "平静"

    /** 送给插件的情绪值：空（旁白）与中性（平静）→ 空串；其余原样。 */
    fun forPlugin(emotion: String?): String =
        emotion?.trim().orEmpty().takeIf { it.isNotEmpty() && it != NEUTRAL }.orEmpty()
}
