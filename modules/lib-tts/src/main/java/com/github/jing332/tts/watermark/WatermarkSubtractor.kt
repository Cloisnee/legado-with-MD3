package com.github.jing332.tts.watermark

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 叠加态水印的"相减 + 谱减"（纯算法，无 Android 依赖）。
 *
 * 前提：已知水印录音的完整波形 [wm]（48 kHz 单声道），且文件里该水印与正文是**线性叠加**。
 * 实测 `audition_direct_1792815416151`：水印窗后半段与模板逐段相关 1.000（纯副本），
 * 前半段被台词压住 —— 所以先按"纯净段"估出增益曲线，再回来减整个窗口。
 *
 * 两个细节（都是听感上踩过的坑）：
 *  1. 增益不是常数：实测 0.69 → 0.56 缓降，用常数会留下残余 → "分层"
 *  2. 直接相减会在窗口两端形成阶跃 → 咔哒；需 30 ms 淡入淡出
 *  3. 相减后重叠段仍有低电平残余 → 对**重叠段**做谱减（纯净段留白，否则会垫噪声）
 */
object WatermarkSubtractor {

    private const val N = 1024
    private const val H = 256
    private const val FADE_MS = 30
    private const val CLEAN_THRESHOLD = 0.90f
    private const val SPEC_FLOOR = 0.15

    class Result(
        /** 修正后的整条 PCM（与输入等长） */
        val pcm: FloatArray,
        /** 纯净段起点（相对水印起点的秒数） */
        val cleanFromSec: Float,
        /** 增益曲线起止 */
        val gainFrom: Float,
        val gainTo: Float,
        /** 纯净段残余相对原信号的 dB（越负越干净） */
        val residualDb: Float,
    )

