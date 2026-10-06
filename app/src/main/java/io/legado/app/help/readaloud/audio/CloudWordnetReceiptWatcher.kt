package io.legado.app.help.readaloud.audio

import android.content.Context
import io.legado.app.constant.AppLog
import io.legado.app.help.http.await
import io.legado.app.help.http.okHttpClient
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * P1.5 · 云端处理完成回执轮询（**非页面作用域**——退出页面不中断）：
 *
 * 提交成功后后台观察仓库回执 `声效/index/_cloud/last_batch.json`（raw 公开通道，无需令牌），
 * 命中本批 → toast「云端已处理：新增 X / 合并 Y，词网已刷新」+ 词网热刷新（强制复核）。
 * 每 10s 一次、最多 5 分钟；超时写音频日志（不打扰）。[isWatching] 供「提交防连击」提示。
 */
object CloudWordnetReceiptWatcher {

    private const val POLL_INTERVAL_MS = 10_000L
    private const val MAX_POLLS = 30
    private const val RECEIPT_PATH = "声效/index/_cloud/last_batch.json"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http by lazy {
        okHttpClient.newBuilder()
            .callTimeout(30, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    @Volatile private var watching = 0

    /** 是否有批次在等待回执（提交防连击提示用） */
    fun isWatching(): Boolean = watching > 0

    /** 开始观察一批（提交成功后调用）；命中回执后自动结束 */
    fun watch(context: Context, slug: String, batch: String) {
        if (slug.isBlank() || batch.isBlank()) return
        val appCtx = context.applicationContext
        watching++
        scope.launch {
            try {
                repeat(MAX_POLLS) {
                    delay(POLL_INTERVAL_MS)
                    val receipt = runCatching { fetchReceipt(slug) }.getOrNull() ?: return@repeat
                    if (receipt.optString("batch") == batch) {
                        onDone(appCtx, batch, receipt)
                        return@launch
                    }
                }
                AppLog.putAudio(
                    "【音效与背景音】云端回执超时：批次 $batch 在 5 分钟内未命中" +
                        "（可打开远程素材库强制复核）"
                )
            } finally {
                watching--
            }
        }
    }

    private suspend fun onDone(context: Context, batch: String, receipt: JSONObject) {
        val summary = receipt.optJSONObject("summary")
        val added = summary?.optInt("added", 0) ?: 0
        val merged = summary?.optInt("merged", 0) ?: 0
        val errors = summary?.optInt("errors", 0) ?: 0
        val msg = buildString {
            append("云端已处理：新增 $added / 合并 $merged")
            if (errors > 0) append("，失败 $errors")
            append("，词网已刷新")
        }
        runCatching { context.toastOnUi(msg) }
        AppLog.putAudio(
            "【音效与背景音】云端处理完成（批次 $batch）：新增 $added、合并 $merged、失败 $errors → 词网热刷新"
        )
        // P1.6.2+（第二刀）：上传账本——本批 pending → confirmed
        runCatching { CloudUploadLedger.confirm(context, batch) }
        runCatching { AudioNetStore.ensureLoaded(context, force = true) }
    }

    private suspend fun fetchReceipt(slug: String): JSONObject {
        val encoded = RECEIPT_PATH.split('/').joinToString("/") { seg ->
            URLEncoder.encode(seg, "UTF-8").replace("+", "%20")
        }
        val url = "https://cnb.cool/$slug/-/git/raw/master/$encoded"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "legado-audio-net")
            .build()
        val resp = http.newCall(req).await()
        require(resp.isSuccessful) { "HTTP ${resp.code}" }
        return JSONObject(resp.body?.string().orEmpty())
    }
}
