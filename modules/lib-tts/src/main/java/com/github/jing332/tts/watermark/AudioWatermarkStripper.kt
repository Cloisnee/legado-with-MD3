package com.github.jing332.tts.watermark

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import com.github.jing332.common.audio.ByteArrayMediaDataSource
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 试听水印剥离器（缓存落盘前调用）。
 *
 * 背景：呱呱有声的接口会在音频里插入一段固定水印录音（“欢迎使用呱呱有声”，1.22 s），
 * 有两种物理形态：
 *  - **干净拼接**：水印整段落在台词后的静音里 → 可以按 MP3 帧边界截断/抽帧（无损、不重编码）
 *  - **叠加态**：水印和台词线性相加（甚至同时结束）→ 只能时域相减（必须转 WAV）
 *
 * 分流（2026-10-26 v6）：
 *  1. **尾部位置先验探针**：模板末 0.3s 与混音（双方 800Hz 高通）做时域 NCC，
 *     在末尾 2.2s 内 1ms 粗扫 + 1 采样精修（平台落点实测 EOF−0.25s ~ −0.63s 浮动）。
 *     干净语音 ≤0.13，叠加态 0.25~1.0；mel 探针在叠加态只能到 0.44，这是老版“未命中”的根因。
 *  2. 命中后看 12 个 100ms 块的 NCC：**全 12 块都 ≥0.9** → 截尾 / 抽帧（无损、不重编码）；
 *     否则 → 相减（[WatermarkSubtractor] 逐样本线性增益 + maxBlockNcc 对齐，不做谱减）。
 *  3. 尾部没命中 → 回退 mel 全片扫描（句中插入），原有 ec/窄带闸门保留。
 *
 * **失败一律 fail-open**：返回原始字节，绝不影响合成。
 */
object AudioWatermarkStripper {

    private const val TAG = "WmStrip"
    private const val MAX_BYTES = 8 * 1024 * 1024
    private const val MIN_BYTES = 8 * 1024
    private const val LOG_MAX = 256 * 1024L

    /** 尾部探针下限：干净语音实测 max 0.11，叠加态实测 0.25~1.0 */
    private const val TAIL_MIN = 0.25f
    /** 中分档：0.25~0.45 之间要求至少 2 个干净块，挡掉“只剩尾字巧合”（e3/e6 那种单块 0.93） */
    private const val TAIL_HI = 0.45f
    /** 中分档的第二判据：mel 全片最高分（实测真水印 0.345~0.53，干净巧合 0.185~0.243） */
    private const val MEL_HI = 0.30f
    /** 低于这个分但高于 0.10：写一行“接近未命中”日志，便于事后定位 */
    private const val TAIL_NEAR = 0.10f
    /** 深埋兜底入口：低于此值连位置带都不看（原 0.10；0010/0012/0013 实测 0.07~0.10） */
    private const val BAND_ENTER = 0.04f
    /** 深埋全窗 NCC 下限（干净语音最大 0.085，真水印 0.09~0.20） */
    private const val BAND_MIN_SCORE = 0.08f
    /** 深埋四段最小 NCC 下限（原 0.08；0012 是 0.086、0084 是 0.078，差一点点就漏） */
    private const val BAND_MIN_Q = 0.035f

    /** 相减后：结尾近静音收到「最后有声帧 + 100ms」（试听拍板），末尾 10ms 淡出 */
    private const val TAIL_KEEP_MS = 100
    /** 10ms 帧 @48k */
    private const val SILENCE_FRAME = 480
    /** 静音判定阈值（实测残留广告 -50~-60dBFS，台词 -25~-35dBFS） */
    private const val SILENCE_DB = -45.0

    /** 质量分档：NA=没走水印处理（不算硬样本）；GOOD/WEAK/HARD 用于决定要不要重抽 */
    const val Q_NA = -1
    const val Q_GOOD = 0
    const val Q_WEAK = 1
    const val Q_HARD = 2

    class Outcome(
        val bytes: ByteArray,
        val removed: Boolean,
        val note: String,
        val quality: Int = Q_NA,
        /** 前 2s 语音指纹（8k 降采样）：重抽时比对台词是否同一条 */
        val speechPrint: FloatArray? = null
    )

