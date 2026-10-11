package io.legado.app.help.readaloud.audio

import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.NotificationId
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
 * 命中本批 → toast + **系统通知**「云端已处理：新增 X / 合并 Y，词网已刷新」+ 词网热刷新（强制复核）。
 * 每 5s 一次、最多 5 分钟；超时写音频日志（不打扰）。[isWatching] 供「提交防连击」提示。
 * （批212：提示升级为系统通知——切到其它软件也能看到；点击打开 App。）
 */
object CloudWordnetReceiptWatcher {

    private const val POLL_INTERVAL_MS = 5_000L
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
        runCatching { postCompletionNotification(context, msg) }
        AppLog.putAudio(
            "【音效与背景音】云端处理完成（批次 $batch）：新增 $added、合并 $merged、失败 $errors → 词网热刷新"
        )
        // P1.6.2+（第二刀）：上传账本——本批 pending → confirmed
        runCatching { CloudUploadLedger.confirm(context, batch) }
        // 批212：回执回写——云端 targetId 补写为本地 soundId（建立云端身份，后续改名/改类型精准对齐）
        runCatching {
            val arr = receipt.optJSONArray("items")
            val pairs = ArrayList<Pair<String, String>>()
            if (arr != null) {
                for (k in 0 until arr.length()) {
                    val o = arr.optJSONObject(k) ?: continue
                    val nm = o.optString("name")
                    val tid = o.optString("targetId")
                    if (nm.isNotBlank() && tid.isNotBlank()) pairs += nm to tid
                }
            }
            AudioLibrary.applyReceiptSoundIds(context, pairs)
        }
        runCatching { AudioNetStore.ensureLoaded(context, force = true) }
    }

    /** 批212：完成提示升级为系统通知（后台可见；点击打开 App） */
    private fun postCompletionNotification(context: Context, msg: String) {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        val pi = PendingIntent.getActivity(
            context, 0, launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, AppConst.channelIdCloudWord)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSmallIcon(io.legado.app.R.drawable.ic_outline_cloud_24)
            .setContentTitle("云端词网已处理")
            .setContentText(msg)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NotificationId.CloudWordnet, n) }
    }

    private suspend fun fetchReceipt(slug: String): JSONObject {
        val encoded = RECEIPT_PATH.split('/').joinToString("/") { seg ->
            URLEncoder.encode(seg, "UTF-8").replace("+", "%20")
        }
        val url = "https://cnb.cool/$slug/-/git/raw/main/$encoded"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "legado-audio-net")
            .build()
        val resp = http.newCall(req).await()
        require(resp.isSuccessful) { "HTTP ${resp.code}" }
        return JSONObject(resp.body?.string().orEmpty())
    }
}
