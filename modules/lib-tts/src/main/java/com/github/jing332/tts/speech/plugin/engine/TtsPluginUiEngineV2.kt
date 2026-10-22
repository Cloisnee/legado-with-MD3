package com.github.jing332.tts.speech.plugin.engine

import android.R.attr.country
import android.content.Context
import android.widget.LinearLayout
import com.github.jing332.common.utils.dp
import com.github.jing332.common.utils.toCountryFlagEmoji
import com.github.jing332.database.entities.plugin.Plugin
import com.github.jing332.compat.log.KLog
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Wrapper
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.to

class TtsPluginUiEngineV2(context: Context, plugin: Plugin) : TtsPluginEngineV2(context, plugin) {
    companion object {
        private val logger = KLog.logger("TtsPluginUiEngineV2")

        const val FUNC_SAMPLE_RATE = "getAudioSampleRate"
        const val FUNC_IS_NEED_DECODE = "isNeedDecode"

        const val FUNC_LOCALES = "getLocales"
        const val FUNC_VOICES = "getVoices"

        const val FUNC_ON_LOAD_UI = "onLoadUI"
        const val FUNC_ON_LOAD_DATA = "onLoadData"
        const val FUNC_ON_VOICE_CHANGED = "onVoiceChanged"

        const val OBJ_UI_JS = "EditorJS"
    }

    fun dp(px: Int): Int {
        return px.dp
    }

    private val editUiJsObject: ScriptableObject by lazy {
        engine.get(OBJ_UI_JS) as? ScriptableObject
            ?: throw IllegalStateException("$OBJ_UI_JS not found")
    }

    override fun execute(script: String): Any? {
        return super.execute(PackageImporter.default + script)
    }


    fun getSampleRate(locale: String, voice: String): Int? {
        runtime.console.debug("getSampleRate($locale, $voice)")

        return engine.invokeMethod(
            editUiJsObject,
            FUNC_SAMPLE_RATE,
            locale,
            voice
        )?.run {
            return if (this is Int) this
            else (this as Double).toInt()
        }
    }

    fun isNeedDecode(locale: String, voice: String): Boolean {
        runtime.console.debug("isNeedDecode($locale, $voice)")

        return try {
            engine.invokeMethod(editUiJsObject, FUNC_IS_NEED_DECODE, locale, voice)?.run {
                if (this is Boolean) this
                else (this as Double).toInt() == 1
            } ?: true
        } catch (_: NoSuchMethodException) {
            true
        }
    }

    fun getLocales(): Map<String, String> {
        return engine.invokeMethod(editUiJsObject, FUNC_LOCALES).run {
            when (this) {
                is List<*> -> this.associate {
                    val locale = Locale.forLanguageTag(it.toString())
                    val displayName = locale.country.toCountryFlagEmoji() + " " + locale.displayName
                    it.toString() to displayName
                }

                is Map<*, *> -> {
                    this.map { (key, value) ->
                        key.toString() to value.toString()
                    }.toMap()
                }

                else -> emptyMap()
            }
        }
    }

    fun getVoices(locale: String): List<Voice> {
        // 优先走 getVoicesJson：大对象经过 JS→Kotlin 桥时会被截成最后一个值，
        // 序列化成 JSON 字符串再交给宿主解析，绕开桥的对象转换。
        val json = runCatching {
            engine.invokeMethod(editUiJsObject, "getVoicesJson", locale)
        }.getOrNull()
        if (json is CharSequence) {
            val text = json.toString().trim()
            if (text.startsWith("{") || text.startsWith("[")) {
                voicesFromJson(text)?.let { return it }
            }
        }
        return voiceResultToOptions(engine.invokeMethod(editUiJsObject, FUNC_VOICES, locale))
    }