    fun subtract(pcm: FloatArray, startSampleHint: Int, wm: FloatArray): Result? {
        val len = wm.size
        // 0) 波形级精细对齐：mel 定位只有 ±50ms 精度，差一个采样就减不干净。
        //    先 ±150ms 粗搜（步长 1ms），再 ±1ms 细搜到 1 个采样。
        var start: Int
        run {
            var bestLag = 0
            var bestScore = -9f
            var lag = -7200
            while (lag <= 7200) {
                val s = startSampleHint + lag
                if (s >= 0 && s + len <= pcm.size) {
                    val sc = maxBlockNcc(pcm, wm, s)
                    if (sc > bestScore) { bestScore = sc; bestLag = lag }
                }
                lag += 48
            }
            var l = bestLag - 48
            while (l <= bestLag + 48) {
                val s = startSampleHint + l
                if (s >= 0 && s + len <= pcm.size) {
                    val sc = maxBlockNcc(pcm, wm, s)
                    if (sc > bestScore) { bestScore = sc; bestLag = l }
                }
                l++
            }
            start = startSampleHint + bestLag
            // 对齐后若连一块都到不了 0.90，说明不是我们这段录音 → 不做
            if (bestScore < 0.85f) return null
        }
        if (start < 0 || start + len > pcm.size) return null
        val seg = FloatArray(len) { pcm[start + it] }

        // 1) 找"纯净段"：逐 100 ms 与模板的归一化相关 ≥0.98 的第一处
        val h100 = 4800
        var cleanFrom = -1
        var i = 0
        while (i + h100 <= len) {
            if (nccSame(seg, wm, i, h100) >= CLEAN_THRESHOLD) {
                cleanFrom = i
                break
            }
            i += h100
        }
        // 没有纯净段（水印被台词全覆盖）→ 用实测先验增益 0.62 兜底，仍然尝试相减
        var priorGain = -1f
        if (cleanFrom < 0 || cleanFrom > len * 0.6) {
            cleanFrom = len
            priorGain = 0.62f
        }

        // 2) 纯净段上逐 20 ms 估增益，中值平滑后线性拟合
        val hp = 960
        val ts = ArrayList<Float>()
        val gs = ArrayList<Float>()
        if (priorGain > 0f) {
            // 直接用先验增益，跳过拟合
            ts.add(0f); gs.add(priorGain)
            ts.add(len.toFloat() / WatermarkDsp.SAMPLE_RATE); gs.add(priorGain)
        }
        var p = cleanFrom
        while (p + hp <= len) {
            var num = 0.0
            var den = 0.0
            for (k in 0 until hp) {
                val a = seg[p + k].toDouble()
                val b = wm[p + k].toDouble()
                num += a * b
                den += b * b
            }
            if (den > 1e-9) {
                gs.add((num / den).toFloat())
                ts.add((p + hp / 2).toFloat() / WatermarkDsp.SAMPLE_RATE)
            }
            p += hp
        }
        if (gs.size < 2) return null
        val smooth = FloatArray(gs.size) { idx ->
            val a = maxOf(0, idx - 3)
            val b = minOf(gs.size - 1, idx + 3)
            val tmp = gs.subList(a, b + 1).sorted()
            tmp[tmp.size / 2]
        }
        // 最小二乘直线 g = k*t + b
        val n = smooth.size
        var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
        for (idx in 0 until n) {
            val x = ts[idx].toDouble()
            val y = smooth[idx].toDouble()
            sx += x; sy += y; sxx += x * x; sxy += x * y
        }
        val denom = n * sxx - sx * sx
        if (abs(denom) < 1e-12) return null
        val k = (n * sxy - sx * sy) / denom
        val b0 = (sy - k * sx) / n

        // 3) 按曲线相减（两端 30 ms 淡入淡出，避免阶跃）
        val gCurve = FloatArray(len) { idx ->
            val t = idx.toDouble() / WatermarkDsp.SAMPLE_RATE
            (k * t + b0).toFloat().coerceIn(0f, 1.5f)
        }
        var res = FloatArray(len)
        val fade = FADE_MS * WatermarkDsp.SAMPLE_RATE / 1000
        for (idx in 0 until len) {
            var corr = gCurve[idx] * wm[idx]
            if (idx < fade) corr *= idx.toFloat() / fade
            else if (idx >= len - fade) corr *= (len - 1 - idx).toFloat() / fade
            res[idx] = seg[idx] - corr
        }

        // 4) 谱减：把残余里"像水印"的频点再削一道
        // 谱减只做在"有台词"的重叠段：纯净段时域减完已是静音，谱减在那里只会垫噪声
        val wc = FloatArray(len) { gCurve[it] * wm[it] }
        val specEnd = min(len, cleanFrom + 3 * WatermarkDsp.SAMPLE_RATE / 100)   // 重叠段 + 30ms 过渡
        val partial = spectralSubtract(res.copyOfRange(0, specEnd), wc.copyOfRange(0, specEnd))
        for (i in 0 until specEnd) res[i] = partial[i]

        // 5) 纯净段残余电平（诊断用）
        var e0 = 0.0
        var e1 = 0.0
        for (idx in cleanFrom until len) {
            e0 += seg[idx].toDouble() * seg[idx]
            e1 += res[idx].toDouble() * res[idx]
        }
        val resDb = (10.0 * kotlin.math.log10(e1 / (e0 + 1e-12) + 1e-12)).toFloat()

        // 6) 写回
        val out = pcm.copyOf()
        for (idx in 0 until len) out[start + idx] = res[idx]
        return Result(out, cleanFrom.toFloat() / WatermarkDsp.SAMPLE_RATE, (k * ts.first() + b0).toFloat(), (k * ts.last() + b0).toFloat(), resDb)
    }

    /** 任意偏移相关：x 从 xFrom 起、y 从 yFrom 起，各取 n 点 */
    private fun nccAt(x: FloatArray, y: FloatArray, xFrom: Int, yFrom: Int, n: Int): Float {
        var mx = 0.0; var my = 0.0
        for (i in 0 until n) { mx += x[xFrom + i]; my += y[yFrom + i] }
        mx /= n; my /= n
        var num = 0.0; var dx = 0.0; var dy = 0.0
        for (i in 0 until n) {
            val a = x[xFrom + i] - mx
            val b = y[yFrom + i] - my
            num += a * b; dx += a * a; dy += b * b
        }
        val d = sqrt(dx * dy)
        return if (d < 1e-12) 0f else (num / d).toFloat()
    }

