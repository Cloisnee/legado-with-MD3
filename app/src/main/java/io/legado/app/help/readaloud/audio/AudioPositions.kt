package io.legado.app.help.readaloud.audio

/**
 * B34.2 · 音效触发位置（⑨）：
 *  - AI 导演回「前/中/后」→ **中点映射**（前≈17% / 中≈50% / 后≈83%）；
 *  - 本地规则用精确命中位（命中字符位 ÷ 句长，钳 5%~95%）；
 *  - 播放时按「句长 ÷ 读速（跟朗读速度设置换算）≈ 本句时长」估算延迟毫秒。
 */
object AudioPositions {

    const val POS_FRONT = "前"
    const val POS_MID = "中"
    const val POS_BACK = "后"

    /** 前/中/后 → 代表性位置比例（中点映射；非法/空白返回 0=句首） */
    fun ratioOf(pos: String): Float = when (pos.trim().lowercase()) {
        POS_FRONT, "front", "begin" -> 1f / 6f
        POS_MID, "mid", "middle" -> 0.5f
        POS_BACK, "back", "end" -> 5f / 6f
        else -> 0f
    }

    /** 命中字符位 → 句内比例（钳 5%~95%） */
    fun ratioOfMatch(matchStart: Int, textLength: Int): Float {
        if (textLength <= 0 || matchStart < 0) return 0f
        return (matchStart.toFloat() / textLength).coerceIn(0.05f, 0.95f)
    }

    /** 本地切分函数：按字数三等分（前/中/后段的字符区间；空串返回三个空区间） */
    fun splitThirds(text: String): Triple<IntRange, IntRange, IntRange> {
        val len = text.length
        if (len <= 0) return Triple(IntRange.EMPTY, IntRange.EMPTY, IntRange.EMPTY)
        val a = ((len + 2) / 3).coerceAtMost(len)
        val b = ((2 * len + 2) / 3).coerceAtMost(len)
        return Triple(0 until a, a until b, b until len)
    }

    /** 延迟估算：比例 × 本句估算时长（毫秒）；比例 ≤0 或空串返回 0（句首） */
    fun delayMs(textLength: Int, ratio: Float, charsPerSec: Float): Long {
        if (textLength <= 0 || ratio <= 0f) return 0L
        val cps = charsPerSec.coerceAtLeast(1f)
        val durMs = textLength * 1000f / cps
        return (durMs * ratio.coerceIn(0f, 0.95f)).toLong().coerceAtLeast(0L)
    }
}