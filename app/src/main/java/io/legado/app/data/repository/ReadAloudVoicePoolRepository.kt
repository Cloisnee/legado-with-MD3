package io.legado.app.data.repository

import android.app.Application
import com.github.jing332.compat.fs.TtsDirProvider
import com.github.jing332.tts.store.TtsConfigStore
import io.legado.app.domain.model.readaloud.VoiceBankRoleType
import io.legado.app.domain.model.readaloud.VoiceGroupInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * 声线池读取（配置列表 → 已选中池）；不存任何朗读分析数据。
 * 不存任何朗读分析数据。
 */
class ReadAloudVoicePoolRepository(private val app: Application) {

    suspend fun loadActiveVoiceBanks(): List<String> = withContext(Dispatchers.IO) {
        runCatching {
            val file = File(TtsDirProvider.baseDir(app), "_store/readaloud_ext.json")
            if (!file.exists()) return@runCatching emptyList()
            val arr = JSONObject(file.readText().removePrefix("\uFEFF"))
                .optJSONArray("activeVoiceBanks") ?: return@runCatching emptyList()
            buildList {
                for (i in 0 until arr.length()) {
                    arr.optString(i).trim().takeIf { it.isNotEmpty() }?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    /** 标签池 = 仅“已选中”声线池（配置列表勾选的分组）内的标签。 */
    suspend fun loadVoiceTagPool(): List<String> = withContext(Dispatchers.IO) {
        val banks = loadActiveVoiceBanks().toSet()
        if (banks.isEmpty()) return@withContext emptyList()
        val out = LinkedHashSet<String>()
        runCatching {
            val voices = TtsConfigStore.loadVoices(app)
            for (g in 0 until voices.length()) {
                val group = voices.optJSONObject(g) ?: continue
                val groupName = group.optJSONObject("group")?.optString("name").orEmpty()
                if (groupName !in banks) continue
                val list = group.optJSONArray("list") ?: continue
                for (i in 0 until list.length()) {
                    val entry = list.optJSONObject(i) ?: continue
                    if (!entry.optBoolean("isEnabled", true)) continue
                    val tag = entry.optJSONObject("config")
                        ?.optJSONObject("speechRule")?.optString("tag").orEmpty().trim()
                    if (tag.isNotEmpty()) out.add(tag)
                }
            }
        }
        out.toList()
    }

    /** 「当前启用池」标签 → (条目id, 条目config)。 */
    suspend fun loadActivePoolTagEntries(): Map<String, Pair<String, String>> =
        withContext(Dispatchers.IO) {
            val banks = loadActiveVoiceBanks().toSet()
            if (banks.isEmpty()) return@withContext emptyMap()
            val out = LinkedHashMap<String, Pair<String, String>>()
            runCatching {
                val disabledPlugins = runCatching {
                    val arr = TtsConfigStore.loadPlugins(app)
                    buildSet {
                        for (i in 0 until arr.length()) {
                            val o = arr.optJSONObject(i) ?: continue
                            val id = o.optString("pluginId").takeIf { it.isNotBlank() }
                                ?: o.optString("id").takeIf { it.isNotBlank() } ?: continue
                            val enabled = if (o.has("isEnabled")) {
                                o.optBoolean("isEnabled", true)
                            } else {
                                o.optBoolean("enabled", true)
                            }
                            if (!enabled) add(id)
                        }
                    }
                }.getOrDefault(emptySet())
                val voices = TtsConfigStore.loadVoices(app)
                for (gi in 0 until voices.length()) {
                    val group = voices.optJSONObject(gi) ?: continue
                    val info = group.optJSONObject("group") ?: continue
                    if (info.optString("name") !in banks) continue
                    val list = group.optJSONArray("list") ?: continue
                    for (i in 0 until list.length()) {
                        val entry = list.optJSONObject(i) ?: continue
                        if (!entry.optBoolean("isEnabled", true)) continue
                        val config = entry.optJSONObject("config") ?: continue
                        val speechRule = config.optJSONObject("speechRule") ?: continue
                        val tag = speechRule.optString("tag").trim()
                        val entryId = entry.optString("id").trim()
                        if (tag.isBlank() || entryId.isBlank() || out.containsKey(tag)) continue
                        val pluginId = config.optJSONObject("source")?.optString("pluginId").orEmpty()
                        if (pluginId.isNotBlank() && pluginId in disabledPlugins) continue
                        out[tag] = entryId to config.toString()
                    }
                }
            }
            out
        }

    suspend fun loadActiveVoiceGroups(): List<VoiceGroupInfo> = withContext(Dispatchers.IO) {
        val banks = loadActiveVoiceBanks().toSet()
        if (banks.isEmpty()) return@withContext emptyList()
        val out = ArrayList<VoiceGroupInfo>()
        runCatching {
            val voices = TtsConfigStore.loadVoices(app)
            for (g in 0 until voices.length()) {
                val group = voices.optJSONObject(g) ?: continue
                val info = group.optJSONObject("group") ?: continue
                val name = info.optString("name")
                if (name.isBlank() || name !in banks) continue
                val tags = ArrayList<String>()
                val list = group.optJSONArray("list") ?: continue
                for (i in 0 until list.length()) {
                    val entry = list.optJSONObject(i) ?: continue
                    if (!entry.optBoolean("isEnabled", true)) continue
                    val tag = entry.optJSONObject("config")
                        ?.optJSONObject("speechRule")?.optString("tag").orEmpty().trim()
                    if (tag.isNotEmpty()) tags.add(tag)
                }
                if (tags.isEmpty()) continue
                out.add(VoiceGroupInfo(name = name, roleType = info.optString("roleType").trim(), tags = tags))
            }
        }
        out
    }

    fun expectedVoicePrefix(roletype: String, gender: String, age: String): String =
        VoiceBankRoleType.tagPrefix(roletype, gender, age)

    fun voiceAgePrefix(voice: String): String {
        val match = Regex("^(路人)?(男童|女童|少年|少女|男青年|女青年|男中年|女中年|男老年|女老年|特殊男|特殊女)")
            .find(voice)
        return match?.value.orEmpty()
    }
}
