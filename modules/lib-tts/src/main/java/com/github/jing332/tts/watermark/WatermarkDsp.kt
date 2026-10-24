package com.github.jing332.tts.watermark

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 试听水印检测（纯算法，无 Android 依赖，便于单测）。
 *
 * 原理（2026-10 实测，32 条呱呱样本全对）：
 * 平台在试听音频尾部叠加一段固定水印录音，该录音**本身频带只到 ~13.5 kHz**
 * （升采样进 48 kHz 主线后 14.5 kHz 以上是数字零）。正文 TTS 是 48 kHz 全带，
 * 两者在 "≥14.5 kHz 能量 / 0.2–4 kHz 能量" 这个比值上差 40~70 dB：
 *
 *   水印段：−71 ~ −59 dB      正文段：−57 ~ −33 dB
 *
 * 判据 = (正文比值 − 尾巴比值) ≥ Δ阈值 且 尾巴比值 ≤ 绝对阈值。
 * 16 bit 解码时受量化底噪限制余量只剩 ~2 dB，故阈值分两档（float 解码余量大）。
 */
object WatermarkDsp {

    private const val N = 1024
    private const val HOP_MS = 10

    /** 水印录音固定时长（秒） */
    const val WATERMARK_SEC = 1.22

    /** 尾巴窗口相对"最后一个有声帧"的右边界留白（秒）：水印结束后还有 ~0.25s 静音 */
    const val TAIL_MARGIN_SEC = 0.25

    /** float PCM 解码：余量大，收紧阈值 */
    private const val DELTA_FLOAT = 30f
    private const val TAIL_ABS_FLOAT = -85f

    /** 16 bit PCM 解码：受量化噪声限制，阈值放宽 */
    private const val DELTA_INT16 = 13f
    private const val TAIL_ABS_INT16 = -58f

    /** 有声帧门限（dBFS） */
    private const val LOUD_DB = -50f

    class Env(val ratio: FloatArray, val level: FloatArray, val hop: Int, val sr: Int)

    class Verdict(
        /** 建议裁剪位置（秒）：从该时刻到文件尾全部丢弃 */
        val cutSec: Double,
        val deltaDb: Float,
        val bodyDb: Float,
        val tailDb: Float
    )

    /** 判定结果（无论是否命中都带回依据，便于日志与探针） */
    class Analysis(
        val verdict: Verdict?,
        val reason: String,
        val bodyDb: Float = 0f,
        val tailDb: Float = 0f,
        val deltaDb: Float = 0f
    )

    private val cosT = DoubleArray(N / 2) { cos(-2.0 * PI * it / N) }
    private val sinT = DoubleArray(N / 2) { sin(-2.0 * PI * it / N) }
    private val hann = DoubleArray(N) { 0.5 - 0.5 * cos(2.0 * PI * it / (N - 1)) }

