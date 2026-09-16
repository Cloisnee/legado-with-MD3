package com.github.jing332.tts.store

import android.content.Context
import com.github.jing332.compat.fs.TtsDirProvider
import com.github.jing332.compat.log.KLog
import com.github.jing332.database.entities.plugin.Plugin
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 移植层配置存储（<数据根>/_store/）：
 *  - plugins.json      插件壳列表（与 TTS Server 导入导出 JSON 一致；jread bundle 取 plugins 数组）
 *  - voices.json       配置列表/声线池（原生 [{group,list:[{...,config}]}] 格式，原样保存保证导出保真）
 *  - speech_rules.json 朗读规则 [{ruleId,name,code}]（基础支撑 runSpeechRule/getSpeechRuleList）
 *
 * 供：管理 API（TtsEngineContext）、_ops 指令通道、（后续）管理 UI 共用。
 * 语义对齐补丁版：标签匹配要求 speechRule.tagRuleId == 调用方 engineId。
 */
object TtsConfigStore {
    private const val TAG = "TtsConfigStore"

    data class FoundConfig(
        val displayName: String,
        val tag: String,
        val tagName: String,
        val pluginId: String,
        val locale: String,
        val voice: String,
        val speed: Float,
        val volume: Float,
        val pitch: Float,
        val sampleRate: Int,
    )

    fun storeDir(context: Context): File =
        File(TtsDirProvider.baseDir(context), "_store").apply { mkdirs() }

    private fun readSafe(file: File): String =
        if (!file.exists()) "" else runCatching { file.readText().removePrefix("\uFEFF") }.getOrDefault("")

