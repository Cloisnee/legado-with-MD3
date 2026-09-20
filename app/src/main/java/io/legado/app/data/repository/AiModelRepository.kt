package io.legado.app.data.repository

import android.app.Application
import com.github.jing332.compat.fs.TtsDirProvider
import kotlinx.coroutines.Dispatchers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 朗读分析 · AI 模型管理（复刻自研插件「厂商模式模型管理」的存储层）。
 *
 * 结构（_store/ai_models.json）：
 *  providers：[{id,name,baseUrl,apiKey,protocol,enabled}]
 *  models   ：[{id,providerId,name,modelId,enabled,requestAttempts,validateRetries,timeoutMs,disableThinking}]
 *  stages   ：{stage1:[modelId...], stage2:[...], stage4:[...], emotion:[...]}
 *   - requestAttempts：响应尝试次数（超时/HTTP错/非JSON → 算一次），1=只试一次
 *   - validateRetries：内容校验重试次数（JSON合法但字段不符 → 算一次）
 *   - disableThinking：关闭思考（B10.4·A6，默认 true=按协议发送关闭字段；false=不干预）
 */
data class AiProvider(
    val id: String,
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val protocol: String = "openai",
    val enabled: Boolean = true,
)

data class AiModelEntry(
    val id: String,
    val providerId: String,
    val name: String,
    val modelId: String,
    val enabled: Boolean = true,
    val requestAttempts: Int = 2,
    val validateRetries: Int = 2,
    val timeoutMs: Long = 120_000L,
    val disableThinking: Boolean = true,
    val testOk: Boolean? = null,
    val testLatencyMs: Long? = null,
    val testMessage: String? = null,
    val testAt: Long = 0L,
)

data class AiStageAssignments(
    val stage1: List<String> = emptyList(),
    val stage2: List<String> = emptyList(),
    val stage4: List<String> = emptyList(),
    val emotion: List<String> = emptyList(),
)

data class AiModelsConfig(
    val providers: List<AiProvider> = emptyList(),
    val models: List<AiModelEntry> = emptyList(),
    val stages: AiStageAssignments = AiStageAssignments(),
)

class AiModelRepository(private val app: Application) {

    private fun file(): File =
        File(TtsDirProvider.baseDir(app), "_store/ai_models.json")

    suspend fun load(): AiModelsConfig = withContext(Dispatchers.IO) {
        runCatching {
            val f = file()
            if (!f.exists()) return@runCatching AiModelsConfig()
            parse(JSONObject(f.readText().removePrefix("\uFEFF")))
        }.getOrDefault(AiModelsConfig())
    }

