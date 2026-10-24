package com.github.jing332.compat.net

import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * 插件专用宿主网络桥。
 *
 * 与 [HostHttp] 的区别：
 *  - 不复用阅读书源的 OkHttp（其 callTimeout=60s，会掐断千问 78 页音色分页）；
 *  - connect/read/write 均 300s，callTimeout 默认 0（不设总时限）；
 *  - 支持插件按请求传入 timeoutMs（透传为 callTimeout），不再因 4 参重载缺失而丢掉超时。
 *
 * 只服务 TTS 插件（JsNet / handleAudioResult），不改变阅读书源/朗读分析等其他网络行为。
 */
object PluginHttp {
    private val baseClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(300, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .writeTimeout(300, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    data class TagHolder(val value: Any)

    /** timeoutMs > 0 时只给本次 call 设置总时限；共享连接池/dispatcher。 */
    private fun clientFor(timeoutMs: Long?): OkHttpClient {
        if (timeoutMs == null || timeoutMs <= 0L) return baseClient
        val capped = timeoutMs.coerceAtMost(300_000L)
        return baseClient.newBuilder()
            .callTimeout(capped, TimeUnit.MILLISECONDS)
            .build()
    }

    @JvmOverloads
    fun get(
        url: String,
        headers: Map<*, *>? = null,
        group: Any? = null,
        timeoutMs: Long? = null,
    ): Response {
        val builder = Request.Builder().url(url)
        headers?.forEach { (k, v) -> builder.header(k.toString(), v.toString()) }
        group?.let { builder.tag(TagHolder::class.java, TagHolder(it)) }
        return clientFor(timeoutMs).newCall(builder.build()).execute()
    }

    @JvmOverloads
    fun post(
        url: String,
        body: RequestBody? = null,
        headers: Map<*, *>? = null,
        group: Any? = null,
        timeoutMs: Long? = null,
    ): Response {
        val builder = Request.Builder().url(url)
        headers?.forEach { (k, v) -> builder.header(k.toString(), v.toString()) }
        group?.let { builder.tag(TagHolder::class.java, TagHolder(it)) }
        builder.post(body ?: ByteArray(0).toRequestBody(null))
        return clientFor(timeoutMs).newCall(builder.build()).execute()
    }

    @JvmOverloads
    fun postMultipart(
        url: String,
        multipart: MultipartBody,
        headers: Map<*, *>? = null,
        group: Any? = null,
        timeoutMs: Long? = null,
    ): Response {
        val builder = Request.Builder().url(url)
        headers?.forEach { (k, v) -> builder.header(k.toString(), v.toString()) }
        group?.let { builder.tag(TagHolder::class.java, TagHolder(it)) }
        builder.post(multipart)
        return clientFor(timeoutMs).newCall(builder.build()).execute()
    }

    /** 取消同一分组（插件实例）的在途请求，对齐原 cancelNetwork() 语义。 */
    fun cancelGroup(group: Any) {
        val c = baseClient
        val all = c.dispatcher.runningCalls() + c.dispatcher.queuedCalls()
        all.filter { it.request().tag(TagHolder::class.java)?.value == group }
            .forEach { runCatching { it.cancel() } }
    }
}
