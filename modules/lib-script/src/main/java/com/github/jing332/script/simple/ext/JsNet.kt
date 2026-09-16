package com.github.jing332.script.simple.ext

import com.github.jing332.compat.net.HostHttp
import com.github.jing332.script.annotation.ScriptInterface
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File

/**
 * 移植版：原实现基于 com.drake.net，这里替换为宿主 OkHttp（[HostHttp]）。
 * 对外 API 与调用契约保持与原版一致（httpGet/httpGetString/httpGetBytes/httpPost/httpPostMultipart/cancelNetwork）。
 */
open class JsNet(private val engineId: String) {
    private val groupId by lazy { engineId + hashCode() }

    fun cancelNetwork() {
        HostHttp.cancelGroup(groupId)
    }

    @JvmOverloads
    @ScriptInterface
    fun httpGet(url: CharSequence, headers: Map<CharSequence, CharSequence>? = null): Response {
        return HostHttp.get(url.toString(), headers, groupId)
    }

    /**
     * HTTP GET
     */
    @JvmOverloads
    @ScriptInterface
    fun httpGetString(
        url: CharSequence, headers: Map<CharSequence, CharSequence>? = null
    ): String? {
        val resp = httpGet(url, headers)
        if (!resp.isSuccessful) {
            val code = resp.code
            val message = resp.message
            resp.close()
            throw Exception("Body is not a String, HTTP-$code=$message")
        }
        return resp.body?.string()
    }

    @JvmOverloads
    @ScriptInterface
    fun httpGetBytes(
        url: CharSequence, headers: Map<CharSequence, CharSequence>? = null
    ): ByteArray? {
        val resp = httpGet(url, headers)
        if (!resp.isSuccessful) {
            val code = resp.code
            val message = resp.message
            resp.close()
            throw Exception("Body is not a Bytes, HTTP-$code=$message")
        }
        return resp.body?.bytes()
    }

    /**
     * HTTP POST
     */
    @JvmOverloads
    @ScriptInterface
    fun httpPost(
        url: CharSequence,
        body: CharSequence? = null,
        headers: Map<CharSequence, CharSequence>? = null
    ): Response {
        return HostHttp.post(url.toString(), body?.toString()?.toRequestBody(), headers, groupId)
    }

    @Suppress("UNCHECKED_CAST")
    @ScriptInterface
    private fun postMultipart(type: String, form: Map<String, Any>): MultipartBody.Builder {
        val multipartBody = MultipartBody.Builder()
        multipartBody.setType(type.toMediaType())

        form.forEach { entry ->
            when (entry.value) {
                // 文件表单
                is Map<*, *> -> {
                    val filePartMap = entry.value as Map<String, Any>
                    val fileName = filePartMap["fileName"] as? String
                    val body = filePartMap["body"]
                    val contentType = filePartMap["contentType"] as? String

                    val mediaType = contentType?.toMediaType()
                    val requestBody = when (body) {
                        is File -> body.asRequestBody(mediaType)
                        is ByteArray -> body.toRequestBody(mediaType)
                        is String -> body.toRequestBody(mediaType)
                        else -> body.toString().toRequestBody()
                    }

                    multipartBody.addFormDataPart(entry.key, fileName, requestBody)
                }

                // 常规表单
                else -> multipartBody.addFormDataPart(entry.key, entry.value as String)
            }
        }

        return multipartBody
    }

    @JvmOverloads
    @ScriptInterface
    fun httpPostMultipart(
        url: CharSequence,
        form: Map<String, Any>,
        type: String = "multipart/form-data",
        headers: Map<CharSequence, CharSequence>? = null
    ): Response {
        return HostHttp.postMultipart(
            url.toString(), postMultipart(type, form).build(), headers, groupId
        )
    }
}
