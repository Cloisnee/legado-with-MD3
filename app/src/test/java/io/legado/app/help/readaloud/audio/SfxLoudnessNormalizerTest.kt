package io.legado.app.help.readaloud.audio

import io.legado.app.help.readaloud.playback.AudioLoudnessMeasure
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * P1.6.2 · 声效响度均衡：trim 公式与测量口径（纯函数）单测。
 */
class SfxLoudnessNormalizerTest {

    private fun pcm16(vararg samples: Int): ByteArray {
        val out = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, s ->
            out[2 * i] = (s and 0xFF).toByte()
            out[2 * i + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return out
    }

    @Test
    fun `等响度时 trim 为零`() {
        assertEquals(0.0, SfxLoudnessNormalizer.trimDbOf(0.01, 0.01), 1e-9)
    }

    @Test
    fun `低于基准一半需提升约 3dB`() {
        // ref/p = 2 → 10·log10(2) ≈ 3.0103
        assertEquals(3.0103, SfxLoudnessNormalizer.trimDbOf(0.01, 0.005), 1e-3)
    }

    @Test
    fun `高于基准十倍需衰减 10dB`() {
        assertEquals(-10.0, SfxLoudnessNormalizer.trimDbOf(0.01, 0.1), 1e-9)
    }

    @Test
    fun `超上限被 clamp 至正 12dB`() {
        assertEquals(12.0, SfxLoudnessNormalizer.trimDbOf(1.0, 0.01), 1e-9)
    }

    @Test
    fun `超下限被 clamp 至负 12dB`() {
        assertEquals(-12.0, SfxLoudnessNormalizer.trimDbOf(0.0001, 1.0), 1e-9)
    }

    @Test
    fun `非法输入返回零`() {
        assertEquals(0.0, SfxLoudnessNormalizer.trimDbOf(0.0, 0.1), 1e-9)
        assertEquals(0.0, SfxLoudnessNormalizer.trimDbOf(0.1, 0.0), 1e-9)
    }

    @Test
    fun `有效区均方 半幅常量`() {
        val bytes = pcm16(*IntArray(4000) { 16384 })
        val mean = AudioLoudnessMeasure.effectiveMeanSquare(bytes)
        assertEquals(0.25, mean ?: -1.0, 1e-6)
    }

    @Test
    fun `短窗峰值 取窗口最大`() {
        // 前半低幅（不足阈值）、后半半幅：窗口扫描应命中后半
        val low = IntArray(4410) { 100 }
        val high = IntArray(4410) { 16384 }
        val bytes = pcm16(*(low + high))
        val win = AudioLoudnessMeasure.windowPeakPower(bytes, 44100)
        assertEquals(0.25, win ?: -1.0, 1e-6)
    }

    @Test
    fun `全静音无法测量`() {
        val zero = ByteArray(4000)
        assertEquals(null, AudioLoudnessMeasure.effectiveMeanSquare(zero))
        assertEquals(null, AudioLoudnessMeasure.windowPeakPower(zero, 44100))
    }
}