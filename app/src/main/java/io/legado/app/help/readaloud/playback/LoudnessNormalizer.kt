package io.legado.app.help.readaloud.playback

import android.app.Application
import com.github.jing332.compat.fs.TtsDirProvider
import com.github.jing332.tts.store.TtsConfigStore
import io.legado.app.constant.AppLog
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

    /** 批212e：旧键迁移只做一次/进程（迁完文件里不再有旧键） */
    private var legacyMigrated = false
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
        val migrated = runCatching { migrateLegacyTtsServerKeys() }.getOrDefault(false)
        loadedStamp = if (migrated) {
            if (file.exists()) file.lastModified() else -1L
        } else {
            lm
        }
    }

    /**
     * 批212e · 声线身份重构（tagRuleId → 条目 id）后的一次性键迁移：
     * 旧键 `tts_server|<tagRuleId>|<tag>` 若能在 voices.json 找到同 (tagRuleId, tag) 的条目
     * （即声线本体没变、只是身份写法变了），改写为 `tts_server|<条目 id>|<tag>`（同键合并取加权平均）；
     * 找不到对应条目的旧键（声线已不存在）丢弃。读不到 voices.json 时不动（防误伤，下次再试）。
     * 幂等：迁移完成后 legacyMigrated=true，本进程不再扫描。
     */
    private fun migrateLegacyTtsServerKeys(): Boolean {
        if (legacyMigrated) return false
        val legacy = stats.keys.filter {
            it.startsWith("tts_server|") && it.count { c -> c == '|' } == 2
        }
        if (legacy.isEmpty()) {
            legacyMigrated = true
            return false
        }
        val voices = runCatching { TtsConfigStore.loadVoices(app) }.getOrNull() ?: return false
        val idSet = HashSet<String>()
        val byRuleTag = HashMap<String, String>()
        for (g in 0 until voices.length()) {
            val list = voices.optJSONObject(g)?.optJSONArray("list") ?: continue
            for (i in 0 until list.length()) {
                val entry = list.optJSONObject(i) ?: continue
                val id = entry.optString("id").trim()
                if (id.isEmpty()) continue
                idSet.add(id)
                val sr = entry.optJSONObject("config")?.optJSONObject("speechRule") ?: continue
                val tag = sr.optString("tag").trim()
                val rid = sr.optString("tagRuleId").trim()
                if (tag.isNotEmpty() && rid.isNotEmpty()) {
                    byRuleTag.putIfAbsent("$rid\u0000$tag", id)
                }
            }
        }
        if (idSet.isEmpty()) return false
        var migrated = 0
        var dropped = 0
        // 两段式：先收集（遍历快照），再统一落盘到 stats——避免边遍历边写 map
        val additions = HashMap<String, VoiceStat>()
        stats.keys.toList().forEach { k ->
            if (!k.startsWith("tts_server|")) return@forEach
            val seg = k.split('|')
            if (seg.size != 3) return@forEach
            val x = seg[1]
            val t = seg[2]
            if (x in idSet) return@forEach
            val st = stats[k] ?: return@forEach
            val nid = byRuleTag["$x\u0000$t"]
            stats.remove(k)
            if (nid != null) {
                val nk = "tts_server|$nid|$t"
                val old = stats[nk] ?: additions[nk]
                if (old == null) {
                    additions[nk] = st
                } else {
                    val n = old.n + st.n
                    old.p = if (n > 0) (old.p * old.n + st.p * st.n) / n else 0.0
                    old.n = n
                }
                migrated++
            } else {
                dropped++
            }
        }
        additions.forEach { (k, v) -> stats[k] = v }
        legacyMigrated = true
        if (migrated > 0 || dropped > 0) {
            AppLog.putAudio("【响度均衡】声线键迁移：改写 $migrated 条、清理 $dropped 条（身份重构）")
            save()
            return true
        }
        return false
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
