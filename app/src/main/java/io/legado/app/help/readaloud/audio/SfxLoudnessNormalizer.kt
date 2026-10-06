package io.legado.app.help.readaloud.audio

import android.content.Context
import com.github.jing332.compat.fs.TtsDirProvider
import io.legado.app.help.readaloud.playback.AudioLoudnessMeasure
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.log10
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * P1.6.2 · 声效响度均衡（并入现有「响度均衡」体系；独立键空间）。
 *
 * 目标：以人声「响度均衡」基准（`loudness_stats.json` 已学习声线的均方均值 ref≈-18.1dB）为基准线，
 * 把声效 100% 响度拉平到与人声一致；用户「朗读设置→音效音量」在此之后照常相乘（两链独立）。
 *
 * 口径（拍板）：
 * - 点状音效（SFX/ADULT 轨）=「短窗峰值」（100ms 窗口最大 RMS²、25ms 步进）；
 * - 环境声 / BGM（连续声）=「均方」（与人声同口径，直接可比）；
 * - trim = 10·log10(ref / (K·p))，clamp ±12dB；K=短窗折算系数（先 1.0 常量，真机听感微调）；
 * - 施加：正增益 → LoudnessEnhancer（与人声/循环轨共用封装）；负增益 → volume 衰减。
 *
 * 学习时机：素材落库（合成/下载/导入）即后台测；首次播放未见样本后台补测 →
 * 本次按当前档播、测完自动接上（fail-open、后台线程、绝不阻塞播放）。
 * 存储：`<base>/sfx_loudness_stats.json`（独立键空间；键=库内相对路径）。
 */
object SfxLoudnessNormalizer {

    /** 短窗峰值与人声均方的折算系数（K=1.0 起步；>1 → 整体更收敛、<1 → 整体更提升） */
    const val WINDOW_CALIBRATION_K = 1.0

    /** 增益 clamp（±12dB） */
    private const val CLAMP_DB = 12.0

    private const val MODE_WINDOW = "win"
    private const val MODE_MEAN = "mean"

    /** 测量失败冷却（防坏文件反复解码） */
    private const val FAIL_COOLDOWN_MS = 30 * 60 * 1000L

    private class ItemStat(var n: Int = 0, var p: Double = 0.0, var mode: String = MODE_MEAN)

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inflight = ConcurrentHashMap.newKeySet<String>()
    private val failUntil = ConcurrentHashMap<String, Long>()
    private val items = HashMap<String, ItemStat>()
    private var loaded = false

    @Volatile
    private var appCtx: Context? = null

    /** 库根目录（`data/audio_lib`）路径；[remember] 时缓存（播放热路径免 Context 传递） */
    @Volatile
    private var baseDirPath: String? = null

    // 人声基准缓存（loudness_stats.json；lastModified 节流）
    private var refStamp = Long.MIN_VALUE
    private var refCache: Double? = null

    /** 记住应用上下文（引擎/落库链路调用；与 [AudioPlanStore] 同款口径） */
    fun remember(context: Context) {
        val app = context.applicationContext
        appCtx = app
        baseDirPath = TtsDirProvider.baseDir(app).absolutePath
    }

    /** 已学习声效条数（日志展示用） */
    fun learnedCount(): Int = synchronized(lock) {
        runCatching {
            ensureLoaded()
            items.values.count { it.n > 0 }
        }.getOrDefault(0)
    }

    /**
     * 落库 / 首次播放触发测量：未学习过则后台测（同素材至多一个测量任务；失败 30 分钟冷却）。
     * 热路径调用为内存快查（已学习即返回），不阻塞播放。
     */
    fun ensureMeasured(context: Context?, file: File) {
        val ctx = context?.applicationContext ?: appCtx ?: return
        if (baseDirPath == null) remember(ctx)
        val relPath = relPathOf(ctx, file) ?: return
        if (hasItem(relPath)) return
        if ((failUntil[relPath] ?: 0L) > System.currentTimeMillis()) return
        if (!inflight.add(relPath)) return
        scope.launch {
            try {
                measure(file, relPath)
            } finally {
                inflight.remove(relPath)
            }
        }
    }

    /**
     * 该素材当前增益（dB；未学习 / 人声基准不足（<2 条声线）/ 未就绪 → 0；±12 clamp）。
     * 播放链路热查询：内存态 + 节流读基准，失败静默 fail-open。
     */
    fun trimDbFor(relPath: String): Float = synchronized(lock) {
        runCatching {
            if (relPath.isBlank()) return 0f
            ensureLoaded()
            val st = items[relPath] ?: return 0f
            val ref = referencePower() ?: return 0f
            val p = st.p * (if (st.mode == MODE_WINDOW) WINDOW_CALIBRATION_K else 1.0)
            if (p <= 0.0) return 0f
            trimDbOf(ref, p).toFloat()
        }.getOrDefault(0f)
    }

