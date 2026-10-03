package io.legado.app.help.readaloud.audio

/**
 * B34.2b · 音效触发位置：
 *  - 片段化（第1阶段同款 〖第N段〗/[m] 模板）后，位置由「选片段」表达；
 *  - 本地规则另带**片内命中位**（命中字符位 ÷ 片段长），与片段起点合成句内比例；
 *  - 播放时按「句长 ÷ 读速（跟朗读速度设置换算）≈ 本句时长」估算延迟毫秒。
 */
object AudioPositions {

    /** 命中字符位 → 片段内比例（钳 5%~95%） */
    fun ratioOfMatch(matchStart: Int, textLength: Int): Float {
        if (textLength <= 0 || matchStart < 0) return 0f
        return (matchStart.toFloat() / textLength).coerceIn(0.05f, 0.95f)
    }

    /** 片段起点 + 片内命中比 → 所在剧本行的句内比例（钳 0..1） */
    fun ratioOfUnit(
        segStart: Int,
        segEnd: Int,
        unitStart: Int,
        unitLen: Int,
        hitRatioInUnit: Float,
    ): Float {
        val segLen = (segEnd - segStart).coerceAtLeast(1)
        val base = (unitStart - segStart) +
            hitRatioInUnit.coerceIn(0f, 1f) * unitLen.coerceAtLeast(0)
        return (base / segLen).coerceIn(0f, 1f)
    }

    /** 延迟估算：比例 × 本句估算时长（毫秒）；比例 ≤0 或空串返回 0（句首） */
    fun delayMs(textLength: Int, ratio: Float, charsPerSec: Float): Long {
        if (textLength <= 0 || ratio <= 0f) return 0L
        val cps = charsPerSec.coerceAtLeast(1f)
        val durMs = textLength * 1000f / cps
        return (durMs * ratio.coerceIn(0f, 0.95f)).toLong().coerceAtLeast(0L)
    }
}