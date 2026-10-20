package io.legado.app.ui.ttssrv

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import io.legado.app.help.http.CookieStore
import io.legado.app.ui.rss.read.VisibleWebViewCompose
import io.legado.app.utils.openUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.coroutines.resume

/**
 * 插件变量登录（对齐 TTS Server 补丁版 PluginLoginActivity）：
 * defVars 里的 loginUrl / binding / loginDesc / ua 由宿主提供网页登录，
 * 登录完成后按 binding 从 Cookie / localStorage / sessionStorage 取值写回 userVars。
 */
private const val LOGIN_PC_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36 Edg/133.0.0.0"

/** 补丁版口径：空/pc = 桌面 UA，mobile/android = WebView 默认 UA，其余按原样。 */
fun resolveLoginUserAgent(ua: String): String = when (ua.trim().lowercase()) {
    "", "pc" -> LOGIN_PC_UA
    "mobile", "android" -> ""
    else -> ua.trim()
}

@SuppressLint("SetJavaScriptEnabled", "WebViewClientOnReceivedSslError")
@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
@Composable
fun PluginLoginWebView(
    url: String,
    ua: String,
    modifier: Modifier = Modifier,
    onCreated: (WebView) -> Unit,
    onDestroyed: () -> Unit = {},
) {
    val context = LocalContext.current
    var progress by remember { mutableStateOf(0) }
    val userAgent = remember(ua) { resolveLoginUserAgent(ua) }

    Box(modifier) {
        VisibleWebViewCompose(
            modifier = Modifier.fillMaxSize(),
            onCreated = { webView ->
                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(webView, true)
                webView.settings.apply {
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    domStorageEnabled = true
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    builtInZoomControls = true
                    javaScriptEnabled = true
                    displayZoomControls = false
                    if (userAgent.isNotBlank()) userAgentString = userAgent
                }
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        progress = 1
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        progress = 100
                    }

                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest,
                    ): Boolean = handleUrl(request.url)

                    @Deprecated("Deprecated in Java")
                    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                        handleUrl(Uri.parse(url))

                    private fun handleUrl(uri: Uri): Boolean {
                        if (uri.scheme == "http" || uri.scheme == "https") return false
                        // 扫码/唤端等非 http 链接交给系统
                        runCatching { context.openUrl(uri.toString()) }
                        return true
                    }

                    override fun onReceivedSslError(
                        view: WebView?,
                        handler: SslErrorHandler?,
                        error: SslError?,
                    ) {
                        handler?.proceed()
                    }
                }
                webView.webChromeClient = object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                        progress = newProgress
                    }
                }
                webView.loadUrl(url)
                onCreated(webView)
            },
            onDestroyed = onDestroyed,
        )
        if (progress in 0..99) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter),
            )
        }
    }
}

/**
 * 按补丁版 binding 语法取值：
 * - cookies[.name]   → WebView CookieManager（优先当前页 URL，回退 loginUrl）
 * - locals[.name]    → localStorage.getItem(name)
 * - sessions[.name]  → sessionStorage.getItem(name)
 * 返回空串表示没取到。
 */
suspend fun readPluginBinding(
    webView: WebView,
    fallbackUrl: String,
    binding: String,
): String {
    val raw = binding.trim()
    val dot = raw.indexOf('.')
    val kind = when {
        raw.isBlank() -> "cookies"
        dot >= 0 -> raw.substring(0, dot).trim().lowercase()
        else -> raw.lowercase()
    }
    val name = if (dot >= 0) raw.substring(dot + 1).trim() else ""
    return when (kind) {
        "cookies" -> {
            // WebView.url 必须在主线程读；Cookie 读取放 IO。
            // 同时查「当前页 / 登录页 / 各自 origin」，覆盖 OAuth 跳转与 domain cookie。
            val currentUrl = webView.url ?: fallbackUrl
            val urls = linkedSetOf(
                currentUrl, originOf(currentUrl),
                fallbackUrl, originOf(fallbackUrl),
            )
            withContext(Dispatchers.IO) {
                val cm = CookieManager.getInstance()
                runCatching { cm.flush() }
                val merged = linkedMapOf<String, String>()
                urls.forEach { u ->
                    val cookie = runCatching { cm.getCookie(u) }.getOrNull().orEmpty()
                    CookieStore.cookieToMap(cookie).forEach { (k, v) -> merged[k] = v }
                }
                if (name.isBlank()) CookieStore.mapToCookie(merged).orEmpty()
                else merged[name].orEmpty()
            }
        }

        "locals" -> if (name.isBlank()) "" else decodeJsString(
            webView.evaluateJs("localStorage.getItem(${JSONObject.quote(name)})")
        )

        "sessions" -> if (name.isBlank()) "" else decodeJsString(
            webView.evaluateJs("sessionStorage.getItem(${JSONObject.quote(name)})")
        )

        else -> ""
    }
}

private fun originOf(url: String): String = runCatching {
    val u = java.net.URI(url)
    if (!u.scheme.isNullOrBlank() && !u.host.isNullOrBlank()) "${u.scheme}://${u.host}" else url
}.getOrDefault(url)

private suspend fun WebView.evaluateJs(script: String): String =
    suspendCancellableCoroutine { cont ->
        evaluateJavascript(script) { value ->
            if (cont.isActive) cont.resume(value ?: "")
        }
    }

/** evaluateJavascript 返回的是 JSON 字面量（带引号/转义）或 null。 */
private fun decodeJsString(raw: String?): String {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty() || text == "null" || text == "undefined") return ""
    return runCatching {
        when (val value = JSONTokener(text).nextValue()) {
            is String -> value
            else -> ""
        }
    }.getOrElse { text.removeSurrounding("\"") }
}