package io.legado.app.help.readaloud.audio

import android.content.Context
import io.legado.app.help.readaloud.analysis.AudioDirectorContract

/**
 * P1.6.1 · BGM 选曲器 v2（四段关键词 · 全命中匹配；替代旧「画像/情绪/强度打分+降级」链）。
 *
 * - 关键词 = {题材, 场景, 情绪, 速度}（值集见 [AudioDirectorContract]）——对应素材库 BGM 文件名
 *   「题材-场景-情绪-速度-循环-描述」的前四段；
 * - 匹配 = 对曲目「文件名 + name + aliases」逐维子串命中，**四维全部命中**才入选；
 *   曲目在某维无任何标注（全名/别名都找不到该维词汇）→ 该维视为「宽松」不拦截；
 * - 选取 = 同池内「严格命中（零宽松维）」优先；无严格则取「宽松维最少」组；组内随机
 *   （优先排除最近使用曲，防连续轰炸）；
 * - 调用顺序（引擎/预合成）：[pickLocal] → [pickNet] → 缺失上报（平台合成）。
 */
object AudioBgmPicker {

    data class Query(
        val theme: String = "",
        val scene: String = "",
        val mood: String = "",
        val speed: String = "",
        val tag: String = "",
    ) {
        val isBlank: Boolean
            get() = theme.isBlank() && scene.isBlank() && mood.isBlank() && speed.isBlank()

        val displayName: String
            get() = listOf(theme, scene, mood, speed).filter { it.isNotBlank() }
                .joinToString("·").ifBlank { tag }
    }

    fun queryOf(item: AudioPlanItem): Query =
        Query(item.theme, item.scene, item.mood, item.speed, item.tag)

    // ---------------- 匹配（纯函数，可单测） ----------------

    /** 单曲匹配结果：matched=四维全过；strict=无任何宽松维（即每维都有标注且精确命中） */
    data class MatchInfo(val matched: Boolean, val strict: Boolean, val softDims: Int)

    private val DIM_VOCABS: List<List<String>>
        get() = listOf(
            AudioDirectorContract.THEMES,
            AudioDirectorContract.SCENES,
            AudioDirectorContract.MOODS,
            AudioDirectorContract.SPEEDS,
        )

    internal fun matchOf(haystack: String, q: Query): MatchInfo {
        if (q.isBlank || haystack.isBlank()) return MatchInfo(false, false, Int.MAX_VALUE)
        val values = listOf(q.theme, q.scene, q.mood, q.speed)
        var soft = 0
        DIM_VOCABS.forEachIndexed { i, vocab ->
            val value = values[i]
            if (value.isBlank()) return MatchInfo(false, false, Int.MAX_VALUE) // 四段须全给
            val annotated = vocab.any { it in haystack }
            if (!annotated) {
                soft++ // 该维无标注 → 宽松
            } else if (value !in haystack) {
                return MatchInfo(false, false, Int.MAX_VALUE)
            }
        }
        return MatchInfo(true, soft == 0, soft)
    }

    /** 从任意名字/文本解析四维词（委托 [AudioDirectorContract.dimsOf]；顺序 题材/场景/情绪/速度） */
    fun dimsOf(text: String): List<String> = AudioDirectorContract.dimsOf(text)

    // ---------------- 曲目池 ----------------

    private fun haystackOf(a: AudioLibrary.AudioAsset): String =
        buildString {
            append(a.relPath.substringAfterLast('/').substringBeforeLast('.'))
            append(' ').append(a.name)
            a.aliases.forEach { append(' ').append(it) }
        }

    private fun haystackOf(a: AudioNetStore.NetAsset): String =
        buildString {
            append(a.file.substringAfterLast('/').substringBeforeLast('.'))
            append(' ').append(a.name)
            a.aliases.forEach { append(' ').append(it) }
        }

    /** 本地库选曲：严格优先、其次宽松；文件缺失的条目跳过。 */
    fun pickLocal(context: Context, q: Query): AudioLibrary.ResolvedAsset? {
        if (q.isBlank) return null
        val candidates = ArrayList<Pair<AudioLibrary.ResolvedAsset, MatchInfo>>(8)
        AudioLibrary.snapshot().forEach { a ->
            if (a.category != "BGM" || !a.enabled || a.zipRel.isNotBlank()) return@forEach
            val info = matchOf(haystackOf(a), q)
            if (!info.matched) return@forEach
            val f = AudioLibrary.fileOf(context, a)
            if (!f.isFile) return@forEach
            candidates += AudioLibrary.ResolvedAsset(a, f) to info
        }
        val pick = choose(candidates) { it.asset.id } ?: return null
        remember(pick.asset.id)
        return pick
    }

    /** 远程词网 BGM 池选曲（命中后由调用方 [AudioNetStore.fetchAsset] 下载落库）。 */
    fun pickNet(q: Query): AudioNetStore.NetAsset? {
        if (q.isBlank) return null
        val candidates = ArrayList<Pair<AudioNetStore.NetAsset, MatchInfo>>(8)
        AudioNetStore.snapshot().forEach { a ->
            if (a.lane != "bgm") return@forEach
            val info = matchOf(haystackOf(a), q)
            if (info.matched) candidates += a to info
        }
        val pick = choose(candidates) { it.id } ?: return null
        remember(pick.id)
        return pick
    }

    /** 同池选取：宽松维最少组优先；组内随机（优先排除最近使用） */
    private fun <T> choose(candidates: List<Pair<T, MatchInfo>>, idOf: (T) -> String): T? {
        if (candidates.isEmpty()) return null
        val minSoft = candidates.minOf { it.second.softDims }
        val pool = candidates.filter { it.second.softDims == minSoft }
        val recentSet = recentSet()
        val prefer = pool.filter { idOf(it.first) !in recentSet }
        val finals = if (prefer.isNotEmpty()) prefer else pool
        return finals.random().first
    }

    // ---------------- 轮换（进程内小窗） ----------------

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
}
