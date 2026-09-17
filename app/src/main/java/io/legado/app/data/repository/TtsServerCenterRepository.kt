package io.legado.app.data.repository

import android.app.Application
import android.content.Context
import com.github.jing332.compat.fs.TtsDirProvider
import com.github.jing332.database.entities.systts.source.PluginTtsSource
import com.github.jing332.tts.debug.SynthProbe
import com.github.jing332.tts.speech.plugin.TtsPluginEngineManager
import com.github.jing332.tts.speech.plugin.engine.TtsEngineContext
import com.github.jing332.tts.store.TtsConfigStore
import io.legado.app.data.appDb
import io.legado.app.domain.gateway.ReadAloudSettingsGateway
import io.legado.app.domain.model.readaloud.ReadAloudEngineSelection
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * TTS-Server 管理中心 · 数据层（UI-1）
 * 直接操作 TtsConfigStore（插件 / 配置列表），引擎选择走原版 ttsEngine 字段。
 */
data class PluginRow(
    val pluginId: String,
    val name: String,
    val author: String,
    val version: Int,
    val enabled: Boolean,
    val groupName: String,
)

data class EntryRow(
    val id: Long,
    val displayName: String,
    val tag: String,
    val tagRuleId: String,
    val tagName: String,
    val pluginId: String,
    val locale: String,
    val voice: String,
    val categoryPath: String,
    val speed: Float,
    val volume: Float,
    val pitch: Float,
)

data class GroupRow(
    val name: String,
    val entries: List<EntryRow>,
)

data class EngineOption(val value: String?, val label: String)

data class VarField(
    val key: String,
    val label: String,
    val description: String,
    val value: String,
)

data class AuditionOutcome(
    val ok: Boolean,
    val path: String?,
    val message: String,
)

class TtsServerCenterRepository(private val app: Application) {

    companion object {
        const val BUILTIN_ENGINE_JSON =
            """{"engineType":"tts_server","engineId":"","speakerId":"","displayName":"内置引擎"}"""
    }

    private val ctx: Context get() = app

    // ---------------- 读取 ----------------