    private fun voicesFromJson(text: String): List<Voice>? {
        val out = mutableListOf<Voice>()
        val seen = hashSetOf<String>()
        try {
            if (text.startsWith("[")) {
                val arr = JSONArray(text)
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    addJsonVoice(obj, "", out, seen)
                }
            } else {
                val obj = JSONObject(text)
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    when (val value = obj.opt(key)) {
                        is JSONObject -> addJsonVoice(value, key, out, seen)
                        is String -> addVoice(key, value, null, out, seen)
                    }
                }
            }
        } catch (_: Throwable) {
            return null
        }
        return out
    }

    private fun addJsonVoice(
        obj: JSONObject,
        key: String,
        out: MutableList<Voice>,
        seen: MutableSet<String>,
    ) {
        val id = firstJsonString(obj, listOf("id", "_id", "voice_id", "voiceId", "value", "code"))
            ?: key
        val name = firstJsonString(obj, listOf("name", "label", "title")) ?: id
        val icon = firstJsonString(obj, listOf("iconUrl", "icon"))
        addVoice(id, name, icon, out, seen)
    }

    private fun firstJsonString(obj: JSONObject, names: List<String>): String? {
        for (name in names) {
            val value = obj.optString(name).trim()
            if (value.isNotEmpty() && value != "null") return value
        }
        return null
    }

    /**
     * 兼容墨听D/JRead 的声线返回形态：
     * Map、Scriptable、Wrapper、字符串、嵌套组、数组都能展开；避免 NOT_FOUND 把自己弄崩。
     */
    private fun voiceResultToOptions(result: Any?): List<Voice> {
        val out = mutableListOf<Voice>()
        val seen = hashSetOf<String>()
        collectVoices("", result, out, seen)
        return out
    }

    private fun collectVoices(
        key: String,
        raw: Any?,
        out: MutableList<Voice>,
        seen: MutableSet<String>,
    ) {
        val value = if (raw is Wrapper) raw.unwrap() else raw
        if (value == null) return

        if (value is CharSequence) {
            val text = value.toString().trim()
            if (text.isEmpty()) return
            addVoice(key.ifBlank { text }, text, null, out, seen)
            return
        }

        if (value is Map<*, *>) {
            value.forEach { (k, v) -> collectVoices(k?.toString().orEmpty(), v, out, seen) }
            return
        }

        if (value is Scriptable) {
            if (looksLikeVoice(value)) {
                val id = readString(value, listOf("id", "_id", "voice_id", "voiceId", "value", "code"))
                    ?: key
                val name = readString(value, listOf("name", "label", "title")) ?: id
                val icon = readString(value, listOf("iconUrl", "icon"))
                addVoice(id, name, icon, out, seen)
                return
            }
            val ids = value.ids
            if (ids != null) {
                for (id in ids) {
                    val child = ScriptableObject.getProperty(value, id.toString())
                    if (child == null || child == Scriptable.NOT_FOUND) continue
                    collectVoices(id.toString(), child, out, seen)
                }
            }
        }
    }

    private fun looksLikeVoice(value: Scriptable): Boolean {
        val idKeys = listOf("id", "_id", "voice_id", "voiceId", "value", "code")
        if (idKeys.any { hasProperty(value, it) }) return true
        if (listOf("voices", "list", "children").any { hasProperty(value, it) }) return false
        return listOf("name", "label", "title").any { hasProperty(value, it) }
    }

    private fun hasProperty(value: Scriptable, name: String): Boolean {
        val v = ScriptableObject.getProperty(value, name)
        return v != null && v != Scriptable.NOT_FOUND
    }

    private fun readString(value: Scriptable, names: List<String>): String? {
        for (name in names) {
            val v = ScriptableObject.getProperty(value, name)
            if (v == null || v == Scriptable.NOT_FOUND) continue
            val text = v.toString().trim()
            if (text.isNotEmpty() && text != "undefined" && text != "null") return text
        }
        return null
    }

    private fun addVoice(
        id: String?,
        name: String?,
        icon: String?,
        out: MutableList<Voice>,
        seen: MutableSet<String>,
    ) {
        val voiceId = id?.trim().orEmpty()
        if (voiceId.isEmpty()) return
        if (!seen.add(voiceId)) return
        val voiceName = name?.trim().takeUnless { it.isNullOrEmpty() } ?: voiceId
        out += Voice(voiceId, voiceName, icon)
    }

    fun onLoadData() {
        runtime.console.debug("onLoadData()...")

        try {
            engine.invokeMethod(editUiJsObject, FUNC_ON_LOAD_DATA)
        } catch (_: NoSuchMethodException) {
        }
    }

    private val onLoadDataDone = AtomicBoolean(false)

    /**
     * B12：数据初始化（onLoadData）幂等封装。
     *
     * 语言/声线列表路径此前只 eval、未执行 onLoadData，数据型插件（猫箱-v2 / 微软翻译 等）
     * 的发音人数据从未加载 → 列表为空/「加载中…或该语言无声音」。
     * 每个引擎实例至多执行一次（失败亦标记，避免每次列表操作都重试网络/解析）。
     */
    fun ensureOnLoadData() {
        if (onLoadDataDone.get()) return
        synchronized(onLoadDataDone) {
            if (onLoadDataDone.get()) return
            try {
                onLoadData()
            } finally {
                onLoadDataDone.set(true)
            }
        }
    }

    fun onLoadUI(context: Context, container: LinearLayout) {
        runtime.console.debug("onLoadUI()...")
        try {
            engine.invokeMethod(
                editUiJsObject,
                FUNC_ON_LOAD_UI,
                context,
                container
            )
        } catch (_: NoSuchMethodException) {
        }
    }

    /** 已让插件自身加载过声线表的语言（随语言变化重载） */
    private var selfVoicesLocale: String? = null

    /**
     * 让插件自身执行 EditorJS.getVoices(locale)（补丁版 PluginTtsViewModel.updateVoices 同款）。
     *
     * 为什么必须在 onVoiceChanged 之前调用：微软翻译等插件的 onVoiceChanged 会读取插件内部
     * 声线表（getVoices 时才填充的 Map），缺失则直接 return —— 风格/角色/风格强度等特色控件
     * 永远不出现（真机实测：只显示 服务区域+音频格式 两个下拉的根因）。
     * 每个语言至多执行一次；失败不抛（部分插件无 getVoices）。
     */
    private fun ensureSelfVoices(locale: String) {
        if (selfVoicesLocale == locale) return
        runCatching { engine.invokeMethod(editUiJsObject, FUNC_VOICES, locale) }
            .onFailure { runtime.console.debug("ensureSelfVoices($locale) failed: ${it.message}") }
        selfVoicesLocale = locale
    }

    fun onVoiceChanged(locale: String, voice: String) {
        runtime.console.debug("onVoiceChanged($locale, $voice)")

        // 补丁版同款顺序：先 getVoices 填充插件内部声线表，再通知 onVoiceChanged
        ensureSelfVoices(locale)
        try {
            engine.invokeMethod(
                editUiJsObject,
                FUNC_ON_VOICE_CHANGED,
                locale,
                voice
            )
        } catch (_: NoSuchMethodException) {
        }
    }

    data class Voice(val id: String, val name: String, val icon: String? = null)
}