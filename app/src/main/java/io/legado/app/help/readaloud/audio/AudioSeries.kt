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

    /** 变体号：尾部 _N（无后缀=1；取最后一段数字，如 推门_2_2 → 2） */
    fun variantOf(name: String): Int {
        val t = name.trim()
        val i = t.lastIndexOf('_')
        if (i <= 0 || i >= t.length - 1) return 1
        return t.substring(i + 1).toIntOrNull() ?: 1
    }

    /** 云端 id 尾号（SFX_打雷_02 → 2；无尾号 → 1） */
    private fun variantFromId(id: String): Int =
        id.substringAfterLast('_').toIntOrNull() ?: 1

    /** 同族·云端成员（含未下载；同栏口径=显示名；excludeCloudId=本地条目的 soundId） */
    fun cloudFamilyMembers(lane: String, key: String, excludeCloudId: String): List<AudioNetStore.NetAsset> =
        runCatching {
            AudioNetStore.snapshot().filter {
                it.id != excludeCloudId &&
                    AudioRemoteCatalog.laneNameOf(it.lane) == lane &&
                    seriesKey(it.name) == key
            }
        }.getOrDefault(emptyList())

    /** 同族·本地成员（不含本条） */
    fun localFamilyMembers(lane: String, key: String, selfId: String): List<AudioLibrary.AudioAsset> =
        AudioLibrary.snapshot().filter {
            it.id != selfId && AudioLibrary.laneOf(it) == lane && seriesKey(it.name) == key
        }

    /** 族最大号（云端含未下载 + 本地 取最大） */
    fun maxVariant(lane: String, key: String, selfId: String, selfCloudId: String): Int {
        var max = 0
        cloudFamilyMembers(lane, key, selfCloudId).forEach {
            max = maxOf(max, variantFromId(it.id), variantOf(it.name))
        }
        localFamilyMembers(lane, key, selfId).forEach {
            max = maxOf(max, variantOf(it.name))
        }
        return max
    }

    /** 族词集（云端成员关联词 ∪ 本地成员词；「抄同族别名」用） */
    fun familyWords(lane: String, key: String, selfId: String, selfCloudId: String): List<String> {
        val out = LinkedHashSet<String>()
        val cloudIds = cloudFamilyMembers(lane, key, selfCloudId).map { it.id }.toSet()
        out += AudioNetStore.wordsForIds(cloudIds)
        localFamilyMembers(lane, key, selfId).forEach { a ->
            out += wordsOf(a)
        }
        return out.toList()
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

    /**
     * B2 · 全族词集同步（增 + 减）：把本条目词集的增减应用到同系列其它成员
     * （正则条目不动；返回实际变更条目数）。
     */
    suspend fun syncWordsDelta(
        context: Context,
        self: AudioLibrary.AudioAsset,
        add: List<String>,
        remove: List<String>,
    ): Int {
        val addClean = add.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val remSet = remove.map { it.trim() }.filter { it.isNotBlank() }.toSet()
        if (addClean.isEmpty() && remSet.isEmpty()) return 0
        var n = 0
        for (m in members(context, self)) {
            if (m.isRegex) continue // 正则条目不动
            val before = wordsOf(m)
            var after = (before + addClean).distinct()
            if (remSet.isNotEmpty()) after = after.filter { it !in remSet }
            if (after == before) continue
            runCatching {
                AudioLibrary.updateAsset(context, m.copy(pattern = joinWords(after)))
            }.onSuccess { if (it) n++ }
        }
        return n
    }
}
