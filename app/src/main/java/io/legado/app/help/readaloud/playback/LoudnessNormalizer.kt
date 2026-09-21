package io.legado.app.help.readaloud.playback

import android.app.Application
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.github.jing332.compat.fs.TtsDirProvider
import io.legado.app.domain.model.readaloud.ReadAloudVoice
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * B11 响度均衡：按「声线」学习平均响度，播放端（LoudnessEnhancer）据此拉平不同插件声线的音量。
 *
 *  - 学习：合成完成 / 播放缓存命中时异步测量音频（PCM 有效区均方功率，样本文件 `loudness_stats.json`）。
 *  - 增益：以全部已学习声线的平均功率为基准，gain = 10·log10(ref / p)，Clamp ±6000mB（±6dB）。
 *  - 仅作用于朗读播放端；不改音频文件本体；全程 fail-open（任何失败静默，不影响朗读）。
 */
class LoudnessNormalizer(private val app: Application) {

    private data class VoiceStat(var n: Int = 0, var p: Double = 0.0)

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inflight = ConcurrentHashMap.newKeySet<String>()
    private val stats = HashMap<String, VoiceStat>()
    private var loadedStamp = Long.MIN_VALUE
    private val file: File get() = File(TtsDirProvider.baseDir(app), "loudness_stats.json")

    /** 声线身份键（跨插件唯一） */
    fun voiceKeyOf(voice: ReadAloudVoice): String =
        "${voice.engineType}|${voice.engineId}|${voice.speakerId}"

    /** 该声线当前增益（mB；无数据 / 仅一个声线时为 0） */
    fun gainMbFor(voice: ReadAloudVoice): Int = synchronized(lock) {
        runCatching {
            ensureLoaded()
            val st = stats[voiceKeyOf(voice)]
            if (st == null || st.n <= 0 || st.p <= 0.0) {
                0
            } else {
                val valid = stats.values.filter { it.n > 0 && it.p > 0.0 }
                if (valid.size < 2) {
                    0
                } else {
                    val ref = valid.map { it.p }.average()
                    if (ref <= 0.0) 0
                    else (10.0 * log10(ref / st.p) * 100).roundToInt().coerceIn(-6000, 6000)
                }
            }
        }.getOrDefault(0)
    }

    /** 该声线样本是否不足（播放侧缓存补测用，每声线封顶 [BACKFILL_CAP] 样本） */
    fun needsSamples(voice: ReadAloudVoice): Boolean = synchronized(lock) {
        runCatching {
            ensureLoaded()
            (stats[voiceKeyOf(voice)]?.n ?: 0) < BACKFILL_CAP
        }.getOrDefault(false)
    }

    /** 异步测量并学习（同一时刻每声线至多一个测量任务；失败静默） */
    fun measureAsync(voice: ReadAloudVoice, audio: File) {
        val key = voiceKeyOf(voice)
        if (!inflight.add(key)) return
        scope.launch {
            try {
                val power = measurePower(audio) ?: return@launch
                synchronized(lock) {
                    runCatching {
                        ensureLoaded()
                        val st = stats.getOrPut(key) { VoiceStat() }
                        st.n += 1
                        st.p += (power - st.p) / st.n
                        save()
                    }
                }
            } finally {
                inflight.remove(key)
            }
        }
    }

    // ---------------- 内部 ----------------

    private fun ensureLoaded() {
        val lm = if (file.exists()) file.lastModified() else -1L
        if (lm == loadedStamp) return
        stats.clear()
        runCatching {
            val voices = JSONObject(file.readText().removePrefix("\uFEFF")).optJSONObject("voices")
            voices?.keys()?.forEach { k ->
                val o = voices.optJSONObject(k) ?: return@forEach
                stats[k] = VoiceStat(o.optInt("n", 0), o.optDouble("p", 0.0))
            }
        }
        loadedStamp = lm
    }

    private fun save() {
        val o = JSONObject().apply {
            put("version", 1)
            put(
                "voices",
                JSONObject().apply {
                    stats.forEach { (k, st) ->
                        if (st.n > 0 && st.p > 0.0) {
                            put(
                                k,
                                JSONObject().apply {
                                    put("n", st.n)
                                    put("p", st.p)
                                },
                            )
                        }
                    }
                },
            )
        }
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(o.toString())
            loadedStamp = file.lastModified()
        }
    }

    /**
     * 解码音频 → 有效区（首个/末个超过阈值的采样之间）均方值（0..1，16bit 满幅 = 1）。
     * 无有效信号（静音）或解码失败返回 null。
     */
    private fun measurePower(audio: File): Double? = runCatching {
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

            val bytes = out.toByteArray()
            val n = bytes.size / 2
            if (n <= 0) return null

            fun sampleAt(i: Int): Int {
                val lo = bytes[2 * i].toInt() and 0xFF
                val hi = bytes[2 * i + 1].toInt()
                return (hi shl 8) or lo
            }

            var first = -1
            var last = -1
            for (i in 0 until n) {
                val s = sampleAt(i)
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
                val v = sampleAt(i) / 32768.0
                acc += v * v
            }
            acc / (last - first + 1)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }.getOrNull()

    companion object {
        /** 播放侧缓存补测封顶（每声线至多补学样本数） */
        private const val BACKFILL_CAP = 8

        /** 有效信号阈值（16bit 满幅 32768） */
        private const val SILENCE_THRESHOLD = 300
    }
}
