package io.legado.app.data.repository

import android.app.Application
import com.github.jing332.compat.fs.TtsDirProvider
import io.legado.app.help.http.await
import io.legado.app.help.http.okHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * B33 · 音频合成（AI 补缺）平台配置：`_store/audio_synth.json`
 *
 * 用途：本地库命中失败时，按轨道（音效/环境/BGM）挑选启用的平台合成补缺（B33.3 接入生成管线）。
 * 本类只负责：配置读写 + 「测试连接」鉴权探测（不产生生成费用）。
 *
 * 平台（2026-09-30 实探口径）：
 *  - stepfun    阶跃星辰 StepAudio：Gen（音效/环境，限时免费）+ Music（BGM，限时免费）
 *  - senseaudio SenseAudio（商汤）：SFX 0.08 元/组（1~4 条）+ Music 0.5 元/首
 *  - elevenlabs ElevenLabs：SFX 50 次/月（海外备选）
 */
class AudioSynthConfigRepository(private val app: Application) {

    data class ProviderConfig(
        val enabled: Boolean = false,
        val apiKey: String = "",
        /** 音效/环境模型（ElevenLabs 为音效模型） */
        val modelA: String = "",
        /** BGM 模型（可为空：该平台不用于 BGM） */
        val modelB: String = "",
        /** 参与轨道：sfx / amb / bgm */
        val lanes: Set<String> = emptySet(),
    )

    data class Config(
        val providers: Map<String, ProviderConfig> = emptyMap(),
    ) {
        fun provider(id: String): ProviderConfig =
            providers[id] ?: defaultConfig().providers[id] ?: ProviderConfig()
    }

    data class ProviderDef(
        val id: String,
        val name: String,
        val baseUrl: String,
        val defaultModelA: String,
        val defaultModelB: String,
        val defaultLanes: Set<String>,
        val note: String,
    )

    private fun file(): File =
        File(File(TtsDirProvider.baseDir(app), "_store"), "audio_synth.json")

    suspend fun load(): Config = withContext(Dispatchers.IO) {
        runCatching {
            val f = file()
            if (!f.exists()) return@runCatching defaultConfig()
            parse(JSONObject(f.readText().removePrefix("\uFEFF")))
        }.getOrDefault(defaultConfig())
    }

