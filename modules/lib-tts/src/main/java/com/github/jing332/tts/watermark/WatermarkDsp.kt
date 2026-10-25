package com.github.jing332.tts.watermark

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 试听水印识别（纯算法，无 Android 依赖，可单测）。
 *
 * 判据 = 与内嵌水印模板的 log-mel 相关度（不是"高频带限"这类启发式）。
 * 2026-10-24 在 32 条实测样本上的分布：
 *
 *   有水印：0.772 ~ 0.987      无水印：0.143 ~ 0.569
 *
 * 阈值 [MATCH_THRESHOLD]=0.80（0.65~0.79 这段实测出现过误切，宁可不动）；
 * 再叠加"仅 48 kHz MP3"与"窗口必须窄带"两道确认，
 * 其他插件既不会有这个采样率特征、也匹配不上这段录音，误伤面收敛到零。
 *
 * 特征 = 40 维 log-mel（25 ms 窗 / 10 ms 帧移 / 60–7600 Hz），**逐帧 z-score**，
 * 因此对水印增益差（实测 −3 ~ −19 dB）免疫。
 */
object WatermarkDsp {

    const val SAMPLE_RATE = 48000
    private const val NFFT = 2048
    private const val WIN = 1200          // 25 ms @48k
    private const val HOP = 480           // 10 ms
    private const val NMEL = 40
    private const val MEL_LO = 60f
    private const val MEL_HI = 7600f

    /** 模板长度（帧）= 水印录音时长 1.2 s */
    const val TEMPLATE_FRAMES = 120
    private const val WATERMARK_FRAMES = 120

    /** 命中阈值 */
    const val MATCH_THRESHOLD = 0.80f

    class Match(val score: Float, val startSample: Int)

    private val cosT = DoubleArray(NFFT / 2) { cos(-2.0 * PI * it / NFFT) }
    private val sinT = DoubleArray(NFFT / 2) { sin(-2.0 * PI * it / NFFT) }
    private val hann = DoubleArray(WIN) { 0.5 - 0.5 * cos(2.0 * PI * it / (WIN - 1)) }

    private fun hz2mel(f: Double) = 2595.0 * log10(1.0 + f / 700.0)
    private fun mel2hz(m: Double) = 700.0 * (exp(m / 2595.0) - 1.0)

    private val filters: Array<DoubleArray> by lazy {
        val binHz = SAMPLE_RATE.toDouble() / NFFT
        val lo = hz2mel(MEL_LO.toDouble())
        val hi = hz2mel(MEL_HI.toDouble())
        val edges = DoubleArray(NMEL + 2) { mel2hz(lo + (hi - lo) * it / (NMEL + 1)) }
        Array(NMEL) { i ->
            val l = edges[i]
            val c = edges[i + 1]
            val r = edges[i + 2]
            DoubleArray(NFFT / 2 + 1) { b ->
                val f = b * binHz
                max(0.0, min((f - l) / max(c - l, 1e-9), (r - f) / max(r - c, 1e-9)))
            }
        }
    }

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

    /** [startSample, startSample + frames*HOP) 的 log-mel，逐帧 z-score；行优先 frames*NMEL */
    fun mel(pcm: FloatArray, startSample: Int, frames: Int): FloatArray {
        val out = FloatArray(frames * NMEL)
        val re = DoubleArray(NFFT)
        val im = DoubleArray(NFFT)
        val pow = DoubleArray(NFFT / 2 + 1)
        for (f in 0 until frames) {
            val base = startSample + f * HOP
            if (base < 0 || base + WIN > pcm.size) continue
            java.util.Arrays.fill(re, 0.0)
            java.util.Arrays.fill(im, 0.0)
            for (i in 0 until WIN) re[i] = pcm[base + i].toDouble() * hann[i]
            fft(re, im)
            for (b in 0..NFFT / 2) pow[b] = re[b] * re[b] + im[b] * im[b]
            var sum = 0.0
            var sum2 = 0.0
            for (m in 0 until NMEL) {
                var e = 1e-8
                val filt = filters[m]
                for (b in 0..NFFT / 2) e += pow[b] * filt[b]
                val v = ln(e)
                out[f * NMEL + m] = v.toFloat()
                sum += v
                sum2 += v * v
            }
            val mean = sum / NMEL
            var sd = sum2 / NMEL - mean * mean
            if (sd < 1e-12) sd = 1e-12
            sd = sqrt(sd)
            for (m in 0 until NMEL) {
                val idx = f * NMEL + m
                out[idx] = ((out[idx] - mean) / sd).toFloat()
            }
        }
        return out
    }

