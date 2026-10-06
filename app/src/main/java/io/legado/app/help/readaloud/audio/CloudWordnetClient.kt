package io.legado.app.help.readaloud.audio

import io.legado.app.help.http.await
import io.legado.app.help.http.okHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * P1.4 · 云端词网客户端：把本地条目（新音频 + 词/别名）推送到 CNB 仓库，
 * 由仓库流水线（`api_trigger_cloudwords`）合并进词网并回推 master；App 下次听书自动拉取新索引。
 *
 * 流程（对齐 tools/cloud_words_merge.py）：
 *  1) 读 master 头提交 sha；
 *  2) 需上传的新音频 → commit-asset 直传（asset-upload-url → PUT → verify；7 天 TTL，处理后可删/自清）；
 *  3) `build/start(event=api_trigger_cloudwords, env.CW_PAYLOAD=批载荷)` 触发流水线（异步）；
 *  4) 仓库侧：SHA-256/git 指纹判重（固有=只并词）→ 新音频落库 → 更新索引 → 回推 master。
 */
object CloudWordnetClient {

    private const val API = "https://api.cnb.cool"
    private const val EVENT = "api_trigger_cloudwords"
    private const val MAX_BATCH = 20
    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
    private val OCTET_TYPE = "application/octet-stream".toMediaType()

    /** 批条目（filePath=null 表示库内同名，只发词不够上传） */
    data class Item(
        val name: String,
        val lane: String,
        val words: List<String>,
        val aliases: List<String>,
        val filePath: String?,
        val ext: String,
        /** 第三刀：改名修订——上次上传名（与当前名不同时非空；云端据此重命名而非新建/并词） */
        val renamedFrom: String? = null,
    )

    data class PushResult(
        val uploaded: Int,
        val mergeOnly: Int,
        val skipped: Int,
        val sn: String?,
        val buildUrl: String?,
        val error: String? = null,
        /** P1.5 · 批标识与仓库标识（完成回执轮询用） */
        val batch: String? = null,
        val slug: String? = null,
    )

