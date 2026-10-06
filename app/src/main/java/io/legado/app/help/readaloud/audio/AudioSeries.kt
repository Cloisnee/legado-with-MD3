package io.legado.app.help.readaloud.audio

import android.content.Context

/**
 * P1.6.2+（第四刀）· 同系列工具：系列键、词集、跨系列冲突、同系列词同步。
 *
 * 「同系列」= 同一栏目内、主名（去掉尾部全部 `_N` 后）相同的条目集合（如 推门 / 推门_2）；
 * BGM 不参与词管理（四维匹配）；ADULT 按独立栏处理（仅与同栏比）。
 */
object AudioSeries {

    private val tailVariant = Regex("(_\\d+)+$")

    /** 系列键：去掉尾部全部 _N 变体号（推门_2_2 → 推门） */
    fun seriesKey(name: String): String {
        val t = name.trim()
        return t.replace(tailVariant, "").ifBlank { t }
    }

    /** 条目词集（非正则的词模式；正则/空 = 空） */
    fun wordsOf(a: AudioLibrary.AudioAsset): List<String> =
        if (!a.isRegex && a.pattern.isNotBlank()) splitWordList(a.pattern) else emptyList()

    /** 词集 → pattern 文本（加词模式；用 "|" 连接） */
    fun joinWords(words: List<String>): String = words.joinToString("|")

    /** 同栏同系列成员（不含本条目） */
    fun members(context: Context, self: AudioLibrary.AudioAsset): List<AudioLibrary.AudioAsset> {
        val lane = AudioLibrary.laneOf(self)
        val key = seriesKey(self.name)
        return AudioLibrary.snapshot().filter {
            it.id != self.id && AudioLibrary.laneOf(it) == lane && seriesKey(it.name) == key
        }
    }

    /**
     * 跨系列冲突（硬拦截用）：
     * added 词中，与「同栏、非同系列」条目的词集重复者 → (词, 占用条目名)。
     * 本系列成员之间的重复不算冲突；BGM 栏不参与。
     */
    fun crossSeriesConflicts(
        self: AudioLibrary.AudioAsset,
        added: List<String>,
    ): List<Pair<String, String>> {
        val lane = AudioLibrary.laneOf(self)
        if (lane == "BGM") return emptyList()
        if (added.isEmpty()) return emptyList()
        val key = seriesKey(self.name)
        val addSet = added.map { it.trim() }.filter { it.isNotBlank() }.toSet()
        if (addSet.isEmpty()) return emptyList()
        val others = AudioLibrary.snapshot().filter {
            it.id != self.id && AudioLibrary.laneOf(it) == lane && seriesKey(it.name) != key
        }
        if (others.isEmpty()) return emptyList()
        val out = ArrayList<Pair<String, String>>()
        for (o in others) {
            for (w in wordsOf(o)) {
                if (w in addSet) out.add(w to o.name)
            }
        }
        return out.distinctBy { it.first }
    }

    /** 同步加词给同系列成员：返回实际同步条目数（跳过正则条目/已含全部词者） */
    suspend fun syncWordsToSeries(
        context: Context,
        self: AudioLibrary.AudioAsset,
        add: List<String>,
    ): Int {
        val addClean = add.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (addClean.isEmpty()) return 0
        var n = 0
        for (m in members(context, self)) {
            if (m.isRegex) continue // 正则条目不动
            val before = wordsOf(m)
            val merged = (before + addClean).distinct()
            if (merged.size == before.size) continue // 无新增
            runCatching {
                AudioLibrary.updateAsset(context, m.copy(pattern = joinWords(merged)))
            }.onSuccess { if (it) n++ }
        }
        return n
    }
}
