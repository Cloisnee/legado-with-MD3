package com.github.jing332.tts.watermark

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 叠加态水印的时域相减（v6，2026-10-26 重写）。
 *
 * 与 v5 的差别：
 *  1. 增益不再依赖"纯净段必须在窗口前 60%"：逐 100ms 块做最小二乘，
 *     干净块 ≥3 时拟合线性 `g(t)=a*t+b`（平台叠加时真的有淡出，斜率逐条不同）；
 *     1~2 块时用全局斜率 -0.22/s + 中值截距；一块都没有时用逐块增益中值。
 *  2. ±2 采样内按"干净块增益的线性拟合残差"选对齐（1 个采样误差就够毁掉高频）。
 *  3. 不做谱减：模型准了以后谱减只会二次削台词。
 */
object WatermarkSubtractor {

    private const val BLOCK = 4800
    private const val GLOBAL_SLOPE = -0.22f

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
        val nb: Int,
        val err: Double
    ) {
        fun gainAt(i: Int): Float {
            val t = i.toFloat() / WatermarkDsp.SAMPLE_RATE
            return (slope * t + intercept).coerceIn(0f, 1.5f)
        }
    }

    fun subtract(pcm: FloatArray, wm: FloatArray, startHint: Int, minBlockNcc: Float = 0f): Result? {
        val n = wm.size
        if (startHint < 0 || startHint + n > pcm.size) return null

        // 1) 对齐：±2 采样，按干净块增益的线性拟合残差选
        var best: Fit? = null
        for (d in -2..2) {
            val s = startHint + d
            if (s < 0 || s + n > pcm.size) continue
            val f = fit(pcm, wm, s) ?: continue
            if (best == null || f.err < best.err) best = f
        }
        val f = best ?: return null

        val stats = WatermarkDsp.blockStats(pcm, wm, f.start)
        var maxNcc = -1f
        for (st in stats) if (st.ncc > maxNcc) maxNcc = st.ncc
        if (maxNcc < minBlockNcc) return null

        val out = pcm.copyOf()
        for (i in 0 until n) {
            out[f.start + i] -= f.gainAt(i) * wm[i]
        }

        // 2) 剩余水印（投影）在干净块上的中值，dB
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
            nccs[i] = if (dx * dy > 1e-24) (cc / sqrt(dx * dy)).toFloat() else 0f
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

        // 干净块上的增益拟合残差（相对）
        var err = 0.0
        var ref = 0.0
        for (i in clean) {
            val t = ((i * BLOCK + BLOCK / 2.0) / WatermarkDsp.SAMPLE_RATE).toFloat()
            val g = max(abs(slope * t + intercept), 1e-6f)
            err += (g - gains[i]).toDouble() * (g - gains[i])
            ref += g.toDouble() * g
        }
        if (clean.isEmpty()) err = 1e9
        return Fit(start, slope, intercept, clean.size, nb, if (ref > 1e-12) err / ref else 1e9)
    }
}