    /** 同偏移相关：以 from 为起点、两者同偏移比较（纯净段判定用） */
    private fun nccSame(x: FloatArray, y: FloatArray, from: Int, n: Int): Float {
        var mx = 0.0; var my = 0.0
        for (i in 0 until n) { mx += x[from + i]; my += y[from + i] }
        mx /= n; my /= n
        var num = 0.0; var dx = 0.0; var dy = 0.0
        for (i in 0 until n) {
            val a = x[from + i] - mx
            val b = y[from + i] - my
            num += a * b; dx += a * a; dy += b * b
        }
        val d = sqrt(dx * dy)
        return if (d < 1e-12) 0f else (num / d).toFloat()
    }

    /** 对齐目标：窗口内**最大** 100 ms 块相关（叠加态下整窗相关会被台词稀释） */
    private fun maxBlockNcc(x: FloatArray, y: FloatArray, from: Int): Float {
        val block = 4800
        var best = -9f
        var i = 0
        while (i + block <= y.size) {
            val v = nccAt(x, y, from + i, i, block)
            if (v > best) best = v
            i += block
        }
        return best
    }

    private fun ncc(x: FloatArray, y: FloatArray, from: Int, n: Int): Float {
        var mx = 0.0; var my = 0.0
        for (i in 0 until n) { mx += x[from + i]; my += y[i] }
        mx /= n; my /= n
        var num = 0.0; var dx = 0.0; var dy = 0.0
        for (i in 0 until n) {
            val a = x[from + i] - mx
            val b = y[i] - my
            num += a * b; dx += a * a; dy += b * b
        }
        val d = sqrt(dx * dy)
        return if (d < 1e-12) 0f else (num / d).toFloat()
    }

    /** STFT 幅度减：|X| - |Wc|，下限 SPEC_FLOOR·|X|，保留原相位 */
    private fun spectralSubtract(x: FloatArray, wc: FloatArray): FloatArray {
        val n = x.size
        val out = FloatArray(n)
        val wsum = FloatArray(n)
        val win = DoubleArray(N) { 0.5 - 0.5 * kotlin.math.cos(2.0 * Math.PI * it / (N - 1)) }
        val re = DoubleArray(N)
        val im = DoubleArray(N)
        val re2 = DoubleArray(N)
        val im2 = DoubleArray(N)
        var i = 0
        while (i + N <= n) {
            java.util.Arrays.fill(re, 0.0); java.util.Arrays.fill(im, 0.0)
            java.util.Arrays.fill(re2, 0.0); java.util.Arrays.fill(im2, 0.0)
            for (k in 0 until N) {
                re[k] = x[i + k] * win[k]
                re2[k] = wc[i + k] * win[k]
                im[k] = 0.0; im2[k] = 0.0
            }
            WatermarkDsp.fft(re, im)
            WatermarkDsp.fft(re2, im2)
            for (k in 0..N / 2) {
                val mag = sqrt(re[k] * re[k] + im[k] * im[k])
                val wm = sqrt(re2[k] * re2[k] + im2[k] * im2[k])
                val target = maxOf(mag - wm, SPEC_FLOOR * mag)
                val scale = if (mag < 1e-12) 0.0 else target / mag
                re[k] *= scale; im[k] *= scale
                if (k > 0 && k < N / 2) { re[N - k] *= scale; im[N - k] *= scale }
            }
            // 逆变换（用共轭法）
            for (k in 0 until N) im[k] = -im[k]
            WatermarkDsp.fft(re, im)
            for (k in 0 until N) {
                val v = (re[k] / N) * win[k]
                out[i + k] += v.toFloat()
                wsum[i + k] += (win[k] * win[k]).toFloat()
            }
            i += H
        }
        for (k in 0 until n) {
            if (wsum[k] > 1e-6f) out[k] = out[k] / wsum[k]
            else out[k] = x[k]
        }
        return out
    }
}