    // ---------------- 内部 ----------------

    private fun hasItem(relPath: String): Boolean = synchronized(lock) {
        runCatching {
            ensureLoaded()
            items.containsKey(relPath)
        }.getOrDefault(false)
    }

    private fun measure(file: File, relPath: String) {
        val pcm = AudioLoudnessMeasure.decodePcm16(file)
        if (pcm == null) {
            failUntil[relPath] = System.currentTimeMillis() + FAIL_COOLDOWN_MS
            return
        }
        val window = AudioLibrary.laneSynthOfRelPath(relPath) == SynthLane.SFX
        val p = if (window) {
            AudioLoudnessMeasure.windowPeakPower(pcm.bytes, pcm.sampleRate)
        } else {
            AudioLoudnessMeasure.effectiveMeanSquare(pcm.bytes)
        }
        if (p == null || p <= 0.0) {
            failUntil[relPath] = System.currentTimeMillis() + FAIL_COOLDOWN_MS
            return
        }
        synchronized(lock) {
            runCatching {
                ensureLoaded()
                val st = items.getOrPut(relPath) { ItemStat() }
                st.n += 1
                st.p = p
                st.mode = if (window) MODE_WINDOW else MODE_MEAN
                save()
            }
        }
    }

    private fun ensureLoaded() {
        if (loaded) return
        val base = baseDirPath ?: return
        loaded = true
        runCatching {
            val f = File(base, "sfx_loudness_stats.json")
            if (!f.isFile) return
            val itemsObj = JSONObject(f.readText().removePrefix("\uFEFF")).optJSONObject("items")
                ?: return
            itemsObj.keys().forEach { k ->
                val o = itemsObj.optJSONObject(k) ?: return@forEach
                items[k] = ItemStat(
                    o.optInt("n", 0),
                    o.optDouble("p", 0.0),
                    o.optString("mode", MODE_MEAN),
                )
            }
        }
    }

    private fun save() {
        val base = baseDirPath ?: return
        runCatching {
            val f = File(base, "sfx_loudness_stats.json")
            f.parentFile?.mkdirs()
            val o = JSONObject().apply {
                put("version", 1)
                put(
                    "items",
                    JSONObject().apply {
                        items.forEach { (k, st) ->
                            if (st.n > 0 && st.p > 0.0) {
                                put(
                                    k,
                                    JSONObject().apply {
                                        put("n", st.n)
                                        put("p", st.p)
                                        put("mode", st.mode)
                                    },
                                )
                            }
                        }
                    },
                )
            }
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(f)) {
                tmp.copyTo(f, overwrite = true)
                tmp.delete()
            }
        }
    }

    /** 人声基准功率（已学习声线均方均值；有效声线 <2 条 → null=不调；lastModified 节流） */
    private fun referencePower(): Double? {
        val base = baseDirPath ?: return null
        val f = File(base, "loudness_stats.json")
        val lm = if (f.exists()) f.lastModified() else -1L
        if (lm == refStamp) return refCache
        refStamp = lm
        refCache = runCatching {
            if (!f.isFile) return@runCatching null
            val voices = JSONObject(f.readText().removePrefix("\uFEFF")).optJSONObject("voices")
                ?: return@runCatching null
            val ps = ArrayList<Double>()
            voices.keys().forEach { k ->
                val o = voices.optJSONObject(k) ?: return@forEach
                val n = o.optInt("n", 0)
                val p = o.optDouble("p", 0.0)
                if (n > 0 && p > 0.0) ps.add(p)
            }
            if (ps.size < 2) null else ps.average()
        }.getOrNull()
        return refCache
    }

    private fun relPathOf(context: Context, file: File): String? = runCatching {
        file.relativeTo(TmDemoAssets.libRoot(context)).path.replace(File.separatorChar, '/')
    }.getOrNull()?.takeIf { it.isNotBlank() && !it.startsWith("..") }

    /**
     * trim 公式（internal 供单测）：10·log10(ref / p)，clamp ±12dB。
     * 量纲：ref=人声均方；p=本条实测（点状=短窗峰值·K / 连续=均方）。
     */
    internal fun trimDbOf(ref: Double, p: Double): Double {
        if (ref <= 0.0 || p <= 0.0) return 0.0
        return (10.0 * log10(ref / p)).coerceIn(-CLAMP_DB, CLAMP_DB)
    }
}