    private val http by lazy {
        okHttpClient.newBuilder()
            .callTimeout(180, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    private fun encodeRepo(repo: String): String {
        val r = repo.trim().removePrefix("https://").removePrefix("http://")
            .removePrefix("cnb.cool/").removeSuffix(".git").trim('/')
        require(Regex("^[\\w.-]+/[\\w.-]+$").matches(r)) { "仓库格式应为 组织/仓库（当前：$repo）" }
        return r
    }

    /** 连接测试：读 master 头提交；成功返回短描述 */
    suspend fun testConnection(repo: String, token: String): String {
        val slug = encodeRepo(repo)
        val sha = fetchMasterHead(slug, token)
        return "连接正常：master=${sha.take(12)}"
    }

    /** 全流程：逐条上传新音频（可跳过）→ 触发云端合并流水线 */
    suspend fun pushBatch(repo: String, token: String, items: List<Item>): PushResult {
        if (token.isBlank()) return PushResult(0, 0, 0, null, null, "未配置访问令牌")
        if (items.isEmpty()) return PushResult(0, 0, 0, null, null, "没有可提交的条目")
        if (items.size > MAX_BATCH) return PushResult(0, 0, 0, null, null, "单批最多 $MAX_BATCH 条，请分批")
        return try {
            val slug = encodeRepo(repo)
            val sha = fetchMasterHead(slug, token)
            val batch = "cw" + System.currentTimeMillis()
            var uploaded = 0
            var mergeOnly = 0
            var skipped = 0
            val jItems = JSONArray()
            items.forEachIndexed { i, item ->
                var assetName: String? = null
                val path = item.filePath
                if (path != null) {
                    val f = File(path)
                    if (f.isFile && f.length() > 0L) {
                        assetName = "${batch}__$i.${item.ext}"
                        uploadCommitAsset(slug, token, sha, assetName, f.readBytes())
                        uploaded++
                    } else {
                        skipped++
                    }
                } else {
                    mergeOnly++
                }
                jItems.put(JSONObject().apply {
                    put("idx", i)
                    put("name", item.name)
                    put("lane", item.lane)
                    put("words", JSONArray(item.words))
                    put("aliases", JSONArray(item.aliases))
                    put("asset", assetName ?: JSONObject.NULL)
                    if (item.renamedFrom != null) put("renamedFrom", item.renamedFrom)
                })
            }
            val payload = JSONObject().apply {
                put("batch", batch)
                put("sha", sha)
                put("items", jItems)
            }
            val (sn, url) = startBuild(slug, token, batch, payload.toString())
            PushResult(uploaded, mergeOnly, skipped, sn, url, null, batch, slug)
        } catch (e: Exception) {
            PushResult(0, 0, 0, null, null, e.localizedMessage ?: e.javaClass.simpleName)
        }
    }

    // ------------------------------------------------------------ CNB API

    private suspend fun fetchMasterHead(slug: String, token: String): String {
        val req = Request.Builder()
            .url("$API/$slug/-/git/commits?branch=master&page=1&page_size=1")
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Accept", "application/json")
            .get()
            .build()
        val resp = http.newCall(req).await()
        val text = resp.body?.string().orEmpty()
        require(resp.isSuccessful) { "读取仓库失败 HTTP ${resp.code}: ${text.take(160)}" }
        val sha = JSONArray(text).optJSONObject(0)?.optString("sha").orEmpty()
        require(sha.isNotBlank()) { "未取到 master 头提交" }
        return sha
    }

    private suspend fun uploadCommitAsset(
        slug: String,
        token: String,
        sha: String,
        assetName: String,
        bytes: ByteArray,
    ) {
        // 1) 取预签名上传地址
        val form = JSONObject().apply {
            put("asset_name", assetName)
            put("size", bytes.size)
            put("ttl", 7)
        }.toString()
        val req = Request.Builder()
            .url("$API/$slug/-/git/commit-assets/$sha/asset-upload-url")
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Accept", "application/json")
            .post(form.toRequestBody(JSON_TYPE))
            .build()
        val resp = http.newCall(req).await()
        val text = resp.body?.string().orEmpty()
        require(resp.isSuccessful) { "获取上传地址失败 HTTP ${resp.code}: ${text.take(160)}" }
        val jo = JSONObject(text)
        val uploadUrl = jo.optString("upload_url")
        val verifyUrl = jo.optString("verify_url")
        require(uploadUrl.isNotBlank() && verifyUrl.isNotBlank()) { "上传地址响应异常" }
        // 2) 直传（asset.cnb.cool）
        val put = Request.Builder()
            .url(uploadUrl)
            .put(bytes.toRequestBody(OCTET_TYPE))
            .build()
        val pr = http.newCall(put).await()
        require(pr.isSuccessful) { "附件上传失败 HTTP ${pr.code}" }
        // 3) 确认关联到提交
        val verify = Request.Builder()
            .url(verifyUrl)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Accept", "application/json")
            .post(ByteArray(0).toRequestBody(null))
            .build()
        val vr = http.newCall(verify).await()
        require(vr.code in 200..299) { "附件确认失败 HTTP ${vr.code}" }
    }

    private suspend fun startBuild(
        slug: String,
        token: String,
        batch: String,
        payload: String,
    ): Pair<String, String> {
        val body = JSONObject().apply {
            put("branch", "master")
            put("event", EVENT)
            put("title", "云端词网批次 $batch")
            put("sync", "false")
            put("env", JSONObject().apply { put("CW_PAYLOAD", payload) })
        }.toString()
        val req = Request.Builder()
            .url("$API/$slug/-/build/start")
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Accept", "application/json")
            .post(body.toRequestBody(JSON_TYPE))
            .build()
        val resp = http.newCall(req).await()
        val text = resp.body?.string().orEmpty()
        require(resp.isSuccessful) { "触发构建失败 HTTP ${resp.code}: ${text.take(160)}" }
        val jo = JSONObject(text)
        val sn = jo.optString("sn")
        val url = jo.optString("buildLogUrl")
        require(jo.optBoolean("success") || sn.isNotBlank()) { "触发构建未受理：${text.take(160)}" }
        return sn to url
    }
}