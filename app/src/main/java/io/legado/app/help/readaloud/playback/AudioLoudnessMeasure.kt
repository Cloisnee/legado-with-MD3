package io.legado.app.help.readaloud.playback

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max

/**
 * P1.6.2 · 响度测量共用件（人声声线 / 声效两侧共用「解码 + 度量口径」）。
 *
 * - [decodePcm16]：解码音频为 16bit PCM（含采样率）；失败返回 null。
 * - [effectiveMeanSquare]：有效区（首个/末个超过阈值的采样之间）均方值（0..1，16bit 满幅=1）——
 *   声线 / 环境声 / BGM 等「连续声」口径（与 B11 声线侧口径一致）。
 * - [windowPeakPower]：短窗峰值——100ms 窗口内平均平方（= 最大短窗 RMS 的平方，与「均方」同量纲）的
 *   最大值，25ms 步进（无需静音阈值）——「点状音效」口径（更贴近短促音效的响度听感）。
 */
internal object AudioLoudnessMeasure {

    /** 有效信号阈值（16bit 满幅 32768） */
    const val SILENCE_THRESHOLD = 300

    /** 点状音效短窗：窗口长度 / 步进（ms） */
    const val WINDOW_MS = 100
    const val STEP_MS = 25

    /** 16bit PCM 解码结果 */
    data class Pcm16(val bytes: ByteArray, val sampleRate: Int)

    /**
     * 解码音频 → 16bit PCM（按交错原始数据处理，与既有声线测量口径一致）。
     * 非 16bit 编码 / 无音频轨 / 解码失败 → null。
     */
    fun decodePcm16(audio: File): Pcm16? = runCatching {
        if (!audio.exists() || audio.length() <= 0L) return null
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(audio.absolutePath)
            var track = -1
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    track = i
                    break
                }
            }
            if (track < 0) return null
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            var sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else {
                0
            }
            val c = MediaCodec.createDecoderByType(mime).also { codec = it }
            c.configure(format, null, null, 0)
            c.start()

            val info = MediaCodec.BufferInfo()
            val out = ByteArrayOutputStream(1 shl 20)
            var inputDone = false
            var guard = 0
            while (guard++ < 2_000_000) {
                if (!inputDone) {
                    val inIdx = c.dequeueInputBuffer(10_000L)
                    if (inIdx >= 0) {
                        val inBuf = c.getInputBuffer(inIdx)
                        val size = if (inBuf != null) extractor.readSampleData(inBuf, 0) else -1
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
                    val enc = if (nf.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        nf.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    } else {
                        AudioFormat.ENCODING_PCM_16BIT
                    }
                    if (enc != AudioFormat.ENCODING_PCM_16BIT) return null
                    if (sampleRate <= 0 && nf.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        sampleRate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    }
                } else if (outIdx >= 0) {
                    val outBuf = c.getOutputBuffer(outIdx)
                    if (outBuf != null && info.size > 0 &&
                        (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                    ) {
                        outBuf.position(info.offset)
                        outBuf.limit(info.offset + info.size)
                        val chunk = ByteArray(info.size)
                        outBuf.get(chunk)
                        out.write(chunk)
                    }
                    c.releaseOutputBuffer(outIdx, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                }
            }

            Pcm16(out.toByteArray(), if (sampleRate > 0) sampleRate else 44_100)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }.getOrNull()

    /** 有效区（首个/末个超过阈值的采样之间；有效区过短 <800 采样则回退全段）均方值（0..1） */
    fun effectiveMeanSquare(bytes: ByteArray): Double? {
        val n = bytes.size / 2
        if (n <= 0) return null
        var first = -1
        var last = -1
        for (i in 0 until n) {
            val s = sampleAt(bytes, i)
            if (s > SILENCE_THRESHOLD || s < -SILENCE_THRESHOLD) {
                if (first < 0) first = i
                last = i
            }
        }
        if (first < 0) return null
        if (last - first < 800) {
            first = 0
            last = n - 1
        }
        var acc = 0.0
        for (i in first..last) {
            val v = sampleAt(bytes, i) / 32768.0
            acc += v * v
        }
        return acc / (last - first + 1)
    }

    /**
     * 短窗峰值：以 [WINDOW_MS] 为窗、[STEP_MS] 为步进滑过全段，取窗口内平均平方（RMS²）的最大值。
     * 返回与「均方」同量纲的功率值（0..1；= 最大短窗 RMS 的平方）；无有效数据 → null。
     */
    fun windowPeakPower(bytes: ByteArray, sampleRate: Int): Double? {
        val n = bytes.size / 2
        if (n <= 0) return null
        val sr = if (sampleRate > 0) sampleRate else 44_100
        val win = max(1, sr * WINDOW_MS / 1000)
        val step = max(1, sr * STEP_MS / 1000)
        var best = 0.0
        var start = 0
        while (start < n) {
            val end = minOf(start + win, n)
            var acc = 0.0
            for (i in start until end) {
                val v = sampleAt(bytes, i) / 32768.0
                acc += v * v
            }
            val p = acc / (end - start)
            if (p > best) best = p
            if (end >= n) break
            start += step
        }
        return if (best > 0.0) best else null
    }

    private fun sampleAt(bytes: ByteArray, i: Int): Int {
        val lo = bytes[2 * i].toInt() and 0xFF
        val hi = bytes[2 * i + 1].toInt()
        return (hi shl 8) or lo
    }
}