    suspend fun save(cfg: Config): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val f = file()
            f.parentFile?.mkdirs()
            val providers = JSONObject()
            for (def in PROVIDERS) {
                val p = cfg.providers[def.id] ?: defaultProvider(def)
                providers.put(
                    def.id,
                    JSONObject().apply {
                        put("enabled", p.enabled)
                        put("apiKey", p.apiKey)
                        put("modelA", p.modelA)
                        put("modelB", p.modelB)
                        put("lanes", JSONArray(p.lanes.toList()))
                    },
                )
            }
            val obj = JSONObject().apply {
                put("version", 1)
                put("providers", providers)
            }
            f.writeText(obj.toString())
            true
        }.getOrDefault(false)
    }

    suspend fun update(transform: (Config) -> Config): Config = withContext(Dispatchers.IO) {
        val next = transform(load())
        save(next)
        next
    }

    private fun parse(o: JSONObject): Config {
        val providers = HashMap<String, ProviderConfig>()
        val arr = o.optJSONObject("providers") ?: JSONObject()
        for (def in PROVIDERS) {
            val p = arr.optJSONObject(def.id)
            providers[def.id] = if (p == null) {
                defaultProvider(def)
            } else {
                val lanes = buildSet {
                    val la = p.optJSONArray("lanes") ?: JSONArray()
                    for (i in 0 until la.length()) {
                        val v = la.optString(i)
                        if (v == LANE_SFX || v == LANE_AMB || v == LANE_BGM) add(v)
                    }
                }
                ProviderConfig(
                    enabled = p.optBoolean("enabled", false),
                    apiKey = p.optString("apiKey"),
                    modelA = p.optString("modelA", def.defaultModelA).ifBlank { def.defaultModelA },
                    modelB = if (def.defaultModelB.isBlank()) {
                        ""
                    } else {
                        p.optString("modelB", def.defaultModelB).ifBlank { def.defaultModelB }
                    },
                    lanes = lanes,
                )
            }
        }
        return Config(providers)
    }

    /**
     * 「测试连接」鉴权探测（不产生生成费用）：
     *  - HTTP 401 / 响应含 authentication_error|invalid_api_key|unauthorized → Key 无效；
     *  - 其余任何响应（含 400/404 等参数类错误）→ 视为连通（Key 探测通过）。
     */
    suspend fun testConnection(providerId: String, cfg: ProviderConfig): String =
        withContext(Dispatchers.IO) {
            val key = cfg.apiKey.trim()
            if (key.isEmpty()) return@withContext "未填写 API Key"
            val def = PROVIDERS.firstOrNull { it.id == providerId }
                ?: return@withContext "未知平台"
            runCatching {
                when (providerId) {
                    ID_STEPFUN -> {
                        // 查询一个不存在的任务：不会创建任务、不产生费用
                        val req = Request.Builder()
                            .url("${def.baseUrl}/v1/audio/music/query")
                            .addHeader("Authorization", "Bearer $key")
                            .post("{\"task_id\":\"probe-not-exist\"}".toRequestBody(JSON_TYPE))
                            .build()
                        val resp = okHttpClient.newCall(req).await()
                        classify(resp.code, resp.body?.string().orEmpty())
                    }

                    ID_SENSEAUDIO -> {
                        val req = Request.Builder()
                            .url("${def.baseUrl}/v1/sound-effects/generations")
                            .addHeader("Authorization", "Bearer $key")
                            .post("{}".toRequestBody(JSON_TYPE))
                            .build()
                        val resp = okHttpClient.newCall(req).await()
                        classify(resp.code, resp.body?.string().orEmpty())
                    }

                    ID_ELEVENLABS -> {
                        val req = Request.Builder()
                            .url("${def.baseUrl}/v1/user")
                            .addHeader("xi-api-key", key)
                            .get()
                            .build()
                        val resp = okHttpClient.newCall(req).await()
                        classify(resp.code, resp.body?.string().orEmpty())
                    }

                    else -> "未知平台"
                }
            }.getOrElse { "连接失败：${it.localizedMessage ?: it.javaClass.simpleName}" }
        }

    private fun classify(code: Int, body: String): String {
        val lower = body.lowercase()
        return when {
            code == 401 -> "Key 无效（HTTP 401）"
            lower.contains("authentication_error") ||
                lower.contains("invalid_api_key") ||
                lower.contains("unauthorized") -> "Key 无效（HTTP $code）"

            code in 200..299 -> "连通 ✓（Key 探测通过，HTTP $code）"
            code in 400..499 -> "连通 ✓（Key 探测通过，HTTP $code；正式生成以实际返回为准）"
            else -> "响应异常：HTTP $code"
        }
    }

    companion object {
        private val JSON_TYPE = "application/json".toMediaType()

        const val ID_STEPFUN = "stepfun"
        const val ID_SENSEAUDIO = "senseaudio"
        const val ID_ELEVENLABS = "elevenlabs"

        const val LANE_SFX = "sfx"
        const val LANE_AMB = "amb"
        const val LANE_BGM = "bgm"

        val LANE_LABELS: List<Pair<String, String>> = listOf(
            LANE_SFX to "音效",
            LANE_AMB to "环境",
            LANE_BGM to "BGM",
        )

        val PROVIDERS: List<ProviderDef> = listOf(
            ProviderDef(
                id = ID_STEPFUN,
                name = "阶跃星辰 StepAudio",
                baseUrl = "https://api.stepfun.com",
                defaultModelA = "stepaudio-3-gen-preview",
                defaultModelB = "stepaudio-3-music-preview",
                defaultLanes = setOf(LANE_SFX, LANE_AMB, LANE_BGM),
                note = "音效/环境→Gen、BGM→Music；双模型限时免费（限速较低，批量补缺慢慢来）",
            ),
            ProviderDef(
                id = ID_SENSEAUDIO,
                name = "SenseAudio（商汤）",
                baseUrl = "https://api.senseaudio.cn",
                defaultModelA = "senseaudio-sfx-1.0-260626",
                defaultModelB = "senseaudio-music-2.0-260626",
                defaultLanes = setOf(LANE_SFX, LANE_AMB, LANE_BGM),
                note = "音效 0.08 元/组（一组 1~4 条）、BGM 0.5 元/首；代金券可抵",
            ),
            ProviderDef(
                id = ID_ELEVENLABS,
                name = "ElevenLabs（海外备选）",
                baseUrl = "https://api.elevenlabs.io",
                defaultModelA = "eleven_text_to_sound_v2",
                defaultModelB = "",
                defaultLanes = setOf(LANE_SFX),
                note = "50 次/月、≤30s；适合个别难词补缺",
            ),
        )

        private fun defaultProvider(def: ProviderDef): ProviderConfig = ProviderConfig(
            enabled = false,
            apiKey = "",
            modelA = def.defaultModelA,
            modelB = def.defaultModelB,
            lanes = def.defaultLanes,
        )

        fun defaultConfig(): Config = Config(
            providers = PROVIDERS.associate { def -> def.id to defaultProvider(def) },
        )
    }
}