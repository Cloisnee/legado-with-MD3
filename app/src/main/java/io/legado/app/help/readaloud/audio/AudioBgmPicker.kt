package io.legado.app.help.readaloud.audio

import android.content.Context
import io.legado.app.help.readaloud.analysis.AudioDirectorContract

/**
 * P1.6.1 · BGM 选曲器 v3（四段关键词 · **分层降级**匹配；批214 定稿）。
 *
 * - 关键词 = {题材, 场景, 情绪, 速度}（值集见 [AudioDirectorContract]）——对应素材库 BGM 文件名
 *   「题材-场景-情绪-速度-循环-描述」的前四段；
 * - 匹配 = 对曲目「文件名 + name + aliases」逐维子串命中，**分层降级**（实测 95% 命中）：
 *     L1 四维全中（含宽松维：某维无标注则不拦）
 *     L2 放宽速度（速度维不拦）
 *     L3 放宽速度+情绪（只锁 题材+场景）
 *   取命中的最高层（最严）；组内随机（优先排除最近使用曲）；
 * - 选取 = 同池内「层级最高优先」；组内随机（优先排除最近使用曲，防连续轰炸）；
 * - **已砍掉**旧「字符串包含兜底」（曾以"现代"二字乱命中）——未命中转词网/合成；
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

    /**
     * 单曲匹配结果（批214 分层）：
     * - [level]：命中层级（1=四维全中，2=放宽速度，3=放宽速度+情绪；null=未命中）
     * - [strict]：无任何宽松维（每维都有标注且精确命中；L1 内的最高质量）
     * - [softDims]：宽松维数量（L1 内排序用）
     */
    data class MatchInfo(val level: Int?, val strict: Boolean, val softDims: Int) {
        val matched: Boolean get() = level != null
    }

    private val DIM_VOCABS: List<List<String>>
        get() = listOf(
            AudioDirectorContract.THEMES,
            AudioDirectorContract.SCENES,
            AudioDirectorContract.MOODS,
            AudioDirectorContract.SPEEDS,
        )

    /**
     * 批214 · 分层降级匹配：返回命中的最高层级（1 最严 → 3 最松），null=未命中。
     * 每维规则：曲目该维无任何标注（全名/别名找不到该维词汇）→ 宽松不拦；有标注 → 必须命中。
     */
    internal fun matchOf(haystack: String, q: Query): MatchInfo {
        if (q.isBlank || haystack.isBlank()) return MatchInfo(null, false, Int.MAX_VALUE)
        val values = listOf(q.theme, q.scene, q.mood, q.speed)
        // 四段须全给
        if (values.any { it.isBlank() }) return MatchInfo(null, false, Int.MAX_VALUE)
        // 逐维判定（0..3 = 题材/场景/情绪/速度）：true=过（含宽松）
        val dimPass = BooleanArray(4)
        var soft = 0
        DIM_VOCABS.forEachIndexed { i, vocab ->
            val value = values[i]
            // 批214：查询值="通用" → 该维宽松（通用=万能适配，不锁题材/场景）
            if (value == "通用") {
                soft++
                dimPass[i] = true
                return@forEachIndexed
            }
            // 批214：素材侧"通用"= 宽松维（通用素材万能适配；排序时 softDims 让它让位于精确素材）
            val annotated = vocab.any { it != "通用" && it in haystack }
            if (!annotated) {
                soft++          // 该维无标注（或仅"通用"）→ 宽松
                dimPass[i] = true
            } else {
                dimPass[i] = value in haystack
            }
        }
        // L1 四维全中
        if (dimPass.all { it }) return MatchInfo(1, soft == 0, soft)
        // L2 放宽速度（题材/场景/情绪 全过）
        if (dimPass[0] && dimPass[1] && dimPass[2]) return MatchInfo(2, false, soft)
        // L3 放宽速度+情绪（只锁 题材+场景）
        if (dimPass[0] && dimPass[1]) return MatchInfo(3, false, soft)
        return MatchInfo(null, false, Int.MAX_VALUE)
    }

    /** 从任意名字/文本解析四维词（委托 [AudioDirectorContract.dimsOf]；顺序 题材/场景/情绪/速度） */
    fun dimsOf(text: String): List<String> = AudioDirectorContract.dimsOf(text)

    /**
     * 批214 · 从"四维显示名"（如 现代·室内·紧张·慢速）还原为 [Query]。
     * 解析出完整四维（顺序 题材/场景/情绪/速度）时返回 Query；否则 null（说明它不是四维名）。
     * 用于播放链与缺失链：把 fallback 的四维名重新变成可分层匹配的查询。
     */
    fun parseQuery(text: String): Query? {
        val dims = dimsOf(text)
        if (dims.size < 4) return null
        return Query(theme = dims[0], scene = dims[1], mood = dims[2], speed = dims[3])
    }

    // ---------------- 曲目池 ----------------

    private fun haystackOf(a: AudioLibrary.AudioAsset): String =
        buildString {
            append(a.relPath.substringAfterLast('/').substringBeforeLast('.'))
            append(' ').append(a.name)
            a.aliases.forEach { append(' ').append(it) }
            // 批214b：匹配规则（词林/四维写入）参与匹配——BGM 下载条目靠它被四维命中
            if (!a.isRegex && a.pattern.isNotBlank()) {
                splitWordList(a.pattern).forEach { append(' ').append(it) }
            }
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

    /**
     * 远程词网 BGM 池选曲（命中后由调用方 [AudioNetStore.fetchAsset] 下载落库）。
     *
     * 批214b · **优中选优 + 首选定盘**（甲方定稿）：
     * - 先取"层级最高（L1>L2>L3）"的候选组；组内"宽松维最少"优先；
     * - 该组**仅 1 条 → 直接选它**；多条 → **随机选一条**（一次性）；
     * - 选定结果**按查询记忆**（同查询后续直接返回同一条）——保证最多下载 1 条、播放稳定。
     */
    fun pickNet(q: Query): AudioNetStore.NetAsset? {
        if (q.isBlank) return null
        val key = "net|${q.theme}|${q.scene}|${q.mood}|${q.speed}"
        // 首选定盘：同查询已有选定 → 直接复用（对象仍有效时）
        pickMemo[key]?.let { id -> AudioNetStore.snapshot().firstOrNull { it.id == id }?.let { return it } }
        val candidates = ArrayList<Pair<AudioNetStore.NetAsset, MatchInfo>>(8)
        AudioNetStore.snapshot().forEach { a ->
            if (a.lane != "bgm") return@forEach
            val info = matchOf(haystackOf(a), q)
            if (info.matched) candidates += a to info
        }
        val pick = choose(candidates) { it.id } ?: return null
        pickMemo[key] = pick.id
        return pick
    }

    /** 首选定盘（批214b）：查询键 → 选定条目 id（防重复触发时重选/重复下载） */
    private val pickMemo = HashMap<String, String>()

    /** 同池选取（批214）：命中层级最高优先（L1>L2>L3）；同级内宽松维最少优先；组内随机（优先排除最近使用） */
    private fun <T> choose(candidates: List<Pair<T, MatchInfo>>, idOf: (T) -> String): T? {
        if (candidates.isEmpty()) return null
        val bestLevel = candidates.minOf { it.second.level ?: Int.MAX_VALUE }
        val atLevel = candidates.filter { it.second.level == bestLevel }
        val minSoft = atLevel.minOf { it.second.softDims }
        val pool = atLevel.filter { it.second.softDims == minSoft }
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
