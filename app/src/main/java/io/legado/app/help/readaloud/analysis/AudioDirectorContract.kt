package io.legado.app.help.readaloud.analysis

import io.legado.app.help.readaloud.audio.AudioPlan
import io.legado.app.help.readaloud.audio.AudioPlanItem
import io.legado.app.help.readaloud.audio.AudioPositions
import io.legado.app.help.readaloud.audio.AudioTagCodec
import org.json.JSONObject

/**
 * B33.4b · 音频导演返回契约（本地校验，纯逻辑）。
 *
 * 输入=导演返回的 JSON（`{"items":[…]}`），输出=校验归一后的 [AudioPlan] 或失败原因（failHint 顺延重试）。
 *
 * 口径（2026-10-02 甲方定稿）：
 *  - 不注入库存；靠「简短、具体、常见」的命名 + 结构化 BGM 三字段命中素材库；
 *  - 音效＝拟音式全覆盖（有动静就标）；BGM＝结构化 {画像/情绪/强度} + hold 行数；
 *  - 容错策略：单条非法→剔除并记录；错误过多（≥3 条且占比≥1/3）或全空 → 判失败重试。
 */
object AudioDirectorContract {

    /** 画像值集（与默认提示词保持一致；素材侧同时认中英/变体 token） */
    val PROFILES = listOf("通用", "幻想", "历史", "恐怖", "爱情", "科幻", "悬疑", "现代", "武侠", "仙侠")

    /** 情绪值集 */
    val MOODS = listOf("平静", "舒缓", "温馨", "悲情", "凄凉", "紧张", "压迫感", "悬疑", "热血", "史诗", "幽默", "轻快")

    /** 强度值集 */
    val INTENSITIES = listOf("低", "中", "高")

    private val PROFILE_ALIAS: Map<String, String> = mapOf(
        "common" to "通用", "universal" to "通用", "普通" to "通用",
        "fantasy" to "幻想", "奇幻" to "幻想",
        "history" to "历史", "古风" to "历史", "民国" to "历史", "ancient" to "历史",
        "horror" to "恐怖",
        "romance" to "爱情", "romantic" to "爱情",
        "scifi" to "科幻", "sci-fi" to "科幻", "sciencefiction" to "科幻", "未来" to "科幻",
        "suspense" to "悬疑", "mystery" to "悬疑", "mysterious" to "悬疑",
        "urban" to "现代", "都市" to "现代",
        "wuxia" to "武侠",
        "xianxia" to "仙侠",
    )

    private val MOOD_ALIAS: Map<String, String> = mapOf(
        "calm" to "平静", "quiet" to "平静",
        "gentle" to "舒缓", "轻缓" to "舒缓",
        "warm" to "温馨", "cozy" to "温馨", "温暖" to "温馨",
        "sad" to "悲情", "sadness" to "悲情", "悲伤" to "悲情", "伤感" to "悲情", "melancholic" to "悲情",
        "desolate" to "凄凉", "lonely" to "凄凉", "孤寂" to "凄凉", "荒凉" to "凄凉",
        "tense" to "紧张", "tension" to "紧张", "紧绷" to "紧张",
        "oppressive" to "压迫感", "压抑" to "压迫感", "dark" to "压迫感",
        "mystery" to "悬疑", "suspense" to "悬疑", "mysterious" to "悬疑",
        "battle" to "热血", "heroic" to "热血", "战歌" to "热血", "激昂" to "热血",
        "epic" to "史诗", "宏大" to "史诗", "恢弘" to "史诗",
        "comic" to "幽默", "funny" to "幽默", "comedy" to "幽默", "搞笑" to "幽默",
        "light" to "轻快", "upbeat" to "轻快", "欢快" to "轻快", "清新" to "轻快", "轻快" to "轻快",
    )

    private val INTENSITY_ALIAS: Map<String, String> = mapOf(
        "low" to "低", "轻" to "低", "轻柔" to "低",
        "mid" to "中", "medium" to "中", "中等" to "中",
        "high" to "高", "strong" to "高", "强" to "高", "强烈" to "高",
    )

    private const val MAX_TOTAL = 120
    private const val MAX_SFX = 80
    private const val MAX_AMB = 12
    private const val MAX_BGM = 8

    private val WEIRD_CHARS = Regex("[\\[\\]\\r\\n]")

