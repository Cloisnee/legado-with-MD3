package io.legado.app.help.readaloud.audio

import android.util.Base64
import io.legado.app.data.repository.AiModelEntry
import io.legado.app.data.repository.AiProvider
import io.legado.app.data.repository.AudioSynthPlatforms
import io.legado.app.help.http.await
import io.legado.app.help.http.okHttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * B33.3c · 音频合成平台生成适配器（缺失补缺用）。
 *
 * 契约（工作区实测 + 官方文档，见《B33 施工方案》§8.7）：
 *  - 阶跃 Gen  ：`POST /v1/audio/generate`（同步直出 mp3；sample_rate=48000；`return_url` 模式为 JSON `data.url`）
 *  - 阶跃 Music：`POST /v1/audio/music/submit` + `POST /v1/audio/music/query` 轮询（SUCCESS 的 `audio` 为 base64 mp3）
 *  - SenseAudio：`POST /v1/sound-effects/generations`（解析做容错，具体字段以设备侧联调为准）
 *  - ElevenLabs：`POST /v1/sound-generation`（直出音频字节）
 *
 * 派单失败语义：任何一步失败 → 抛异常，由 [AudioSynthQueue] 换下一个模型继续尝试。
 */
object AudioSynthClients {

    data class GenResult(val bytes: ByteArray, val detail: String)

    private const val MUSIC_POLL_MS = 6_000L
    private const val MUSIC_TIMEOUT_MS = 10 * 60 * 1000L

    private val JSON_TYPE = "application/json".toMediaType()

