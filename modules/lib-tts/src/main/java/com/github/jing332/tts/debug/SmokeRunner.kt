package com.github.jing332.tts.debug

import android.content.Context
import com.github.jing332.compat.fs.TtsDirProvider
import com.github.jing332.compat.log.KLog
import com.github.jing332.database.entities.plugin.Plugin
import com.github.jing332.tts.speech.plugin.TtsPluginEngineManager
import com.github.jing332.tts.speech.plugin.engine.TtsPluginUiEngineV2
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 开机冒烟自检（移植验收工具）：
 *  - 运行条件：结果缺失 / 存在触发标记 _run_smoke / 结果非当前版本（升级自动重跑）
 *  - 自检内容：内置两个探针插件（异步回调型 getAudioV2 / 同步返回型 getAudio），验证
 *      引擎加载 → 语言/音色列表 → 合成取流 → 数据校验（RIFF/WAV 头、字节数）
 *  - 输出：<数据根>/_smoke_result.txt（**逐步落盘**，卡住也能看到进度）
 *  - 读取带单步超时；全局看门狗 120s；后台线程，不阻塞 App 启动
 */
object SmokeRunner {
    private const val TAG = "SmokeRunner"
    private const val RESULT_NAME = "_smoke_result.txt"
    private const val TRIGGER_NAME = "_run_smoke"
    private const val VERSION = "smoke-v3"
    private const val READ_TIMEOUT_MS = 15_000L

    private val started = AtomicBoolean(false)
    private val finished = AtomicBoolean(false)

    @Volatile
    private var latestText = ""

    fun run(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        Thread {
            try {
                Thread.sleep(2000) // 等 App 初始化稳定
                val base = TtsDirProvider.baseDir(appContext)
                val result = File(base, RESULT_NAME)
                val trigger = File(base, TRIGGER_NAME)
                val upToDate = result.exists() && runCatching {
                    result.readText().contains(VERSION)
                }.getOrDefault(false)
                if (upToDate && !trigger.exists()) {
                    log("skip: result up-to-date & no trigger")
                    return@Thread
                }
                val watchdog = Thread {
                    try {
                        Thread.sleep(120_000)
                        if (!finished.get()) {
                            writeResult(
                                result, buildString {
                                    append(latestText)
                                    appendLine()
                                    appendLine("—— 结论 ——")
                                    appendLine("SMOKE FAIL ❌ (watchdog timeout 120s)")
                                })
                        }
                    } catch (_: InterruptedException) {
                    }
                }
                watchdog.isDaemon = true
                watchdog.start()

                val text = runSmoke(appContext, result)
                latestText = text
                finished.set(true)
                writeResult(result, text)
                watchdog.interrupt()
                if (trigger.exists()) trigger.delete()
            } catch (t: Throwable) {
                log("fatal: $t")
            }
        }.apply { isDaemon = true }.start()
    }