    /** 校验导演返回（paraCount=输入编号总数，即段数） */
    fun validate(root: JSONObject, paraCount: Int): ValidateOutcome<AudioPlan> {
        val items = root.optJSONArray("items")
            ?: return ValidateOutcome(null, "缺少 items 数组（输出格式：{\"items\":[…] }）")
        if (items.length() == 0) {
            return ValidateOutcome(null, "items 为空：未返回任何条目，请按「有动静就标」通读全文后重新输出")
        }
        val errors = ArrayList<String>()
        var bad = 0
        var enumHint = false
        val amb = ArrayList<AudioPlanItem>()
        val bgm = ArrayList<AudioPlanItem>()
        val sfx = ArrayList<AudioPlanItem>()
        val seen = HashSet<String>()
        for (i in 0 until items.length()) {
            val o = items.optJSONObject(i)
            if (o == null) {
                bad++
                continue
            }
            val para = o.optInt("para", -1)
            val type = normType(o.optString("type"))
            if (type == null) {
                bad++
                if (bad <= 5) errors += "第${i + 1}条 type 非法（应为 ambience/bgm/sfx）"
                continue
            }
            if (para !in 1..paraCount) {
                bad++
                if (bad <= 5) errors += "第${i + 1}条 para=$para 越界（应为 1..$paraCount）"
                continue
            }
            val tag = cleanTag(o.optString("tag"))
            val desc = cleanText(o.optString("desc"), 90)
            val anchor = cleanText(o.optString("anchor"), 40)
            val item = when (type) {
                AudioTagCodec.TYPE_AMB -> {
                    if (tag.isBlank()) {
                        bad++
                        if (bad <= 5) errors += "第${i + 1}条环境缺少 tag"
                        null
                    } else {
                        AudioPlanItem(para, type, tag = tag, desc = desc, anchor = anchor)
                    }
                }

                AudioTagCodec.TYPE_SFX -> {
                    if (tag.isBlank()) {
                        bad++
                        if (bad <= 5) errors += "第${i + 1}条音效缺少 tag"
                        null
                    } else {
                        AudioPlanItem(
                            para = para,
                            type = type,
                            tag = tag,
                            desc = desc,
                            delayMs = o.optLong("delayMs", 0L).coerceIn(0L, 3000L),
                            posRatio = AudioPositions.ratioOf(o.optString("pos")),
                            anchor = anchor,
                        )
                    }
                }

                else -> {
                    val profile = normEnum(o.optString("profile"), PROFILES, PROFILE_ALIAS)
                    val mood = normEnum(o.optString("mood"), MOODS, MOOD_ALIAS)
                    val intensity = normEnum(o.optString("intensity"), INTENSITIES, INTENSITY_ALIAS)
                    if (profile == null || mood == null || intensity == null) {
                        bad++
                        enumHint = true
                        if (bad <= 5) errors += "第${i + 1}条 BGM 画像/情绪/强度非法（profile=${o.optString("profile")} mood=${o.optString("mood")} intensity=${o.optString("intensity")}）"
                        null
                    } else {
                        AudioPlanItem(
                            para = para,
                            type = type,
                            tag = tag,
                            desc = desc,
                            hold = o.optInt("hold", 15).coerceIn(4, 60),
                            profile = profile,
                            mood = mood,
                            intensity = intensity,
                            anchor = anchor,
                        )
                    }
                }
            } ?: continue
            val key = "${item.type}|${item.para}|${item.tag}"
            if (!seen.add(key)) continue
            when (type) {
                AudioTagCodec.TYPE_AMB -> amb += item
                AudioTagCodec.TYPE_BGM -> bgm += item
                else -> sfx += item
            }
        }
        if (amb.isEmpty() && bgm.isEmpty() && sfx.isEmpty()) {
            return ValidateOutcome(null, "全部条目校验失败：" + errors.take(3).joinToString("；") + hintOf(enumHint))
        }
        if (bad >= 3 && bad * 3 >= items.length()) {
            return ValidateOutcome(null, "错误条目过多（$bad/${items.length()}）：" + errors.take(3).joinToString("；") + hintOf(enumHint))
        }
        val plan = AudioPlan(
            source = "ai",
            ambience = amb.sortedBy { it.para }.take(MAX_AMB),
            bgm = bgm.sortedBy { it.para }.take(MAX_BGM),
            sfx = sfx.sortedBy { it.para }.take(MAX_SFX),
        )
        if (plan.itemCount > MAX_TOTAL) {
            // 极端返回的保护（正常路径不会触发）：按轨截断已足够，这里只兜底总量
            return ValidateOutcome(plan.copy(sfx = plan.sfx.take((MAX_TOTAL - plan.ambience.size - plan.bgm.size).coerceAtLeast(0))))
        }
        return ValidateOutcome(plan)
    }

    private fun hintOf(enumHint: Boolean): String = if (!enumHint) "" else
        "。BGM 值集：画像 ${PROFILES.joinToString("/")}；情绪 ${MOODS.joinToString("/")}；强度 ${INTENSITIES.joinToString("/")}"

    internal fun normType(raw: String): String? {
        return when (raw.trim().lowercase()) {
            "ambience", "amb", "环境", "环境声", "环境音" -> AudioTagCodec.TYPE_AMB
            "sfx", "音效", "effect", "sound" -> AudioTagCodec.TYPE_SFX
            "bgm", "背景音乐", "背景音", "音乐", "music" -> AudioTagCodec.TYPE_BGM
            else -> null
        }
    }

    internal fun normEnum(raw: String, values: List<String>, alias: Map<String, String>): String? {
        val v = raw.trim()
        if (v.isEmpty()) return null
        values.firstOrNull { it == v }?.let { return it }
        alias[v]?.let { return it }
        alias[v.lowercase()]?.let { return it }
        return null
    }

    private fun cleanTag(raw: String): String =
        raw.trim().replace(WEIRD_CHARS, "").trim().take(16)

    private fun cleanText(raw: String, max: Int): String =
        raw.trim().replace(WEIRD_CHARS, "").trim().take(max)
}
