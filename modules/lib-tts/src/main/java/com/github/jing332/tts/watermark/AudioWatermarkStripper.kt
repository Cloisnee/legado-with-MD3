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
    /** 低于这个分但高于 0.10：写一行“接近未命中”日志，便于事后定位 */
    private const val TAIL_NEAR = 0.10f

    /** 相减后：结尾近静音收到「最后有声帧 + 100ms」（试听拍板），末尾 10ms 淡出 */
    private const val TAIL_KEEP_MS = 100
    /** 10ms 帧 @48k */
    private const val SILENCE_FRAME = 480
    /** 静音判定阈值（实测残留广告 -50~-60dBFS，台词 -25~-35dBFS） */
    private const val SILENCE_DB = -45.0

    class Outcome(val bytes: ByteArray, val removed: Boolean, val note: String)

    class Decoded(val pcm: FloatArray, val sampleRate: Int)

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
        if (pluginId.isBlank()) return Outcome(bytes, false, "无插件标识，跳过")
        if (!com.github.jing332.tts.store.TtsConfigStore.declaresWatermark(context, pluginId)) {
            return Outcome(bytes, false, "插件未声明水印，跳过")
        }
        return strip(context, bytes, logDir, keepRaw)
    }

    fun strip(context: android.content.Context?, bytes: ByteArray, logDir: File? = null, keepRaw: Boolean = false): Outcome {
        val head = Mp3Truncator.head(bytes) ?: return Outcome(bytes, false, "非 MP3，跳过")
        if (bytes.size !in MIN_BYTES..MAX_BYTES) return Outcome(bytes, false, "体积超范围，跳过")

        val decoded = try {
            decode(bytes)
        } catch (t: Throwable) {
            Log.w(TAG, "decode failed: ${t.message}")
            return Outcome(bytes, false, "解码失败: ${t.message}")
        } ?: return Outcome(bytes, false, "解码为空")

        // 只在 48 kHz 上判定：呱呱固定 48k；别的采样率直接放行，杜绝误伤
        if (decoded.sampleRate != WatermarkDsp.SAMPLE_RATE) {
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
                // 两层判据：高分直接认；0.25~0.45 要求至少 2 个干净块（挡掉单块巧合）
                val accept = tail.score >= TAIL_HI || clean >= 2
                if (accept) {
                    if (clean == stats.size) {
                        // 整窗干净 → 截尾 / 抽帧（原 MP3 字节，无重编码）
                        return cutPath(head, bytes, decoded, tail.startSample, tail.score, clean, stats.size, logDir, keepRaw, t0)
                    }
                    val r = WatermarkSubtractor.subtract(decoded.pcm, wm, tail.startSample)
                    if (r != null) {
                        return subtractPath(bytes, decoded, r, wm.size, tail.score, clean, stats.size, logDir, keepRaw, t0)
                    }
                    // 相减失败（极少）→ 继续走 mel 回退，不要静默
                    runCatching { writeLog(logDir, "尾部命中 %.3f 但相减失败（块 %d/%d）".format(tail.score, clean, stats.size)) }
                } else {
                    runCatching { writeLog(logDir, "尾部中分未采用（%.3f, 块 %d/%d）".format(tail.score, clean, stats.size)) }
                }
            }
        }

        // ---------- 2. 回退：mel 全片扫描（句中插入） ----------
        var matches = WatermarkDsp.locateByProbe(decoded.pcm, template, 90, 30, 0.90f)
        if (matches.isEmpty()) matches = WatermarkDsp.locateAll(decoded.pcm, template, 0.55f)
        if (matches.isEmpty()) {
            // 高于下限的样本已在上面写过拒绝原因（中分未采用 / 相减失败），这里只报"低于下限"的
            if (tailScore >= TAIL_NEAR && tailScore < TAIL_MIN) {
                runCatching { writeLog(logDir, "未命中（尾部探针 %.3f，低于 %.2f）".format(tailScore, TAIL_MIN)) }
            }
            return Outcome(bytes, false, "未命中水印")
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
            return cutPath(head, bytes, decoded, best.startSample, best.score, clean, 12, logDir, keepRaw, t0)
        }
        if (wm == null) return Outcome(bytes, false, "无 Context，无法加载模板")
        val r = WatermarkSubtractor.subtract(decoded.pcm, wm, best.startSample, 0.80f)
            ?: return Outcome(bytes, false, "相减失败（命中 %.3f）".format(best.score))
        return subtractPath(bytes, decoded, r, wm.size, best.score, 0, 12, logDir, keepRaw, t0)
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
        return Outcome(outBytes, true, note)
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
        val note = "尾部相减（命中 %.3f, 块 %d/%d, 增益 %.2f·t%+.2f, 残差 %.0fdB）".format(
            score, clean, total, r.slope, r.intercept, r.residualDb
        ) + if (trimmedMs > 0) " | 静音收口 ${trimmedMs}ms" else ""
        runCatching { writeLog(logDir, "$note | ${cost}ms" + (if (keepRaw) " | raw=" + (saveRaw(logDir, bytes) ?: "-") else "")) }
        return Outcome(wav, true, note)
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
            return Decoded(pcm, sr)
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