    private fun runSmoke(context: Context, result: File): String {
        val sb = StringBuilder()
        sb.append(header(result))
        var pass = true

        fun step(name: String, body: () -> String) {
            val t0 = System.currentTimeMillis()
            try {
                val detail = body()
                sb.appendLine("PASS  $name （${System.currentTimeMillis() - t0}ms）→ $detail")
            } catch (t: Throwable) {
                pass = false
                sb.appendLine("FAIL  $name → ${(t.message ?: t.javaClass.simpleName).take(300)}")
            }
            // 逐步落盘：即使后续卡死/超时，也能看到进行到哪一步
            latestText = sb.toString()
            writeResult(result, latestText + "\n（…进行中）")
        }

        // ---- 探针 A：异步回调型（getAudioV2）----
        var engineA: TtsPluginUiEngineV2? = null
        step("A1 引擎加载·异步回调型(getAudioV2)") {
            val plugin = Plugin(
                pluginId = "builtin.probe.async", code = PROBE_ASYNC_JS,
                name = "内置探针·异步", isEnabled = true
            )
            engineA = TtsPluginEngineManager.get(context, plugin)
            "name=${plugin.name}, id=${plugin.pluginId}, version=${plugin.version}"
        }
        var localeA = ""
        var voiceA = ""
        step("A2 获取语言列表(EditorJS.getLocales)") {
            val locales = engineA?.getLocales() ?: error("engineA 未初始化")
            if (locales.isEmpty()) error("空")
            localeA = locales.keys.first()
            "locales=[${locales.keys.joinToString()}]"
        }
        step("A3 获取音色列表(EditorJS.getVoices)") {
            val voices = engineA?.getVoices(localeA) ?: error("engineA 未初始化")
            if (voices.isEmpty()) error("空")
            voiceA = voices.first().id
            "voices=[${voices.joinToString { it.id + "/" + it.name }}]"
        }
        step("A4 合成取流(getAudio→getAudioV2 异步通道)") {
            val ins = runBlocking {
                engineA?.getAudio("探针测试 440Hz", localeA, voiceA, 1f, 1f, 1f)
                    ?: error("engineA 未初始化")
            }
            readAndCheck(ins)
        }

        // ---- 探针 B：同步返回型（getAudio 直接返回 Uint8Array）----
        var engineB: TtsPluginUiEngineV2? = null
        step("B1 引擎加载·同步返回型(getAudio)") {
            val plugin = Plugin(
                pluginId = "builtin.probe.sync", code = PROBE_SYNC_JS,
                name = "内置探针·同步", isEnabled = true
            )
            engineB = TtsPluginEngineManager.get(context, plugin)
            "name=${plugin.name}, id=${plugin.pluginId}"
        }
        var localeB = ""
        var voiceB = ""
        step("B2 语言/音色列表") {
            val locales = engineB?.getLocales() ?: error("engineB 未初始化")
            if (locales.isEmpty()) error("locales 空")
            localeB = locales.keys.first()
            val voices = engineB?.getVoices(localeB) ?: error("engineB 未初始化")
            if (voices.isEmpty()) error("voices 空")
            voiceB = voices.first().id
            "locale=$localeB, voice=$voiceB"
        }
        step("B3 合成取流(getAudio 同步通道)") {
            val ins = runBlocking {
                engineB?.getAudio("探针测试 440Hz", localeB, voiceB, 1f, 1f, 1f)
                    ?: error("engineB 未初始化")
            }
            readAndCheck(ins)
        }

        sb.appendLine()
        sb.appendLine("—— 结论 ——")
        sb.appendLine(if (pass) "SMOKE PASS ✅ 引擎全链路正常（异步/同步两个通道均通）"
        else "SMOKE FAIL ❌ 存在失败项，见上方 FAIL 行")
        return sb.toString()
    }

    private fun readAndCheck(ins: InputStream): String {
        val bytes = readAllWithTimeout(ins, READ_TIMEOUT_MS)
        if (bytes.size < 1000) error("字节数过少: ${bytes.size}")
        val head = bytes.take(12).joinToString(" ") { "%02X".format(it) }
        val riff = bytes.size >= 4 &&
                bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
                bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte()
        if (!riff) error("非 RIFF/WAV 头: $head")
        return "bytes=${bytes.size}, head=$head, RIFF=OK"
    }

    /** 带超时的整段读取：卡住时尽量关流解锁并报错，不让整条自检挂死 */
    private fun readAllWithTimeout(ins: InputStream, timeoutMs: Long): ByteArray {
        var data: ByteArray? = null
        var err: Throwable? = null
        val reader = Thread {
            try {
                data = ins.use { it.readBytes() }
            } catch (t: Throwable) {
                err = t
            }
        }
        reader.isDaemon = true
        reader.start()
        reader.join(timeoutMs)
        if (reader.isAlive) {
            runCatching { ins.close() }
            reader.join(2000)
            error("读取超时（${timeoutMs}ms）：回调未 close 或无数据（管道阻塞）")
        }
        err?.let { throw it }
        return data ?: error("无数据")
    }