    class Decoded(val pcm: FloatArray, val sampleRate: Int, val print: FloatArray)

    private val template: FloatArray by lazy {
        WatermarkDsp.decodeTemplate(WatermarkTemplate.BASE64, WatermarkTemplate.SCALE)
    }

    @Volatile
    private var wmPcm: FloatArray? = null

    @Volatile
    private var wmHp: FloatArray? = null

    private fun wmPcm(context: android.content.Context): FloatArray {
        wmPcm?.let { return it }
        val b = context.assets.open("watermark_wm.pcm").use { it.readBytes() }
        val p = FloatArray(b.size / 2) { i ->
            ((((b[i * 2 + 1].toInt() shl 8) or (b[i * 2].toInt() and 0xFF)).toShort())).toFloat() / 32768f
        }
        wmPcm = p
        return p
    }

    private fun wmHp(context: android.content.Context): FloatArray {
        wmHp?.let { return it }
        val h = WatermarkDsp.highPass(wmPcm(context), 800f, WatermarkDsp.SAMPLE_RATE)
        wmHp = h
        return h
    }

    /**
     * 契约门控入口：只有插件**声明**了 `watermark=true` 才处理，否则原样返回。
     * 所有落盘点（朗读缓存 / 三种试听）都应走这里，而不是直接调 [strip]。
     */
    fun stripDeclared(
        context: android.content.Context,
        pluginId: String,
        bytes: ByteArray,
        logDir: File? = null,
        /** 试听链路传 true：处理之前把原始字节另存一份，便于事后取样分析 */
        keepRaw: Boolean = false,
    ): Outcome {
        if (pluginId.isBlank()) {
            skipLog(logDir, "无插件标识")
            return Outcome(bytes, false, "无插件标识，跳过")
        }
        if (!com.github.jing332.tts.store.TtsConfigStore.declaresWatermark(context, pluginId)) {
            skipLog(logDir, "插件未声明水印($pluginId)")
            return Outcome(bytes, false, "插件未声明水印，跳过")
        }
        return strip(context, bytes, logDir, keepRaw)
    }

