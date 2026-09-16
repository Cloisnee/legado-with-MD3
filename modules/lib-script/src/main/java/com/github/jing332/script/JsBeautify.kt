package com.github.jing332.script

import android.content.Context
import org.mozilla.javascript.Function
import org.mozilla.javascript.ScriptableObject

class JsBeautify(context: Context) {
    private val scope: ScriptableObject
    private val formatJsFunc: Function

    init {
        // 说明：不在 withRhinoContext 的 lambda 内对成员属性赋值（K2 限制），
        // 改为闭包内计算、lambda 外统一赋值。
        val pair = withRhinoContext { cx ->
            cx.isInterpretedMode = true
            cx.languageVersion = org.mozilla.javascript.Context.VERSION_ES6
            val s = cx.initStandardObjects()
            context.assets.open("js/beautifier.js").bufferedReader().use { reader ->
                cx.evaluateReader(s, reader, "<beautifier.js>", 1, null)
            }
            s to ((s.get("js_beautify") as ScriptableObject) as Function)
        }
        scope = pair.first
        formatJsFunc = pair.second
    }

    fun format(code: String): String = withRhinoContext { cx ->
        formatJsFunc.call(cx, scope, scope, arrayOf(code)) as String
    }
}