    private fun header(result: File): String = buildString {
        appendLine("========================================")
        appendLine("[TTS-Server 移植] $VERSION 冒烟自检")
        appendLine("时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
        appendLine("数据根: ${result.parentFile?.absolutePath}")
        appendLine("（未授权\"所有文件访问\"时数据根为 App 私有目录；开启权限后删除本文件即可重跑）")
        appendLine("========================================")
        appendLine("—— 步骤 ——")
    }

    private fun writeResult(file: File, text: String) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(text)
            log("result written: ${file.absolutePath} (${text.length} chars)")
        }.onFailure { log("write result failed: $it") }
    }

    private fun log(msg: String) = KLog.logger(TAG).debug { msg }

    // ============================================================
    // 内置探针 A：异步回调型（getAudioV2）
    // 按"异步交付铁律"：写数据放独立线程、getAudioV2 秒回；
    // 否则 JsBridgeInputStream 的 1KB 管道会被同线程大块写死锁。
    // ============================================================
    private val PROBE_ASYNC_JS = """
var PluginJS = {
  name: "内置探针·异步",
  id: "builtin.probe.async",
  author: "ttsrv-port",
  version: 1,
  getAudioV2: function (request, callback) {
    try {
      var sampleRate = 24000;
      var seconds = 0.4;
      var total = Math.round(sampleRate * seconds);
      var dataLen = total * 2;
      var bytes = new java.io.ByteArrayOutputStream();
      function wstr(s) { for (var k = 0; k < s.length; k++) bytes.write(s.charCodeAt(k) & 0xFF); }
      function w32(v) { bytes.write(v & 0xFF); bytes.write((v >> 8) & 0xFF); bytes.write((v >> 16) & 0xFF); bytes.write((v >> 24) & 0xFF); }
      function w16(v) { bytes.write(v & 0xFF); bytes.write((v >> 8) & 0xFF); }
      wstr("RIFF"); w32(36 + dataLen); wstr("WAVE");
      wstr("fmt "); w32(16); w16(1); w16(1); w32(sampleRate); w32(sampleRate * 2); w16(2); w16(16);
      wstr("data"); w32(dataLen);
      for (var i = 0; i < total; i++) { var v = Math.round(Math.sin(2 * Math.PI * 440 * i / sampleRate) * 12000); w16(v & 0xFFFF); }
      var out = bytes.toByteArray();
      var job = new java.lang.Runnable({
        run: function () {
          try { callback.write(out); } catch (e1) {}
          try { callback.close(); } catch (e2) {}
        }
      });
      var th = new java.lang.Thread(job);
      th.start();
      return undefined;
    } catch (err) {
      try { callback.error(String(err && err.message ? err.message : err)); } catch (e2) {}
      return undefined;
    }
  }
};
var EditorJS = {
  getAudioSampleRate: function (locale, voice) { return 24000; },
  isNeedDecode: function (locale, voice) { return false; },
  getLocales: function () { return { "zh": "中文" }; },
  getVoices: function (locale) { return { "probe_async_001": { name: "440Hz声·异步", gender: "未知" } }; }
};
""".trimIndent()

    // ============================================================
    // 内置探针 B：同步返回型（getAudio 直接 return Uint8Array）
    // ============================================================
    private val PROBE_SYNC_JS = """
var PluginJS = {
  name: "内置探针·同步",
  id: "builtin.probe.sync",
  author: "ttsrv-port",
  version: 1,
  getAudio: function (text, locale, voice, rate, volume, pitch) {
    var sampleRate = 24000;
    var seconds = 0.4;
    var total = Math.round(sampleRate * seconds);
    var dataLen = total * 2;
    var buf = new Uint8Array(44 + dataLen);
    function wstr(off, s) { for (var k = 0; k < s.length; k++) buf[off + k] = s.charCodeAt(k) & 0xFF; }
    function w32(off, v) { buf[off] = v & 255; buf[off + 1] = (v >> 8) & 255; buf[off + 2] = (v >> 16) & 255; buf[off + 3] = (v >> 24) & 255; }
    function w16(off, v) { buf[off] = v & 255; buf[off + 1] = (v >> 8) & 255; }
    wstr(0, "RIFF"); w32(4, 36 + dataLen); wstr(8, "WAVE");
    wstr(12, "fmt "); w32(16, 16); w16(20, 1); w16(22, 1);
    w32(24, sampleRate); w32(28, sampleRate * 2); w16(32, 2); w16(34, 16);
    wstr(36, "data"); w32(40, dataLen);
    for (var i = 0; i < total; i++) {
      var v = Math.round(Math.sin(2 * Math.PI * 440 * i / sampleRate) * 12000);
      w16(44 + i * 2, v & 0xFFFF);
    }
    return buf;
  }
};
var EditorJS = {
  getAudioSampleRate: function (locale, voice) { return 24000; },
  isNeedDecode: function (locale, voice) { return false; },
  getLocales: function () { return { "zh": "中文" }; },
  getVoices: function (locale) { return { "probe_sync_001": { name: "440Hz哔声·同步", gender: "未知" } }; }
};
""".trimIndent()
}