    fun strip(context: android.content.Context?, bytes: ByteArray, logDir: File? = null, keepRaw: Boolean = false): Outcome {
        if (bytes.size !in MIN_BYTES..MAX_BYTES) {
            skipLog(logDir, "体积超范围(${bytes.size})")
            return Outcome(bytes, false, "体积超范围，跳过")
        }
        val isWav = isRiffWav(bytes)
        val head = if (isWav) null else Mp3Truncator.head(bytes)
        if (!isWav && head == null) {
            skipLog(logDir, "非 MP3/WAV")
            return Outcome(bytes, false, "非 MP3/WAV，跳过")
        }

        val decoded = try {
            if (isWav) decodeWav(bytes) else decode(bytes)
        } catch (t: Throwable) {
            Log.w(TAG, "decode failed: ${t.message}")
            skipLog(logDir, "解码失败(${t.message})")
            return Outcome(bytes, false, "解码失败: ${t.message}")
        } ?: run {
            skipLog(logDir, "解码为空")
            return Outcome(bytes, false, "解码为空")
        }

        // 只在 48 kHz 上判定：呱呱固定 48k；别的采样率直接放行，杜绝误伤
        if (decoded.sampleRate != WatermarkDsp.SAMPLE_RATE) {
            skipLog(logDir, "采样率 ${decoded.sampleRate}")
            return Outcome(bytes, false, "采样率 ${decoded.sampleRate}，跳过")
        }

        val t0 = android.os.SystemClock.elapsedRealtime()
        val wm = context?.let { runCatching { wmPcm(it) }.getOrNull() }
        val wh = context?.let { runCatching { wmHp(it) }.getOrNull() }

        // ---------- 1. 尾部位置先验探针 ----------
        var tailScore = -1f
        if (wm != null && wh != null) {
            val tail = WatermarkDsp.tailProbe(decoded.pcm, wh, decoded.sampleRate)
            tailScore = tail?.score ?: -1f
            if (tail != null && tail.score >= TAIL_MIN) {
                val stats = WatermarkDsp.blockStats(decoded.pcm, wm, tail.startSample)
                val clean = stats.count { it.ncc >= 0.9f }
                // 两层判据：高分直接认；0.25~0.45 先看干净块，不够再看 mel 全片最高分
                var accept = tail.score >= TAIL_HI || clean >= 2
                var startHint = tail.startSample
                var via = ""
                if (!accept) {
                    val mb = WatermarkDsp.bestMatch(decoded.pcm, template)
                    val melStr = if (mb != null) "%.3f".format(mb.score) else "-"
                    if (mb != null && mb.score >= MEL_HI) {
                        accept = true
                        // mel 位置是 10ms 网格，但实测比被掩蔽时的探针峰更准（miss2 探针偏 9ms）
                        startHint = mb.startSample
                        via = " | mel补判 $melStr"
                    } else {
                        runCatching { writeLog(logDir, "尾部中分未采用（%.3f, 块 %d/%d, mel %s）".format(tail.score, clean, stats.size, melStr)) }
                    }
                }
                if (accept) {
                    if (clean == stats.size) {
                        // 整窗干净 → 截尾 / 抽帧（MP3 原字节无重编码；WAV 直接截 PCM）
                        if (isWav) {
                            return cutWav(decoded, tail.startSample, tail.score, clean, stats.size, logDir, keepRaw, bytes, t0)
                        }
                        return cutPath(head!!, bytes, decoded, tail.startSample, tail.score, clean, stats.size, logDir, keepRaw, t0)
                    }
                    // 干净块 <3（含 0 块）→ 用模型自选：平台给的广告增益在 100ms 内就在变，
                    // 常数/单一直线会整段欠减（0108 就是被 0.00·t+0.73 减了一半）
                    val r = if (clean >= 3) WatermarkSubtractor.subtract(decoded.pcm, wm, startHint)
                            else WatermarkSubtractor.subtractDeep(decoded.pcm, wm, startHint)
                    if (r != null) {
                        return subtractPath(bytes, decoded, r, wm.size, tail.score, clean, stats.size, logDir, keepRaw, t0, via)
                    }
                    // 相减失败（极少）→ 继续走 mel 回退，不要静默
                    runCatching { writeLog(logDir, "尾部命中 %.3f 但相减失败（块 %d/%d）".format(tail.score, clean, stats.size)) }
                }
            }
        }

        // ---------- 2. 回退：mel 全片扫描（句中插入） ----------
        var matches = WatermarkDsp.locateByProbe(decoded.pcm, template, 90, 30, 0.90f)
        if (matches.isEmpty()) matches = WatermarkDsp.locateAll(decoded.pcm, template, 0.55f)
        if (matches.isEmpty()) {
            // ---------- 3. 兜底：位置先验 + 四段一致性（深埋 / 完全重叠） ----------
            // 只在前面所有路径都失败时执行；四段必须都正相关且增益合理，否则一律放行。
            if (tailScore >= BAND_ENTER && wm != null && wh != null) {
                val band = WatermarkDsp.bandProbe(decoded.pcm, wh, decoded.sampleRate)
                // 2026-11-09 重标：minQ 0.08→0.035（0012 差 0.001、0084 差 0.002），
                // 增益不再用「四段最小值」单闸，改由 subtractDeep 的模型自选 + 20ms 残差兜底
                if (band != null && band.minQuarterNcc >= BAND_MIN_Q && band.score >= BAND_MIN_SCORE) {
                    val r = WatermarkSubtractor.subtractDeep(decoded.pcm, wm, band.startSample)
                    if (r != null) {
                        return subtractPath(
                            bytes, decoded, r, wm.size, band.score, 0, 12,
                            logDir, keepRaw, t0,
                            " | 深埋 minQ %.2f/%.2f".format(band.minQuarterNcc, band.minQuarterGain)
                        )
                    }
                }
            }
            // 高于下限的样本已在上面写过拒绝原因（中分未采用 / 相减失败），这里只报"低于下限"的
            if (tailScore >= TAIL_NEAR && tailScore < TAIL_MIN) {
                runCatching { writeLog(logDir, "未命中（尾部探针 %.3f，低于 %.2f）".format(tailScore, TAIL_MIN)) }
            }
            return Outcome(bytes, false, "未命中水印", Q_HARD, decoded.print)
        }

        val best = matches[0]
        val ec = WatermarkDsp.envelopeCorr(decoded.pcm, best.startSample, WatermarkTemplate.ENV)
        val bl = WatermarkDsp.bandLimitDelta(decoded.pcm, best.startSample)

        // 干净拼接（三道闸门全过）→ 切除：无损、不重编码
        val canCut = best.score >= WatermarkDsp.MATCH_THRESHOLD &&
            (ec >= 0.90f || (ec >= 0.65f && bl >= 12f))
        if (canCut) {
            val clean = if (wm != null)
                WatermarkDsp.blockStats(decoded.pcm, wm, best.startSample).count { it.ncc >= 0.9f }
            else 0
            if (isWav) return cutWav(decoded, best.startSample, best.score, clean, 12, logDir, keepRaw, bytes, t0)
            return cutPath(head!!, bytes, decoded, best.startSample, best.score, clean, 12, logDir, keepRaw, t0)
        }
        if (wm == null) return Outcome(bytes, false, "无 Context，无法加载模板")
        val r = WatermarkSubtractor.subtract(decoded.pcm, wm, best.startSample, 0.80f)
            ?: return Outcome(bytes, false, "相减失败（命中 %.3f）".format(best.score))
        return subtractPath(bytes, decoded, r, wm.size, best.score, 0, 12, logDir, keepRaw, t0)
    }

