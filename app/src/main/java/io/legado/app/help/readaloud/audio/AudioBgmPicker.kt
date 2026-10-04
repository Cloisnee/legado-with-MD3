package io.legado.app.help.readaloud.audio

import android.content.Context

/**
 * B33.4b · BGM 选曲器（结构化三字段：画像/情绪/强度）。
 *
 * - 输入 = Ai 导演计划里的 BGM 条目（[AudioPlanItem] → [Query]）；
 * - 本地：对已入库 BGM 条目按「画像×2 + 情绪×3 + 强度×2」打分选曲；
 * - 远程：对 bgm 包按同样打分选条目 → 下载落库（预合成链路）；
 * - 轮换：最近选过的 soundId/条目进入小窗，防重复轰炸（同层打散）。
 */
object AudioBgmPicker {

    data class Query(
        val profile: String = "",
        val mood: String = "",
        val intensity: String = "",
        val tag: String = "",
    ) {
        val displayName: String
            get() = listOf(profile, mood, intensity).filter { it.isNotBlank() }
                .joinToString("·").ifBlank { tag }
    }

    fun queryOf(item: AudioPlanItem): Query =
        Query(item.profile, item.mood, item.intensity, item.tag)

    // ------------------------------------------------------------ 词表映射（值集 → 素材命名 token，中英/变体归一）

    private val PROFILE_TOKENS: Map<String, List<String>> = mapOf(
        "通用" to listOf("common", "通用"),
        "幻想" to listOf("fantasy", "幻想", "奇幻"),
        "历史" to listOf("history", "ancient", "历史", "古风", "民国", "古装"),
        "恐怖" to listOf("horror", "恐怖", "dark"),
        "爱情" to listOf("romance", "romantic", "爱情"),
        "科幻" to listOf("scifi", "sci-fi", "科幻", "未来"),
        "悬疑" to listOf("suspense", "悬疑", "mystery"),
        "现代" to listOf("urban", "现代", "都市"),
        "武侠" to listOf("wuxia", "武侠"),
        "仙侠" to listOf("xianxia", "仙侠"),
    )

    private val MOOD_TOKENS: Map<String, List<String>> = mapOf(
        "平静" to listOf("calm", "quiet", "平静"),
        "舒缓" to listOf("gentle", "舒缓", "轻缓"),
        "温馨" to listOf("warm", "温馨", "温暖"),
        "悲情" to listOf("sad", "sadness", "melancholic", "悲情", "悲伤", "伤感"),
        "凄凉" to listOf("lonely", "desolate", "凄凉", "孤寂"),
        "紧张" to listOf("tense", "tension", "紧张", "紧绷"),
        "压迫感" to listOf("oppressive", "压迫感", "压抑", "dark"),
        "悬疑" to listOf("mystery", "mysterious", "suspense", "悬疑"),
        "热血" to listOf("battle", "heroic", "热血", "战歌", "激昂"),
        "史诗" to listOf("epic", "史诗", "宏大", "恢弘"),
        "幽默" to listOf("comic", "comedy", "funny", "幽默", "搞笑"),
        "轻快" to listOf("light", "upbeat", "轻快", "欢快", "清新"),
    )

    private val INTENSITY_TOKENS: Map<String, List<String>> = mapOf(
        "低" to listOf("low", "低", "轻"),
        "中" to listOf("mid", "medium", "中等", "中"),
        "高" to listOf("high", "strong", "强烈", "高"),
    )

    /** 命中明细（供排序与单测） */
    data class Score(val profile: Int, val mood: Int, val intensity: Int, val tag: Int, val loop: Int) {
        val total: Int get() = profile * 2 + mood * 3 + intensity * 2 + tag * 2 + loop

        /** 有效命中：至少情绪或画像命中其一，避免纯强度/loop 噪声全选 */
        val usable: Boolean get() = mood > 0 || profile > 0
    }

