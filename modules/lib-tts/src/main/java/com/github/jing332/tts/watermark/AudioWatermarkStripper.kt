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
 * 背景：呱呱有声的试听接口 `/story_v2/api/record/tts/trail` 在音频尾部**叠加**一段固定水印录音
 * （“欢迎使用呱呱有声”，1.22 s，电平比正文低 3~19 dB，位置固定在结尾前 0.25 s）。
 * 水印素材频带只到 ~13.5 kHz，与 48 kHz 全带正文有 40 dB 以上的高频差 ——
 * 据此定位后**按 MP3 帧边界截断**，不重编码、无二次压缩损失。
 *
 * 判据：与内嵌水印模板的 log-mel 相关度（阈值 0.65），且**只对 48 kHz MP3 生效**。
 * 分工：[WatermarkDsp] 纯算法；本类负责解码（MediaCodec）、MP3 帧索引、截断与日志。
 * **失败一律 fail-open**：返回原始字节，绝不影响合成。
 */
object AudioWatermarkStripper {

    private const val TAG = "WmStrip"
    private const val MAX_BYTES = 8 * 1024 * 1024
    private const val MIN_BYTES = 8 * 1024
    private const val LOG_MAX = 256 * 1024L

    class Outcome(val bytes: ByteArray, val removed: Boolean, val note: String)

    class Decoded(val pcm: FloatArray, val sampleRate: Int)

    private val template: FloatArray by lazy {
        WatermarkDsp.decodeTemplate(WatermarkTemplate.BASE64, WatermarkTemplate.SCALE)
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
        /** 试听链路传 true：切之前把原始字节另存一份，便于事后取样分析 */
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
        // 低阈值先取候选：减法分支需要它（叠加态 mel 只有 0.67~0.78）
        val matches = WatermarkDsp.locateAll(decoded.pcm, template, 0.55f)
        if (matches.isEmpty()) return Outcome(bytes, false, "未命中水印")

        val best = matches[0]
        val ec = WatermarkDsp.envelopeCorr(decoded.pcm, best.startSample, WatermarkTemplate.ENV)
        val bl = WatermarkDsp.bandLimitDelta(decoded.pcm, best.startSample)

        // A. 干净拼接（三道闸门全过）→ 切除：无损、不重编码
        val canCut = best.score >= WatermarkDsp.MATCH_THRESHOLD && bl >= 12f && ec >= 0.65f
        if (!canCut) {
            // B. 叠加态 / 边界情况 → 相减（自带纯净段校验，判错会返回 null，不误伤）
            return subtractPath(context, bytes, decoded, best, ec, bl, logDir, keepRaw)
        }

        val sr = WatermarkDsp.SAMPLE_RATE
        val durSec = decoded.pcm.size.toDouble() / sr
        val wmEnd = best.startSample + WatermarkDsp.watermarkSamples()
        // 水印是否贴着结尾（后面只剩 ~0.25 s 静音）→ 直接截断；否则是插在句子中间 → 抽帧拼接
        val nearTail = wmEnd + (0.30 * sr).toInt() >= decoded.pcm.size

        val note: String
        val outBytes: ByteArray
        if (nearTail) {
            val center = best.startSample - (0.02 * sr).toInt()
            val cutSample = WatermarkDsp.snapToQuietest(decoded.pcm, maxOf(0, center), 6)
            val cutSec = cutSample.toDouble() / sr
            if (cutSec < 0.5) return Outcome(bytes, false, "剩余正文过短（命中 %.3f）".format(best.score))
            val cutOffset = Mp3Truncator.offsetAt(bytes, head, cutSec)
                ?: return Outcome(bytes, false, "帧索引失败（命中 %.3f）".format(best.score))
            if (cutOffset < MIN_BYTES) return Outcome(bytes, false, "裁剪点过前（命中 %.3f）".format(best.score))
            outBytes = bytes.copyOfRange(0, cutOffset)
            note = "尾部截断 %.2fs（%.2fs→%.2fs, 命中 %.3f）".format(
                durSec - cutSec, durSec, cutSec, best.score
            )
        } else {
            val fromSec = (best.startSample - (0.02 * sr).toInt()).coerceAtLeast(0).toDouble() / sr
            val toSec = (wmEnd + (0.03 * sr).toInt()).toDouble() / sr
            val spliced = Mp3Truncator.splice(bytes, head, fromSec, toSec)
                ?: return Outcome(bytes, false, "中段抽帧失败（命中 %.3f）".format(best.score))
            if (spliced.size < MIN_BYTES) return Outcome(bytes, false, "抽帧后过短（命中 %.3f）".format(best.score))
            outBytes = spliced
            note = "中段抽帧 %.2f-%.2fs（命中 %.3f）".format(fromSec, toSec, best.score)
        }
        val cost = android.os.SystemClock.elapsedRealtime() - t0
        val rawName = if (keepRaw) saveRaw(logDir, bytes) else null
        // 注意：这里不能用 "%.1f...".format() —— note 里含动态内容，任何占位符错配都会抛异常，
        // 而异常在"已经切完"之后抛出会被外层判成失败，等于白切。一律用拼接。
        val rawTag = if (rawName != null) " | raw=$rawName" else ""
        val blStr = "%.1f".format(bl)
        val ecStr = "%.2f".format(ec)
        runCatching {
            writeLog(logDir, "$note | Δ$blStr | 包络$ecStr | 候选 ${matches.size} | ${cost}ms$rawTag")
        }
        return Outcome(outBytes, true, note)
    }

