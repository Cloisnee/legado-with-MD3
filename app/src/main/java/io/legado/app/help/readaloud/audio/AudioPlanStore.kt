package io.legado.app.help.readaloud.audio

import android.content.Context
import com.github.jing332.compat.fs.TtsDirProvider
import io.legado.app.constant.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * B33.4b · AI 导演产出的章节音频计划条目（写剧本 `[[a:…]]` + 驱动播放/预合成）。
 */
data class AudioPlanItem(
    val para: Int,
    val type: String,
    val tag: String = "",
    val desc: String = "",
    val delayMs: Long = 0L,
    /** B34.2：句内触发位置比例（0=句首；AI 前/中/后或规则命中位换算而来） */
    val posRatio: Float = 0f,
    val hold: Int = 0,
    val profile: String = "",
    val mood: String = "",
    val intensity: String = "",
    val anchor: String = "",
) {
    /** 展示名：环境/音效用 tag；BGM 用「画像·情绪·强度」，无字段时回退 tag */
    val displayName: String
        get() = when (type) {
            AudioTagCodec.TYPE_BGM -> listOf(profile, mood, intensity)
                .filter { it.isNotBlank() }.joinToString("·").ifBlank { tag }
            else -> tag
        }
}

/** B33.4b · 章节音频计划（source=ai；本地兜底规则层运行时模拟、不写文件） */
data class AudioPlan(
    val source: String = "ai",
    val scriptHash: String = "",
    val ambience: List<AudioPlanItem> = emptyList(),
    val bgm: List<AudioPlanItem> = emptyList(),
    val sfx: List<AudioPlanItem> = emptyList(),
) {
    val itemCount: Int get() = ambience.size + bgm.size + sfx.size
    val isEmpty: Boolean get() = itemCount == 0

    /** 计数文案（唯一条目口径：同轨同名去重——与音频侧「剧本统计」同口径，日志对照用） */
    fun countsText(): String {
        val amb = ambience.distinctBy { it.tag }.size
        val sfxN = sfx.distinctBy { it.tag }.size
        val bgmN = bgm.distinctBy { it.displayName }.size
        return "bgm ${bgmN}条、环境声 ${amb}条、音效 ${sfxN}条"
    }
}

/**
 * B33.4b · 章节音频计划存储：`data/books/<书>/audio_plan.<书>.json`，键 = `bookUrl|章号`。
 *
 * - 仅 AI 导演链路写入（source=ai）；失败/未配置/首章非连续 → 无计划（播放侧回退规则层）；
 * - 分析侧无 Context 场景（管线）：由 [remember] 记住应用上下文（与 [AudioLaneScan] 同款口径）。
 */
object AudioPlanStore {

    private const val FILE_VERSION = 1

    @Volatile
    private var appCtx: Context? = null

    /** 记住应用上下文（引擎/预合成/规则预热时调用） */
    fun remember(context: Context) {
        appCtx = context.applicationContext
    }

    private fun file(context: Context, book: String): File =
        File(TtsDirProvider.baseDir(context), "data/books/$book/audio_plan.$book.json")

    private fun key(bookUrl: String, chapterIndex: Int): String = "$bookUrl|$chapterIndex"

    suspend fun save(book: String, bookUrl: String, chapterIndex: Int, plan: AudioPlan): Boolean =
        withContext(Dispatchers.IO) {
            val context = appCtx ?: return@withContext false
            if (book.isBlank() || bookUrl.isBlank() || chapterIndex < 0) return@withContext false
            runCatching {
                val f = file(context, book)
                f.parentFile?.mkdirs()
                val root = runCatching {
                    JSONObject(f.readText().removePrefix("\uFEFF"))
                }.getOrElse { JSONObject() }
                root.put("version", FILE_VERSION)
                val plans = root.optJSONObject("plans") ?: JSONObject()
                plans.put(key(bookUrl, chapterIndex), planToJson(plan))
                root.put("plans", plans)
                val tmp = File(f.parentFile, f.name + ".tmp")
                tmp.writeText(root.toString())
                if (!tmp.renameTo(f)) {
                    tmp.copyTo(f, overwrite = true)
                    tmp.delete()
                }
                true
            }.getOrElse { e ->
                AppLog.putAudio("【音效与背景音】音频计划写入失败：${e.localizedMessage}")
                false
            }
        }

    suspend fun load(book: String, bookUrl: String, chapterIndex: Int): AudioPlan? =
        withContext(Dispatchers.IO) {
            val context = appCtx ?: return@withContext null
            runCatching {
                val f = file(context, book)
                if (!f.isFile) return@runCatching null
                val root = JSONObject(f.readText().removePrefix("\uFEFF"))
                val plans = root.optJSONObject("plans") ?: return@runCatching null
                plans.optJSONObject(key(bookUrl, chapterIndex))?.let { planFromJson(it) }
            }.getOrNull()
        }

    private fun planToJson(plan: AudioPlan): JSONObject = JSONObject().apply {
        put("scriptHash", plan.scriptHash)
        put("source", plan.source)
        put("updatedAt", System.currentTimeMillis())
        put("lanes", JSONObject().apply {
            put("ambience", itemsToJson(plan.ambience))
            put("bgm", itemsToJson(plan.bgm))
            put("sfx", itemsToJson(plan.sfx))
        })
    }

    private fun itemsToJson(items: List<AudioPlanItem>): JSONArray = JSONArray().apply {
        items.forEach { item ->
            put(JSONObject().apply {
                put("para", item.para)
                if (item.tag.isNotBlank()) put("tag", item.tag)
                if (item.desc.isNotBlank()) put("desc", item.desc)
                if (item.delayMs > 0) put("delayMs", item.delayMs)
                if (item.posRatio > 0f) put("posRatio", item.posRatio.toDouble())
                if (item.hold > 0) put("hold", item.hold)
                if (item.profile.isNotBlank()) put("profile", item.profile)
                if (item.mood.isNotBlank()) put("mood", item.mood)
                if (item.intensity.isNotBlank()) put("intensity", item.intensity)
                if (item.anchor.isNotBlank()) put("anchor", item.anchor)
            })
        }
    }

    private fun planFromJson(o: JSONObject): AudioPlan {
        val lanes = o.optJSONObject("lanes")
        return AudioPlan(
            source = o.optString("source", "ai"),
            scriptHash = o.optString("scriptHash"),
            ambience = itemsFromJson(lanes?.optJSONArray("ambience"), AudioTagCodec.TYPE_AMB),
            bgm = itemsFromJson(lanes?.optJSONArray("bgm"), AudioTagCodec.TYPE_BGM),
            sfx = itemsFromJson(lanes?.optJSONArray("sfx"), AudioTagCodec.TYPE_SFX),
        )
    }

    private fun itemsFromJson(arr: JSONArray?, type: String): List<AudioPlanItem> {
        if (arr == null) return emptyList()
        val out = ArrayList<AudioPlanItem>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out += AudioPlanItem(
                para = o.optInt("para"),
                type = type,
                tag = o.optString("tag"),
                desc = o.optString("desc"),
                delayMs = o.optLong("delayMs"),
                posRatio = o.optDouble("posRatio", 0.0).toFloat(),
                hold = o.optInt("hold"),
                profile = o.optString("profile"),
                mood = o.optString("mood"),
                intensity = o.optString("intensity"),
                anchor = o.optString("anchor"),
            )
        }
        return out
    }
}
