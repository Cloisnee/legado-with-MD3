package io.legado.app.help.readaloud.analysis

import io.legado.app.help.readaloud.audio.AudioPositions
import io.legado.app.help.readaloud.audio.AudioTagCodec
import org.json.JSONObject

/**
 * B34.2b · 音频导演返回契约（本地校验，纯逻辑）。
 *
 * 输入=导演返回的 JSON（`{"items":[…]}`），锚点为 **段号(para)+片段号(frag)**
 * （与导演输入文本的 〖第N段〗/[m] 模板一致；片段=第1阶段同一套本地切块器产物）。
 *
 * 口径（2026-10-02 定稿 + 2026-10-03 片段化修订 + 2026-10-06 四段化）：
 *  - 不注入库存；靠「简短、具体、常见」的命名 + **BGM 四段关键词**命中素材库；
 *  - 音效＝拟音式全覆盖（有动静就标）；BGM＝结构化 {题材/场景/情绪/速度} + hold 行数；
 *    四段词=素材库 BGM 文件名「题材-场景-情绪-速度-循环-描述」的前四段，按「全部命中」匹配；
 *  - 音效锚点=段号+片段号，另带 **pos（片段内 前/中/后）**：三等分中点映射（前≈17%/中≈50%/后≈83%），
 *    非法/缺省=片段开头；本地规则仍用精确片内命中位；
 *  - 容错策略：单条非法→剔除并记录；错误过多（≥3 条且占比≥1/3）或全空 → 判失败重试。
 */
object AudioDirectorContract {

    /** 题材值集（= 素材库 BGM 命名首段全集） */
    val THEMES = listOf(
        "通用", "历史", "都市", "恐怖", "科幻", "悬疑", "古风", "现代", "浪漫", "幻想",
        "武侠", "仙侠", "民国", "未来", "奇幻",
    )

    /** 场景值集（= 素材库 BGM 命名第二段全集 + 常用场景） */
    val SCENES = listOf(
        "战斗", "日常", "追逐", "调查", "过场", "转场", "古代", "梦境", "城市", "太空",
        "夜晚", "雨夜", "森林", "市集", "客栈", "山门", "古寺", "战场", "宫殿", "室内",
        "回忆", "弄堂", "邮局", "酒店", "酒吧", "咖啡馆", "派对", "婚礼", "约会", "地铁",
        "街头", "街道", "地下城", "夜城", "基地", "公寓", "客厅", "书房", "阳台", "码头",
        "山路", "旅途", "探险", "寻宝", "废墟", "垃圾场", "宫斗", "权谋", "对峙", "会战",
        "表白", "离别", "重逢", "刑侦", "探案", "推理", "案情", "赛车", "竞速", "飞行",
        "赛博朋克", "月夜", "竹林", "庭院", "山水", "丝绸之路", "钟楼", "教堂", "医院", "学校",
        "教室", "酒馆", "茶馆", "当铺", "渔村", "山村", "小镇", "霓虹", "人群", "信号",
        "照片", "电话", "磁带", "暗影", "鬼魅", "诅咒", "禁地", "心魔", "门扉", "床底",
        "天台", "安全屋", "荒野", "葬礼", "尸体", "壁画", "冲击", "利刃", "地下", "审判",
        "恐怖", "悬疑", "神秘", "紧张", "悲伤", "温暖",
    )

    /** 情绪值集（= 素材库 BGM 命名第三段全集 + 常用情绪） */
    val MOODS = listOf(
        "平静", "舒缓", "温馨", "悲情", "凄凉", "紧张", "压迫感", "热血", "史诗", "幽默",
        "轻快", "悲伤", "英勇", "压抑", "温暖", "空灵", "诡异", "神秘", "黑暗", "罗曼蒂克",
        "危险", "绝望", "喜剧", "俏皮", "深沉", "肃穆", "激烈", "不祥", "孤独", "悲痛",
        "沉重", "静谧", "震撼", "恢弘", "抒情", "清新", "温柔", "文雅", "伤感", "惆怅",
        "轻松", "轻缓", "孤寂", "安静", "诙谐", "搞笑", "怀旧", "感人", "空旷", "惊悚",
        "庄严", "和缓", "浪漫",
    )

    /** 速度值集（= 素材库 BGM 命名第四段） */
    val SPEEDS = listOf("慢速", "中速", "快速")

