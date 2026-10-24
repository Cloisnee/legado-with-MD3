package com.github.jing332.tts.debug

import android.content.Context
import com.github.jing332.compat.fs.TtsDirProvider
import org.json.JSONObject
import java.io.File

/**
 * 管线探针：只记录耗时/数量/错误码，不记录正文、票据、密钥。
 * 落盘：<数据根>/_audition/pipeline_v2.log（JSONL，2MB 轮转）。
 */
object PipelineProbe {
    private const val FILE_NAME = "pipeline_v2.log"
    private const val MAX_BYTES = 2L * 1024L * 1024L

    fun log(context: Context, event: String, vararg fields: Pair<String, Any?>) {
        runCatching {
            val dir = File(TtsDirProvider.baseDir(context), "_audition").apply { mkdirs() }
            val file = File(dir, FILE_NAME)
            synchronized(this) {
                if (file.length() > MAX_BYTES) {
                    val old = File(dir, "$FILE_NAME.1")
                    runCatching { old.delete() }
                    runCatching { file.renameTo(old) }
                }
                val json = JSONObject()
                json.put("t", System.currentTimeMillis())
                json.put("event", event)
                fields.forEach { (k, v) -> json.put(k, v ?: JSONObject.NULL) }
                file.appendText(json.toString() + "\n")
            }
        }
    }
}