    /** 叠加态：按纯净段拟合增益 → 相减 → 重叠段谱减 → 写 WAV */
    private fun subtractPath(
        context: android.content.Context?,
        bytes: ByteArray,
        decoded: Decoded,
        best: WatermarkDsp.Match,
        ec: Float,
        bl: Float,
        logDir: File?,
        keepRaw: Boolean,
    ): Outcome {
        if (context == null) return Outcome(bytes, false, "无 Context，无法加载模板")
        val wm = runCatching {
            context.assets.open("watermark_wm.pcm").use { ins ->
                val b = ins.readBytes()
                FloatArray(b.size / 2) { i ->
                    ((((b[i * 2 + 1].toInt() shl 8) or (b[i * 2].toInt() and 0xFF)).toShort())).toFloat() / 32768f
                }
            }
        }.getOrNull() ?: run {
            runCatching { writeLog(logDir, "叠加相减失败：模板资源缺失") }
            return Outcome(bytes, false, "模板资源缺失")
        }

        val t0 = android.os.SystemClock.elapsedRealtime()
        val r = WatermarkSubtractor.subtract(decoded.pcm, best.startSample, wm)
        if (r == null) {
            val why = "叠加相减失败：未找到纯净段（命中 %.3f/包络 %.2f）".format(best.score, ec)
            runCatching { writeLog(logDir, why) }
            return Outcome(bytes, false, why)
        }

        // 截到水印末尾，补 250ms 静音（时长与原始一致）
        val end = best.startSample + wm.size
        val tail = WatermarkDsp.SAMPLE_RATE / 4
        val outPcm = FloatArray(end + tail)
        System.arraycopy(r.pcm, 0, outPcm, 0, minOf(end, r.pcm.size))
        val wav = toWav(outPcm, WatermarkDsp.SAMPLE_RATE)
        val cost = android.os.SystemClock.elapsedRealtime() - t0
        val note = "叠加相减（增益 %.2f→%.2f, 纯净段 %.2fs, 残余 %.1f dB, %dms）".format(
            r.gainFrom, r.gainTo, r.cleanFromSec, r.residualDb, cost
        )
        runCatching { writeLog(logDir, "$note | 命中 ${"%.3f".format(best.score)} | 包络 $ec | Δ$bl") }
        if (keepRaw) saveRaw(logDir, bytes)
        return Outcome(wav, true, note)
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
            // 请求 float 输出：16 bit 量化底噪是判决余量的唯一瓶颈
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

    /** 切之前留档原文件（仅试听链路），返回文件名 */
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
