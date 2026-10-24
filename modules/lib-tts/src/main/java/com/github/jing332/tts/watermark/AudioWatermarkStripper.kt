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
 * 分工：[WatermarkDsp] 纯算法判定；本类负责解码（MediaCodec，优先 float PCM）、
 * MP3 帧索引、截断与日志。**失败一律 fail-open**：返回原始字节，绝不影响合成。
 */
object AudioWatermarkStripper {

    private const val TAG = "WmStrip"
    private const val MAX_BYTES = 8 * 1024 * 1024
    private const val MIN_BYTES = 8 * 1024
    private const val LOG_MAX = 256 * 1024L

    class Outcome(val bytes: ByteArray, val removed: Boolean, val note: String)

    class Decoded(val pcm: FloatArray, val sampleRate: Int, val isFloat: Boolean)

    fun strip(bytes: ByteArray, logDir: File? = null): Outcome {
        val head = Mp3Truncator.head(bytes) ?: return Outcome(bytes, false, "非 MP3，跳过")
        if (bytes.size !in MIN_BYTES..MAX_BYTES) return Outcome(bytes, false, "体积超范围，跳过")

        val decoded = try {
            decode(bytes)
        } catch (t: Throwable) {
            Log.w(TAG, "decode failed: ${t.message}")
            return Outcome(bytes, false, "解码失败: ${t.message}")
        } ?: return Outcome(bytes, false, "解码为空")

        val env = WatermarkDsp.envelope(decoded.pcm, decoded.sampleRate)
            ?: return Outcome(bytes, false, "采样率 ${decoded.sampleRate} 无法判定")

        val a = WatermarkDsp.judge(env, decoded.isFloat)
        val v = a.verdict
        val pcmSec = decoded.pcm.size.toDouble() / decoded.sampleRate
        val base = "正文 %.1f / 尾巴 %.1f / Δ%.1f / %s".format(
            a.bodyDb, a.tailDb, a.deltaDb, if (decoded.isFloat) "float" else "int16"
        )
        if (v == null) return Outcome(bytes, false, "${a.reason}（$base）")

        val cutOffset = Mp3Truncator.offsetAt(bytes, head, v.cutSec)
            ?: return Outcome(bytes, false, "帧索引失败（$base）")
        if (cutOffset < MIN_BYTES) return Outcome(bytes, false, "裁剪点过前（$base）")

        val out = bytes.copyOfRange(0, cutOffset)
        val note = "切掉 %.2fs（%.2fs→%.2fs, %s）".format(
            pcmSec - v.cutSec, pcmSec, v.cutSec, base
        )
        writeLog(logDir, note)
        return Outcome(out, true, note)
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
            return Decoded(pcm, sr, isFloat)
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
