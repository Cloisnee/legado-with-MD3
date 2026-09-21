package io.legado.app.data.repository

import android.app.Application
import android.content.Context
import com.github.jing332.compat.fs.TtsDirProvider
import com.github.jing332.database.entities.systts.source.PluginTtsSource
import com.github.jing332.tts.debug.SynthProbe
import com.github.jing332.tts.speech.plugin.TtsPluginEngineManager
import com.github.jing332.tts.speech.plugin.engine.TtsEngineContext
import com.github.jing332.tts.speech.plugin.engine.TtsPluginUiEngineV2
import com.github.jing332.tts.store.TtsConfigStore
import io.legado.app.data.appDb
import io.legado.app.data.entities.HttpTTS
import io.legado.app.domain.gateway.ReadAloudSettingsGateway
import io.legado.app.domain.model.readaloud.ReadAloudEngineSelection
import io.legado.app.domain.model.readaloud.VoiceBankRoleType
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.isJsonArray
import io.legado.app.utils.isJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
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
    val groupId: Long = 0L,
    val sourceData: Map<String, String> = emptyMap(),
)

data class GroupRow(
    val name: String,
    val entries: List<EntryRow>,
    val groupId: Long = 0L,
    val roleType: String = "",
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
        // 池类型冻结：首次加载把还没有 roleType 的分组推断并落盘（幂等；此后不再随标签漂移）
        ensureRoleTypesFrozen()
        val arr = TtsConfigStore.loadVoices(ctx)
        buildList {
            for (g in 0 until arr.length()) {
                val grp = arr.optJSONObject(g) ?: continue
                val name = grp.optJSONObject("group")?.optString("name") ?: "未命名分组"
                val gid = grp.optJSONObject("group")?.optLong("id") ?: 0L
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
                                groupId = gid,
                                sourceData = src?.optJSONObject("data")?.let { d ->
                                    buildMap {
                                        val ks = d.keys()
                                        while (ks.hasNext()) {
                                            val k = ks.next()
                                            put(k, d.optString(k))
                                        }
                                    }
                                } ?: emptyMap(),
                            )
                        )
                    }
                }
                // 冻结值优先；写盘失败（只读等）时仍按同一口径展示，保证类型不漂移
                val roleType = VoiceBankRoleType.normalize(grp.optJSONObject("group")?.optString("roleType"))
                    .ifEmpty {
                        if (entries.isEmpty()) "" else VoiceBankRoleType.infer(name, entries.map { it.tag })
                    }
                add(GroupRow(name = name, entries = entries, groupId = gid, roleType = roleType))
            }
        }
    }

    /**
     * 池类型冻结（v4-B5）：把还没有 roleType 的分组，按「组名 → 标签前缀 → 核心」推断一次并**落盘**。
     *
     * 幂等且只写一次：已有合法 roleType 的分组绝不改写 —— 即「第一次加入声线的类型固定下来，
     * 后续不慎加入其它类型的声线不影响初始 type」。空分组（无条目）不冻结。
     * 返回本次新冻结的分组数。
     */
    suspend fun ensureRoleTypesFrozen(): Int = withContext(Dispatchers.IO) {
        runCatching {
            val file = TtsConfigStore.voicesFile(ctx)
            if (!file.exists()) return@runCatching 0
            val arr = JSONArray(file.readText().removePrefix("\uFEFF"))
            var frozen = 0
            for (i in 0 until arr.length()) {
                val grp = arr.optJSONObject(i) ?: continue
                val gObj = grp.optJSONObject("group") ?: continue
                if (VoiceBankRoleType.normalize(gObj.optString("roleType")).isNotBlank()) continue
                val tags = ArrayList<String>()
                val list = grp.optJSONArray("list") ?: continue
                for (j in 0 until list.length()) {
                    val tag = list.optJSONObject(j)?.optJSONObject("config")
                        ?.optJSONObject("speechRule")?.optString("tag").orEmpty().trim()
                    if (tag.isNotEmpty()) tags.add(tag)
                }
                if (tags.isEmpty()) continue
                gObj.put("roleType", VoiceBankRoleType.infer(gObj.optString("name"), tags))
                frozen++
            }
            if (frozen > 0) file.writeText(arr.toString())
            frozen
        }.getOrDefault(0)
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

    // ---------------- 引擎（在线朗读 HttpTTS）管理 ----------------

    suspend fun loadHttpTtsList(): List<HttpTTS> = withContext(Dispatchers.IO) {
        runCatching { appDb.httpTTSDao.all }.getOrDefault(emptyList())
    }

    suspend fun saveHttpTts(source: HttpTTS): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            appDb.httpTTSDao.insert(source)
            true
        }.getOrDefault(false)
    }

    suspend fun deleteHttpTts(id: Long): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            appDb.httpTTSDao.get(id)?.let { appDb.httpTTSDao.delete(it) }
            true
        }.getOrDefault(false)
    }

    suspend fun exportHttpTtsJson(): String = withContext(Dispatchers.IO) {
        runCatching { GSON.toJson(appDb.httpTTSDao.all) }.getOrDefault("[]")
    }

    /** 导入在线朗读引擎（支持 URL / JSON 对象 / JSON 数组 / Base64；返回结果描述） */
    suspend fun importHttpTts(text: String): String = withContext(Dispatchers.IO) {
        runCatching {
            val list = parseHttpTts(text.trim())
            require(list.isNotEmpty()) { "未解析到任何引擎" }
            list.forEach { appDb.httpTTSDao.insert(it) }
            "导入成功：${list.size} 个引擎"
        }.getOrElse { "导入失败：${it.localizedMessage ?: "格式错误"}" }
    }

    private fun parseHttpTts(text: String): List<HttpTTS> = when {
        text.startsWith("http", ignoreCase = true) -> {
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
            val request = Request.Builder().url(text).get().build()
            val body = client.newCall(request).execute().use { it.body?.string().orEmpty() }
            require(body.isNotBlank()) { "响应为空" }
            parseHttpTts(body)
        }

        text.isJsonObject() -> listOf(HttpTTS.fromJson(text).getOrThrow())
        text.isJsonArray() -> HttpTTS.fromJsonArray(text).getOrThrow()
        else -> {
            val decoded = runCatching {
                String(android.util.Base64.decode(text, android.util.Base64.DEFAULT))
            }.getOrNull() ?: throw IllegalArgumentException("无法识别的格式")
            parseHttpTts(decoded)
        }
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
            gateway().update { it.copy(ttsEngine = value ?: "") }
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
            // 导入的列表同样「按类型固定」：新增/既有但缺 roleType 的分组按组名→标签前缀推断并落盘
            val frozen = ensureRoleTypesFrozen()
            "导入配置列表完成：新增条目=$ae 覆盖条目=$re 新增分组=$ag 池类型冻结=$frozen"
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
        speed: Float = 1f, volume: Float = 1f, pitch: Float = 1f,
        data: Map<String, String> = emptyMap(),
    ): AuditionOutcome = withContext(Dispatchers.IO) {
        val r = runCatching { SynthProbe.runDirect(ctx, pluginId, locale, voice, text, speed, volume, pitch, data) }
            .getOrElse {
                return@withContext AuditionOutcome(
                    false, null, "SynthProbe 异常: ${it.stackTraceToString()}"
                )
            }
        runCatching {
            val dir = File(TtsDirProvider.baseDir(ctx), "_audition").apply { mkdirs() }
            File(dir, "last_synth_result.txt")
                .writeText("[${ts()}] plugin=$pluginId locale=$locale voice=$voice data=$data\n${r.report}\n")
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

    /** 新条目批量创建：按所选插件声音生成 标签01..N；写入 source.data / audioParams / audioFormat；组含 roleType */
    suspend fun createEntriesFromPlugin(
        groupName: String,
        roleType: String,
        gender: String,
        age: String,
        speed: Float,
        volume: Float,
        pitch: Float,
        sampleRate: Int,
        locale: String,
        pluginId: String,
        pluginVoiceIds: List<String>,
        pluginVoiceNames: List<String>,
        dataParams: Map<String, String>,
        categoryPath: String = "",
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        runCatching {
            if (groupName.isBlank()) return@runCatching false to "分组名不能为空"
            if (pluginId.isBlank() || pluginVoiceIds.isEmpty()) return@runCatching false to "请选择插件与声音"
            val file = TtsConfigStore.voicesFile(ctx)
            val arr = if (file.exists()) JSONArray(file.readText().removePrefix("\uFEFF")) else JSONArray()
            var grp: JSONObject? = null
            for (i in 0 until arr.length()) {
                val g = arr.optJSONObject(i) ?: continue
                if (g.optJSONObject("group")?.optString("name") == groupName) {
                    grp = g
                    break
                }
            }
            val gObj: JSONObject
            var inherited = false
            if (grp == null) {
                gObj = JSONObject().apply {
                    put("id", System.currentTimeMillis())
                    put("name", groupName)
                    put("order", arr.length())
                    put("roleType", roleType)
                }
                grp = JSONObject().apply {
                    put("group", gObj)
                    put("list", JSONArray())
                }
                arr.put(grp)
            } else {
                gObj = grp.optJSONObject("group")
                    ?: JSONObject().apply {
                        put("id", 0L)
                        put("name", groupName)
                    }
                // 池类型冻结（v4-B5）：分组已有合法类型 → 绝不覆盖，新条目沿用分组类型
                val frozen = VoiceBankRoleType.normalize(gObj.optString("roleType"))
                if (frozen.isBlank()) {
                    // 尚未冻结：按组内已有条目的标签推断（=「第一个加入的声线」）；组内无条目才用弹窗所选
                    val existingTags = ArrayList<String>()
                    grp.optJSONArray("list")?.let { l ->
                        for (k in 0 until l.length()) {
                            val t = l.optJSONObject(k)?.optJSONObject("config")
                                ?.optJSONObject("speechRule")?.optString("tag").orEmpty().trim()
                            if (t.isNotEmpty()) existingTags.add(t)
                        }
                    }
                    gObj.put(
                        "roleType",
                        if (existingTags.isEmpty()) roleType
                        else VoiceBankRoleType.infer(groupName, existingTags),
                    )
                } else {
                    inherited = frozen != VoiceBankRoleType.normalize(roleType)
                }
            }
            val gid = gObj.optLong("id")
            val list = grp.optJSONArray("list") ?: JSONArray().also { grp.put("list", it) }
            // 生效类型/年龄一律按分组冻结值归一（防止"特殊↔非特殊"混用时生成 `系统01` 之类错标签）
            val effRole = VoiceBankRoleType.normalize(gObj.optString("roleType")).ifBlank { roleType }
            val effAge = when {
                effRole == VoiceBankRoleType.SPECIAL -> "系统"
                age.isBlank() || age == "系统" -> if (gender == "女") "女青年" else "男青年"
                else -> age
            }
            val prefix = voicePrefixOf(effRole, gender, effAge)
            val baseId = System.currentTimeMillis()
            var idx = 0
            pluginVoiceIds.forEach { vid ->
                val tag = prefix + String.format("%02d", idx + 1)
                val display = pluginVoiceNames.getOrNull(idx)
                    ?.takeIf { it.isNotBlank() } ?: vid
                val dataJson = JSONObject().apply { dataParams.forEach { (k, v) -> put(k, v) } }
                list.put(
                    JSONObject().apply {
                        put("id", baseId + idx)
                        put("displayName", display)
                        put("groupId", gid)
                        put("categoryPath", categoryPath)
                        put(
                            "config",
                            JSONObject().apply {
                                put("#type", "tts")
                                put(
                                    "speechRule",
                                    JSONObject().apply {
                                        put("target", 0)
                                        put("tag", tag)
                                        put("tagRuleId", "local")
                                        put("tagName", "")
                                    }
                                )
                                put("audioFormat", JSONObject().put("sampleRate", sampleRate))
                                put(
                                    "audioParams",
                                    JSONObject().apply {
                                        put("speed", speed)
                                        put("volume", volume)
                                        put("pitch", pitch)
                                    }
                                )
                                put(
                                    "source",
                                    JSONObject().apply {
                                        put("#type", "plugin")
                                        put("locale", locale)
                                        put("voice", vid)
                                        put("pluginId", pluginId)
                                        put("data", JSONObject(dataJson.toString()))
                                    }
                                )
                            }
                        )
                    }
                )
                idx++
            }
            file.parentFile?.mkdirs()
            file.writeText(arr.toString())
            val note = if (inherited) "（沿用分组固定类型「$effRole」）" else ""
            true to "已生成 ${pluginVoiceIds.size} 条：${prefix}01…${String.format("%02d", pluginVoiceIds.size)}$note"
        }.getOrElse { false to "创建失败：${it.message ?: it.javaClass.simpleName}" }
    }

    private fun voicePrefixOf(roleType: String, gender: String, age: String): String =
        VoiceBankRoleType.tagPrefix(roleType, gender, age)

    /**
     * 编辑单条条目：按 (groupId, entryId) 精确定位（entryId=0 时退化为组内 tag 匹配）。
     * newGroupId 与当前分组不同 → 该条目迁移至目标分组（末尾追加），原分组变空则自动清理。
     * tagRuleId/tagName 保持原值不动（编辑界面已去掉这两个字段）。
     */
    suspend fun updateVoiceEntry(
        groupId: Long, entryId: Long, tag: String,
        newName: String, newTag: String, newCategoryPath: String, newGroupId: Long,
        speed: Float, volume: Float, pitch: Float,
        newData: Map<String, String>? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val file = TtsConfigStore.voicesFile(ctx)
            val arr = JSONArray(file.readText().removePrefix("\uFEFF"))
            var hitG = -1
            var hitI = -1
            outer@ for (gi in 0 until arr.length()) {
                val grp = arr.optJSONObject(gi) ?: continue
                val gid = grp.optJSONObject("group")?.optLong("id") ?: 0L
                if (groupId != 0L && gid != groupId) continue
                val list = grp.optJSONArray("list") ?: continue
                for (i in 0 until list.length()) {
                    val e = list.optJSONObject(i) ?: continue
                    val sr = e.optJSONObject("config")?.optJSONObject("speechRule")
                    val byId = entryId != 0L && e.optLong("id") == entryId
                    val byTag = entryId == 0L && sr?.optString("tag") == tag
                    if (byId || byTag) {
                        hitG = gi
                        hitI = i
                        break@outer
                    }
                }
            }
            if (hitG < 0 || hitI < 0) return@runCatching false
            val entry = arr.optJSONObject(hitG)?.optJSONArray("list")?.optJSONObject(hitI)
                ?: return@runCatching false
            val cfg = entry.optJSONObject("config") ?: return@runCatching false

            entry.put("displayName", newName)
            entry.put("categoryPath", newCategoryPath)
            val sr = cfg.optJSONObject("speechRule")
                ?: JSONObject().also { cfg.put("speechRule", it) }
            sr.put("tag", newTag)
            val ap = cfg.optJSONObject("audioParams")
                ?: JSONObject().also { cfg.put("audioParams", it) }
            ap.put("speed", speed)
            ap.put("volume", volume)
            ap.put("pitch", pitch)
            if (newData != null) {
                val srcObj = cfg.optJSONObject("source")
                    ?: JSONObject().also { cfg.put("source", it) }
                srcObj.put(
                    "data",
                    JSONObject().apply { newData.forEach { (k, v) -> put(k, v) } },
                )
            }

            if (newGroupId != 0L && newGroupId != groupId) {
                // 迁移分组：从原组摘除 → 追加到目标组
                val oldItem = arr.optJSONObject(hitG)
                val oldList = oldItem?.optJSONArray("list")
                if (oldList != null) {
                    oldList.remove(hitI)
                    var target: JSONObject? = null
                    for (gi in 0 until arr.length()) {
                        val item = arr.optJSONObject(gi) ?: continue
                        if (item.optJSONObject("group")?.optLong("id") == newGroupId) {
                            target = item
                            break
                        }
                    }
                    if (target == null) return@runCatching false
                    entry.put("groupId", newGroupId)
                    val targetList = target.optJSONArray("list")
                        ?: JSONArray().also { target.put("list", it) }
                    targetList.put(entry)
                    // 原分组变空 → 清理（避免空组残留）
                    if (oldList.length() == 0) arr.remove(hitG)
                }
            }
            file.writeText(arr.toString())
            true
        }.getOrDefault(false)
    }

    // ---------------- 分组操作 ----------------

    /**
     * 重置分组池类型：清掉已冻结的 roleType，再按同一口径（组名 → 首个标签前缀 → 核心）重新推断。
     * 仅供「误固定」后的人工纠正；正常流程不会改已冻结的类型。
     */
    suspend fun resetGroupRoleType(groupId: Long): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val file = TtsConfigStore.voicesFile(ctx)
            if (!file.exists()) return@runCatching false
            val arr = JSONArray(file.readText().removePrefix("\uFEFF"))
            var hit = false
            for (i in 0 until arr.length()) {
                val g = arr.optJSONObject(i)?.optJSONObject("group") ?: continue
                if (g.optLong("id") == groupId) {
                    g.remove("roleType")
                    hit = true
                }
            }
            if (hit) file.writeText(arr.toString())
            if (hit) ensureRoleTypesFrozen()
            hit
        }.getOrDefault(false)
    }

    suspend fun renameGroup(groupId: Long, newName: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val file = TtsConfigStore.voicesFile(ctx)
                val arr = JSONArray(file.readText().removePrefix("\uFEFF"))
                var hit = false
                for (i in 0 until arr.length()) {
                    val g = arr.optJSONObject(i)?.optJSONObject("group") ?: continue
                    if (g.optLong("id") == groupId) {
                        g.put("name", newName)
                        hit = true
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

    /** 导出分组（多选合并为一份文件；数组含多个分组对象，可回导） */
    suspend fun exportGroups(groupIds: Set<Long>): String = withContext(Dispatchers.IO) {
        runCatching {
            if (groupIds.isEmpty()) return@runCatching "未选中分组"
            val arr = TtsConfigStore.loadVoices(ctx)
            val out = JSONArray()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                if (item.optJSONObject("group")?.optLong("id") in groupIds) out.put(item)
            }
            if (out.length() == 0) return@runCatching "未找到分组"
            val f = File(exportsDir(), "voices_group_${ts()}.json")
            f.writeText(out.toString())
            f.absolutePath
        }.getOrDefault("导出失败")
    }

    /** 确保分组存在（不存在则新建空组，返回其 id；roleType 为冻结值，空串表示待推断） */
    suspend fun ensureVoiceGroup(name: String, roleType: String): Long = withContext(Dispatchers.IO) {
        val n = name.trim()
        if (n.isEmpty()) return@withContext 0L
        runCatching {
            val file = TtsConfigStore.voicesFile(ctx)
            val arr = if (file.exists()) {
                JSONArray(file.readText().removePrefix("\uFEFF"))
            } else {
                JSONArray()
            }
            for (i in 0 until arr.length()) {
                val grp = arr.optJSONObject(i) ?: continue
                val gObj = grp.optJSONObject("group") ?: continue
                if (gObj.optString("name") == n) return@runCatching gObj.optLong("id")
            }
            val id = System.currentTimeMillis()
            arr.put(
                JSONObject().apply {
                    put(
                        "group",
                        JSONObject().apply {
                            put("id", id)
                            put("name", n)
                            put("order", arr.length())
                            put("roleType", VoiceBankRoleType.normalize(roleType))
                        },
                    )
                    put("list", JSONArray())
                },
            )
            file.parentFile?.mkdirs()
            file.writeText(arr.toString())
            id
        }.getOrDefault(0L)
    }

    /**
     * 移动条目到目标分组（可同时改二级分组）：从原组摘除 → 追加到目标组末尾；
     * 目标组不存在则新建（roleType 按组名+条目标签推断）；被移空的原组自动清理。
     * keys 支持 g{gid}_e{id} / e_{id} / k_{ruleId}|{tag} 三种形态（同列表 key 口径）。
     */
    suspend fun moveEntriesToGroup(
        keys: Set<String>,
        targetGroupName: String,
        categoryPath: String,
    ): Pair<Int, String> = withContext(Dispatchers.IO) {
        val name = targetGroupName.trim()
        if (name.isEmpty()) return@withContext 0 to "目标分组为空"
        if (keys.isEmpty()) return@withContext 0 to "未选中条目"
        runCatching {
            val file = TtsConfigStore.voicesFile(ctx)
            val arr = JSONArray(file.readText().removePrefix("\uFEFF"))
            val moved = ArrayList<JSONObject>()
            val touched = HashSet<Long>()
            for (gi in 0 until arr.length()) {
                val grp = arr.optJSONObject(gi) ?: continue
                val gid = grp.optJSONObject("group")?.optLong("id") ?: 0L
                val list = grp.optJSONArray("list") ?: continue
                val kept = JSONArray()
                for (i in 0 until list.length()) {
                    val e = list.optJSONObject(i) ?: continue
                    val sr = e.optJSONObject("config")?.optJSONObject("speechRule")
                    val key = when {
                        e.optLong("groupId") != 0L && e.optLong("id") != 0L ->
                            "g${e.optLong("groupId")}_e${e.optLong("id")}"
                        e.optLong("id") != 0L -> "e_${e.optLong("id")}"
                        else -> "k_${sr?.optString("tagRuleId").orEmpty()}|${sr?.optString("tag").orEmpty()}"
                    }
                    if (key in keys) {
                        moved.add(JSONObject(e.toString()))
                        touched.add(gid)
                    } else {
                        kept.put(e)
                    }
                }
                grp.put("list", kept)
            }
            if (moved.isEmpty()) return@runCatching 0 to "未找到可移动的条目"
            // 找/建目标组
            var target: JSONObject? = null
            var targetId = 0L
            for (gi in 0 until arr.length()) {
                val grp = arr.optJSONObject(gi) ?: continue
                val gObj = grp.optJSONObject("group") ?: continue
                if (gObj.optString("name") == name) {
                    target = grp
                    targetId = gObj.optLong("id")
                    break
                }
            }
            val tGrp: JSONObject
            if (target == null) {
                val tags = moved.mapNotNull {
                    it.optJSONObject("config")?.optJSONObject("speechRule")
                        ?.optString("tag")?.takeIf { t -> t.isNotBlank() }
                }
                targetId = System.currentTimeMillis()
                tGrp = JSONObject().apply {
                    put(
                        "group",
                        JSONObject().apply {
                            put("id", targetId)
                            put("name", name)
                            put("order", arr.length())
                            put("roleType", VoiceBankRoleType.infer(name, tags))
                        },
                    )
                    put("list", JSONArray())
                }
                arr.put(tGrp)
            } else {
                tGrp = target
            }
            val tList = tGrp.optJSONArray("list") ?: JSONArray().also { tGrp.put("list", it) }
            moved.forEach { e ->
                e.put("groupId", targetId)
                e.put("categoryPath", categoryPath)
                tList.put(e)
            }
            // 仅清理「被移空」的原组（其它空组不动）
            val pruned = JSONArray()
            for (gi in 0 until arr.length()) {
                val grp = arr.optJSONObject(gi) ?: continue
                val gid = grp.optJSONObject("group")?.optLong("id") ?: 0L
                val list = grp.optJSONArray("list")
                if (gid in touched && (list == null || list.length() == 0)) continue
                pruned.put(grp)
            }
            file.parentFile?.mkdirs()
            file.writeText(pruned.toString())
            moved.size to "已移动 ${moved.size} 条到「$name」"
        }.getOrElse { 0 to "移动失败：${it.message ?: it.javaClass.simpleName}" }
    }

    /** 精确试听：按 (groupId, entryId) 定位条目 → 直连其插件/声音/参数合成（避免同 tag 多条串声） */
    suspend fun auditionByEntry(groupId: Long, entryId: Long, text: String): AuditionOutcome =
        withContext(Dispatchers.IO) {
            val entry: JSONObject? = runCatching {
                val arr = TtsConfigStore.loadVoices(ctx)
                var found: JSONObject? = null
                loop@ for (g in 0 until arr.length()) {
                    val grp = arr.optJSONObject(g) ?: continue
                    val gid = grp.optJSONObject("group")?.optLong("id") ?: 0L
                    if (groupId != 0L && gid != groupId) continue
                    val list = grp.optJSONArray("list") ?: continue
                    for (i in 0 until list.length()) {
                        val e = list.optJSONObject(i) ?: continue
                        if (e.optLong("id") == entryId) {
                            found = e
                            break@loop
                        }
                    }
                }
                found
            }.getOrNull()
            if (entry == null) return@withContext AuditionOutcome(false, null, "未找到条目 id=$entryId")
            val cfg = entry.optJSONObject("config")
                ?: return@withContext AuditionOutcome(false, null, "条目配置缺失")
            val src = cfg.optJSONObject("source")
                ?: return@withContext AuditionOutcome(false, null, "条目 source 缺失")
            val ap = cfg.optJSONObject("audioParams")
            val speed = (ap?.opt("speed") as? Number)?.toFloat() ?: 1f
            val volume = (ap?.opt("volume") as? Number)?.toFloat() ?: 1f
            val pitch = (ap?.opt("pitch") as? Number)?.toFloat() ?: 1f
            val sr = cfg.optJSONObject("audioFormat")?.optInt("sampleRate", 24000) ?: 24000
            val data = src.optJSONObject("data")?.let { d ->
                buildMap {
                    val ks = d.keys()
                    while (ks.hasNext()) {
                        val k = ks.next()
                        put(k, d.optString(k))
                    }
                }
            } ?: emptyMap()
            val r = runCatching {
                SynthProbe.runDirect(
                    ctx,
                    src.optString("pluginId"),
                    src.optString("locale"),
                    src.optString("voice"),
                    text,
                    speed,
                    volume,
                    pitch,
                    data,
                    sr,
                )
            }.getOrElse {
                return@withContext AuditionOutcome(false, null, "SynthProbe 异常: ${it.stackTraceToString()}")
            }
            runCatching {
                val dir = File(TtsDirProvider.baseDir(ctx), "_audition").apply { mkdirs() }
                File(dir, "last_synth_result.txt")
                    .writeText("[${ts()}] entry=$entryId data=$data\n${r.report}\n")
            }
            val bytes = r.bytes ?: return@withContext AuditionOutcome(false, null, r.report)
            runCatching {
                val dir = File(TtsDirProvider.baseDir(ctx), "_audition").apply { mkdirs() }
                val ext = when {
                    bytes.size >= 4 && bytes[0] == 'R'.code.toByte() -> "wav"
                    bytes.size >= 2 && bytes[0] == 0xFF.toByte() -> "mp3"
                    else -> "bin"
                }
                val f = File(dir, "audition_entry_${entryId}_${System.currentTimeMillis()}.$ext")
                f.writeBytes(bytes)
                AuditionOutcome(
                    true, f.absolutePath,
                    "合成成功：${f.absolutePath}\n大小=${bytes.size} bytes\n\n${r.report}"
                )
            }.getOrElse { AuditionOutcome(false, null, "写文件失败: ${it.stackTraceToString()}") }
        }


    // ---------------- 插件：壳字段 / 变量 / UI 会话 ----------------

    /** 读取插件壳（原始 JSONObject 副本） */
    suspend fun loadPluginShell(pluginId: String): JSONObject? = withContext(Dispatchers.IO) {
        runCatching {
            val raw = TtsConfigStore.pluginById(ctx, pluginId) ?: return@runCatching null
            JSONObject(raw.toString())
        }.getOrNull()
    }

    /** 更新插件壳字段（元数据）：name/author/iconUrl/description/pluginGroupName 等 */
    suspend fun updatePluginShell(pluginId: String, fields: Map<String, String>): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val file = TtsConfigStore.pluginsFile(ctx)
                val arr = JSONArray(file.readText().removePrefix("\uFEFF"))
                var hit = false
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("pluginId").ifBlank { o.optString("id") }
                    if (id != pluginId) continue
                    fields.forEach { (k, v) -> o.put(k, v) }
                    hit = true
                    break
                }
                if (hit) file.writeText(arr.toString())
                hit
            }.getOrDefault(false)
        }

    /** 建立插件 UI 会话（补丁版添加插件TTS同款）：eval → source=seed/临时源 → onLoadData →（主线程调用方）onLoadUI */
    suspend fun createPluginUiSession(
        pluginId: String,
        seed: com.github.jing332.database.entities.systts.source.PluginTtsSource? = null,
    ): Pair<TtsPluginUiEngineV2, com.github.jing332.database.entities.systts.source.PluginTtsSource>? =
        withContext(Dispatchers.IO) {
            runCatching {
                val shell = TtsConfigStore.pluginById(ctx, pluginId) ?: return@runCatching null
                val engine = TtsPluginUiEngineV2(ctx, TtsConfigStore.toEnginePlugin(shell)).apply { eval() }
                val source = seed
                    ?: com.github.jing332.database.entities.systts.source.PluginTtsSource(
                        pluginId = pluginId,
                    )
                engine.source = source
                engine.onLoadData()
                engine to source
            }.getOrNull()
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
                val gid = grp.optJSONObject("group")?.optLong("id") ?: 0L
                val list = grp.optJSONArray("list") ?: continue
                val kept = JSONArray()
                for (i in 0 until list.length()) {
                    val e = list.optJSONObject(i) ?: continue
                    val eid = e.optLong("id")
                    val key = when {
                        gid != 0L && eid != 0L -> "g${gid}_e$eid"
                        eid != 0L -> "e_$eid"
                        else -> {
                            val sr = e.optJSONObject("config")?.optJSONObject("speechRule")
                            "k_${sr?.optString("tagRuleId").orEmpty()}|${sr?.optString("tag").orEmpty()}"
                        }
                    }
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
                    val gid = grp.optJSONObject("group")?.optLong("id") ?: 0L
                    val list = grp.optJSONArray("list") ?: continue
                    val entriesAll = (0 until list.length()).mapNotNull { list.optJSONObject(it) }
                    fun keyOf(e: JSONObject): String {
                        val eid = e.optLong("id")
                        return when {
                            gid != 0L && eid != 0L -> "g${gid}_e$eid"
                            eid != 0L -> "e_$eid"
                            else -> {
                                val sr = e.optJSONObject("config")?.optJSONObject("speechRule")
                                "k_${sr?.optString("tagRuleId").orEmpty()}|${sr?.optString("tag").orEmpty()}"
                            }
                        }
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

    /** 一级分组置顶/置底（整组在分组列表中的顺序；ids=group.id 集合） */
    suspend fun moveGroupsToEdge(ids: Set<Long>, toTop: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val file = TtsConfigStore.voicesFile(ctx)
                val arr = JSONArray(file.readText().removePrefix("\uFEFF"))
                val all = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                fun gId(g: JSONObject) = g.optJSONObject("group")?.optLong("id") ?: 0L
                val sel = all.filter { gId(it) in ids }
                if (sel.isEmpty()) return@runCatching false
                val rest = all.filterNot { gId(it) in ids }
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

    /** 二级分类置顶/置底（整块在所属分组内的顺序），keys = "组ID|分类名" */
    suspend fun moveCategoriesToEdge(catKeys: Set<String>, toTop: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val file = TtsConfigStore.voicesFile(ctx)
                val arr = JSONArray(file.readText().removePrefix("\uFEFF"))
                var hit = false
                for (gi in 0 until arr.length()) {
                    val grp = arr.optJSONObject(gi) ?: continue
                    val gid = grp.optJSONObject("group")?.optLong("id") ?: 0L
                    val list = grp.optJSONArray("list") ?: continue
                    val entriesAll = (0 until list.length()).mapNotNull { list.optJSONObject(it) }
                    fun catOf(e: JSONObject) = e.optString("categoryPath").ifBlank { "未分类" }
                    val selCats = entriesAll.map { catOf(it) }.distinct()
                        .filter { "$gid|$it" in catKeys }
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

    /** 同步读（播放服务路径用；文件极小） */
    fun readLoudnessBalanceNow(): Boolean = runCatching {
        val f = extSettingsFile()
        if (!f.exists()) false
        else JSONObject(f.readText().removePrefix("\uFEFF")).optBoolean("loudnessBalance", false)
    }.getOrDefault(false)

    suspend fun getLoudnessBalance(): Boolean = withContext(Dispatchers.IO) {
        readLoudnessBalanceNow()
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
