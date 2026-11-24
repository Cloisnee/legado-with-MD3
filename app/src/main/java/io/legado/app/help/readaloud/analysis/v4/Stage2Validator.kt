package io.legado.app.help.readaloud.analysis.v4

import io.legado.app.domain.model.readaloud.CanonicalSpeechParagraph
import io.legado.app.domain.model.readaloud.v4.RaEvidenceScope
import io.legado.app.domain.model.readaloud.v4.RaEvidenceType
import io.legado.app.help.readaloud.analysis.NarratorRoleStrip
import io.legado.app.help.readaloud.analysis.ValidateOutcome
import org.json.JSONArray
import org.json.JSONObject

/** 阶段2 输出：章内说话人（L 键）。 */
data class Stage2Speaker(
    val key: String,
    val name: String,
    val aliases: List<String> = emptyList(),
    val roleType: String = "",
    val gender: String = "",
    val ageBand: String = "",
    val genderSource: String = "inferred",
    val ageSource: String = "inferred",
) {
    val allNames: List<String>
        get() = (listOf(name) + aliases).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
}

/** 阶段2 输出：一条原文证据。 */
data class Stage2Evidence(
    val type: RaEvidenceType,
    val subjectKey: String,
    val objectKey: String?,
    val objectText: String,
    val chapter: Int,
    val paragraphIndex: Int,
    val start: Int,
    val end: Int,
    val quote: String,
    val relation: String = "",
    val state: String = "",
    val scope: RaEvidenceScope = RaEvidenceScope.MAIN_TEXT,
    val verified: Boolean = true,
)

data class Stage2Payload(
    val seqMap: Map<Int, String>,
    val speakers: List<Stage2Speaker>,
    val mentions: List<Stage2Speaker>,
    val evidence: List<Stage2Evidence>,
)

/**
 * 阶段2 契约校验器（03 §2.2）。
 *
 * 与 V3 的区别：
 *  - seqmap 的值是章内说话人键（L1/L2…）或 "N"；
 *  - speakers 支持多别名与 *Source；
 *  - 新增 mentions / evidence，并对 quote 做原文子串校验（失败只剔除该证据）；
 *  - 保留 V3 的校准（roletype/裸属词/性别年龄标准化）与本地归一（同名/别名合并）。
 *
 * 旧格式（characters + seqmap 值写人名）仍被接受：校验器会先转换成新结构，
 * 以便用户自定义的旧 stage2 提示词不会直接失效。
 */