    /** 长超时客户端（音乐异步轮询/生成等待；连接池复用宿主） */
    private val http: OkHttpClient by lazy {
        okHttpClient.newBuilder()
            .callTimeout(300, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .build()
    }

    /** 生成提示词（Gen 用方括号描述音效/环境；Music 为 caption 描述） */
    fun promptFor(lane: SynthLane, keyword: String): String = when (lane) {
        SynthLane.SFX -> "[$keyword] 写实音效，短促单发，干净，无背景音乐、无对白"
        SynthLane.AMB -> "[$keyword] 环境底噪，持续场景氛围声，无音乐、无对白"
        SynthLane.BGM -> "$keyword 氛围，纯器乐配乐，无人声"
    }

    /** 按平台生成一条音频；成功返回 mp3 字节。 */
    suspend fun generate(
        provider: AiProvider,
        model: AiModelEntry,
        lane: SynthLane,
        keyword: String,
    ): Result<GenResult> = withContext(Dispatchers.IO) {
        runCatching {
            val base = provider.baseUrl.trim().trimEnd('/').ifBlank {
                AudioSynthPlatforms.templateOf(provider.platform)?.baseUrl.orEmpty()
            }
            require(base.isNotBlank()) { "BaseUrl 为空" }
            require(provider.apiKey.isNotBlank()) { "未填写 API Key" }
            val prompt = promptFor(lane, keyword)
            when (provider.platform) {
                AudioSynthPlatforms.PLATFORM_STEPFUN ->
                    if (isMusicModel(model.modelId)) {
                        stepfunMusic(base, provider.apiKey, model, prompt)
                    } else {
                        stepfunGen(base, provider.apiKey, model, prompt)
                    }

                AudioSynthPlatforms.PLATFORM_SENSEAUDIO ->
                    if (isMusicModel(model.modelId)) {
                        senseGenerations(base, provider.apiKey, model, prompt, "/v1/music/generations")
                    } else {
                        senseGenerations(base, provider.apiKey, model, prompt, "/v1/sound-effects/generations")
                    }

                AudioSynthPlatforms.PLATFORM_ELEVENLABS ->
                    elevenLabsSfx(base, provider.apiKey, model, prompt)

                else -> error("自定义平台暂不支持自动生成")
            }
        }.onFailure { e ->
            if (e is CancellationException) throw e
        }
    }

    private fun isMusicModel(modelId: String): Boolean =
        modelId.contains("music", ignoreCase = true)

    // ------------------------------------------------------------ 阶跃

    private suspend fun stepfunGen(
        base: String,
        key: String,
        model: AiModelEntry,
        prompt: String,
    ): GenResult {
        val body = JSONObject().apply {
            put("model", model.modelId)
            put("task", "text_to_audio")
            put("scripts", JSONArray().put(JSONObject().put("text", prompt)))
            put("response_format", "mp3")
            put("sample_rate", 48000)
        }.toString()
        val req = Request.Builder()
            .url("$base/v1/audio/generate")
            .addHeader("Authorization", "Bearer $key")
            .post(body.toRequestBody(JSON_TYPE))
            .build()
        val resp = http.newCall(req).await()
        val ctype = resp.header("Content-Type").orEmpty()
        if (ctype.contains("json", ignoreCase = true)) {
            val text = resp.body?.string().orEmpty()
            require(resp.isSuccessful) { "HTTP ${resp.code}: ${text.take(160)}" }
            val url = JSONObject(text).let { o ->
                o.optJSONObject("data")?.optString("url").orEmpty()
                    .ifBlank { o.optString("url") }
            }
            require(url.isNotBlank()) { "返回 JSON 但无音频 URL：${text.take(160)}" }
            return downloadAudio(url)
        }
        require(resp.isSuccessful) { "HTTP ${resp.code}" }
        val bytes = resp.body?.bytes().orEmpty()
        require(bytes.isNotEmpty()) { "空响应" }
        return GenResult(bytes, "mp3 ${bytes.size / 1024}KB")
    }

    private suspend fun stepfunMusic(
        base: String,
        key: String,
        model: AiModelEntry,
        prompt: String,
    ): GenResult {
        val started = System.currentTimeMillis()
        val submitBody = JSONObject().apply {
            put("task", "text_to_music")
            put("model_id", model.modelId)
            put("caption", prompt)
            put("instrumental", true)
            put("response_format", "mp3")
        }.toString()
        val submitReq = Request.Builder()
            .url("$base/v1/audio/music/submit")
            .addHeader("Authorization", "Bearer $key")
            .post(submitBody.toRequestBody(JSON_TYPE))
            .build()
        val submitResp = http.newCall(submitReq).await()
        val submitText = submitResp.body?.string().orEmpty()
        require(submitResp.isSuccessful) { "submit HTTP ${submitResp.code}: ${submitText.take(160)}" }
        val taskId = JSONObject(submitText).let { o ->
            o.optString("task_id").ifBlank { o.optJSONObject("data")?.optString("task_id").orEmpty() }
        }
        require(taskId.isNotBlank()) { "submit 无 task_id：${submitText.take(160)}" }

        val deadline = System.currentTimeMillis() + MUSIC_TIMEOUT_MS
        var consecutiveFails = 0
        while (System.currentTimeMillis() < deadline) {
            delay(MUSIC_POLL_MS)
            val poll = try {
                queryMusicOnce(base, key, taskId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                consecutiveFails++
                if (consecutiveFails >= 8) error("轮询连续失败：${e.localizedMessage}")
                continue
            }
            consecutiveFails = 0
            if (poll.audioB64.isNotBlank()) {
                val bytes = decodeBase64Audio(poll.audioB64)
                return GenResult(bytes, "异步 ${(System.currentTimeMillis() - started) / 1000}s · mp3 ${bytes.size / 1024}KB")
            }
            if (poll.status.equals("FAILED", true) || poll.status.equals("ERROR", true)) {
                error("任务失败：${poll.raw.take(160)}")
            }
        }
        error("等待超时（${MUSIC_TIMEOUT_MS / 60_000} 分钟）")
    }

    private data class MusicPoll(val status: String, val audioB64: String, val raw: String)

    private suspend fun queryMusicOnce(base: String, key: String, taskId: String): MusicPoll {
        val body = JSONObject().put("task_id", taskId).toString()
        val req = Request.Builder()
            .url("$base/v1/audio/music/query")
            .addHeader("Authorization", "Bearer $key")
            .post(body.toRequestBody(JSON_TYPE))
            .build()
        val resp = http.newCall(req).await()
        val text = resp.body?.string().orEmpty()
        require(resp.isSuccessful) { "query HTTP ${resp.code}: ${text.take(160)}" }
        val o = JSONObject(text)
        val data = o.optJSONObject("data")
        val status = o.optString("status").ifBlank { data?.optString("status").orEmpty() }
        val audio = o.optString("audio").ifBlank { data?.optString("audio").orEmpty() }
        return MusicPoll(status, audio, text)
    }

    // ------------------------------------------------------------ SenseAudio / ElevenLabs

    private suspend fun senseGenerations(
        base: String,
        key: String,
        model: AiModelEntry,
        prompt: String,
        path: String,
    ): GenResult {
        val body = JSONObject().apply {
            put("model", model.modelId)
            put("text", prompt)
        }.toString()
        val req = Request.Builder()
            .url("$base$path")
            .addHeader("Authorization", "Bearer $key")
            .post(body.toRequestBody(JSON_TYPE))
            .build()
        val resp = http.newCall(req).await()
        val ctype = resp.header("Content-Type").orEmpty()
        if (ctype.contains("audio", ignoreCase = true)) {
            require(resp.isSuccessful) { "HTTP ${resp.code}" }
            val bytes = resp.body?.bytes().orEmpty()
            require(bytes.isNotEmpty()) { "空响应" }
            return GenResult(bytes, "mp3 ${bytes.size / 1024}KB")
        }
        val text = resp.body?.string().orEmpty()
        require(resp.isSuccessful) { "HTTP ${resp.code}: ${text.take(160)}" }
        return extractAudioFromJson(JSONObject(text), text)
    }

    private suspend fun elevenLabsSfx(
        base: String,
        key: String,
        model: AiModelEntry,
        prompt: String,
    ): GenResult {
        val body = JSONObject().apply {
            put("text", prompt)
            put("model_id", model.modelId.ifBlank { "eleven_text_to_sound_v2" })
        }.toString()
        val req = Request.Builder()
            .url("$base/v1/sound-generation")
            .addHeader("xi-api-key", key)
            .post(body.toRequestBody(JSON_TYPE))
            .build()
        val resp = http.newCall(req).await()
        if (!resp.isSuccessful) {
            error("HTTP ${resp.code}: ${resp.body?.string()?.take(160).orEmpty()}")
        }
        val bytes = resp.body?.bytes().orEmpty()
        require(bytes.isNotEmpty()) { "空响应" }
        return GenResult(bytes, "mp3 ${bytes.size / 1024}KB")
    }

    // ------------------------------------------------------------ 工具

    /** 兜底解析：从 JSON 响应中尽量提取音频（base64 字段 / 下载 URL） */
    private suspend fun extractAudioFromJson(root: JSONObject, raw: String): GenResult {
        val data = root.optJSONObject("data")
        val candidates = listOfNotNull(
            root,
            data,
            data?.optJSONArray("audios")?.optJSONObject(0),
            root.optJSONArray("data")?.optJSONObject(0),
        )
        val b64 = candidates.firstNotNullOfOrNull { obj ->
            listOf("audio", "audio_base64", "audioBase64", "base64").firstNotNullOfOrNull { field ->
                obj.optString(field).takeIf { it.isNotBlank() }
            }
        }
        if (!b64.isNullOrBlank()) {
            return GenResult(decodeBase64Audio(b64), "base64 解码")
        }
        val url = listOfNotNull(root, data).firstNotNullOfOrNull { obj ->
            listOf("url", "audio_url", "audioUrl").firstNotNullOfOrNull { field ->
                obj.optString(field).takeIf { it.isNotBlank() }
            }
        }
        if (!url.isNullOrBlank()) {
            return downloadAudio(url)
        }
        error("无法解析音频响应：${raw.take(200)}")
    }

    private suspend fun downloadAudio(url: String): GenResult {
        val resp = http.newCall(Request.Builder().url(url).build()).await()
        require(resp.isSuccessful) { "音频下载失败 HTTP ${resp.code}" }
        val bytes = resp.body?.bytes().orEmpty()
        require(bytes.isNotEmpty()) { "音频下载为空" }
        return GenResult(bytes, "url 下载 ${bytes.size / 1024}KB")
    }

    private fun decodeBase64Audio(b64: String): ByteArray {
        val raw = b64.substringAfter(',', b64).trim()
        val bytes = Base64.decode(raw, Base64.DEFAULT)
        require(bytes.isNotEmpty()) { "audio 解码为空" }
        return bytes
    }
}