    /** 导演条目（段号/片段号定位；后续由管线换算为剧本行锚点+句内比例） */
    data class DirectorItem(
        val para: Int,
        val frag: Int,
        val type: String,
        val tag: String = "",
        val desc: String = "",
        val delayMs: Long = 0L,
        /** 音效片内比例（由 pos 前/中/后 三等分中点映射；非法/缺省=0=片段开头） */
        val posRatio: Float = 0f,
        val hold: Int = 0,
        val theme: String = "",
        val scene: String = "",
        val mood: String = "",
        val speed: String = "",
        val anchor: String = "",
    )

    private val THEME_ALIAS: Map<String, String> = mapOf(
        "common" to "通用", "universal" to "通用", "普通" to "通用",
        "fantasy" to "幻想", "magical" to "奇幻",
        "history" to "历史", "ancient" to "历史", "古装" to "历史",
        "horror" to "恐怖",
        "romance" to "浪漫", "romantic" to "浪漫", "爱情" to "浪漫",
        "scifi" to "科幻", "sci-fi" to "科幻", "sciencefiction" to "科幻",
        "mystery" to "悬疑", "suspense" to "悬疑", "mysterious" to "悬疑",
        "urban" to "都市", "modern" to "现代",
        "wuxia" to "武侠", "xianxia" to "仙侠",
        "republic" to "民国", "future" to "未来",
    )

    private val SCENE_ALIAS: Map<String, String> = mapOf(
        "battle" to "战斗", "combat" to "战斗", "fight" to "战斗", "打斗" to "战斗", "厮杀" to "战斗",
        "daily" to "日常", "dailylife" to "日常",
        "chase" to "追逐",
        "investigation" to "调查", "investigate" to "调查",
        "transition" to "过场",
        "dream" to "梦境",
        "city" to "城市",
        "space" to "太空",
        "night" to "夜晚",
        "forest" to "森林",
        "market" to "市集",
        "inn" to "客栈", "tavern" to "客栈",
        "temple" to "古寺",
        "war" to "战场", "battlefield" to "战场",
        "palace" to "宫殿",
        "indoor" to "室内",
        "memory" to "回忆",
        "subway" to "地铁", "metro" to "地铁",
        "street" to "街道", "cafe" to "咖啡馆", "café" to "咖啡馆", "bar" to "酒吧",
        "party" to "派对", "wedding" to "婚礼", "funeral" to "葬礼", "desert" to "荒野",
        "school" to "学校", "hospital" to "医院", "village" to "山村", "town" to "小镇",
        "侦探" to "探案", "悬案" to "案情",
    )

    private val MOOD_ALIAS: Map<String, String> = mapOf(
        "calm" to "平静", "quiet" to "平静", "peaceful" to "平静",
        "gentle" to "舒缓", "轻缓" to "轻缓",
        "warm" to "温暖", "cozy" to "温馨",
        "sad" to "悲伤", "sadness" to "悲伤", "melancholic" to "悲情",
        "desolate" to "凄凉", "lonely" to "孤寂",
        "tense" to "紧张", "tension" to "紧张", "紧绷" to "紧张",
        "oppressive" to "压迫感",
        "mysterious" to "神秘", "eerie" to "诡异", "weird" to "诡异",
        "heroic" to "热血", "battle" to "热血", "激昂" to "热血",
        "epic" to "史诗", "宏大" to "恢弘", "grand" to "恢弘", "majestic" to "恢弘",
        "comic" to "喜剧", "comedic" to "喜剧", "funny" to "搞笑", "humorous" to "幽默",
        "light" to "轻快", "upbeat" to "轻快", "欢快" to "轻快",
        "dark" to "黑暗", "dangerous" to "危险", "danger" to "危险",
        "despair" to "绝望", "desperate" to "绝望", "solemn" to "肃穆",
        "ethereal" to "空灵", "ominous" to "不祥", "painful" to "悲痛", "heavy" to "沉重",
        "deep" to "深沉", "excited" to "激烈", "mournful" to "悲情", "romantic" to "浪漫",
    )

    private val SPEED_ALIAS: Map<String, String> = mapOf(
        "low" to "慢速", "mid" to "中速", "medium" to "中速", "high" to "快速", "strong" to "快速",
        "慢" to "慢速", "中" to "中速", "快" to "快速", "低" to "慢速", "高" to "快速",
    )