    // ---------------- WAV（mk4 / 已处理产物） ----------------

    private fun isRiffWav(b: ByteArray): Boolean =
        b.size >= 44 &&
            b[0] == 0x52.toByte() && b[1] == 0x49.toByte() &&
            b[2] == 0x46.toByte() && b[3] == 0x46.toByte() &&
            b[8] == 0x57.toByte() && b[9] == 0x41.toByte() &&
            b[10] == 0x56.toByte() && b[11] == 0x45.toByte()

    private fun le16(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    /** RIFF/WAVE 的 PCM16 解码；非 PCM16 返回 null */
    private fun decodeWav(bytes: ByteArray): Decoded? {
        if (!isRiffWav(bytes)) return null
        var p = 12
        var fmtOk = false
        var channels = 1
        var sr = 0
        var bits = 16
        var dataOff = -1
        var dataLen = 0
        while (p + 8 <= bytes.size) {
            val id = String(bytes, p, 4, Charsets.US_ASCII)
            val size = le32(bytes, p + 4)
            if (size < 0) return null
            val body = p + 8
            if (id == "fmt " && body + 16 <= bytes.size) {
                val af = le16(bytes, body)
                channels = le16(bytes, body + 2)
                sr = le32(bytes, body + 4)
                bits = le16(bytes, body + 14)
                fmtOk = af == 1
            } else if (id == "data") {
                dataOff = body
                dataLen = minOf(size, bytes.size - body)
            }
            p = body + size + (size and 1)
        }
        if (!fmtOk || dataOff < 0 || sr <= 0 || bits != 16) return null
        val ch = if (channels > 0) channels else 1
        val n = dataLen / 2
        if (n <= 0) return null
        val all = FloatArray(n) { k ->
            (((bytes[dataOff + k * 2 + 1].toInt() shl 8) or
                (bytes[dataOff + k * 2].toInt() and 0xFF)).toShort()).toFloat() / 32768f
        }
        val pcm = if (ch == 1) all else downmix(all, ch)
        return Decoded(pcm, sr, fingerprint(pcm))
    }

    /** WAV 输入的截尾：直接截 PCM 再包回 WAV（WAV 没有 MP3 帧边界问题） */
    private fun cutWav(
        decoded: Decoded,
        startSample: Int,
        score: Float,
        clean: Int,
        total: Int,
        logDir: File?,
        keepRaw: Boolean,
        bytes: ByteArray,
        t0: Long,
    ): Outcome {
        val sr = WatermarkDsp.SAMPLE_RATE
        val cut = maxOf(0, startSample - (0.02 * sr).toInt())
        val cutSec = cut.toDouble() / sr
        if (cutSec < 0.5) return Outcome(bytes, false, "剩余正文过短（命中 %.3f）".format(score))
        val body = FloatArray(cut) { decoded.pcm[it] }
        val note = "尾部截断 %.2fs（%.2fs→%.2fs, 命中 %.3f, 块 %d/%d, WAV）".format(
            (decoded.pcm.size - cut).toDouble() / sr,
            decoded.pcm.size.toDouble() / sr,
            cutSec, score, clean, total
        )
        val cost = android.os.SystemClock.elapsedRealtime() - t0
        val rawTag = if (keepRaw) saveRaw(logDir, bytes)?.let { " | raw=$it" } ?: "" else ""
        runCatching { writeLog(logDir, "$note | ${cost}ms$rawTag") }
        return Outcome(toWav(body, sr), true, note)
    }

    // ---------------- 跳过原因日志 ----------------

    private val skipLogTimes = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** 跳过原因写日志；同一原因 10 分钟内只记一次，避免刷屏（0004/0103 那种“查无此人”就是没日志） */
    private fun skipLog(dir: File?, msg: String) {
        if (dir == null) return
        val key = msg.take(64)
        val now = System.currentTimeMillis()
        val last = skipLogTimes[key]
        if (last != null && now - last < 10 * 60_000L) return
        skipLogTimes[key] = now
        runCatching { writeLog(dir, "跳过：$msg") }
    }

    // ---------------- 语音指纹 / 重抽（v8） ----------------

    /** 前 2s 降采样到 8k（48k÷6），用于比对两次合成是不是同一句台词 */
    private fun fingerprint(pcm: FloatArray): FloatArray {
        val n = minOf(pcm.size, 2 * WatermarkDsp.SAMPLE_RATE)
        val step = 6
        val out = FloatArray(n / step + 1)
        var oi = 0
        var i = 0
        while (i < n) {
            var sum = 0.0
            var k = 0
            while (k < step && i + k < n) { sum += pcm[i + k]; k++ }
            out[oi++] = if (k > 0) (sum / k).toFloat() else 0f
            i += step
        }
        return out
    }

    private fun sameSpeech(a: FloatArray?, b: FloatArray?): Boolean {
        if (a == null || b == null) return false
        val n = minOf(a.size, b.size)
        if (n < 100) return false
        var ma = 0.0
        var mb = 0.0
        for (i in 0 until n) { ma += a[i]; mb += b[i] }
        ma /= n; mb /= n
        var num = 0.0
        var da = 0.0
        var db = 0.0
        for (i in 0 until n) {
            val x = a[i] - ma
            val y = b[i] - mb
            num += x * y
            da += x * x
            db += y * y
        }
        if (da * db < 1e-12) return false
        return (num / kotlin.math.sqrt(da * db)) >= 0.95
    }

    private val breakerFails = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val breakerUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val BREAKER_LIMIT = 3
    private const val BREAKER_MS = 10 * 60_000L

    private fun breakerBlocked(key: String): Boolean =
        (breakerUntil[key] ?: 0L) > System.currentTimeMillis()

    private fun breakerReport(key: String, improved: Boolean) {
        if (improved) { breakerFails.remove(key); return }
        val n = (breakerFails[key] ?: 0) + 1
        if (n >= BREAKER_LIMIT) {
            breakerFails.remove(key)
            breakerUntil[key] = System.currentTimeMillis() + BREAKER_MS
        } else {
            breakerFails[key] = n
        }
    }

    /**
     * 硬样本重抽：合成一次 → 剥离 → 还是硬就再来，最多 [maxRetry] 次。
     * 守卫：语音指纹 NCC < 0.95 丢弃；只有 quality 更小才替换；
     * 同声线连续 3 次无改善 → 10 分钟熔断（触发时回落 v7 相减，用户无感）。
     */
    fun stripWithRetry(
        context: android.content.Context,
        pluginId: String,
        logDir: File?,
        maxRetry: Int,
        breakerKey: String,
        keepRaw: Boolean = false,
        synthOnce: () -> ByteArray?
    ): Outcome? {
        val retries = maxRetry.coerceIn(0, 5)
        var best: Outcome? = null
        var tries = 0
        var improved = false
        while (true) {
            val bytes = synthOnce() ?: return best
            val cleaned = stripDeclared(context, pluginId, bytes, logDir, keepRaw)
            val cur = best
            if (cur == null) {
                best = cleaned
            } else if (!sameSpeech(cur.speechPrint, cleaned.speechPrint)) {
                runCatching { writeLog(logDir, "重抽 $tries/$retries：语音变了，丢弃") }
            } else if (cleaned.quality < cur.quality) {
                val prevQ = cur.quality
                runCatching { writeLog(logDir, "重抽 $tries/$retries：q $prevQ→${cleaned.quality}（收）") }
                best = cleaned
                improved = true
            } else {
                runCatching { writeLog(logDir, "重抽 $tries/$retries：q ${cleaned.quality}（不收）") }
            }
            val b = best ?: return null
            if (b.quality <= Q_GOOD) break
            if (tries >= retries) break
            if (breakerBlocked(breakerKey)) {
                runCatching { writeLog(logDir, "重抽熔断中（$breakerKey），跳过") }
                break
            }
            tries++
            val curQ = b.quality
            runCatching { writeLog(logDir, "重抽 $tries/$retries：开始（当前 q=$curQ）") }
        }
        val out = best ?: return null
        breakerReport(breakerKey, out.quality <= Q_GOOD || improved)
        return out
    }

    // ---------------- 截尾 / 抽帧 ----------------

    private fun cutPath(
        head: Mp3Truncator.Mp3Head,
        bytes: ByteArray,
        decoded: Decoded,
        startSample: Int,
        score: Float,
        clean: Int,
        total: Int,
        logDir: File?,
        keepRaw: Boolean,
        t0: Long,
    ): Outcome {
        val sr = WatermarkDsp.SAMPLE_RATE
        val durSec = decoded.pcm.size.toDouble() / sr
        val wmEnd = startSample + WatermarkDsp.watermarkSamples()
        val nearTail = wmEnd + (0.30 * sr).toInt() >= decoded.pcm.size
        val note: String
        val outBytes: ByteArray
        if (nearTail) {
            val center = startSample - (0.02 * sr).toInt()
            val cutSample = WatermarkDsp.snapToQuietest(decoded.pcm, maxOf(0, center), 6)
            val cutSec = cutSample.toDouble() / sr
            if (cutSec < 0.5) return Outcome(bytes, false, "剩余正文过短（命中 %.3f）".format(score))
            val cutOffset = Mp3Truncator.offsetAt(bytes, head, cutSec)
                ?: return Outcome(bytes, false, "帧索引失败（命中 %.3f）".format(score))
            if (cutOffset < MIN_BYTES) return Outcome(bytes, false, "裁剪点过前（命中 %.3f）".format(score))
            outBytes = bytes.copyOfRange(0, cutOffset)
            note = "尾部截断 %.2fs（%.2fs→%.2fs, 命中 %.3f, 块 %d/%d）".format(
                durSec - cutSec, durSec, cutSec, score, clean, total
            )
        } else {
            val fromSec = (startSample - (0.02 * sr).toInt()).coerceAtLeast(0).toDouble() / sr
            val toSec = (wmEnd + (0.03 * sr).toInt()).toDouble() / sr
            val spliced = Mp3Truncator.splice(bytes, head, fromSec, toSec)
                ?: return Outcome(bytes, false, "中段抽帧失败（命中 %.3f）".format(score))
            if (spliced.size < MIN_BYTES) return Outcome(bytes, false, "抽帧后过短（命中 %.3f）".format(score))
            outBytes = spliced
            note = "中段抽帧 %.2f-%.2fs（命中 %.3f, 块 %d/%d）".format(fromSec, toSec, score, clean, total)
        }
        val cost = android.os.SystemClock.elapsedRealtime() - t0
        val rawTag = if (keepRaw) {
            val n = saveRaw(logDir, bytes)
            if (n != null) " | raw=$n" else ""
        } else ""
        runCatching { writeLog(logDir, "$note | ${cost}ms$rawTag") }
        return Outcome(outBytes, true, note, Q_GOOD, decoded.print)
    }

    // ---------------- 叠加态相减 ----------------

    private fun subtractPath(
        bytes: ByteArray,
        decoded: Decoded,
        r: WatermarkSubtractor.Result,
        wmSize: Int,
        score: Float,
        clean: Int,
        total: Int,
        logDir: File?,
        keepRaw: Boolean,
        t0: Long,
        via: String = "",
    ): Outcome {
        val sr = WatermarkDsp.SAMPLE_RATE
        val end = r.startSample + wmSize
        val nearTail = end + (0.30 * sr).toInt() >= decoded.pcm.size
        val outPcm: FloatArray
        var trimmedMs = 0
        if (nearTail) {
            val tail = sr / 4
            val buf = FloatArray(end + tail)
            System.arraycopy(r.pcm, 0, buf, 0, minOf(end, r.pcm.size))
            outPcm = trimSilentTail(buf, sr)
            trimmedMs = (buf.size - outPcm.size) * 1000 / sr
        } else {
            outPcm = r.pcm
        }
        val wav = toWav(outPcm, sr)
        val cost = android.os.SystemClock.elapsedRealtime() - t0
        val resPart = if (clean > 0 && r.residualDb > -900f) ", 残差 %.0fdB".format(r.residualDb) else ""
        val note = "尾部相减（命中 %.3f, 块 %d/%d, 增益 %.2f·t%+.2f%s）".format(
            score, clean, total, r.slope, r.intercept, resPart
        ) + via + if (trimmedMs > 0) " | 静音收口 ${trimmedMs}ms" else ""
        runCatching { writeLog(logDir, "$note | ${cost}ms" + (if (keepRaw) " | raw=" + (saveRaw(logDir, bytes) ?: "-") else "")) }
        val q = when {
            clean >= 2 -> Q_GOOD
            clean == 1 -> Q_WEAK
            else -> Q_HARD
        }
        return Outcome(wav, true, note, q, decoded.print)
    }

    /**
     * 相减后的结尾收口：从后往前找最后一个高于 [SILENCE_DB] 的 10ms 帧，
     * 保留 [TAIL_KEEP_MS]，末尾 10ms 淡出。
     * 目的是把“广告被减掉后留下的那一大段近静音”收短，让句子之间连贯；
     * 残留广告若仍高于阈值，会被当成有声，这里就不切（宁可不切，不切出空洞）。
     */
    private fun trimSilentTail(pcm: FloatArray, sr: Int): FloatArray {
        var last = 0
        var i = 0
        while (i + SILENCE_FRAME <= pcm.size) {
            var e = 0.0
            for (k in 0 until SILENCE_FRAME) e += pcm[i + k].toDouble() * pcm[i + k]
            if (10 * kotlin.math.log10(e / SILENCE_FRAME + 1e-12) > SILENCE_DB) last = i + SILENCE_FRAME
            i += SILENCE_FRAME
        }
        if (last <= 0) return pcm
        val keep = sr * TAIL_KEEP_MS / 1000
        val end = minOf(pcm.size, last + keep)
        if (pcm.size - end < sr * 30 / 1000) return pcm
        val out = pcm.copyOf(end)
        val fade = sr / 100
        if (fade in 1 until out.size) {
            for (k in 0 until fade) out[out.size - fade + k] *= (fade - k - 1).toFloat() / fade
        }
        return out
    }

    /** PCM(float) → WAV(16bit) 字节 */
    private fun toWav(pcm: FloatArray, sr: Int): ByteArray {
        val dataLen = pcm.size * 2
        val out = ByteArray(44 + dataLen)
        fun putInt(o: Int, v: Int) {
            out[o] = (v and 0xFF).toByte()
            out[o + 1] = ((v shr 8) and 0xFF).toByte()
            out[o + 2] = ((v shr 16) and 0xFF).toByte()
            out[o + 3] = ((v shr 24) and 0xFF).toByte()
        }
        fun putShort(o: Int, v: Int) {
            out[o] = (v and 0xFF).toByte()
            out[o + 1] = ((v shr 8) and 0xFF).toByte()
        }
        putInt(0, 0x46464952); putInt(4, 36 + dataLen); putInt(8, 0x45564157)
        putInt(12, 0x20746D66); putInt(16, 16); putShort(20, 1); putShort(22, 1)
        putInt(24, sr); putInt(28, sr * 2); putShort(32, 2); putShort(34, 16)
        putInt(36, 0x61746164); putInt(40, dataLen)
        for (i in pcm.indices) {
            val s = (pcm[i].coerceIn(-1f, 1f) * 32767).toInt()
            puts(out, 44 + i * 2, s)
        }
        return out
    }

    private fun puts(out: ByteArray, o: Int, v: Int) {
        out[o] = (v and 0xFF).toByte()
        out[o + 1] = ((v shr 8) and 0xFF).toByte()
    }

    // ---------------- 解码 ----------------

    private fun decode(bytes: ByteArray): Decoded? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(ByteArrayMediaDataSource(bytes))
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    track = i; format = f; break
                }
            }
            if (track < 0 || format == null) return null
            extractor.selectTrack(track)

            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            runCatching {
                format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_FLOAT)
            }
            val c = MediaCodec.createDecoderByType(mime)
            codec = c
            c.configure(format, null, null, 0)
            c.start()

            val info = MediaCodec.BufferInfo()
            val out = ByteArrayOutputStream(1 shl 20)
            var sr = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE))
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 0
            var channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1
            var isFloat = false
            var inputDone = false
            var guard = 0

            while (guard++ < 4_000_000) {
                if (!inputDone) {
                    val inIdx = c.dequeueInputBuffer(10_000L)
                    if (inIdx >= 0) {
                        val buf = c.getInputBuffer(inIdx)
                        val size = if (buf != null) extractor.readSampleData(buf, 0) else -1
                        if (size < 0) {
                            c.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            c.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = c.dequeueOutputBuffer(info, 10_000L)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val nf = c.outputFormat
                    if (nf.containsKey(MediaFormat.KEY_SAMPLE_RATE))
                        sr = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    if (nf.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                        channels = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    isFloat = nf.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        nf.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                } else if (outIdx >= 0) {
                    val ob = c.getOutputBuffer(outIdx)
                    if (ob != null && info.size > 0 &&
                        (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                    ) {
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        val chunk = ByteArray(info.size)
                        ob.get(chunk)
                        out.write(chunk)
                    }
                    c.releaseOutputBuffer(outIdx, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                }
            }
            if (sr <= 0) return null
            val raw = out.toByteArray()
            val ch = if (channels > 0) channels else 1
            val pcm = if (isFloat) {
                val n = raw.size / 4
                val all = FloatArray(n)
                for (k in 0 until n) {
                    val bits = (raw[k * 4].toInt() and 0xFF) or
                        ((raw[k * 4 + 1].toInt() and 0xFF) shl 8) or
                        ((raw[k * 4 + 2].toInt() and 0xFF) shl 16) or
                        ((raw[k * 4 + 3].toInt() and 0xFF) shl 24)
                    all[k] = java.lang.Float.intBitsToFloat(bits)
                }
                if (ch == 1) all else downmix(all, ch)
            } else {
                val n = raw.size / 2
                val all = FloatArray(n)
                for (k in 0 until n) {
                    val lo = raw[k * 2].toInt() and 0xFF
                    val hi = raw[k * 2 + 1].toInt()
                    all[k] = (((hi shl 8) or lo).toShort()).toFloat() / 32768f
                }
                if (ch == 1) all else downmix(all, ch)
            }
            if (pcm.isEmpty()) return null
            return Decoded(pcm, sr, fingerprint(pcm))
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun downmix(all: FloatArray, ch: Int): FloatArray {
        val n = all.size / ch
        val out = FloatArray(n)
        for (i in 0 until n) {
            var s = 0f
            for (c in 0 until ch) s += all[i * ch + c]
            out[i] = s / ch
        }
        return out
    }

    // ---------------- 日志 ----------------

    private fun saveRaw(dir: File?, bytes: ByteArray): String? {
        if (dir == null) return null
        return runCatching {
            if (!dir.exists()) dir.mkdirs()
            val name = "raw_${System.currentTimeMillis()}.mp3"
            File(dir, name).writeBytes(bytes)
            name
        }.getOrNull()
    }

    private fun writeLog(dir: File?, msg: String) {
        if (dir == null) return
        runCatching {
            if (!dir.exists()) dir.mkdirs()
            val f = File(dir, "watermark.log")
            if (f.exists() && f.length() > LOG_MAX) f.delete()
            val ts = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date())
            f.appendText("[$ts] $msg\n")
        }
    }
}