class Stage2Validator(
    private val paragraphs: List<CanonicalSpeechParagraph>,
    private val chapterIndex: Int,
    private val prevText: String,
    private val nextText: String,
    private val isBareClassWord: (String) -> Boolean,
    private val isSpecialClassWord: (String) -> Boolean,
    private val log: (String) -> Unit,
) {

    private val pronouns = setOf("你", "我", "他", "她", "它", "祂")

    private data class Raw(
        val key: String,
        var name: String,
        var aliases: List<String>,
        var roleType: String,
        var gender: String,
        var age: String,
        val genderSource: String,
        val ageSource: String,
        var minSeq: Int = Int.MAX_VALUE,
    ) {
        val allNames: List<String>
            get() = (listOf(name) + aliases).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }

    fun validate(raw: String, expectedCount: Int): ValidateOutcome<Stage2Payload> {
        val json = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val root = runCatching { JSONObject(json) }.getOrNull()
            ?: return ValidateOutcome(null, "返回不是JSON对象")

        val smObj = root.optJSONObject("seqmap") ?: root.optJSONObject("seqMap")
            ?: root.optJSONObject("序号映射") ?: return ValidateOutcome(null, "缺少seqmap")
        val sm = LinkedHashMap<Int, String>()
        val keys = smObj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val n = k.trim().toIntOrNull() ?: continue
            if (n < 1) continue
            sm[n] = smObj.optString(k).trim()
        }
        if (sm.isEmpty() && expectedCount > 0) return ValidateOutcome(null, "seqmap为空")

        val speakersRaw = ArrayList<Raw>()
        val newFormat = root.optJSONArray("speakers") != null
        if (newFormat) {
            val arr = root.optJSONArray("speakers")!!
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val nm = o.optString("name").trim()
                if (nm.isBlank()) return ValidateOutcome(null, "存在name为空的角色")
                speakersRaw.add(
                    Raw(
                        key = o.optString("key").trim().ifBlank { "L${i + 1}" },
                        name = nm,
                        aliases = parseAliases(o.opt("aliases")),
                        roleType = o.optString("roletype").ifBlank { o.optString("type") }.trim(),
                        gender = o.optString("gender").ifBlank { o.optString("性别") }.trim(),
                        age = o.optString("age").ifBlank { o.optString("年龄") }.trim(),
                        genderSource = normalizeSource(o.optString("genderSource")),
                        ageSource = normalizeSource(o.optString("ageSource")),
                    ),
                )
            }
        } else {
            val arr = ArrayList<JSONObject>()
            val rcArr = root.optJSONArray("characters") ?: root.optJSONArray("角色列表")
            if (rcArr != null) {
                for (i in 0 until rcArr.length()) rcArr.optJSONObject(i)?.let { arr.add(it) }
            } else {
                val rcObj = root.optJSONObject("characters") ?: root.optJSONObject("角色列表")
                if (rcObj != null) {
                    val ks = rcObj.keys()
                    while (ks.hasNext()) {
                        val kk = ks.next()
                        val oo = rcObj.optJSONObject(kk) ?: JSONObject()
                        if (oo.optString("name").isBlank()) oo.put("name", kk)
                        arr.add(oo)
                    }
                }
            }
            for (i in 0 until arr.size) {
                val o = arr[i]
                val nm = o.optString("name").ifBlank { o.optString("主名") }.trim()
                if (nm.isBlank()) return ValidateOutcome(null, "存在name为空的角色")
                speakersRaw.add(
                    Raw(
                        key = "L${i + 1}",
                        name = nm,
                        aliases = parseAliases(o.opt("aliases")),
                        roleType = o.optString("roletype").ifBlank { o.optString("type") }.trim(),
                        gender = o.optString("gender").ifBlank { o.optString("性别") }.trim(),
                        age = o.optString("age").ifBlank { o.optString("年龄") }.trim(),
                        genderSource = "inferred",
                        ageSource = "inferred",
                    ),
                )
            }
        }
        if (speakersRaw.isEmpty()) {
            val allNarrator = sm.isNotEmpty() && sm.values.all { NarratorRoleStrip.isNarratorName(it) }
            if (!allNarrator) return ValidateOutcome(null, "speakers为空")
        }

        // 旁白角色剥离：不参与定义/引用校验
        speakersRaw.removeAll { NarratorRoleStrip.isNarratorName(it.name) }
        // 主名为人称代词视为非法
        speakersRaw.firstOrNull { it.name in pronouns }?.let {
            return ValidateOutcome(null, "主名不得使用人称代词「${it.name}」")
        }

        val byKey = speakersRaw.associateBy { it.key }
        val byName = LinkedHashMap<String, Raw>()
        speakersRaw.forEach { c ->
            byName[c.name] = c
            c.aliases.forEach { a -> if (a.isNotBlank()) byName.putIfAbsent(a, c) }
        }
        for (s in 1..expectedCount) {
            if (sm[s].isNullOrBlank()) return ValidateOutcome(null, "seqmap缺少序号${s.toString().padStart(2, '0')}")
        }
        for ((n, v) in sm) {
            if (v.isBlank()) return ValidateOutcome(null, "seqmap序号${n.toString().padStart(2, '0')}为空")
            if (v == "N" || NarratorRoleStrip.isNarratorName(v)) {
                sm[n] = "N"
                continue
            }
            val hit = byKey[v] ?: byName[v]
                ?: return ValidateOutcome(null, "seqmap中人物「$v」未在speakers中定义")
            sm[n] = hit.key
        }
        val referenced = sm.values.filter { it != "N" }.toSet()
        val unreferenced = byKey.values.filter { it.key !in referenced }
        if (unreferenced.isNotEmpty()) {
            return ValidateOutcome(null, "角色「${unreferenced.first().name}」未被任何序号引用")
        }

        val calibrated = speakersRaw.map { calibrate(it) }
        calibrated.forEach { c ->
            c.minSeq = sm.entries.filter { it.value == c.key }.minOfOrNull { it.key } ?: Int.MAX_VALUE
        }
        val keyRemap = HashMap<String, String>()
        val merged = localNormalize(calibrated, sm, keyRemap)
        val mentions = parseMentions(root, merged)
        val evidence = parseEvidence(root, merged, mentions, keyRemap)

        val speakers = merged.map { c ->
            Stage2Speaker(
                key = c.key,
                name = c.name,
                aliases = c.aliases.filter { it != c.name },
                roleType = c.roleType,
                gender = c.gender,
                ageBand = c.age,
                genderSource = c.genderSource,
                ageSource = c.ageSource,
            )
        }
        return ValidateOutcome(Stage2Payload(sm.toMap(), speakers, mentions, evidence))
    }

    private fun normalizeSource(raw: String): String = when (raw.trim().lowercase()) {
        "explicit", "明确" -> "explicit"
        "default", "默认" -> "default"
        else -> "inferred"
    }

    private fun parseAliases(value: Any?): List<String> = when (value) {
        is JSONArray -> buildList {
            for (i in 0 until value.length()) {
                value.optString(i).trim().takeIf { it.isNotEmpty() }?.let(::add)
            }
        }
        is String -> value.split('|', '、', ',').map { it.trim() }.filter { it.isNotEmpty() }
        else -> emptyList()
    }.distinct()

    private fun calibrate(item: Raw): Raw {
        val rt = item.roleType.trim()
        item.roleType = when {
            rt == "核心" || rt == "路人" || rt == "特殊" -> rt
            isBareClassWord(item.name) -> "路人"
            isSpecialClassWord(item.name) -> "特殊"
            else -> "核心"
        }
        if (item.roleType == "路人") {
            item.aliases = emptyList()
        } else {
            item.aliases = item.aliases.filterNot { isBareClassWord(it) }
            if (isBareClassWord(item.name)) {
                val promoted = item.aliases.firstOrNull { !isBareClassWord(it) }
                if (promoted != null) {
                    log("校准：主名「${item.name}」为裸属词，别名「$promoted」上位为主名")
                    item.name = promoted
                    item.aliases = item.aliases.filterNot { it == promoted }
                } else {
                    log("校准：主名「${item.name}」与别名均为裸属词，降级为路人")
                    item.roleType = "路人"
                    item.aliases = emptyList()
                }
            }
        }
        if (item.gender != "男" && item.gender != "女") item.gender = inferGenderFromAge(item.age)
        item.age = if (item.roleType == "特殊") "系统" else standardizeAge(item.age, item.gender)
        return item
    }

    private fun localNormalize(
        list: List<Raw>,
        sm: MutableMap<Int, String>,
        keyRemap: MutableMap<String, String>,
    ): List<Raw> {
        val work = ArrayList(list)
        var changed = true
        var guard = 80
        while (changed && guard-- > 0) {
            changed = false
            var i = 0
            while (i < work.size) {
                var j = i + 1
                while (j < work.size) {
                    val a = work[i]
                    val b = work[j]
                    if (a.roleType == "路人" || b.roleType == "路人") {
                        j++
                        continue
                    }
                    if (!isSamePerson(a, b)) {
                        j++
                        continue
                    }
                    val winner = if (b.minSeq < a.minSeq) b else a
                    val loser = if (winner === a) b else a
                    log("本地归一：${loser.name} 并入 ${winner.name}")
                    val tokens = LinkedHashSet(winner.aliases)
                    loser.allNames.forEach { n -> if (n != winner.name) tokens.add(n) }
                    winner.aliases = tokens.distinct()
                    sm.forEach { (n, v) -> if (v == loser.key) sm[n] = winner.key }
                    keyRemap[loser.key] = winner.key
                    work.remove(loser)
                    changed = true
                }
                i++
            }
        }
        return work
    }

    private fun isSamePerson(a: Raw, b: Raw): Boolean {
        if (a.name == b.name) return true
        if (b.name in a.aliases) return true
        if (a.name in b.aliases) return true
        return a.aliases.any { it in b.aliases }
    }

    private fun parseMentions(root: JSONObject, speakers: List<Raw>): List<Stage2Speaker> {
        val arr = root.optJSONArray("mentions") ?: root.optJSONArray("提及") ?: return emptyList()
        val keys = speakers.map { it.key }.toSet()
        val out = ArrayList<Stage2Speaker>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val nm = o.optString("name").trim()
            if (nm.isBlank() || NarratorRoleStrip.isNarratorName(nm)) continue
            val key = o.optString("key").trim().ifBlank { "M${i + 1}" }
            if (key in keys) continue
            out.add(
                Stage2Speaker(
                    key = key,
                    name = nm,
                    aliases = parseAliases(o.opt("aliases")),
                    roleType = o.optString("roletype").trim(),
                    gender = o.optString("gender").trim(),
                    ageBand = o.optString("age").trim(),
                    genderSource = normalizeSource(o.optString("genderSource")),
                    ageSource = normalizeSource(o.optString("ageSource")),
                ),
            )
        }
        return out
    }

    private fun parseEvidence(
        root: JSONObject,
        speakers: List<Raw>,
        mentions: List<Stage2Speaker>,
        keyRemap: Map<String, String>,
    ): List<Stage2Evidence> {
        val arr = root.optJSONArray("evidence") ?: root.optJSONArray("证据") ?: return emptyList()
        val allKeys = speakers.map { it.key }.toSet() + mentions.map { it.key }.toSet()
        val byKey = speakers.associateBy { it.key }
        val mentionNames = mentions.associate { it.key to it.name }
        val byName = HashMap<String, String>()
        speakers.forEach { c ->
            byName[c.name] = c.key
            c.aliases.forEach { byName[it] = c.key }
        }
        mentions.forEach { m -> byName[m.name] = m.key }
        fun canonicalKey(raw: String): String? {
            val base = when {
                raw in allKeys -> raw
                keyRemap.containsKey(raw) -> keyRemap.getValue(raw)
                else -> byName[raw] ?: return null
            }
            var current = base
            var guard = 0
            while (keyRemap.containsKey(current) && guard++ < 20) {
                current = keyRemap.getValue(current)
            }
            return current
        }
        val out = ArrayList<Stage2Evidence>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val type = RaEvidenceType.fromStorage(o.optString("type").trim().uppercase())
            val subjectRaw = o.optString("subject").trim()
            val subjectKey = canonicalKey(subjectRaw) ?: continue
            val objectRaw = o.optString("object").trim()
            val objectKey = if (objectRaw in allKeys || keyRemap.containsKey(objectRaw)) {
                canonicalKey(objectRaw)
            } else {
                null
            }
            val objectText = if (objectKey != null) {
                byKey[objectKey]?.name ?: mentionNames[objectKey].orEmpty()
            } else {
                objectRaw
            }
            val ch = o.optInt("ch", chapterIndex + 1)
            val quote = o.optString("quote").trim()
            if (quote.isEmpty()) continue
            if (ch == chapterIndex + 1) {
                val located = locateInChapter(quote)
                if (located == null) {
                    log("证据剔除：quote 非本章原文（${quote.take(20)}）")
                    continue
                }
                out.add(
                    Stage2Evidence(
                        type = type,
                        subjectKey = subjectKey,
                        objectKey = objectKey,
                        objectText = objectText,
                        chapter = ch,
                        paragraphIndex = located.first,
                        start = located.second,
                        end = located.third,
                        quote = quote,
                        relation = o.optString("relation").trim(),
                        state = o.optString("state").trim(),
                        scope = RaEvidenceScope.MAIN_TEXT,
                        verified = true,
                    ),
                )
            } else {
                val inContext = (prevText.isNotEmpty() && prevText.contains(quote)) ||
                    (nextText.isNotEmpty() && nextText.contains(quote))
                if (!inContext) {
                    log("证据剔除：上下文 quote 不在给定前情/后续中（${quote.take(20)}）")
                    continue
                }
                out.add(
                    Stage2Evidence(
                        type = type,
                        subjectKey = subjectKey,
                        objectKey = objectKey,
                        objectText = objectText,
                        chapter = ch,
                        paragraphIndex = -1,
                        start = -1,
                        end = -1,
                        quote = quote,
                        relation = o.optString("relation").trim(),
                        state = o.optString("state").trim(),
                        scope = RaEvidenceScope.CONTEXT_CLUE,
                        verified = false,
                    ),
                )
            }
        }
        return out
    }

    private fun locateInChapter(quote: String): Triple<Int, Int, Int>? {
        paragraphs.forEach { p ->
            val idx = p.text.indexOf(quote)
            if (idx >= 0) return Triple(p.index, idx, idx + quote.length)
        }
        return null
    }

    private fun inferGenderFromAge(rawAge: String): String = when (rawAge.trim()) {
        "女童", "少女", "女青年", "女中年", "女老年" -> "女"
        else -> "男"
    }

    private fun standardizeAge(rawAge: String, gender: String): String {
        val age = rawAge.trim()
        if (age in AGE_SET) return age
        val female = gender == "女"
        val mapped = when (age) {
            "儿童", "孩子", "小孩" -> if (female) "女童" else "男童"
            "少年", "青少年" -> if (female) "少女" else "少年"
            "青年", "年轻人", "成年" -> if (female) "女青年" else "男青年"
            "中年" -> if (female) "女中年" else "男中年"
            "老年", "老人" -> if (female) "女老年" else "男老年"
            else -> ""
        }
        if (mapped.isNotEmpty()) return mapped
        return if (female) "女青年" else "男青年"
    }

    private companion object {
        val AGE_SET = setOf(
            "男童", "少年", "男青年", "男中年", "男老年",
            "女童", "少女", "女青年", "女中年", "女老年", "系统",
        )
    }
}