    private const val MAX_SFX = 80
    private const val MAX_AMB = 12
    private const val MAX_BGM = 8

    private val WEIRD_CHARS = Regex("[\\[\\]\\r\\n]")

    /**
     * 校验导演返回。
     * @param unitCounts 段号（1 基）→ 该段片段数（=导演输入内 [m] 总数）
     */
    fun validate(root: JSONObject, unitCounts: Map<Int, Int>): ValidateOutcome<List<DirectorItem>> {
        val items = root.optJSONArray("items")
            ?: return ValidateOutcome(null, "缺少 items 数组（输出格式：{\"items\":[…] }）")
        if (items.length() == 0) {
            return ValidateOutcome(null, "items 为空：未返回任何条目，请按「有动静就标」通读全文后重新输出")
        }
        val errors = ArrayList<String>()
        var bad = 0
        var enumHint = false
        val amb = ArrayList<DirectorItem>()
        val bgm = ArrayList<DirectorItem>()
        val sfx = ArrayList<DirectorItem>()
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
            val fragCount = unitCounts[para]
            if (fragCount == null || fragCount <= 0) {
                bad++
                if (bad <= 5) errors += "第${i + 1}条 para=$para 不在输入段号内（1..${unitCounts.keys.maxOrNull() ?: 0}）"
                continue
            }
            val fragRaw = o.optInt("frag", 0)
            val frag = when {
                fragRaw <= 0 -> 1
                fragRaw > fragCount -> fragCount
                else -> fragRaw
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
                        DirectorItem(para, frag, type, tag = tag, desc = desc, anchor = anchor)
                    }
                }

                AudioTagCodec.TYPE_SFX -> {
                    if (tag.isBlank()) {
                        bad++
                        if (bad <= 5) errors += "第${i + 1}条音效缺少 tag"
                        null
                    } else {
                        DirectorItem(
                            para = para,
                            frag = frag,
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
                    val theme = normEnum(o.optString("theme"), THEMES, THEME_ALIAS)
                    val scene = normEnum(o.optString("scene"), SCENES, SCENE_ALIAS)
                    val mood = normEnum(o.optString("mood"), MOODS, MOOD_ALIAS)
                    val speed = normEnum(o.optString("speed"), SPEEDS, SPEED_ALIAS)
                    if (theme == null || scene == null || mood == null || speed == null) {
                        bad++
                        enumHint = true
                        if (bad <= 5) errors += "第${i + 1}条 BGM 题材/场景/情绪/速度非法" +
                            "（theme=${o.optString("theme")} scene=${o.optString("scene")} " +
                            "mood=${o.optString("mood")} speed=${o.optString("speed")}）"
                        null
                    } else {
                        DirectorItem(
                            para = para,
                            frag = frag,
                            type = type,
                            tag = tag,
                            desc = desc,
                            hold = o.optInt("hold", 15).coerceIn(4, 60),
                            theme = theme,
                            scene = scene,
                            mood = mood,
                            speed = speed,
                            anchor = anchor,
                        )
                    }
                }
            } ?: continue
            val key = "${item.type}|${item.para}|${item.frag}|${item.tag}"
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
        return ValidateOutcome(
            amb.take(MAX_AMB) + bgm.take(MAX_BGM) + sfx.take(MAX_SFX),
        )
    }

    private fun hintOf(enumHint: Boolean): String = if (!enumHint) "" else
        "。BGM 值集：题材 ${THEMES.joinToString("/")}；场景 ${SCENES.joinToString("/")}；" +
            "情绪 ${MOODS.joinToString("/")}；速度 ${SPEEDS.joinToString("/")}"

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

    /** 从任意名字/文本解析四维词（顺序 题材/场景/情绪/速度；同词不复用；缺省跳过）——建议标记等展示用 */
    fun dimsOf(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val used = HashSet<String>()
        val out = ArrayList<String>(4)
        listOf(THEMES, SCENES, MOODS, SPEEDS).forEach { vocab ->
            val hit = vocab.firstOrNull { it !in used && it in text } ?: return@forEach
            used.add(hit)
            out += hit
        }
        return out
    }

    private fun cleanTag(raw: String): String =
        raw.trim().replace(WEIRD_CHARS, "").trim().take(16)

    private fun cleanText(raw: String, max: Int): String =
        raw.trim().replace(WEIRD_CHARS, "").trim().take(max)
}
