package com.github.jing332.tts.debug

import android.content.Context
import com.github.jing332.compat.fs.TtsDirProvider
import com.github.jing332.compat.log.KLog
import com.github.jing332.database.entities.systts.source.PluginTtsSource
import com.github.jing332.tts.speech.plugin.engine.TtsEngineContext
import com.github.jing332.tts.store.TtsConfigStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * _ops 指令通道（手机调试魔法）：
 *   把 cmd_* 文件放进 <数据根>/_ops/，App 启动（约 3 秒后）自动处理：
 *    - cmd_import_plugins*.json  导入插件壳（顶层数组 / {plugins:[...]} / 单对象）
 *    - cmd_import_voices*.json   导入配置列表（原生 [{group,list:[{...,config}]}]）
 *    - cmd_import_rules*.json|.js 导入朗读规则（[{ruleId,name,code}]；裸 js 则以文件名为 ruleId）
 *    - cmd_export_plugins*.json  → out_plugins.json
 *    - cmd_export_voices*.json   → out_voices.json
 *    - cmd_list*.txt             → 概览（插件/配置/标签/缺失插件）
 *    - cmd_synth*.json           {"engineId":"local","tag":"女童01","text":"..."} → 按标签合成试听
 *   处理结果写入同目录 out_<原文件名>.txt；原命令移入 _ops/done/。
 */
object OpsRunner {
    private const val TAG = "OpsRunner"
    private val started = AtomicBoolean(false)

    fun run(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        Thread {
            try {
                Thread.sleep(3000)
                process(appContext)
            } catch (t: Throwable) {
                KLog.logger(TAG).debug { "fatal: $t" }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun opsDir(context: Context): File =
        File(TtsDirProvider.baseDir(context), "_ops").apply { mkdirs() }

    private fun process(context: Context) {
        val dir = opsDir(context)
        val cmds = dir.listFiles { f -> f.isFile && f.name.startsWith("cmd_") }
            ?.sortedBy { it.name } ?: return
        if (cmds.isEmpty()) return
        val doneDir = File(dir, "done").apply { mkdirs() }
        for (cmd in cmds) {
            val out = runCatching { handle(context, dir, cmd) }
                .getOrElse { "ERROR: ${it.message ?: it.javaClass.simpleName}" }
            val header = "[${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}] ${cmd.name}\n"
            runCatching { File(dir, "out_${cmd.name}.txt").writeText(header + out + "\n") }
            runCatching { cmd.renameTo(File(doneDir, "${cmd.name}.${System.currentTimeMillis()}.bak")) }
            KLog.logger(TAG).debug { "processed ${cmd.name}: ${out.take(160)}" }
        }
    }

    private fun handle(context: Context, dir: File, cmd: File): String {
        val text = runCatching { cmd.readText().removePrefix("\uFEFF") }.getOrElse { "" }
        return when {
            cmd.name.startsWith("cmd_import_plugins") -> {
                val arr = parsePluginShells(text)
                val (a, r, s) = TtsConfigStore.importPlugins(context, arr)
                buildString {
                    appendLine("导入插件完成: 新增=$a 覆盖=$r 跳过=$s（共 ${arr.length()} 条）")
                    append(TtsConfigStore.summary(context))
                }
            }

            cmd.name.startsWith("cmd_import_voices") -> {
                val arr = JSONArray(text.trim())
                val (ae, re, ag) = TtsConfigStore.importVoices(context, arr)
                buildString {
                    appendLine("导入配置列表完成: 新增条目=$ae 覆盖条目=$re 新增分组=$ag（共 ${arr.length()} 组）")
                    append(TtsConfigStore.summary(context))
                }
            }

            cmd.name.startsWith("cmd_import_rules") -> {
                val n = if (cmd.name.endsWith(".js")) {
                    val ruleId = cmd.name.substringBeforeLast(".js")
                        .removePrefix("cmd_import_rules").trim('_', '-', ' ')
                        .ifBlank { "imported_rule" }
                    TtsConfigStore.importRules(
                        context,
                        JSONArray().put(
                            JSONObject().put("ruleId", ruleId).put("name", ruleId).put("code", text)
                        )
                    )
                } else {
                    TtsConfigStore.importRules(context, JSONArray(text.trim()))
                }
                "导入朗读规则完成: $n 条\n" + TtsConfigStore.summary(context)
            }

            cmd.name.startsWith("cmd_export_plugins") -> {
                File(dir, "out_plugins.json").writeText(TtsConfigStore.loadPlugins(context).toString())
                "已导出: out_plugins.json（${TtsConfigStore.loadPlugins(context).length()} 个插件）"
            }

            cmd.name.startsWith("cmd_export_voices") -> {
                File(dir, "out_voices.json").writeText(TtsConfigStore.loadVoices(context).toString())
                "已导出: out_voices.json"
            }

            cmd.name.startsWith("cmd_list") -> TtsConfigStore.summary(context)

            cmd.name.startsWith("cmd_synth") -> {
                val o = JSONObject(text.trim())
                val engineId = o.optString("engineId", "local")
                val tag = o.optString("tag")
                val synthText = o.optString("text", "测试文本")
                runSynth(context, engineId, tag, synthText)
            }

            else -> "未知命令: ${cmd.name}"
        }
    }

    private fun runSynth(context: Context, engineId: String, tag: String, text: String): String {
        var report: String? = null
        val t = Thread {
            report = runCatching {
                val r = SynthProbe.run(context, engineId, tag, text)
                val bytes = r.bytes
                if (bytes != null) {
                    val dir = File(TtsDirProvider.baseDir(context), "_audition").apply { mkdirs() }
                    val ext = when {
                        bytes.size >= 4 && bytes[0] == 'R'.code.toByte() -> "wav"
                        bytes.size >= 2 && bytes[0] == 0xFF.toByte() -> "mp3"
                        else -> "bin"
                    }
                    val f = File(dir, "ops_${tag}_${System.currentTimeMillis()}.$ext")
                    f.writeBytes(bytes)
                    buildString {
                        appendLine("合成成功: ${f.absolutePath}")
                        appendLine("大小: ${f.length()} bytes")
                        appendLine("首12字节: ${bytes.take(12).joinToString(" ") { "%02X".format(it) }}")
                        appendLine("—— 诊断详情 ——")
                        append(r.report)
                    }
                } else {
                    "合成失败：\n" + r.report
                }
            }.getOrElse { "SynthProbe 异常: ${it.stackTraceToString()}" }
        }
        t.isDaemon = true
        t.start()
        t.join(90_000)
        if (t.isAlive) return "合成超时(90s): engineId=$engineId, tag=$tag"
        return report ?: "未知结果"
    }

    /** 插件壳解析：数组 / jread bundle {plugins:[...]} / 单对象 */
    private fun parsePluginShells(text: String): JSONArray {
        val t = text.trim()
        if (t.startsWith("[")) return JSONArray(t)
        if (t.startsWith("{")) {
            val o = JSONObject(t)
            o.optJSONArray("plugins")?.let { return it }
            return JSONArray().put(o)
        }
        error("无法解析插件壳（既不是数组也不是对象；前80字符: ${t.take(80)}）")
    }
}
