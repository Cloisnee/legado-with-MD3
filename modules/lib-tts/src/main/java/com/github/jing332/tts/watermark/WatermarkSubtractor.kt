package com.github.jing332.tts.watermark

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max

/**
 * 叠加态水印的时域相减（v6.1，2026-10-25 修对齐）。
 *
 * 与 v6 的差别只有一处，但是关键：**对齐不再用"增益拟合误差"做判据**。
 * 那个值在 1e-5~5e-4 之间、几乎不随 lag 变化，等于没有判据，会随机挑到偏 1~2 采样的 lag，
 * 实测同一段音频在广告窗内相差 15~20dB（真机上就是"听得见残留"）。
 * 现在改用 `maxBlockNcc`（干净块相关最大值）做粗搜 ±60ms / 细搜 1 采样，
 * 只有明显优于 hint（+0.03）才移动，否则保留检测给的位置。
 *
 * 增益模型不变：逐 100ms 块最小二乘，干净块 ≥3 拟合线性淡出，1~2 块用全局斜率 -0.22/s，
 * 一块都没有时用逐块增益中值。不做谱减。
 */
object WatermarkSubtractor {

    private const val BLOCK = 4800
    private const val GLOBAL_SLOPE = -0.22f
    private const val SEARCH_SEC = 0.06

    class Result(
        val pcm: FloatArray,
        val startSample: Int,
        val slope: Float,
        val intercept: Float,
        val cleanBlocks: Int,
        val totalBlocks: Int,
        /** 干净块上"剩余水印投影 / 原水印"的中值，dB */
        val residualDb: Float
    )

    private class Fit(
        val start: Int,
        val slope: Float,
        val intercept: Float,
        val clean: Int,
        val nb: Int
    ) {
        fun gainAt(i: Int): Float {
            val t = i.toFloat() / WatermarkDsp.SAMPLE_RATE
            return (slope * t + intercept).coerceIn(0f, 1.5f)
        }
    }

    fun subtract(pcm: FloatArray, wm: FloatArray, startHint: Int, minBlockNcc: Float = 0f): Result? {
        val n = wm.size
        if (startHint < 0 || startHint + n > pcm.size) return null

        val start = align(pcm, wm, startHint)
        val f = fit(pcm, wm, start) ?: return null

        // 增益合理性闸门：没有干净块时增益只能靠中值猜（例如 4134 会猜出 0.09），
        // 这种"减了等于没减"甚至减错方向的，宁可不做。
        var sumG = 0.0
        for (i in 0 until n) sumG += f.gainAt(i)
        val meanGain = (sumG / n).toFloat()
        if (meanGain < 0.25f || meanGain > 1.5f) return null

        val stats = WatermarkDsp.blockStats(pcm, wm, f.start)
        var maxNcc = -1f
        for (st in stats) if (st.ncc > maxNcc) maxNcc = st.ncc
        if (maxNcc < minBlockNcc) return null

        val out = pcm.copyOf()
        for (i in 0 until n) {
            out[f.start + i] -= f.gainAt(i) * wm[i]
        }

        // 干净块上"剩余水印"的投影，dB（相对原水印）
        val res = ArrayList<Float>()
        for (i in 0 until f.nb) {
            if (stats[i].ncc < 0.9f) continue
            val from = f.start + i * BLOCK
            var num = 0.0
            var den = 0.0
            for (k in 0 until BLOCK) {
                num += out[from + k].toDouble() * wm[i * BLOCK + k]
                den += wm[i * BLOCK + k].toDouble() * wm[i * BLOCK + k]
            }
            val g = if (den > 1e-12) num / den else 0.0
            res.add((20.0 * log10(abs(g) / max(abs(stats[i].gain), 1e-6f) + 1e-12)).toFloat())
        }
        res.sort()
        val rdb = if (res.isEmpty()) -999f else res[res.size / 2]
        return Result(out, f.start, f.slope, f.intercept, f.clean, f.nb, rdb)
    }

