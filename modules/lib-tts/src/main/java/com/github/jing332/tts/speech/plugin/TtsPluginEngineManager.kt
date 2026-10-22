package com.github.jing332.tts.speech.plugin

import android.content.Context
import com.github.jing332.database.entities.plugin.Plugin
import com.github.jing332.tts.debug.PipelineProbe
import com.github.jing332.tts.speech.plugin.engine.TtsPluginUiEngineV2
import com.github.jing332.tts.util.AbstractCachedManager
import java.util.concurrent.ConcurrentHashMap

object TtsPluginEngineManager : AbstractCachedManager<String, TtsPluginUiEngineV2>(
    timeout = 1000L * 60L * 30L, // 30 min
    delay = 1000L * 60L * 1L, // 1 min
) {
    /** 同一 pluginId 单飞：避免 UI 并发 get 时各建一个引擎、各自 eval/认证。 */
    private val locks = ConcurrentHashMap<String, Any>()

    fun get(context: Context, plugin: Plugin): TtsPluginUiEngineV2 {
        // 关键：eval() 会用 PluginJS.id 改写 plugin.pluginId；缓存键必须在 eval 前捕获 JSON 的原始 pluginId。
        val key = plugin.pluginId
        val lock = locks.computeIfAbsent(key) { Any() }
        return synchronized(lock) {
            val t0 = System.currentTimeMillis()
            val cached = cache.get(key)
            if (cached != null && cached.plugin.code == plugin.code) {
                PipelineProbe.log(
                    context, "engine.get",
                    "pluginId" to key,
                    "cacheHit" to true,
                    "ms" to (System.currentTimeMillis() - t0)
                )
                return@synchronized cached
            }
            if (cached != null) cache.remove(key)
            val engine = TtsPluginUiEngineV2(context, plugin)
            val evalStart = System.currentTimeMillis()
            engine.eval()
            val evalMs = System.currentTimeMillis() - evalStart
            cache.put(key, engine)
            PipelineProbe.log(
                context, "engine.get",
                "pluginId" to key,
                "jsId" to plugin.pluginId,
                "cacheHit" to false,
                "evalMs" to evalMs,
                "ms" to (System.currentTimeMillis() - t0)
            )
            engine
        }
    }

    /** 使某插件的已缓存引擎失效（修改变量 / 替换插件后调用） */
    fun remove(pluginId: String) {
        cache.remove(pluginId)
    }
}