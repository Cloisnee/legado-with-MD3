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

    fun subtract(
        pcm: FloatArray,
        wm: FloatArray,
        startHint: Int,
        minBlockNcc: Float = 0f,
        fadeWhenNoClean: Boolean = false
    ): Result? {
        val n = wm.size
        if (startHint < 0 || startHint + n > pcm.size) return null

        val start = align(pcm, wm, startHint)
        val f = fit(pcm, wm, start, fadeWhenNoClean) ?: return null

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

    // ================= 2026-11-09：深埋 / 中分（0~2 干净块）的模型自选 =================

    /**
     * 深埋 / 中分（干净块 <3）专用相减。
     *
     * 为什么不能沿用 [subtract] 的「中值 / 单一直线」：
     *  - 平台给广告的增益在 100ms 尺度上就在变，单一中值会整段欠减或过减；
     *  - 「相减后残差与模板的投影」在最小二乘下恒为零，不能用它判断减没减干净。
     *
     * 这里改为：生成若干候选增益包络，各自减一遍，用 **20ms 窗**（与 100ms 估计尺度不同）
     * 的残差相关挑最优 —— 这个指标不会被最小二乘「自己证明自己」。
     */
    fun subtractDeep(
        pcm: FloatArray,
        wm: FloatArray,
        startHint: Int,
        minMeanGain: Float = 0.25f
    ): Result? {
        val n = wm.size
        if (startHint < 0 || startHint + n > pcm.size) return null
        val start = align(pcm, wm, startHint)
        val stats = WatermarkDsp.blockStats(pcm, wm, start)
        val nb = stats.size
        if (nb < 2) return null
        val gains = FloatArray(nb) { stats[it].gain }
        val nccs = FloatArray(nb) { stats[it].ncc }
        val clean = ArrayList<Int>()
        for (i in 0 until nb) if (nccs[i] >= 0.9f) clean.add(i)

        // 四段一致性闸门：12 块按 3 块一段，至少 3 段的中值增益 ≥0.30。
        // 184 条干净语料实测最多只有 2 段达标（0 命中）；本批真水印全部 3~4 段。
        // 这是「减错方向 / 减空气」的最后一道保险，比单看 minQ 稳。
        var goodQuarters = 0
        for (q in 0 until 4) {
            val from = q * 3
            val to = minOf(from + 3, nb)
            if (to <= from) continue
            val vals = FloatArray(to - from) { gains[from + it] }
            if (median(vals) >= 0.30f) goodQuarters++
        }
        if (goodQuarters < 3) return null

        val cands = ArrayList<FloatArray>()
        val med = median(gains)
        cands.add(FloatArray(nb) { med })
        cands.add(FloatArray(nb) { gains.average().toFloat() })
        cands.add(weightedLine(gains, nccs))
        cands.add(robustLine(gains, nccs))
        cands.add(weightedQuad(gains, nccs))
        if (clean.size >= 2) cands.add(anchorLine(gains, clean))
        if (clean.size == 1) cands.add(FloatArray(nb) { gains[clean[0]] })

        var bestScore = Float.MAX_VALUE
        var bestEnv: FloatArray? = null
        var bestMean = 0f
        for (env in cands) {
            var sg = 0.0
            for (i in 0 until n) sg += env[minOf(i / BLOCK, nb - 1)].toDouble().coerceIn(0.0, 1.5)
            val meanGain = (sg / n).toFloat()
            if (meanGain < minMeanGain || meanGain > 1.5f) continue
            val s = fineScore(pcm, wm, start, env)
            if (s < bestScore) { bestScore = s; bestEnv = env; bestMean = meanGain }
        }
        val env = bestEnv ?: return null

        val out = pcm.copyOf()
        for (i in 0 until n) {
            out[start + i] -= env[minOf(i / BLOCK, nb - 1)].coerceIn(0f, 1.5f) * wm[i]
        }
        return Result(out, start, 0f, bestMean, clean.size, nb, -999f)
    }

    /** 残差在 20ms 窗上与模板的归一化相关（模板能量过低的窗跳过），越小越干净 */
    private fun fineScore(pcm: FloatArray, wm: FloatArray, start: Int, env: FloatArray): Float {
        val w = 960 // 20ms @48k
        val nb = env.size
        var sum = 0.0
        var cnt = 0
        var i = 0
        while (i + w <= wm.size) {
            var ew = 0.0
            for (k in 0 until w) { val v = wm[i + k]; ew += v * v }
            if (ew / w < 1e-4) { i += w; continue }   // 模板该窗 RMS < 0.01，跳过
            var mx = 0.0
            var my = 0.0
            for (k in 0 until w) {
                val r = pcm[start + i + k] - env[minOf((i + k) / BLOCK, nb - 1)].coerceIn(0f, 1.5f) * wm[i + k]
                mx += r
                my += wm[i + k]
            }
            mx /= w; my /= w
            var num = 0.0
            var dx = 0.0
            var dy = 0.0
            for (k in 0 until w) {
                val r = pcm[start + i + k] - env[minOf((i + k) / BLOCK, nb - 1)].coerceIn(0f, 1.5f) * wm[i + k]
                val a = r - mx
                val b = wm[i + k] - my
                num += a * b
                dx += a * a
                dy += b * b
            }
            if (dx * dy > 1e-24) {
                val v = num / kotlin.math.sqrt(dx * dy)
                sum += v * v
                cnt++
            }
            i += w
        }
        return if (cnt == 0) 9f else (sum / cnt).toFloat()
    }

    private fun median(v: FloatArray): Float {
        val a = v.clone()
        a.sort()
        return a[a.size / 2]
    }

    /** 加权最小二乘直线（权重 = |NCC|，至少 0.02） */
    private fun weightedLine(g: FloatArray, c: FloatArray): FloatArray {
        val n = g.size
        var sw = 0.0; var stw = 0.0; var stt = 0.0; var sg = 0.0; var stg = 0.0
        for (i in 0 until n) {
            val w = max(abs(c[i]), 0.02f).toDouble()
            val t = i.toDouble()
            sw += w; stw += t * w; stt += t * t * w
            sg += w * g[i]; stg += t * w * g[i]
        }
        return lineFromSums(sw, stw, stt, sg, stg, n)
    }

    /** 迭代重加权：压掉被台词带偏的离群块（如 9064 的 -1.48） */
    private fun robustLine(g: FloatArray, c: FloatArray): FloatArray {
        val n = g.size
        var env = weightedLine(g, c)
        repeat(3) {
            val res = FloatArray(n) { g[it] - env[it] }
            val medR = median(res)
            val dev = FloatArray(n) { abs(res[it] - medR) }
            val mad = max(median(dev), 1e-3f)
            var sw = 0.0; var stw = 0.0; var stt = 0.0; var sg = 0.0; var stg = 0.0
            for (i in 0 until n) {
                var w = max(abs(c[i]), 0.02f).toDouble()
                if (abs(res[i] - medR) > 2.5f * mad) w = 0.0
                val t = i.toDouble()
                sw += w; stw += t * w; stt += t * t * w
                sg += w * g[i]; stg += t * w * g[i]
            }
            if (sw > 1e-9) env = lineFromSums(sw, stw, stt, sg, stg, n)
        }
        return env
    }

    private fun lineFromSums(
        sw: Double, stw: Double, stt: Double, sg: Double, stg: Double, n: Int
    ): FloatArray {
        val den = sw * stt - stw * stw
        if (abs(den) < 1e-12) return FloatArray(n) { (sg / sw).toFloat() }
        val b = (sw * stg - stw * sg) / den
        val a = (sg - b * stw) / sw
        return FloatArray(n) { (a + b * it).toFloat() }
    }

    /** 加权二次（贴平台的缓变包络） */
    private fun weightedQuad(g: FloatArray, c: FloatArray): FloatArray {
        val n = g.size
        val m = Array(3) { DoubleArray(4) }
        for (i in 0 until n) {
            val w = max(abs(c[i]), 0.05f).toDouble()
            val t = i.toDouble()
            val p = doubleArrayOf(1.0, t, t * t)
            for (r in 0 until 3) {
                for (q in 0 until 3) m[r][q] += w * p[r] * p[q]
                m[r][3] += w * p[r] * g[i]
            }
        }
        // 3x3 高斯消元
        for (col in 0 until 3) {
            var piv = col
            for (r in col + 1 until 3) if (abs(m[r][col]) > abs(m[piv][col])) piv = r
            if (abs(m[piv][col]) < 1e-12) return FloatArray(n) { g.average().toFloat() }
            val tmp = m[col]; m[col] = m[piv]; m[piv] = tmp
            for (r in 0 until 3) {
                if (r == col) continue
                val f = m[r][col] / m[col][col]
                for (k in col until 4) m[r][k] -= f * m[col][k]
            }
        }
        val coef = DoubleArray(3) { m[it][3] / m[it][it] }
        return FloatArray(n) { i ->
            val t = i.toDouble()
            (coef[0] + coef[1] * t + coef[2] * t * t).toFloat()
        }
    }

    /** 用 NCC=1.0 的干净块锚定直线（它们的增益是精确值） */
    private fun anchorLine(g: FloatArray, clean: List<Int>): FloatArray {
        val n = g.size
        var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
        for (i in clean) {
            sx += i; sy += g[i]; sxx += i.toDouble() * i; sxy += i.toDouble() * g[i]
        }
        val cnt = clean.size.toDouble()
        val den = cnt * sxx - sx * sx
        if (abs(den) < 1e-12) return FloatArray(n) { (sy / cnt).toFloat() }
        val b = (cnt * sxy - sx * sy) / den
        val a = (sy - b * sx) / cnt
        return FloatArray(n) { (a + b * it).toFloat() }
    }

    /**
     * 对齐：以 hint 为基准，用 maxBlockNcc 粗搜 ±60ms（1ms 步）+ 细搜 ±1ms（1 采样步）。
     * 只有比 hint 高 0.03 才改用新的 lag —— 尾部探针给的 hint 本身就是 1 采样精度，
     * 不能被噪声带着乱跑。
     */
    private fun align(pcm: FloatArray, wm: FloatArray, hint: Int): Int {
        val hintScore = maxBlockNcc(pcm, wm, hint)

        // 没有干净块（完全被台词盖住）：maxBlockNcc 的分辨力不够，
        // 改用全窗 NCC 在 ±15ms 内做 1 采样精修（mel 探针是 10ms 网格，实测会偏 9~10ms；
        // 0024 那种「减了一半」就是偏了半个字）
        if (hintScore < 0.9f) {
            var bestLag = hint
            var best = nccFull(pcm, wm, hint)
            val span = (0.015 * WatermarkDsp.SAMPLE_RATE).toInt()
            for (d in -span..span) {
                val lag = hint + d
                if (lag < 0 || lag + wm.size > pcm.size) continue
                val v = nccFull(pcm, wm, lag)
                if (v > best) { best = v; bestLag = lag }
            }
            return bestLag
        }

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

    private fun nccFull(pcm: FloatArray, wm: FloatArray, start: Int): Float {
        if (start < 0 || start + wm.size > pcm.size) return -1f
        return WatermarkDsp.nccAt(pcm, wm, start, 0, wm.size)
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

    private fun fit(pcm: FloatArray, wm: FloatArray, start: Int, fadeWhenNoClean: Boolean): Fit? {
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
            // 没有任何干净块：默认用中值常数；深埋路径已知水印是线性淡出，
            // 用全局斜率 + 中位时间处的截距（实测两条干净对：0.76→0.51 / 0.85→0.58）。
            slope = if (fadeWhenNoClean) GLOBAL_SLOPE else 0f
            val v = gains.toMutableList()
            v.sort()
            intercept = v[v.size / 2] - slope * 0.6f
        }
        return Fit(start, slope, intercept, clean.size, nb)
    }
}