    /** Base64 → float 模板（int8 量化） */
    fun decodeTemplate(b64: String, scale: Float): FloatArray {
        val bytes = java.util.Base64.getDecoder().decode(b64)
        val out = FloatArray(bytes.size)
        for (i in bytes.indices) out[i] = bytes[i] / scale
        return out
    }

    /**
     * 全片扫描：水印可能被插在**句子中间**（前后都还有台词），只看尾巴会漏。
     * @return 所有 ≥[MATCH_THRESHOLD] 的命中，按得分降序
     */
    fun locateAll(pcm: FloatArray, template: FloatArray, minScore: Float = MATCH_THRESHOLD): List<Match> {
        val frames = pcm.size / HOP
        if (frames < TEMPLATE_FRAMES + 8) return emptyList()
        val m = mel(pcm, 0, frames)
        val out = ArrayList<Match>()
        val last = frames - TEMPLATE_FRAMES
        for (i in 0..last) {
            var s = 0f
            var a = i * NMEL
            var b = 0
            for (f in 0 until TEMPLATE_FRAMES) {
                for (k in 0 until NMEL) s += m[a + k] * template[b + k]
                a += NMEL
                b += NMEL
            }
            s /= (TEMPLATE_FRAMES * NMEL)
            if (s >= minScore) out.add(Match(s, i * HOP))
        }
        out.sortByDescending { it.score }
        return out
    }

    /**
     * 与模板包络的相关系数。干净拼接 ≈0.95+；与台词叠加时被污染，掉到 0.4 以下。
     * 用途：叠加态下**不要切**（切了会连台词一起切掉）。
     */
    fun envelopeCorr(pcm: FloatArray, startSample: Int, env: IntArray): Float {
        var best = -1f
        for (shift in -5..5) {
            val v = envelopeCorrAt(pcm, startSample + shift * HOP, env)
            if (v > best) best = v
        }
        return best
    }