    /** 原地基 2 FFT（Double 精度；Float 旋转因子递推会漏出 −7 dB 假高频） */
    fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val half = len / 2
            val step = n / len
            var i = 0
            while (i < n) {
                for (k in 0 until half) {
                    val cr = cosT[k * step]
                    val ci = sinT[k * step]
                    val ur = re[i + k]
                    val ui = im[i + k]
                    val vr = re[i + k + half] * cr - im[i + k + half] * ci
                    val vi = re[i + k + half] * ci + im[i + k + half] * cr
                    re[i + k] = ur + vr
                    im[i + k] = ui + vi
                    re[i + k + half] = ur - vr
                    im[i + k + half] = ui - vi
                }
                i += len
            }
            len = len shl 1
        }
    }

    /**
     * @param pcm 单声道，归一化到 [-1,1]
     * @return null 表示采样率过低/长度不足，无法判定
     */
    fun envelope(pcm: FloatArray, sr: Int): Env? {
        if (sr < 32000) return null
        val hop = sr / (1000 / HOP_MS)
        if (pcm.size < N + hop) return null
        val nFrames = (pcm.size - N) / hop + 1
        val binHz = sr.toFloat() / N
        val loB = max(1, (200f / binHz).toInt())
        val hiB = min(N / 2, (4000f / binHz).toInt())
        val hfB = (14500f / binHz).toInt()
        if (hiB <= loB || hfB >= N / 2 - 1) return null

        val ratio = FloatArray(nFrames)
        val level = FloatArray(nFrames)
        val re = DoubleArray(N)
        val im = DoubleArray(N)
        for (f in 0 until nFrames) {
            val base = f * hop
            for (i in 0 until N) {
                re[i] = pcm[base + i].toDouble() * hann[i]
                im[i] = 0.0
            }
            fft(re, im)
            var lf = 0.0
            var hf = 0.0
            for (b in loB..hiB) lf += re[b] * re[b] + im[b] * im[b]
            for (b in hfB..N / 2) hf += re[b] * re[b] + im[b] * im[b]
            ratio[f] = if (lf <= 1e-12) -120f else (10.0 * log10(hf / lf + 1e-20)).toFloat()
            level[f] = if (lf <= 1e-12) -120f else (10.0 * log10(lf / N + 1e-20)).toFloat()
        }
        return Env(ratio, level, hop, sr)
    }

    private fun median(a: FloatArray): Float {
        if (a.isEmpty()) return -999f
        a.sort()
        return a[a.size / 2]
    }

    /**
     * @param isFloat 解码输出是否为 float PCM（决定阈值档位）
     */
    fun judge(env: Env, isFloat: Boolean): Analysis {
        val frames = env.ratio.size
        val loudIdx = ArrayList<Int>(frames)
        for (i in 0 until frames) if (env.level[i] > LOUD_DB) loudIdx.add(i)
        if (loudIdx.size < 40) return Analysis(null, "有声帧过少")

        val bodyArr = FloatArray(loudIdx.size) { env.ratio[loudIdx[it]] }
        val body = median(bodyArr)
        if (body < -72f) return Analysis(null, "整体窄带音源", body)

        val last = loudIdx[loudIdx.size - 1]
        val from = max(0, last - (WATERMARK_SEC * 1000 / HOP_MS).roundToInt())
        val tailVals = ArrayList<Float>()
        for (i in from..last) if (env.level[i] > LOUD_DB) tailVals.add(env.ratio[i])
        if (tailVals.size < 30) return Analysis(null, "尾巴有声帧过少", body)
        val tail = median(tailVals.toFloatArray())
        val delta = body - tail

        val deltaNeed = if (isFloat) DELTA_FLOAT else DELTA_INT16
        val absNeed = if (isFloat) TAIL_ABS_FLOAT else TAIL_ABS_INT16
        if (delta < deltaNeed) return Analysis(null, "尾巴非窄带", body, tail, delta)
        if (tail > absNeed) return Analysis(null, "尾巴高频活跃", body, tail, delta)

        // 名义切点：最后一个有声帧往前 (水印时长 + 60ms)；再吸附到 ±80ms 内的能量最低帧，
        // 避免切在字尾（爆音）或水印起音（残留咔哒）。
        var cutFrame = last - ((WATERMARK_SEC + 0.06) * 1000 / HOP_MS).roundToInt()
        val lo = max(0, cutFrame - 8)
        val hi = min(frames - 1, cutFrame + 8)
        var bestF = cutFrame
        var bestLv = Float.MAX_VALUE
        for (i in lo..hi) if (env.level[i] < bestLv) { bestLv = env.level[i]; bestF = i }
        cutFrame = bestF
        val cut = cutFrame.toDouble() * env.hop / env.sr
        if (cut < 0.5) return Analysis(null, "剩余正文过短", body, tail, delta)
        return Analysis(Verdict(cut, delta, body, tail), "命中", body, tail, delta)
    }
}