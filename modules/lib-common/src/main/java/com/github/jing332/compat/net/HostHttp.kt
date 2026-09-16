package com.github.jing332.compat.net

import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * 宿主网络桥：替代 com.drake.net。
 * 由阅读（宿主）在 Application 初始化时注入统一 OkHttpClient：HostHttp.init(legadoOkHttpClient)
 * 未注入时使用默认实例（超时对齐宿主约定：300s）。
 */
object HostHttp {
    @Volatile
    private var httpClient: OkHttpClient? = null

    @Volatile
    private var clientProvider: (() -> OkHttpClient)? = null

    /** 直接注入已构建的客户端（可选） */
    fun init(client: OkHttpClient) {
        httpClient = client
    }

    /** 延迟注入：首次用网时再解析宿主客户端（推荐，避免初始化顺序问题） */
    fun init(provider: () -> OkHttpClient) {
        clientProvider = provider
    }

    val client: OkHttpClient
        get() = clientProvider?.invoke() ?: httpClient ?: defaultClient

    private val defaultClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(300, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .writeTimeout(300, TimeUnit.SECONDS)
            .build()
    }

    data class TagHolder(val value: Any)

    fun get(url: String, headers: Map<*, *>? = null, group: Any? = null): Response {
        val builder = Request.Builder().url(url)
        headers?.forEach { (k, v) -> builder.header(k.toString(), v.toString()) }
        group?.let { builder.tag(TagHolder::class.java, TagHolder(it)) }
        return client.newCall(builder.build()).execute()
    }

    fun post(
        url: String,
        body: RequestBody?,
        headers: Map<*, *>? = null,
        group: Any? = null,
    ): Response {
        val builder = Request.Builder().url(url)
        headers?.forEach { (k, v) -> builder.header(k.toString(), v.toString()) }
        group?.let { builder.tag(TagHolder::class.java, TagHolder(it)) }
        builder.post(body ?: ByteArray(0).toRequestBody(null))
        return client.newCall(builder.build()).execute()
    }

    fun postMultipart(
        url: String,
        multipart: MultipartBody,
        headers: Map<*, *>? = null,
        group: Any? = null,
    ): Response {
        val builder = Request.Builder().url(url)
        headers?.forEach { (k, v) -> builder.header(k.toString(), v.toString()) }
        group?.let { builder.tag(TagHolder::class.java, TagHolder(it)) }
        builder.post(multipart)
        return client.newCall(builder.build()).execute()
    }

    /** 取消同一分组（插件实例）的在途请求，对齐原 cancelNetwork() 语义 */
    fun cancelGroup(group: Any) {
        val c = client
        val all = c.dispatcher.runningCalls() + c.dispatcher.queuedCalls()
        all.filter { it.request().tag(TagHolder::class.java)?.value == group }
            .forEach { runCatching { it.cancel() } }
    }
}