    private fun envelopeCorrAt(pcm: FloatArray, startSample: Int, env: IntArray): Float {
        val n = min(env.size, TEMPLATE_FRAMES)
        if (n < 40 || startSample < 0) return -1f
        if (startSample + n * HOP > pcm.size) return -1f
        val a = FloatArray(n)
        var mean = 0.0
        for (i in 0 until n) {
            val b = startSample + i * HOP
            var e = 0.0
            for (k in 0 until HOP) {
                val v = pcm[b + k].toDouble()
                e += v * v
            }
            a[i] = sqrt(e / HOP).toFloat()
            mean += a[i]
        }
        mean /= n
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in 0 until n) {
            val x = a[i] - mean.toFloat()
            val y = env[i].toFloat()
            dot += x * y
            na += x * x
            nb += y * y
        }
        if (na <= 1e-12 || nb <= 1e-12) return -1f
        return (dot / sqrt(na * nb)).toFloat()
    }

    /**
     * 用模板的**后半段**做探针。叠加态时水印前半段常被台词压住，整模板匹配会被稀释到 0.5 以下；
     * 而尾部通常干净（台词早已说完），0.6s 探针能拿到 0.98+。
     * @return Match.startSample 已换算成**水印起点**
     */
    fun locateByProbe(
        pcm: FloatArray,
        template: FloatArray,
        probeFrom: Int,
        probeFrames: Int,
        minScore: Float
    ): List<Match> {
        val frames = pcm.size / HOP
        if (frames < probeFrames + 8) return emptyList()
        val m = mel(pcm, 0, frames)
        val out = ArrayList<Match>()
        val last = frames - probeFrames
        for (i in 0..last) {
            var acc = 0f
            var a = i * NMEL
            var b = probeFrom * NMEL
            for (f in 0 until probeFrames) {
                for (k in 0 until NMEL) acc += m[a + k] * template[b + k]
                a += NMEL
                b += NMEL
            }
            acc /= (probeFrames * NMEL)
            if (acc >= minScore) out.add(Match(acc, i * HOP - probeFrom * HOP))
        }
        out.sortByDescending { it.score }
        return out
    }

    /** 水印时长（样本数） */
    fun watermarkSamples(): Int = WATERMARK_FRAMES * HOP

    /** 在 [centerSample] 附近 ±range 帧内取能量最低的 10 ms 帧起点（避免切在字尾） */
    fun snapToQuietest(pcm: FloatArray, centerSample: Int, range: Int): Int {
        var best = centerSample
        var bestE = Double.MAX_VALUE
        for (k in -range..range) {
            val s = centerSample + k * HOP
            if (s < 0 || s + HOP > pcm.size) continue
            var e = 0.0
            for (i in 0 until HOP) {
                val v = pcm[s + i].toDouble()
                e += v * v
            }
            if (e < bestE) {
                bestE = e
                best = s
            }
        }
        return best
    }

    /** 逐帧高频比：10log10(≥14.5k / 0.2–4k)，帧移 10 ms */
    fun hfRatios(pcm: FloatArray): FloatArray {
        val n = pcm.size / HOP
        if (n < 8) return FloatArray(0)
        val out = FloatArray(n)
        val re = DoubleArray(NFFT)
        val im = DoubleArray(NFFT)
        val hfB = (14500.0 / (SAMPLE_RATE.toDouble() / NFFT)).toInt()
        val loB = max(1, (200.0 / (SAMPLE_RATE.toDouble() / NFFT)).toInt())
        val hiB = min(NFFT / 2, (4000.0 / (SAMPLE_RATE.toDouble() / NFFT)).toInt())
        for (f in 0 until n) {
            val base = f * HOP
            if (base + WIN > pcm.size) break
            java.util.Arrays.fill(re, 0.0)
            java.util.Arrays.fill(im, 0.0)
            for (i in 0 until WIN) re[i] = pcm[base + i].toDouble() * hann[i]
            fft(re, im)
            var lf = 0.0
            var hf = 0.0
            for (b in loB..hiB) lf += re[b] * re[b] + im[b] * im[b]
            for (b in hfB..NFFT / 2) hf += re[b] * re[b] + im[b] * im[b]
            out[f] = if (lf <= 1e-12) -120f else (10.0 * log10(hf / lf + 1e-20)).toFloat()
        }
        return out
    }

    /**
     * 确认命中窗口是否"窄带"：水印素材频带只到 ~13.5 kHz。
     * @return 正文高频比 − 窗口高频比（dB）；≥12 基本可判定为水印
     */
    fun bandLimitDelta(pcm: FloatArray, startSample: Int): Float {
        val r = hfRatios(pcm)
        if (r.isEmpty()) return -999f
        val loud = ArrayList<Float>()
        val level = hfLevels(pcm)
        for (i in r.indices) if (i < level.size && level[i] > -50f) loud.add(r[i])
        if (loud.size < 20) return -999f
        loud.sort()
        val body = loud[loud.size / 2]
        val from = (startSample / HOP).coerceAtLeast(0)
        val to = min(r.size - 1, from + TEMPLATE_FRAMES - 1)
        if (to - from < 40) return -999f
        val win = ArrayList<Float>()
        for (i in from..to) if (i < level.size && level[i] > -50f) win.add(r[i])
        if (win.size < 30) return -999f
        win.sort()
        return body - win[win.size / 2]
    }

    private fun hfLevels(pcm: FloatArray): FloatArray {
        val n = pcm.size / HOP
        val out = FloatArray(n)
        val re = DoubleArray(NFFT)
        val im = DoubleArray(NFFT)
        val loB = max(1, (200.0 / (SAMPLE_RATE.toDouble() / NFFT)).toInt())
        val hiB = min(NFFT / 2, (4000.0 / (SAMPLE_RATE.toDouble() / NFFT)).toInt())
        for (f in 0 until n) {
            val base = f * HOP
            if (base + WIN > pcm.size) break
            java.util.Arrays.fill(re, 0.0)
            java.util.Arrays.fill(im, 0.0)
            for (i in 0 until WIN) re[i] = pcm[base + i].toDouble() * hann[i]
            fft(re, im)
            var lf = 0.0
            for (b in loB..hiB) lf += re[b] * re[b] + im[b] * im[b]
            out[f] = if (lf <= 1e-12) -120f else (10.0 * log10(lf / NFFT + 1e-20)).toFloat()
        }
        return out
    }

    // ================= v6 追加：尾部时域探针 =================

    /** 一阶高通（前向+后向，零相位）；去掉语音的低频包络，保留相位相干性 */
    fun highPass(x: FloatArray, fc: Float, sr: Int): FloatArray {
        val a = exp(-2.0 * PI * fc / sr).toFloat()
        val y = FloatArray(x.size)
        var px = 0f
        var py = 0f
        for (i in x.indices) {
            val v = x[i]
            y[i] = a * (py + v - px)
            px = v
            py = y[i]
        }
        val z = FloatArray(x.size)
        px = 0f
        py = 0f
        for (i in x.indices.reversed()) {
            val v = y[i]
            z[i] = a * (py + v - px)
            px = v
            py = z[i]
        }
        return z
    }

    /** 任意偏移的归一化互相关 */
    fun nccAt(x: FloatArray, y: FloatArray, xFrom: Int, yFrom: Int, n: Int): Float {
        if (xFrom < 0 || xFrom + n > x.size || yFrom < 0 || yFrom + n > y.size) return -1f
        var mx = 0.0
        var my = 0.0
        for (i in 0 until n) { mx += x[xFrom + i]; my += y[yFrom + i] }
        mx /= n
        my /= n
        var num = 0.0
        var dx = 0.0
        var dy = 0.0
        for (i in 0 until n) {
            val a = x[xFrom + i] - mx
            val b = y[yFrom + i] - my
            num += a * b
            dx += a * a
            dy += b * b
        }
        val d = sqrt(dx * dy)
        return if (d < 1e-12) 0f else (num / d).toFloat()
    }

    class TailMatch(val score: Float, val startSample: Int)

    /**
     * 尾部位置先验探针：模板末 0.3s 与混音（双方都 800Hz 高通）的时域 NCC。
     * 只在 EOF-0.25s-1.22s 附近 ±90ms 搜。
     * 实测：干净语音 ≤0.07；被台词完全盖住的叠加态 0.25~0.45；干净拼接 ≈1.0。
     */
    fun tailProbe(
        pcm: FloatArray,
        templateHp: FloatArray,
        sr: Int,
        searchSec: Double = 0.09,
        minScore: Float = 0f
    ): TailMatch? {
        val m = templateHp.size
        val l = 30 * HOP
        if (l >= m || pcm.size < m + (0.4 * sr).toInt()) return null
        val margin = (0.25 * sr).toInt() + (0.09 * sr).toInt() + (0.10 * sr).toInt()
        val segStart = maxOf(0, pcm.size - m - margin)
        val seg = highPass(pcm.copyOfRange(segStart, pcm.size), 800f, sr)
        val exp = pcm.size - (0.25 * sr).toInt() - m - segStart
        val half = (searchSec * sr).toInt()
        val lo = maxOf(0, exp - half)
        val hi = minOf(seg.size - l, exp + half)
        if (hi <= lo) return null
        var best = -9f
        var bestLag = lo
        var lag = lo
        while (lag <= hi) {
            val v = nccAt(seg, templateHp, lag + m - l, m - l, l)
            if (v > best) { best = v; bestLag = lag }
            lag += 48
        }
        val from = maxOf(lo, bestLag - 48)
        val to = minOf(hi, bestLag + 48)
        lag = from
        while (lag <= to) {
            val v = nccAt(seg, templateHp, lag + m - l, m - l, l)
            if (v > best) { best = v; bestLag = lag }
            lag++
        }
        if (best < minScore) return null
        return TailMatch(best, segStart + bestLag)
    }

    class BlockStat(val gain: Float, val ncc: Float)

    /** 逐 100ms 块的最小二乘增益与相关（用于分流/增益拟合） */
    fun blockStats(pcm: FloatArray, wm: FloatArray, start: Int, block: Int = HOP * 10): Array<BlockStat> {
        val nb = wm.size / block
        return Array(nb) { i ->
            val from = start + i * block
            var num = 0.0
            var den = 0.0
            for (k in 0 until block) {
                num += pcm[from + k].toDouble() * wm[i * block + k]
                den += wm[i * block + k].toDouble() * wm[i * block + k]
            }
            val g = if (den > 1e-12) (num / den).toFloat() else 0f
            BlockStat(g, nccAt(pcm, wm, from, i * block, block))
        }
    }

}