    suspend fun loadPlugins(): List<PluginRow> = withContext(Dispatchers.IO) {
        val arr = TtsConfigStore.loadPlugins(ctx)
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("pluginId").ifBlank { o.optString("id") }
                if (id.isBlank()) continue
                add(
                    PluginRow(
                        pluginId = id,
                        name = o.optString("name", id),
                        author = o.optString("author"),
                        version = o.opt("version")?.toString()
                            ?.let { v -> Regex("\\d+").find(v)?.value?.toIntOrNull() } ?: 0,
                        enabled = if (o.has("isEnabled")) o.optBoolean("isEnabled", true)
                        else o.optBoolean("enabled", true),
                        groupName = o.optString("pluginGroupName"),
                    )
                )
            }
        }
    }

    suspend fun loadGroups(): List<GroupRow> = withContext(Dispatchers.IO) {
        val arr = TtsConfigStore.loadVoices(ctx)
        buildList {
            for (g in 0 until arr.length()) {
                val grp = arr.optJSONObject(g) ?: continue
                val name = grp.optJSONObject("group")?.optString("name") ?: "未命名分组"
                val list = grp.optJSONArray("list") ?: JSONArray()
                val entries = buildList {
                    for (i in 0 until list.length()) {
                        val e = list.optJSONObject(i) ?: continue
                        val cfg = e.optJSONObject("config") ?: continue
                        val sr = cfg.optJSONObject("speechRule") ?: continue
                        val src = cfg.optJSONObject("source")
                        val ap = cfg.optJSONObject("audioParams")
                        add(
                            EntryRow(
                                id = e.optLong("id"),
                                displayName = e.optString("displayName"),
                                tag = sr.optString("tag"),
                                tagRuleId = sr.optString("tagRuleId"),
                                tagName = sr.optString("tagName"),
                                pluginId = src?.optString("pluginId").orEmpty(),
                                locale = src?.optString("locale").orEmpty(),
                                voice = src?.optString("voice").orEmpty(),
                                categoryPath = e.optString("categoryPath"),
                                speed = (ap?.opt("speed") as? Number)?.toFloat() ?: 0f,
                                volume = (ap?.opt("volume") as? Number)?.toFloat() ?: 0f,
                                pitch = (ap?.opt("pitch") as? Number)?.toFloat() ?: 0f,
                            )
                        )
                    }
                }
                add(GroupRow(name = name, entries = entries))
            }
        }
    }

    // ---------------- 引擎选择 ----------------

    suspend fun loadEngineOptions(): List<EngineOption> = withContext(Dispatchers.IO) {
        buildList {
            add(EngineOption(null, "系统 TTS"))
            add(EngineOption(BUILTIN_ENGINE_JSON, "内置引擎（TTS Server）"))
            runCatching {
                appDb.httpTTSDao.all.forEach { h ->
                    add(EngineOption(h.id.toString(), "在线朗读 · ${h.name}"))
                }
            }
        }
    }

    fun currentEngineValue(): String? =
        ReadBook.book?.getTtsEngine() ?: gateway().currentSettings.ttsEngine

    fun engineLabel(value: String?): String = when {
        value.isNullOrBlank() -> "系统 TTS"
        value.all { it.isDigit() } -> runCatching {
            appDb.httpTTSDao.get(value.toLong())?.name?.let { "在线朗读 · $it" }
        }.getOrNull() ?: "在线朗读"
        value.contains("tts_server") -> "内置引擎"
        else -> GSON.fromJsonObject<ReadAloudEngineSelection>(value).getOrNull()
            ?.displayName?.takeIf { it.isNotBlank() } ?: "在线朗读"
    }

    private fun gateway(): ReadAloudSettingsGateway =
        org.koin.core.context.GlobalContext.get().get()

    /** 应用引擎选择（镜像云 TTS 的 applyDefaultEngine 语义：本书写 Book / 全局写设置） */
    suspend fun applyEngine(value: String?, forBook: Boolean): String {
        val book = ReadBook.book
        if (forBook && book != null) {
            book.setTtsEngine(value)
            withContext(Dispatchers.IO) {
                appDb.bookDao.getBook(book.bookUrl)?.let { b ->
                    b.setTtsEngine(value)
                    appDb.bookDao.update(b)
                }
            }
        } else {
            book?.setTtsEngine(null)
            val url = book?.bookUrl
            if (url != null) {
                withContext(Dispatchers.IO) {
                    appDb.bookDao.getBook(url)?.let { b ->
                        b.setTtsEngine(null)
                        appDb.bookDao.update(b)
                    }
                }
            }
            gateway().update { it.copy(ttsEngine = value) }
        }
        withContext(Dispatchers.Main) { ReadAloud.upReadAloudClass() }
        return if (forBook && book != null) "已应用到本书" else "已设为全局"
    }

    // ---------------- 插件操作 ----------------

    suspend fun setPluginEnabled(pluginId: String, enabled: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val file = TtsConfigStore.pluginsFile(ctx)
                val arr = JSONArray(file.readText().removePrefix("\uFEFF"))
                var hit = false
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("pluginId").ifBlank { o.optString("id") }
                    if (id == pluginId) {
                        o.put("isEnabled", enabled)
                        hit = true
                    }
                }
                if (hit) file.writeText(arr.toString())
                hit
            }.getOrDefault(false)
        }

    suspend fun deletePlugin(pluginId: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val file = TtsConfigStore.pluginsFile(ctx)
            val arr = JSONArray(file.readText().removePrefix("\uFEFF"))
            val out = JSONArray()
            var hit = false
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("pluginId").ifBlank { o.optString("id") }
                if (id == pluginId) hit = true else out.put(o)
            }
            if (hit) file.writeText(out.toString())
            hit
        }.getOrDefault(false)
    }

    suspend fun pluginVarsText(pluginId: String): String = withContext(Dispatchers.IO) {
        runCatching {
            val arr = TtsConfigStore.loadPlugins(ctx)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("pluginId").ifBlank { o.optString("id") }
                if (id == pluginId) {
                    return@runCatching buildString {
                        appendLine("插件：${o.optString("name")} ($id)")
                        appendLine()
                        appendLine("defVars（插件定义变量）：")
                        appendLine(o.optJSONObject("defVars")?.toString(2) ?: "{}")
                        appendLine()
                        appendLine("userVars（用户变量）：")
                        appendLine(o.optJSONObject("userVars")?.toString(2) ?: "{}")
                    }
                }
            }
            "插件不存在：$pluginId"
        }.getOrDefault("读取失败")
    }

    // ---------------- 声线条目操作 ----------------

    suspend fun renameEntry(ruleId: String, tag: String, newName: String): Boolean =
        withContext(Dispatchers.IO) { TtsConfigStore.updateDisplayName(ctx, ruleId, tag, newName) }

    suspend fun deleteEntry(ruleId: String, tag: String): Boolean =
        withContext(Dispatchers.IO) { TtsConfigStore.deleteConfig(ctx, ruleId, tag) }

    /** 试听（详细诊断版）：报告逐步写入 _audition/last_synth_result.txt；成功时返回文件路径 */
    suspend fun auditionDetailed(ruleId: String, tag: String, text: String): AuditionOutcome =
        withContext(Dispatchers.IO) {
            val r = runCatching { SynthProbe.run(ctx, ruleId, tag, text) }
                .getOrElse {
                    return@withContext AuditionOutcome(
                        false, null, "SynthProbe 异常: ${it.stackTraceToString()}"
                    )
                }
            runCatching {
                val dir = File(TtsDirProvider.baseDir(ctx), "_audition").apply { mkdirs() }
                File(dir, "last_synth_result.txt")
                    .writeText("[${ts()}] ruleId=$ruleId tag=$tag\n${r.report}\n")
            }
            val bytes = r.bytes
                ?: return@withContext AuditionOutcome(false, null, r.report)
            runCatching {
                val dir = File(TtsDirProvider.baseDir(ctx), "_audition").apply { mkdirs() }
                val ext = when {
                    bytes.size >= 4 && bytes[0] == 'R'.code.toByte() -> "wav"
                    bytes.size >= 2 && bytes[0] == 0xFF.toByte() -> "mp3"
                    else -> "bin"
                }
                val f = File(dir, "audition_${tag}_${System.currentTimeMillis()}.$ext")
                f.writeBytes(bytes)
                AuditionOutcome(
                    true, f.absolutePath,
                    "合成成功：${f.absolutePath}\n大小=${bytes.size} bytes\n\n${r.report}"
                )
            }.getOrElse {
                AuditionOutcome(false, null, "写文件失败: ${it.stackTraceToString()}")
            }
        }

    /** 读取插件的变量表单（defVars schema + 当前 userVars 值） */
    suspend fun loadVarFields(pluginId: String): List<VarField> = withContext(Dispatchers.IO) {
        runCatching {
            val arr = TtsConfigStore.loadPlugins(ctx)
            var shell: JSONObject? = null
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("pluginId").ifBlank { o.optString("id") }
                if (id == pluginId) {
                    shell = o; break
                }
            }
            val o = shell ?: return@runCatching emptyList()
            val defVars = o.optJSONObject("defVars") ?: JSONObject()
            val userVars = o.optJSONObject("userVars") ?: JSONObject()
            val fields = linkedMapOf<String, VarField>()
            val keys = defVars.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val schema = defVars.optJSONObject(k)
                fields[k] = VarField(
                    key = k,
                    label = schema?.optString("label").orEmpty().ifBlank { k },
                    description = schema?.let { s ->
                        listOf(
                            s.optString("description"),
                            s.optString("hint"),
                            s.optString("placeholder"),
                        ).firstOrNull { it.isNotBlank() }.orEmpty()
                    } ?: "",
                    value = userVars.optString(k).orEmpty(),
                )
            }
            val ukeys = userVars.keys()
            while (ukeys.hasNext()) {
                val k = ukeys.next()
                if (!fields.containsKey(k)) {
                    fields[k] = VarField(k, k, "自定义", userVars.optString(k).orEmpty())
                }
            }
            fields.values.toList()
        }.getOrDefault(emptyList())
    }

    /** 保存插件 userVars（写回 plugins.json 并使引擎缓存失效） */
    suspend fun savePluginUserVars(pluginId: String, vars: Map<String, String>): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val file = TtsConfigStore.pluginsFile(ctx)
                val arr = JSONArray(file.readText().removePrefix("\uFEFF"))
                var hit = false
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("pluginId").ifBlank { o.optString("id") }
                    if (id == pluginId) {
                        val uv = JSONObject()
                        vars.forEach { (k, v) -> uv.put(k, v) }
                        o.put("userVars", uv)
                        hit = true
                    }
                }
                if (hit) {
                    file.writeText(arr.toString())
                    TtsPluginEngineManager.remove(pluginId)
                }
                hit
            }.getOrDefault(false)
        }

    /** 编辑插件元信息（name / pluginId / author / version），并失效引擎缓存 */
    suspend fun updatePluginMeta(
        oldId: String,
        newName: String,
        newId: String,
        newAuthor: String,
        newVersion: Int,
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val file = TtsConfigStore.pluginsFile(ctx)
            val arr = JSONArray(file.readText().removePrefix("\uFEFF"))
            val finalId = newId.trim().ifBlank { oldId }
            var hit = false
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("pluginId").ifBlank { o.optString("id") }
                if (id == oldId) {
                    o.put("name", newName)
                    o.put("pluginId", finalId)
                    o.put("author", newAuthor)
                    o.put("version", newVersion)
                    hit = true
                }
            }
            if (hit) {
                file.writeText(arr.toString())
                TtsPluginEngineManager.remove(oldId)
                if (finalId != oldId) TtsPluginEngineManager.remove(finalId)
            }
            hit
        }.getOrDefault(false)
    }

    // ---------------- 导入导出 ----------------

    private fun exportsDir(): File =
        File(TtsDirProvider.baseDir(ctx), "_exports").apply { mkdirs() }

    private fun ts(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())

    suspend fun exportPlugins(): String = withContext(Dispatchers.IO) {
        val arr = TtsConfigStore.loadPlugins(ctx)
        val f = File(exportsDir(), "plugins_${ts()}.json")
        f.writeText(arr.toString())
        f.absolutePath
    }

    suspend fun exportPlugin(pluginId: String): String = withContext(Dispatchers.IO) {
        runCatching {
            val arr = TtsConfigStore.loadPlugins(ctx)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("pluginId").ifBlank { o.optString("id") }
                if (id == pluginId) {
                    val f = File(exportsDir(), "plugin_${pluginId}_${ts()}.json")
                    f.writeText(JSONArray().put(o).toString())
                    return@runCatching f.absolutePath
                }
            }
            "插件不存在：$pluginId"
        }.getOrDefault("导出失败")
    }

    suspend fun exportVoices(): String = withContext(Dispatchers.IO) {
        val arr = TtsConfigStore.loadVoices(ctx)
        val f = File(exportsDir(), "voices_${ts()}.json")
        f.writeText(arr.toString())
        f.absolutePath
    }

    suspend fun importPlugins(text: String): String = withContext(Dispatchers.IO) {
        runCatching {
            val t = text.trim().removePrefix("\uFEFF")
            val arr = when {
                t.startsWith("[") -> JSONArray(t)
                t.startsWith("{") -> {
                    val o = JSONObject(t)
                    o.optJSONArray("plugins") ?: JSONArray().put(o)
                }

                else -> error("无法解析：既不是数组也不是对象")
            }
            var okShape = false
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val pid = o.optString("pluginId").ifBlank { o.optString("id") }
                if (pid.isNotBlank() && o.optString("code").isNotBlank()) {
                    okShape = true; break
                }
            }
            if (!okShape) {
                return@runCatching "导入失败：这不是音色插件 JSON（未找到 pluginId/code 字段）"
            }
            val (a, r, s) = TtsConfigStore.importPlugins(ctx, arr)
            "导入插件完成：新增=$a 覆盖=$r 跳过=$s"
        }.getOrElse { "导入失败：${it.message ?: it.javaClass.simpleName}" }
    }

    suspend fun importVoices(text: String): String = withContext(Dispatchers.IO) {
        runCatching {
            val t = text.trim().removePrefix("\uFEFF")
            val arr = JSONArray(t)
            var okShape = false
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optJSONObject("group") != null && o.optJSONArray("list") != null) {
                    okShape = true; break
                }
            }
            if (!okShape) {
                return@runCatching "导入失败：这不是配置列表 JSON（缺少 group/list 结构）"
            }
            val (ae, re, ag) = TtsConfigStore.importVoices(ctx, arr)
            "导入配置列表完成：新增条目=$ae 覆盖条目=$re 新增分组=$ag"
        }.getOrElse { "导入失败：${it.message ?: it.javaClass.simpleName}" }
    }

    // ================= UI-2A：声线选择 / 新建编辑 / 分组与批量 =================

    // ---------------- 声线选择（新建/试听） ----------------

    suspend fun loadLocales(pluginId: String): List<LocaleOption> = withContext(Dispatchers.IO) {
        runCatching {
            val shell = TtsConfigStore.pluginById(ctx, pluginId) ?: return@runCatching emptyList()
            val engine = TtsPluginEngineManager.get(ctx, TtsConfigStore.toEnginePlugin(shell))
            engine.getLocales().map { (k, v) -> LocaleOption(k, v) }
        }.getOrDefault(emptyList())
    }

    suspend fun loadVoices(pluginId: String, locale: String): List<VoiceOption> =
        withContext(Dispatchers.IO) {
            runCatching {
                val shell = TtsConfigStore.pluginById(ctx, pluginId) ?: return@runCatching emptyList()
                val engine = TtsPluginEngineManager.get(ctx, TtsConfigStore.toEnginePlugin(shell))
                engine.getVoices(locale).map { VoiceOption(it.id, it.name, it.icon) }
            }.getOrDefault(emptyList())
        }

    /** 插件直连试听（不依赖配置条目） */
    suspend fun auditionDirect(
        pluginId: String, locale: String, voice: String, text: String,
    ): AuditionOutcome = withContext(Dispatchers.IO) {
        val r = runCatching { SynthProbe.runDirect(ctx, pluginId, locale, voice, text) }
            .getOrElse {
                return@withContext AuditionOutcome(
                    false, null, "SynthProbe 异常: ${it.stackTraceToString()}"
                )
            }
        runCatching {
            val dir = File(TtsDirProvider.baseDir(ctx), "_audition").apply { mkdirs() }
            File(dir, "last_synth_result.txt")
                .writeText("[${ts()}] plugin=$pluginId locale=$locale voice=$voice\n${r.report}\n")
        }
        val bytes = r.bytes ?: return@withContext AuditionOutcome(false, null, r.report)
        runCatching {
            val dir = File(TtsDirProvider.baseDir(ctx), "_audition").apply { mkdirs() }
            val ext = when {
                bytes.size >= 4 && bytes[0] == 'R'.code.toByte() -> "wav"
                bytes.size >= 2 && bytes[0] == 0xFF.toByte() -> "mp3"
                else -> "bin"
            }
            val f = File(dir, "audition_direct_${System.currentTimeMillis()}.$ext")
            f.writeBytes(bytes)
            AuditionOutcome(
                true, f.absolutePath,
                "合成成功：${f.absolutePath}\n大小=${bytes.size} bytes\n\n${r.report}"
            )
        }.getOrElse { AuditionOutcome(false, null, "写文件失败: ${it.stackTraceToString()}") }
    }

    // ---------------- 新建 / 编辑条目 ----------------

    suspend fun appendVoiceEntry(
        groupName: String, displayName: String, tag: String, tagRuleId: String,
        tagName: String, categoryPath: String, pluginId: String, locale: String, voice: String,
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val file = TtsConfigStore.voicesFile(ctx)
            val arr = if (file.exists()) JSONArray(file.readText().removePrefix("\uFEFF")) else JSONArray()
            var found: JSONObject? = null
            for (i in 0 until arr.length()) {
                val g = arr.optJSONObject(i) ?: continue
                if (g.optJSONObject("group")?.optString("name") == groupName) { found = g; break }
            }
            if (found == null) {
                val g = JSONObject().apply {
                    put("group", JSONObject().apply {
                        put("id", System.currentTimeMillis())
                        put("name", groupName)
                        put("order", arr.length())
                    })
                    put("list", JSONArray())
                }
                arr.put(g)
                found = g
            }
            val grp = found!!
            val list = grp.optJSONArray("list") ?: JSONArray().also { grp.put("list", it) }
            list.put(JSONObject().apply {
                put("id", System.currentTimeMillis())
                put("displayName", displayName)
                put("groupId", grp.optJSONObject("group")?.optLong("id") ?: 0L)
                put("categoryPath", categoryPath)
                put("config", JSONObject().apply {
                    put("#type", "tts")
                    put("speechRule", JSONObject().apply {
                        put("target", 4)
                        put("tag", tag)
                        put("tagRuleId", tagRuleId)
                        put("tagName", tagName)
                    })
                    put("audioFormat", JSONObject().put("sampleRate", 24000))
                    put("source", JSONObject().apply {
                        put("#type", "plugin")
                        put("locale", locale)
                        put("voice", voice)
                        put("pluginId", pluginId)
                        put("data", JSONObject())
                    })
                })
            })
            file.parentFile?.mkdirs()
            file.writeText(arr.toString())
            true
        }.getOrDefault(false)
    }

    suspend fun updateVoiceEntry(
        oldRuleId: String, oldTag: String,
        newName: String, newTag: String, newRuleId: String, newTagName: String,
        newCategoryPath: String, speed: Float, volume: Float, pitch: Float,
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val file = TtsConfigStore.voicesFile(ctx)
            val arr = JSONArray(file.readText().removePrefix("\uFEFF"))
            var hit = false
            outer@ for (gi in 0 until arr.length()) {
                val list = arr.optJSONObject(gi)?.optJSONArray("list") ?: continue
                for (i in 0 until list.length()) {
                    val e = list.optJSONObject(i) ?: continue
                    val cfg = e.optJSONObject("config") ?: continue
                    val sr = cfg.optJSONObject("speechRule") ?: continue
                    if (sr.optString("tag") == oldTag && sr.optString("tagRuleId") == oldRuleId) {
                        e.put("displayName", newName)
                        e.put("categoryPath", newCategoryPath)
                        sr.put("tag", newTag)
                        sr.put("tagRuleId", newRuleId)
                        sr.put("tagName", newTagName)
                        val ap = cfg.optJSONObject("audioParams")
                            ?: JSONObject().also { cfg.put("audioParams", it) }
                        ap.put("speed", speed)
                        ap.put("volume", volume)
                        ap.put("pitch", pitch)
                        hit = true
                        break@outer
                    }
                }
            }
            if (hit) file.writeText(arr.toString())
            hit
        }.getOrDefault(false)
    }

    // ---------------- 分组操作 ----------------

    suspend fun renameGroup(oldName: String, newName: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val file = TtsConfigStore.voicesFile(ctx)
                val arr = JSONArray(file.readText().removePrefix("\uFEFF"))
                var hit = false
                for (i in 0 until arr.length()) {
                    val g = arr.optJSONObject(i)?.optJSONObject("group") ?: continue
                    if (g.optString("name") == oldName) {
                        g.put("name", newName); hit = true
                    }
                }
                if (hit) file.writeText(arr.toString())
                hit
            }.getOrDefault(false)
        }

    suspend fun deleteGroup(name: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val file = TtsConfigStore.voicesFile(ctx)
            val arr = JSONArray(file.readText().removePrefix("\uFEFF"))
            val out = JSONArray()
            var hit = false
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                if (item.optJSONObject("group")?.optString("name") == name) {
                    hit = true
                } else {
                    out.put(item)
                }
            }
            if (hit) file.writeText(out.toString())
            hit
        }.getOrDefault(false)
    }

    suspend fun exportGroup(name: String): String = withContext(Dispatchers.IO) {
        runCatching {
            val arr = TtsConfigStore.loadVoices(ctx)
            val out = JSONArray()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                if (item.optJSONObject("group")?.optString("name") == name) out.put(item)
            }
            if (out.length() == 0) return@runCatching "未找到分组：$name"
            val f = File(exportsDir(), "voices_group_${ts()}.json")
            f.writeText(out.toString())
            f.absolutePath
        }.getOrDefault("导出失败")
    }

    // ---------------- 插件：排序 / 批量 ----------------

    suspend fun movePlugin(pluginId: String, action: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val file = TtsConfigStore.pluginsFile(ctx)
                val arr = JSONArray(file.readText().removePrefix("\uFEFF"))
                val list = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.toMutableList()
                fun idOf(o: JSONObject) = o.optString("pluginId").ifBlank { o.optString("id") }
                val idx = list.indexOfFirst { idOf(it) == pluginId }
                if (idx < 0) return@runCatching false
                val newIdx = when (action) {
                    "up" -> (idx - 1).coerceAtLeast(0)
                    "down" -> (idx + 1).coerceAtMost(list.size - 1)
                    "top" -> 0
                    else -> list.size - 1
                }
                if (newIdx != idx) {
                    val item = list.removeAt(idx)
                    list.add(newIdx, item)
                }
                val out = JSONArray()
                list.forEachIndexed { i, o -> o.put("order", i); out.put(o) }
                file.writeText(out.toString())
                true
            }.getOrDefault(false)
        }

    suspend fun setPluginsOrder(orderedIds: List<String>): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val file = TtsConfigStore.pluginsFile(ctx)
            val arr = JSONArray(file.readText().removePrefix("\uFEFF"))
            val byId = linkedMapOf<String, JSONObject>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                byId[o.optString("pluginId").ifBlank { o.optString("id") }] = o
            }
            val out = JSONArray()
            var order = 0
            orderedIds.forEach { id ->
                byId.remove(id)?.let { o -> o.put("order", order++); out.put(o) }
            }
            byId.values.forEach { o -> o.put("order", order++); out.put(o) }
            file.writeText(out.toString())
            true
        }.getOrDefault(false)
    }

    suspend fun movePluginsToEdge(ids: Set<String>, toTop: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val file = TtsConfigStore.pluginsFile(ctx)
                val arr = JSONArray(file.readText().removePrefix("\uFEFF"))
                val all = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                fun idOf(o: JSONObject) = o.optString("pluginId").ifBlank { o.optString("id") }
                val sel = all.filter { idOf(it) in ids }
                val rest = all.filterNot { idOf(it) in ids }
                val list = if (toTop) sel + rest else rest + sel
                val out = JSONArray()
                list.forEachIndexed { i, o -> o.put("order", i); out.put(o) }
                file.writeText(out.toString())
                true
            }.getOrDefault(false)
        }

    suspend fun setPluginsEnabled(ids: Set<String>, enabled: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val file = TtsConfigStore.pluginsFile(ctx)
                val arr = JSONArray(file.readText().removePrefix("\uFEFF"))
                var hit = false
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("pluginId").ifBlank { o.optString("id") }
                    if (id in ids) {
                        o.put("isEnabled", enabled); hit = true
                    }
                }
                if (hit) file.writeText(arr.toString())
                hit
            }.getOrDefault(false)
        }

    suspend fun deletePlugins(ids: Set<String>): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val file = TtsConfigStore.pluginsFile(ctx)
            val arr = JSONArray(file.readText().removePrefix("\uFEFF"))
            val out = JSONArray()
            var hit = false
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("pluginId").ifBlank { o.optString("id") }
                if (id in ids) hit = true else out.put(o)
            }
            if (hit) file.writeText(out.toString())
            hit
        }.getOrDefault(false)
    }

    suspend fun exportPluginsByIds(ids: Set<String>): String = withContext(Dispatchers.IO) {
        runCatching {
            val arr = TtsConfigStore.loadPlugins(ctx)
            val out = JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("pluginId").ifBlank { o.optString("id") }
                if (id in ids) out.put(o)
            }
            if (out.length() == 0) return@runCatching "未选中任何插件"
            val f = File(exportsDir(), "plugins_selected_${ts()}.json")
            f.writeText(out.toString())
            f.absolutePath
        }.getOrDefault("导出失败")
    }

    // ---------------- 条目：批量 ----------------

    suspend fun deleteEntries(keys: Set<String>): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val file = TtsConfigStore.voicesFile(ctx)
            val arr = JSONArray(file.readText().removePrefix("\uFEFF"))
            var hit = false
            for (gi in 0 until arr.length()) {
                val grp = arr.optJSONObject(gi) ?: continue
                val list = grp.optJSONArray("list") ?: continue
                val kept = JSONArray()
                for (i in 0 until list.length()) {
                    val e = list.optJSONObject(i) ?: continue
                    val sr = e.optJSONObject("config")?.optJSONObject("speechRule")
                    val key = "${sr?.optString("tagRuleId").orEmpty()}|${sr?.optString("tag").orEmpty()}"
                    if (key in keys) hit = true else kept.put(e)
                }
                grp.put("list", kept)
            }
            if (hit) {
                val pruned = JSONArray()
                for (gi in 0 until arr.length()) {
                    val grp = arr.optJSONObject(gi) ?: continue
                    val list = grp.optJSONArray("list")
                    if (list == null || list.length() == 0) continue
                    pruned.put(grp)
                }
                file.writeText(pruned.toString())
            }
            hit
        }.getOrDefault(false)
    }

    suspend fun exportEntries(keys: Set<String>): String = withContext(Dispatchers.IO) {
        runCatching {
            val arr = TtsConfigStore.loadVoices(ctx)
            val out = JSONArray()
            for (gi in 0 until arr.length()) {
                val grp = arr.optJSONObject(gi) ?: continue
                val list = grp.optJSONArray("list") ?: continue
                val sel = JSONArray()
                for (i in 0 until list.length()) {
                    val e = list.optJSONObject(i) ?: continue
                    val sr = e.optJSONObject("config")?.optJSONObject("speechRule")
                    val key = "${sr?.optString("tagRuleId").orEmpty()}|${sr?.optString("tag").orEmpty()}"
                    if (key in keys) sel.put(e)
                }
                if (sel.length() > 0) {
                    out.put(JSONObject().apply {
                        put("group", grp.optJSONObject("group") ?: JSONObject())
                        put("list", sel)
                    })
                }
            }
            if (out.length() == 0) return@runCatching "未选中任何条目"
            val f = File(exportsDir(), "voices_selected_${ts()}.json")
            f.writeText(out.toString())
            f.absolutePath
        }.getOrDefault("导出失败")
    }

    // ---------------- 条目排序（分类内拖动 / 置顶置底） ----------------

    /** 组内某分类条目重新排序（orderedKeys 为该分类的新顺序） */
    suspend fun setEntriesOrderInGroup(
        groupName: String,
        categoryPath: String,
        orderedKeys: List<String>,
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val file = TtsConfigStore.voicesFile(ctx)
            val arr = JSONArray(file.readText().removePrefix("\uFEFF"))
            var hit = false
            for (gi in 0 until arr.length()) {
                val grp = arr.optJSONObject(gi) ?: continue
                if (grp.optJSONObject("group")?.optString("name") != groupName) continue
                val list = grp.optJSONArray("list") ?: continue
                val entriesAll = (0 until list.length()).mapNotNull { list.optJSONObject(it) }
                fun keyOf(e: JSONObject): String {
                    val sr = e.optJSONObject("config")?.optJSONObject("speechRule")
                    return "${sr?.optString("tagRuleId").orEmpty()}|${sr?.optString("tag").orEmpty()}"
                }
                fun catOf(e: JSONObject) = e.optString("categoryPath").ifBlank { "未分类" }
                val catEntries = LinkedHashMap<String, JSONObject>()
                entriesAll.forEach { e ->
                    if (catOf(e) == categoryPath) catEntries[keyOf(e)] = e
                }
                if (catEntries.isEmpty()) continue
                val ordered = ArrayList<JSONObject>()
                orderedKeys.forEach { k -> catEntries.remove(k)?.let { ordered.add(it) } }
                ordered.addAll(catEntries.values)
                var oi = 0
                val result = ArrayList<JSONObject>()
                entriesAll.forEach { e ->
                    if (catOf(e) == categoryPath) {
                        if (oi < ordered.size) {
                            result.add(ordered[oi]); oi++
                        }
                    } else {
                        result.add(e)
                    }
                }
                val outList = JSONArray()
                result.forEach { outList.put(it) }
                grp.put("list", outList)
                hit = true
                break
            }
            if (hit) file.writeText(arr.toString())
            hit
        }.getOrDefault(false)
    }

    /** 批量置顶/置底（各自分类内，保持选中项相对顺序） */
    suspend fun moveEntriesToEdge(keys: Set<String>, toTop: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val file = TtsConfigStore.voicesFile(ctx)
                val arr = JSONArray(file.readText().removePrefix("\uFEFF"))
                var hit = false
                for (gi in 0 until arr.length()) {
                    val grp = arr.optJSONObject(gi) ?: continue
                    val list = grp.optJSONArray("list") ?: continue
                    val entriesAll = (0 until list.length()).mapNotNull { list.optJSONObject(it) }
                    fun keyOf(e: JSONObject): String {
                        val sr = e.optJSONObject("config")?.optJSONObject("speechRule")
                        return "${sr?.optString("tagRuleId").orEmpty()}|${sr?.optString("tag").orEmpty()}"
                    }
                    fun catOf(e: JSONObject) = e.optString("categoryPath").ifBlank { "未分类" }
                    val changedCats = LinkedHashSet<String>()
                    entriesAll.forEach { e -> if (keyOf(e) in keys) changedCats.add(catOf(e)) }
                    if (changedCats.isEmpty()) continue
                    val catOrdered = HashMap<String, ArrayDeque<JSONObject>>()
                    changedCats.forEach { cat ->
                        val selInCat = entriesAll.filter { catOf(it) == cat && keyOf(it) in keys }
                        val restInCat = entriesAll.filter { catOf(it) == cat && keyOf(it) !in keys }
                        val ordered = if (toTop) selInCat + restInCat else restInCat + selInCat
                        catOrdered[cat] = ArrayDeque(ordered)
                    }
                    val result = ArrayList<JSONObject>()
                    entriesAll.forEach { e ->
                        val q = catOrdered[catOf(e)]
                        if (q != null && q.isNotEmpty()) result.add(q.removeFirst()) else result.add(e)
                    }
                    val outList = JSONArray()
                    result.forEach { outList.put(it) }
                    grp.put("list", outList)
                    hit = true
                }
                if (hit) file.writeText(arr.toString())
                hit
            }.getOrDefault(false)
        }

    /** 一级分组置顶/置底（整组在分组列表中的顺序） */
    suspend fun moveGroupsToEdge(names: Set<String>, toTop: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val file = TtsConfigStore.voicesFile(ctx)
                val arr = JSONArray(file.readText().removePrefix("\uFEFF"))
                val all = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                fun gName(g: JSONObject) = g.optJSONObject("group")?.optString("name").orEmpty()
                val sel = all.filter { gName(it) in names }
                if (sel.isEmpty()) return@runCatching false
                val rest = all.filterNot { gName(it) in names }
                val ordered = if (toTop) sel + rest else rest + sel
                val out = JSONArray()
                ordered.forEachIndexed { i, g ->
                    g.optJSONObject("group")?.put("order", i)
                    out.put(g)
                }
                file.writeText(out.toString())
                true
            }.getOrDefault(false)
        }

    /** 二级分类置顶/置底（整块在所属分组内的顺序），keys = "组名|分类名" */
    suspend fun moveCategoriesToEdge(catKeys: Set<String>, toTop: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val file = TtsConfigStore.voicesFile(ctx)
                val arr = JSONArray(file.readText().removePrefix("\uFEFF"))
                var hit = false
                for (gi in 0 until arr.length()) {
                    val grp = arr.optJSONObject(gi) ?: continue
                    val gName = grp.optJSONObject("group")?.optString("name").orEmpty()
                    val list = grp.optJSONArray("list") ?: continue
                    val entriesAll = (0 until list.length()).mapNotNull { list.optJSONObject(it) }
                    fun catOf(e: JSONObject) = e.optString("categoryPath").ifBlank { "未分类" }
                    val selCats = entriesAll.map { catOf(it) }.distinct()
                        .filter { "$gName|$it" in catKeys }
                    if (selCats.isEmpty()) continue
                    val selBlocks = selCats.map { cat -> entriesAll.filter { catOf(it) == cat } }
                        .sortedBy { block -> entriesAll.indexOfFirst { it === block.first() } }
                    val selEntries = selBlocks.flatten()
                    val rest = entriesAll.filter { catOf(it) !in selCats }
                    val ordered = if (toTop) selEntries + rest else rest + selEntries
                    val outList = JSONArray()
                    ordered.forEach { outList.put(it) }
                    grp.put("list", outList)
                    hit = true
                }
                if (hit) file.writeText(arr.toString())
                hit
            }.getOrDefault(false)
        }

    // ---------------- 朗读扩展设置（响度均衡） ----------------

    private fun extSettingsFile(): File =
        File(TtsDirProvider.baseDir(ctx), "_store/readaloud_ext.json")

    suspend fun getLoudnessBalance(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val f = extSettingsFile()
            if (!f.exists()) false
            else JSONObject(f.readText().removePrefix("\uFEFF")).optBoolean("loudnessBalance", false)
        }.getOrDefault(false)
    }

    suspend fun setLoudnessBalance(value: Boolean): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val f = extSettingsFile()
            val o = if (f.exists()) {
                runCatching { JSONObject(f.readText().removePrefix("\uFEFF")) }
                    .getOrElse { JSONObject() }
            } else JSONObject()
            o.put("loudnessBalance", value)
            f.parentFile?.mkdirs()
            f.writeText(o.toString())
            true
        }.getOrDefault(false)
    }

    /** 重置响度数据（清除扩展设置响度字段 + 已知响度学习数据文件） */
    suspend fun resetLoudnessData(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val f = extSettingsFile()
            if (f.exists()) {
                val o = runCatching { JSONObject(f.readText().removePrefix("\uFEFF")) }
                    .getOrElse { JSONObject() }
                o.remove("loudnessBalance")
                f.writeText(o.toString())
            }
            listOf("loudness_stats.json", "loudness_learn.json").forEach { name ->
                runCatching {
                    File(TtsDirProvider.baseDir(ctx), name).takeIf { it.exists() }?.delete()
                }
            }
            true
        }.getOrDefault(false)
    }

    // ---------------- 当前声线库（配置列表 · 一级分组选中） ----------------

    /** 当前声线库（多选：多个池同时生效；自动分配只看选中的池，缺标签则走默认声线） */
    suspend fun getActiveVoiceBanks(): List<String> = withContext(Dispatchers.IO) {
        runCatching {
            val f = extSettingsFile()
            if (!f.exists()) return@runCatching emptyList()
            val arr = JSONObject(f.readText().removePrefix("\uFEFF"))
                .optJSONArray("activeVoiceBanks") ?: return@runCatching emptyList()
            buildList {
                for (i in 0 until arr.length()) {
                    val v = arr.optString(i)
                    if (v.isNotBlank()) add(v)
                }
            }
        }.getOrDefault(emptyList())
    }

    suspend fun setActiveVoiceBanks(names: List<String>): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val f = extSettingsFile()
            val o = if (f.exists()) {
                runCatching { JSONObject(f.readText().removePrefix("\uFEFF")) }
                    .getOrElse { JSONObject() }
            } else JSONObject()
            o.remove("activeVoiceBank")
            o.put("activeVoiceBanks", org.json.JSONArray(names.distinct()))
            f.parentFile?.mkdirs()
            f.writeText(o.toString())
            true
        }.getOrDefault(false)
    }
}

data class LocaleOption(val id: String, val name: String)

data class VoiceOption(val id: String, val name: String, val icon: String? = null)