    internal fun scoreOf(text: String, q: Query): Score {
        if (text.isBlank()) return Score(0, 0, 0, 0, 0)
        val t = text.lowercase()
        fun count(tokens: List<String>?): Int =
            tokens?.count { tok -> tok.isNotBlank() && t.contains(tok.lowercase()) } ?: 0
        return Score(
            profile = count(PROFILE_TOKENS[q.profile]),
            mood = count(MOOD_TOKENS[q.mood]),
            intensity = count(INTENSITY_TOKENS[q.intensity]),
            tag = if (q.tag.isNotBlank() && t.contains(q.tag.lowercase())) 1 else 0,
            loop = if (t.contains("loop") || t.contains("循环")) 1 else 0,
        )
    }

    private fun sortScore(): Comparator<Score> =
        compareByDescending<Score> { it.total }
            .thenByDescending { it.intensity }
            .thenByDescending { it.loop }

    internal fun searchText(asset: AudioLibrary.AudioAsset): String =
        buildString {
            append(asset.name)
            asset.aliases.forEach { append(' ').append(it) }
        }

    internal fun searchText(sound: AudioRemoteCatalog.RemoteSound): String =
        buildString {
            append(sound.name)
            sound.aliases.forEach { append(' ').append(it) }
            sound.tags.forEach { append(' ').append(it) }
            if (sound.subType.isNotBlank()) append(' ').append(sound.subType)
            if (sound.categoryName.isNotBlank()) append(' ').append(sound.categoryName)
        }

    // ------------------------------------------------------------ 轮换（进程内小窗）

    private const val RECENT_CAP = 30
    private val recent = ArrayDeque<String>()

    private fun recentSet(): Set<String> = synchronized(recent) { recent.toSet() }

    private fun remember(id: String) {
        if (id.isBlank()) return
        synchronized(recent) {
            recent.addLast(id)
            while (recent.size > RECENT_CAP) recent.removeFirst()
        }
    }

    // ------------------------------------------------------------ 选曲

    /** 本地选曲：已入库 BGM 类条目打分；无有效命中返回 null（调用方走远程/合成） */
    fun pickLocal(context: Context, q: Query): AudioLibrary.ResolvedAsset? {
        val snap = AudioLibrary.snapshot()
            .filter { it.category == "BGM" && it.enabled && it.zipRel.isBlank() }
        if (snap.isEmpty()) return null
        val scored = snap.map { it to scoreOf(searchText(it), q) }
            .filter { it.second.usable }
        if (scored.isEmpty()) return null
        val recentSet = recentSet()
        val prefer = scored.filter { it.first.id !in recentSet }
        val pool = (if (prefer.isNotEmpty()) prefer else scored).shuffled()
        val best = pool.sortedWith(
            compareByDescending<Pair<AudioLibrary.AudioAsset, Score>> { it.second.total }
                .thenByDescending { it.second.intensity },
        ).first().first
        val file = AudioLibrary.fileOf(context, best)
        if (!file.isFile) return null
        remember(best.id)
        return AudioLibrary.ResolvedAsset(best, file)
    }

    /** 远程选曲（在给定 bgm 池内打分；供单测） */
    internal fun pickRemote(
        list: List<AudioRemoteCatalog.RemoteSound>,
        q: Query,
    ): AudioRemoteCatalog.RemoteSound? {
        val scored = list.map { it to scoreOf(searchText(it), q) }
            .filter { it.second.usable }
        if (scored.isEmpty()) return null
        val recentSet = recentSet()
        val prefer = scored.filter { it.first.soundId !in recentSet }
        val pool = (if (prefer.isNotEmpty()) prefer else scored).shuffled()
        val best = pool.sortedWith(
            compareByDescending<Pair<AudioRemoteCatalog.RemoteSound, Score>> { it.second.total }
                .thenByDescending { it.second.intensity },
        ).first().first
        remember(best.soundId)
        return best
    }
}