    private fun writeSafe(file: File, text: String) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(text)
        }.onFailure { KLog.logger(TAG).debug { "write failed: ${file.name}: ${it.message}" } }
    }

    // ---------------- 插件 ----------------
    fun pluginsFile(context: Context) = File(storeDir(context), "plugins.json")

    fun loadPlugins(context: Context): JSONArray {
        val t = readSafe(pluginsFile(context)).trim()
        if (t.isEmpty()) return JSONArray()
        return runCatching { JSONArray(t) }.getOrElse { JSONArray() }
    }

    private fun effectivePluginId(o: JSONObject): String =
        o.optString("pluginId").takeIf { it.isNotBlank() }
            ?: o.optString("id").takeIf { it.isNotBlank() }
            ?: ""

    /** 合并导入：同 pluginId 覆盖，否则追加。返回 新增/覆盖/跳过 */
    fun importPlugins(context: Context, incoming: JSONArray): Triple<Int, Int, Int> {
        val current = loadPlugins(context)
        val ordered = linkedMapOf<String, JSONObject>()
        for (i in 0 until current.length()) {
            val o = current.optJSONObject(i) ?: continue
            ordered[effectivePluginId(o)] = o
        }
        var add = 0
        var replace = 0
        var skip = 0
        for (i in 0 until incoming.length()) {
            val o = incoming.optJSONObject(i) ?: continue
            val id = effectivePluginId(o)
            if (id.isBlank()) {
                skip++; continue
            }
            if (ordered.containsKey(id)) replace++ else add++
            ordered[id] = o
        }
        val out = JSONArray()
        ordered.values.forEach { out.put(it) }
        writeSafe(pluginsFile(context), out.toString())
        return Triple(add, replace, skip)
    }

    fun pluginById(context: Context, pluginId: String): JSONObject? {
        val arr = loadPlugins(context)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (effectivePluginId(o) == pluginId) return o
        }
        return null
    }

    fun referencedPluginIds(context: Context): List<String> {
        val set = linkedSetOf<String>()
        val arr = loadVoices(context)
        for (g in 0 until arr.length()) {
            val list = arr.optJSONObject(g)?.optJSONArray("list") ?: continue
            for (i in 0 until list.length()) {
                val pid = list.optJSONObject(i)?.optJSONObject("config")
                    ?.optJSONObject("source")?.optString("pluginId").orEmpty()
                if (pid.isNotBlank()) set.add(pid)
            }
        }
        return set.toList()
    }

    fun missingPluginIds(context: Context): List<String> =
        referencedPluginIds(context).filter { pluginById(context, it) == null }

    fun toEnginePlugin(o: JSONObject): Plugin {
        val userVars = linkedMapOf<String, String>()
        o.optJSONObject("userVars")?.let { m ->
            m.keys().forEach { k -> userVars[k] = m.optString(k) }
        }
        return Plugin(
            id = 0L,
            isEnabled = if (o.has("isEnabled")) o.optBoolean("isEnabled", true)
            else o.optBoolean("enabled", true),
            version = parseVersion(o.opt("version")),
            name = o.optString("name"),
            pluginId = effectivePluginId(o),
            author = o.optString("author"),
            iconUrl = o.optString("iconUrl"),
            code = o.optString("code"),
            defVars = emptyMap(),
            userVars = userVars,
            order = o.optInt("order", 0),
        )
    }

    private fun parseVersion(v: Any?): Int = when (v) {
        is Number -> v.toInt()
        is String -> Regex("\\d+").find(v)?.value?.toIntOrNull() ?: 0
        else -> 0
    }

    // ---------------- 配置列表/声线池 ----------------
    fun voicesFile(context: Context) = File(storeDir(context), "voices.json")

    fun loadVoices(context: Context): JSONArray {
        val t = readSafe(voicesFile(context)).trim()
        if (t.isEmpty()) return JSONArray()
        return runCatching { JSONArray(t) }.getOrElse { JSONArray() }
    }

    /** 合并导入：group 按 id 合并，list 条目按 id 覆盖/追加。返回 新增条目/覆盖条目/新增分组 */
    fun importVoices(context: Context, incoming: JSONArray): Triple<Int, Int, Int> {
        val current = loadVoices(context)
        val groups = linkedMapOf<String, JSONObject>()
        for (i in 0 until current.length()) {
            val g = current.optJSONObject(i) ?: continue
            groups[g.optJSONObject("group")?.optString("id") ?: "g$i"] = g
        }
        var addEntries = 0
        var replaceEntries = 0
        var addGroups = 0
        for (i in 0 until incoming.length()) {
            val g = incoming.optJSONObject(i) ?: continue
            val gid = g.optJSONObject("group")?.optString("id") ?: "g$i"
            val existing = groups[gid]
            if (existing == null) {
                groups[gid] = g; addGroups++; continue
            }
            val list = existing.optJSONArray("list") ?: JSONArray().also { existing.put("list", it) }
            val byId = linkedMapOf<String, JSONObject>()
            for (j in 0 until list.length()) {
                val e = list.optJSONObject(j) ?: continue
                byId[e.optString("id", "e$j")] = e
            }
            val incomingList = g.optJSONArray("list") ?: JSONArray()
            for (j in 0 until incomingList.length()) {
                val e = incomingList.optJSONObject(j) ?: continue
                val eid = e.optString("id", "e$j")
                if (byId.containsKey(eid)) replaceEntries++ else addEntries++
                byId[eid] = e
            }
            val newList = JSONArray()
            byId.values.forEach { newList.put(it) }
            existing.put("list", newList)
        }
        val out = JSONArray()
        groups.values.forEach { out.put(it) }
        writeSafe(voicesFile(context), out.toString())
        return Triple(addEntries, replaceEntries, addGroups)
    }

    // ---------------- 查询（tag 维度；tagRuleId 必须 == engineId，与补丁版一致） ----------------
    fun findConfig(context: Context, engineId: String, tag: String): FoundConfig? {
        val arr = loadVoices(context)
        for (g in 0 until arr.length()) {
            val list = arr.optJSONObject(g)?.optJSONArray("list") ?: continue
            for (i in 0 until list.length()) {
                val entry = list.optJSONObject(i) ?: continue
                val cfg = entry.optJSONObject("config") ?: continue
                val sr = cfg.optJSONObject("speechRule") ?: continue
                if (sr.optString("tag") != tag) continue
                if (sr.optString("tagRuleId") != engineId) continue
                val src = cfg.optJSONObject("source") ?: continue
                val ap = cfg.optJSONObject("audioParams")
                return FoundConfig(
                    displayName = entry.optString("displayName"),
                    tag = tag,
                    tagName = sr.optString("tagName"),
                    pluginId = src.optString("pluginId"),
                    locale = src.optString("locale"),
                    voice = src.optString("voice"),
                    speed = (ap?.opt("speed") as? Number)?.toFloat() ?: 0f,
                    volume = (ap?.opt("volume") as? Number)?.toFloat() ?: 0f,
                    pitch = (ap?.opt("pitch") as? Number)?.toFloat() ?: 0f,
                    sampleRate = cfg.optJSONObject("audioFormat")?.optInt("sampleRate", 24000) ?: 24000,
                )
            }
        }
        return null
    }

    /** 删除某 tag 对应配置；true=删除成功 */
    fun deleteConfig(context: Context, engineId: String, tag: String): Boolean {
        val arr = loadVoices(context)
        var changed = false
        for (g in 0 until arr.length()) {
            val grp = arr.optJSONObject(g) ?: continue
            val list = grp.optJSONArray("list") ?: continue
            val kept = JSONArray()
            for (i in 0 until list.length()) {
                val entry = list.optJSONObject(i) ?: continue
                val sr = entry.optJSONObject("config")?.optJSONObject("speechRule")
                val match = sr != null && sr.optString("tag") == tag && sr.optString("tagRuleId") == engineId
                if (match) changed = true else kept.put(entry)
            }
            if (changed) grp.put("list", kept)
        }
        if (changed) writeSafe(voicesFile(context), arr.toString())
        return changed
    }

    /** 改配置显示名；true=修改成功 */
    fun updateDisplayName(context: Context, engineId: String, tag: String, newName: String): Boolean {
        val arr = loadVoices(context)
        var changed = false
        for (g in 0 until arr.length()) {
            val list = arr.optJSONObject(g)?.optJSONArray("list") ?: continue
            for (i in 0 until list.length()) {
                val entry = list.optJSONObject(i) ?: continue
                val sr = entry.optJSONObject("config")?.optJSONObject("speechRule") ?: continue
                if (sr.optString("tag") == tag && sr.optString("tagRuleId") == engineId) {
                    entry.put("displayName", newName)
                    changed = true
                }
            }
        }
        if (changed) writeSafe(voicesFile(context), arr.toString())
        return changed
    }

    // ---------------- 朗读规则（基础支撑） ----------------
    fun rulesFile(context: Context) = File(storeDir(context), "speech_rules.json")

    fun loadRules(context: Context): JSONArray {
        val t = readSafe(rulesFile(context)).trim()
        if (t.isEmpty()) return JSONArray()
        return runCatching { JSONArray(t) }.getOrElse { JSONArray() }
    }

    fun importRules(context: Context, incoming: JSONArray): Int {
        val current = loadRules(context)
        val byId = linkedMapOf<String, JSONObject>()
        for (i in 0 until current.length()) {
            val o = current.optJSONObject(i) ?: continue
            byId[o.optString("ruleId")] = o
        }
        var n = 0
        for (i in 0 until incoming.length()) {
            val o = incoming.optJSONObject(i) ?: continue
            val id = o.optString("ruleId").takeIf { it.isNotBlank() } ?: o.optString("id")
            if (id.isBlank()) continue
            if (o.optString("ruleId").isBlank()) o.put("ruleId", id)
            byId[id] = o; n++
        }
        val out = JSONArray()
        byId.values.forEach { out.put(it) }
        writeSafe(rulesFile(context), out.toString())
        return n
    }

    fun findSpeechRule(context: Context, ruleId: String): JSONObject? {
        val arr = loadRules(context)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("ruleId") == ruleId) return o
        }
        return null
    }

    /** 与补丁版一致：JSON [{name, ruleId}] */
    fun speechRuleListJson(context: Context): String {
        val arr = loadRules(context)
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.put(JSONObject().put("name", o.optString("name")).put("ruleId", o.optString("ruleId")))
        }
        return out.toString()
    }

    // ---------------- 概览（_ops 用） ----------------
    fun summary(context: Context): String {
        val plugins = loadPlugins(context)
        val voices = loadVoices(context)
        var groups = 0
        var entries = 0
        var tags = 0
        val tagRuleIds = linkedSetOf<String>()
        for (g in 0 until voices.length()) {
            groups++
            val list = voices.optJSONObject(g)?.optJSONArray("list") ?: continue
            for (i in 0 until list.length()) {
                entries++
                val sr = list.optJSONObject(i)?.optJSONObject("config")?.optJSONObject("speechRule") ?: continue
                if (sr.optString("tag").isNotBlank()) tags++
                if (sr.optString("tagRuleId").isNotBlank()) tagRuleIds.add(sr.optString("tagRuleId"))
            }
        }
        val missing = missingPluginIds(context)
        return buildString {
            appendLine("插件: ${plugins.length()} 个")
            for (i in 0 until plugins.length()) {
                val o = plugins.optJSONObject(i) ?: continue
                appendLine("  - ${effectivePluginId(o)} | ${o.optString("name")}")
            }
            appendLine("配置列表: ${groups} 组 / ${entries} 条 / ${tags} 个带标签")
            appendLine("标签规则(tagRuleId): ${tagRuleIds.joinToString()}")
            if (missing.isEmpty()) appendLine("插件引用: 全部齐备 ✓")
            else appendLine("⚠ 缺失插件: ${missing.joinToString()}")
        }
    }
}
