package io.legado.app.ui.ttssrv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import io.legado.app.base.BaseComposeActivity
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.text.AppText
import io.legado.app.ui.widget.components.button.series.MediumTonalButton
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.launch

/**
 * 插件变量网页登录（全屏 Activity，对齐 TTS Server 补丁版 PluginLoginActivity）。
 *
 * 为什么不放 Dialog/底部弹层：真机实测同一 WebView 在 Dialog 内不绘制、
 * 在 ModalBottomSheet 内会被纵向手势抢滚动。独立 Activity 的窗口最稳，
 * 也是补丁版的做法。
 */
class PluginLoginActivity : BaseComposeActivity() {

    companion object {
        const val EXTRA_PLUGIN_ID = "plugin_id"
        const val EXTRA_KEY = "key"
        const val EXTRA_LABEL = "label"
        const val EXTRA_LOGIN_URL = "login_url"
        const val EXTRA_BINDING = "binding"
        const val EXTRA_LOGIN_DESC = "login_desc"
        const val EXTRA_UA = "ua"
        const val EXTRA_CAPTURE_VOICE_LIST = "capture_voice_list"

        const val RESULT_VALUE = "result_value"
        const val RESULT_DATA = "result_data"
        const val RESULT_DEBUG = "result_debug"

        fun startIntent(
            context: Context,
            pluginId: String,
            key: String,
            label: String,
            loginUrl: String,
            binding: String,
            loginDesc: String,
            ua: String,
            captureVoiceList: Boolean,
        ): Intent = Intent(context, PluginLoginActivity::class.java).apply {
            putExtra(EXTRA_PLUGIN_ID, pluginId)
            putExtra(EXTRA_KEY, key)
            putExtra(EXTRA_LABEL, label)
            putExtra(EXTRA_LOGIN_URL, loginUrl)
            putExtra(EXTRA_BINDING, binding)
            putExtra(EXTRA_LOGIN_DESC, loginDesc)
            putExtra(EXTRA_UA, ua)
            putExtra(EXTRA_CAPTURE_VOICE_LIST, captureVoiceList)
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    @Composable
    override fun Content() {
        val label = intent.getStringExtra(EXTRA_LABEL).orEmpty()
        val loginUrl = intent.getStringExtra(EXTRA_LOGIN_URL).orEmpty()
        val binding = intent.getStringExtra(EXTRA_BINDING).orEmpty()
        val loginDesc = intent.getStringExtra(EXTRA_LOGIN_DESC).orEmpty()
        val ua = intent.getStringExtra(EXTRA_UA).orEmpty()
        val captureVoiceList = intent.getBooleanExtra(EXTRA_CAPTURE_VOICE_LIST, false)

        var webView by remember { mutableStateOf<WebView?>(null) }
        var busy by remember { mutableStateOf(false) }

        fun finishWith(value: String, data: String?, debug: String?) {
            setResult(
                RESULT_OK,
                Intent().apply {
                    putExtra(RESULT_VALUE, value)
                    if (!data.isNullOrBlank()) putExtra(RESULT_DATA, data)
                    if (!debug.isNullOrBlank()) putExtra(RESULT_DEBUG, debug)
                }
            )
            finish()
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(LegadoTheme.colorScheme.surface)
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MediumTonalButton(
                    onClick = { finish() },
                    text = "关闭",
                    contentDescription = "关闭",
                )
                AppText(
                    text = "登录：$label",
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                    style = LegadoTheme.typography.titleMediumEmphasized,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                MediumTonalButton(
                    onClick = { webView?.reload() },
                    icon = Icons.Default.Refresh,
                    contentDescription = "刷新",
                )
                MediumTonalButton(
                    enabled = !busy,
                    onClick = {
                        val wv = webView
                        if (wv == null) {
                            toastOnUi("网页还没加载完成")
                            return@MediumTonalButton
                        }
                        busy = true
                        lifecycleScope.launch {
                            val value = runCatching {
                                readPluginBinding(wv, loginUrl, binding)
                            }.getOrElse {
                                toastOnUi("读取登录态失败：${it.message.orEmpty()}")
                                busy = false
                                return@launch
                            }
                            if (value.isBlank()) {
                                toastOnUi(
                                    if (binding.isBlank()) "没拿到登录态：请确认已登录后再保存"
                                    else "没拿到 $binding：请确认已登录后再保存"
                                )
                                busy = false
                                return@launch
                            }
                            var data: String? = null
                            var debug: String? = null
                            if (captureVoiceList) {
                                toastOnUi("正在抓取音色列表…")
                                val probe = runCatching { awaitQwenVoiceList(wv) }
                                    .getOrElse { "ERR:${it.message.orEmpty()}" }
                                if (probe.isNotBlank() && !probe.startsWith("ERR")) {
                                    data = probe
                                    toastOnUi("已取到音色列表（${probe.length} 字符）")
                                } else {
                                    debug = probe.ifBlank { "超时/无返回" }
                                    toastOnUi("已保存登录态；列表抓取失败：${debug.take(160)}")
                                }
                            }
                            finishWith(value, data, debug)
                        }
                    },
                    icon = Icons.Default.Check,
                    text = "保存登录态",
                    contentDescription = "保存登录态",
                )
            }
            if (loginDesc.isNotBlank()) {
                AppText(
                    text = loginDesc,
                    style = LegadoTheme.typography.bodySmall,
                    color = LegadoTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }
            PluginLoginWebView(
                url = loginUrl,
                ua = ua,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                onCreated = { webView = it },
                onDestroyed = { webView = null },
            )
            AppText(
                text = "登录完成后点右上角「保存登录态」；binding = ${binding.ifBlank { "cookies" }}",
                style = LegadoTheme.typography.bodySmall,
                color = LegadoTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
    }
}