    private fun parse(o: JSONObject): AiModelsConfig {
        val providers = buildList {
            val arr = o.optJSONArray("providers") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i) ?: continue
                add(
                    AiProvider(
                        id = p.optString("id"),
                        name = p.optString("name"),
                        baseUrl = p.optString("baseUrl"),
                        apiKey = p.optString("apiKey"),
                        protocol = p.optString("protocol", "openai").ifBlank { "openai" },
                        enabled = p.optBoolean("enabled", true),
                    )
                )
            }
        }
        val models = buildList {
            val arr = o.optJSONArray("models") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val m = arr.optJSONObject(i) ?: continue
                add(
                    AiModelEntry(
                        id = m.optString("id"),
                        providerId = m.optString("providerId"),
                        name = m.optString("name"),
                        modelId = m.optString("modelId"),
                        enabled = m.optBoolean("enabled", true),
                        requestAttempts = m.optInt("requestAttempts", 2).coerceIn(1, 5),
                        validateRetries = m.optInt("validateRetries", 2).coerceIn(0, 5),
                        timeoutMs = m.optLong("timeoutMs", 120_000L).coerceIn(5_000L, 600_000L),
                        disableThinking = m.optBoolean("disableThinking", true),
                        testOk = if (m.has("testOk") && !m.isNull("testOk")) {
                            m.optBoolean("testOk")
                        } else {
                            null
                        },
                        testLatencyMs = if (m.has("testLatencyMs") && !m.isNull("testLatencyMs")) {
                            m.optLong("testLatencyMs")
                        } else {
                            null
                        },
                        testMessage = m.optString("testMessage").takeIf { it.isNotBlank() },
                        testAt = m.optLong("testAt", 0L),
                    )
                )
            }
        }
        val stagesObj = o.optJSONObject("stages") ?: JSONObject()
        fun ids(key: String): List<String> {
            val arr = stagesObj.optJSONArray(key) ?: return emptyList()
            return buildList {
                for (i in 0 until arr.length()) {
                    val v = arr.optString(i)
                    if (v.isNotBlank()) add(v)
                }
            }
        }
        return AiModelsConfig(
            providers = providers,
            models = models,
            stages = AiStageAssignments(
                stage1 = ids("stage1"),
                stage2 = ids("stage2"),
                stage4 = ids("stage4"),
                emotion = ids("emotion"),
            ),
        )
    }

    suspend fun save(cfg: AiModelsConfig): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val o = JSONObject()
            val pArr = JSONArray()
            cfg.providers.forEach { p ->
                pArr.put(JSONObject().apply {
                    put("id", p.id); put("name", p.name); put("baseUrl", p.baseUrl)
                    put("apiKey", p.apiKey); put("protocol", p.protocol); put("enabled", p.enabled)
                })
            }
            o.put("providers", pArr)
            val mArr = JSONArray()
            cfg.models.forEach { m ->
                mArr.put(JSONObject().apply {
                    put("id", m.id); put("providerId", m.providerId); put("name", m.name)
                    put("modelId", m.modelId); put("enabled", m.enabled)
                    put("requestAttempts", m.requestAttempts)
                    put("validateRetries", m.validateRetries)
                    put("timeoutMs", m.timeoutMs)
                    put("disableThinking", m.disableThinking)
                    put("testOk", m.testOk ?: JSONObject.NULL)
                    put("testLatencyMs", m.testLatencyMs ?: JSONObject.NULL)
                    put("testMessage", m.testMessage ?: JSONObject.NULL)
                    put("testAt", m.testAt)
                })
            }
            o.put("models", mArr)
            o.put("stages", JSONObject().apply {
                put("stage1", JSONArray(cfg.stages.stage1))
                put("stage2", JSONArray(cfg.stages.stage2))
                put("stage4", JSONArray(cfg.stages.stage4))
                put("emotion", JSONArray(cfg.stages.emotion))
            })
            val f = file()
            f.parentFile?.mkdirs()
            f.writeText(o.toString())
            true
        }.getOrDefault(false)
    }

    // ---------------- provider / model 增删改 ----------------

    suspend fun upsertProvider(p: AiProvider): String? {
        val cfg = load()
        val id = p.id.ifBlank { "p_${System.currentTimeMillis()}" }
        val newP = p.copy(id = id)
        val list = cfg.providers.toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx >= 0) list[idx] = newP else list.add(newP)
        return if (save(cfg.copy(providers = list))) id else null
    }

    suspend fun deleteProvider(id: String): Boolean {
        val cfg = load()
        val modelIds = cfg.models.filter { it.providerId == id }.map { it.id }.toSet()
        return save(
            cfg.copy(
                providers = cfg.providers.filterNot { it.id == id },
                models = cfg.models.filterNot { it.providerId == id },
                stages = cfg.stages.copy(
                    stage1 = cfg.stages.stage1.filterNot { it in modelIds },
                    stage2 = cfg.stages.stage2.filterNot { it in modelIds },
                    stage4 = cfg.stages.stage4.filterNot { it in modelIds },
                    emotion = cfg.stages.emotion.filterNot { it in modelIds },
                ),
            )
        )
    }

    suspend fun upsertModel(m: AiModelEntry): Boolean {
        val cfg = load()
        val id = m.id.ifBlank { "m_${System.currentTimeMillis()}" }
        val newM = m.copy(id = id)
        val list = cfg.models.toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx >= 0) list[idx] = newM else list.add(newM)
        return save(cfg.copy(models = list))
    }

    suspend fun deleteModel(id: String): Boolean {
        val cfg = load()
        return save(
            cfg.copy(
                models = cfg.models.filterNot { it.id == id },
                stages = cfg.stages.copy(
                    stage1 = cfg.stages.stage1.filterNot { it == id },
                    stage2 = cfg.stages.stage2.filterNot { it == id },
                    stage4 = cfg.stages.stage4.filterNot { it == id },
                    emotion = cfg.stages.emotion.filterNot { it == id },
                ),
            )
        )
    }

    // ---------------- 阶段分配 ----------------

    suspend fun updateStage(stageKey: String, ids: List<String>): Boolean {
        val cfg = load()
        val s = cfg.stages
        val newStages = when (stageKey) {
            "stage1" -> s.copy(stage1 = ids)
            "stage2" -> s.copy(stage2 = ids)
            "stage4" -> s.copy(stage4 = ids)
            "emotion" -> s.copy(emotion = ids)
            else -> return false
        }
        return save(cfg.copy(stages = newStages))
    }

    // ---------------- 厂商模型拉取 / 测试 / 批量与排序 ----------------

    /** 拉取厂商模型列表（openai: GET /models Bearer；google: GET /models?key=；claude: GET /models x-api-key） */
    suspend fun fetchProviderModels(p: AiProvider): Result<List<String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val base = p.baseUrl.trim().trimEnd('/')
                require(base.isNotBlank()) { "BaseUrl 为空" }
                val client = http()
                val request = when (p.protocol.lowercase()) {
                    "google" -> Request.Builder()
                        .url("$base/models?key=${p.apiKey}")
                        .get()
                        .build()

                    "claude" -> Request.Builder()
                        .url("$base/models")
                        .header("x-api-key", p.apiKey)
                        .header("anthropic-version", "2023-06-01")
                        .get()
                        .build()

                    else -> Request.Builder()
                        .url("$base/models")
                        .header("Authorization", "Bearer ${p.apiKey}")
                        .get()
                        .build()
                }
                client.newCall(request).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    require(resp.isSuccessful) { "HTTP ${resp.code}: ${body.take(160)}" }
                    val root = JSONObject(body)
                    val arr = when {
                        root.has("data") -> root.optJSONArray("data")
                        root.has("models") -> root.optJSONArray("models")
                        else -> null
                    } ?: JSONArray()
                    buildList {
                        for (i in 0 until arr.length()) {
                            val o = arr.optJSONObject(i) ?: continue
                            val id = o.optString("id").ifBlank {
                                o.optString("name").removePrefix("models/")
                            }
                            if (id.isNotBlank()) add(id)
                        }
                    }
                }
            }
        }

    /** 单模型连通性测试（最小请求；返回延迟与错误信息） */
    suspend fun testModel(m: AiModelEntry, p: AiProvider): TestResult =
        withContext(Dispatchers.IO) {
            val start = System.currentTimeMillis()
            try {
                val base = p.baseUrl.trim().trimEnd('/')
                require(base.isNotBlank()) { "BaseUrl 为空" }
                val client = http()
                val jsonType = "application/json; charset=utf-8".toMediaType()
                val request = when (p.protocol.lowercase()) {
                    "google" -> Request.Builder()
                        .url("$base/models/${m.modelId}:generateContent?key=${p.apiKey}")
                        .post(
                            """{"contents":[{"parts":[{"text":"ping"}]}]}"""
                                .toRequestBody(jsonType)
                        )
                        .build()

                    "claude" -> Request.Builder()
                        .url("$base/messages")
                        .header("x-api-key", p.apiKey)
                        .header("anthropic-version", "2023-06-01")
                        .post(
                            """{"model":"${m.modelId}","max_tokens":1,"messages":[{"role":"user","content":"ping"}]}"""
                                .toRequestBody(jsonType)
                        )
                        .build()

                    else -> Request.Builder()
                        .url("$base/chat/completions")
                        .header("Authorization", "Bearer ${p.apiKey}")
                        .post(
                            """{"model":"${m.modelId}","messages":[{"role":"user","content":"ping"}],"max_tokens":1}"""
                                .toRequestBody(jsonType)
                        )
                        .build()
                }
                client.newCall(request).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    val latency = System.currentTimeMillis() - start
                    if (resp.isSuccessful) {
                        TestResult(ok = true, latencyMs = latency, message = "")
                    } else {
                        TestResult(
                            ok = false,
                            latencyMs = latency,
                            message = "HTTP ${resp.code}: ${body.take(140)}",
                        )
                    }
                }
            } catch (e: Exception) {
                TestResult(
                    ok = false,
                    latencyMs = System.currentTimeMillis() - start,
                    message = e.localizedMessage ?: e.javaClass.simpleName,
                )
            }
        }

    /** 批量写入厂商模型（已存在则跳过），返回新增数量 */
    suspend fun addModelsFromProvider(providerId: String, names: List<String>): Int {
        val cfg = load()
        val existing =
            cfg.models.filter { it.providerId == providerId }.map { it.modelId }.toSet()
        val toAdd = names.distinct().filterNot { it in existing }
        if (toAdd.isEmpty()) return 0
        val now = System.currentTimeMillis()
        val list = cfg.models + toAdd.mapIndexed { i, name ->
            AiModelEntry(
                id = "m_${now}_$i",
                providerId = providerId,
                name = name,
                modelId = name,
                enabled = false,
            )
        }
        save(cfg.copy(models = list))
        return toAdd.size
    }

    /** 批量启用/停用（对应"选中/未选中"） */
    suspend fun setModelsEnabled(ids: Set<String>, enabled: Boolean): Boolean {
        if (ids.isEmpty()) return false
        val cfg = load()
        return save(
            cfg.copy(
                models = cfg.models.map { if (it.id in ids) it.copy(enabled = enabled) else it }
            )
        )
    }

    /** 厂商置顶/置底 */
    suspend fun moveProvider(id: String, toTop: Boolean): Boolean {
        val cfg = load()
        val list = cfg.providers.toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return false
        val item = list.removeAt(idx)
        if (toTop) list.add(0, item) else list.add(item)
        return save(cfg.copy(providers = list))
    }

    /** 模型在其厂商内 置顶/置底 */
    suspend fun moveModel(id: String, toTop: Boolean): Boolean {
        val cfg = load()
        val target = cfg.models.firstOrNull { it.id == id } ?: return false
        val others = cfg.models.toMutableList()
        val idx = others.indexOfFirst { it.id == id }
        if (idx < 0) return false
        val item = others.removeAt(idx)
        if (toTop) {
            val firstIdx = others.indexOfFirst { it.providerId == target.providerId }
            if (firstIdx >= 0) others.add(firstIdx, item) else others.add(item)
        } else {
            val lastIdx = others.indexOfLast { it.providerId == target.providerId }
            if (lastIdx >= 0) others.add(lastIdx + 1, item) else others.add(item)
        }
        return save(cfg.copy(models = others))
    }

    /** 持久化单模型测试结果（标签/延迟数据跨界面留存） */
    suspend fun updateModelTest(
        id: String,
        ok: Boolean,
        latencyMs: Long,
        message: String,
    ): Boolean {
        val cfg = load()
        return save(
            cfg.copy(
                models = cfg.models.map {
                    if (it.id == id) {
                        it.copy(
                            testOk = ok,
                            testLatencyMs = latencyMs,
                            testMessage = message,
                            testAt = System.currentTimeMillis(),
                        )
                    } else {
                        it
                    }
                }
            )
        )
    }

    internal fun http(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()
}

data class TestResult(val ok: Boolean, val latencyMs: Long, val message: String)