    /**
     * 对齐：以 hint 为基准，用 maxBlockNcc 粗搜 ±60ms（1ms 步）+ 细搜 ±1ms（1 采样步）。
     * 只有比 hint 高 0.03 才改用新的 lag —— 尾部探针给的 hint 本身就是 1 采样精度，
     * 不能被噪声带着乱跑。
     */
    private fun align(pcm: FloatArray, wm: FloatArray, hint: Int): Int {
        val hintScore = maxBlockNcc(pcm, wm, hint)
        var bestLag = hint
        var best = hintScore
        val span = (SEARCH_SEC * WatermarkDsp.SAMPLE_RATE).toInt()
        val lo = maxOf(0, hint - span)
        val hi = minOf(pcm.size - wm.size, hint + span)
        var lag = lo
        while (lag <= hi) {
            val v = maxBlockNcc(pcm, wm, lag)
            if (v > best) { best = v; bestLag = lag }
            lag += 48
        }
        val from = maxOf(0, bestLag - 48)
        val to = minOf(pcm.size - wm.size, bestLag + 48)
        lag = from
        while (lag <= to) {
            val v = maxBlockNcc(pcm, wm, lag)
            if (v > best) { best = v; bestLag = lag }
            lag++
        }
        return if (best >= hintScore + 0.03f) bestLag else hint
    }

    private fun maxBlockNcc(pcm: FloatArray, wm: FloatArray, start: Int): Float {
        if (start < 0 || start + wm.size > pcm.size) return -1f
        var best = -1f
        var i = 0
        while (i + BLOCK <= wm.size) {
            val v = WatermarkDsp.nccAt(pcm, wm, start + i, i, BLOCK)
            if (v > best) best = v
            i += BLOCK
        }
        return best
    }

    private fun fit(pcm: FloatArray, wm: FloatArray, start: Int): Fit? {
        val n = wm.size
        val nb = n / BLOCK
        if (nb < 2) return null
        val gains = FloatArray(nb)
        val nccs = FloatArray(nb)
        for (i in 0 until nb) {
            val from = start + i * BLOCK
            var num = 0.0
            var den = 0.0
            var mx = 0.0
            var my = 0.0
            for (k in 0 until BLOCK) { mx += pcm[from + k]; my += wm[i * BLOCK + k] }
            mx /= BLOCK
            my /= BLOCK
            var dx = 0.0
            var dy = 0.0
            var cc = 0.0
            for (k in 0 until BLOCK) {
                val a = pcm[from + k] - mx
                val b = wm[i * BLOCK + k] - my
                num += pcm[from + k].toDouble() * wm[i * BLOCK + k]
                den += wm[i * BLOCK + k].toDouble() * wm[i * BLOCK + k]
                cc += a * b
                dx += a * a
                dy += b * b
            }
            gains[i] = if (den > 1e-12) (num / den).toFloat() else 0f
            nccs[i] = if (dx * dy > 1e-24) (cc / kotlin.math.sqrt(dx * dy)).toFloat() else 0f
        }
        val clean = ArrayList<Int>()
        for (i in 0 until nb) if (nccs[i] >= 0.9f) clean.add(i)

        var slope: Float
        var intercept: Float
        if (clean.size >= 3) {
            var sx = 0.0
            var sy = 0.0
            var sxx = 0.0
            var sxy = 0.0
            for (i in clean) {
                val t = (i * BLOCK + BLOCK / 2.0) / WatermarkDsp.SAMPLE_RATE
                val g = gains[i].toDouble()
                sx += t; sy += g; sxx += t * t; sxy += t * g
            }
            val cnt = clean.size.toDouble()
            val den = cnt * sxx - sx * sx
            if (abs(den) < 1e-12) {
                slope = 0f
                intercept = (sy / cnt).toFloat()
            } else {
                slope = ((cnt * sxy - sx * sy) / den).toFloat()
                intercept = ((sy - slope * sx) / cnt).toFloat()
            }
        } else if (clean.isNotEmpty()) {
            slope = GLOBAL_SLOPE
            val v = ArrayList<Float>()
            for (i in clean) {
                val t = ((i * BLOCK + BLOCK / 2.0) / WatermarkDsp.SAMPLE_RATE).toFloat()
                v.add(gains[i] - slope * t)
            }
            v.sort()
            intercept = v[v.size / 2]
        } else {
            slope = 0f
            val v = gains.toMutableList()
            v.sort()
            intercept = v[v.size / 2]
        }
        return Fit(start, slope, intercept, clean.size, nb)
    }
}
