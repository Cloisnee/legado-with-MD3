package io.legado.app.help.readaloud.playback

import android.app.Application
import com.github.jing332.compat.fs.TtsDirProvider
import io.legado.app.domain.model.readaloud.ReadAloudVoice
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

    /** 已学习声线数（日志展示用） */
    fun learnedVoiceCount(): Int = synchronized(lock) {
        runCatching {
            ensureLoaded()
            stats.values.count { it.n > 0 }
        }.getOrDefault(0)
    }

    /** 已学习样本总数（日志展示用） */
    fun learnedSampleCount(): Int = synchronized(lock) {
        runCatching {
            ensureLoaded()
            stats.values.sumOf { it.n }
        }.getOrDefault(0)
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
     * 解码音频 → 有效区均方值（0..1，16bit 满幅 = 1）。无有效信号（静音）或解码失败返回 null。
     * P1.6.2：解码与度量已抽到 [AudioLoudnessMeasure]（与声效侧共用同一口径）。
     */
    private fun measurePower(audio: File): Double? = runCatching {
        val pcm = AudioLoudnessMeasure.decodePcm16(audio) ?: return null
        AudioLoudnessMeasure.effectiveMeanSquare(pcm.bytes)
    }.getOrNull()

    companion object {
        /** 播放侧缓存补测封顶（每声线至多补学样本数） */
        private const val BACKFILL_CAP = 8
    }
}
