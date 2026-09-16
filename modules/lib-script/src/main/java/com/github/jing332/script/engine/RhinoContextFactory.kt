package com.github.jing332.script.engine

import org.mozilla.javascript.Callable
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.Wrapper
import java.util.Locale

object RhinoContextFactory : ContextFactory() {

    // 注意：切勿调用 ContextFactory.initGlobal(this)！
    // 阅读（legado）的书源引擎 com.script 已占用 Rhino 全局 ContextFactory（且内部强转 RhinoContext），
    // 我们改为“实例级上下文”进入自己的 factory（见 withRhinoContext 的 RhinoContextFactory.enterContext()），
    // 与全局 factory 互不干扰，实现两个 Rhino 引擎在同一进程内共存。

    override fun makeContext(): Context {
        return super.makeContext().apply {
            isInterpretedMode = true // optimizationLevel = -1
            languageVersion = Context.VERSION_ES6
            setClassShutter(RhinoClassShutter)
            wrapFactory = RhinoWrapFactory
            locale = Locale.getDefault()
        }
    }

    override fun hasFeature(cx: Context?, featureIndex: Int): Boolean {
        return when (featureIndex) {
//            Context.FEATURE_ENHANCED_JAVA_ACCESS -> true
            Context.FEATURE_ENABLE_JAVA_MAP_ACCESS -> true
            else -> super.hasFeature(cx, featureIndex)
        }
    }

    override fun doTopCall(
        callable: Callable?,
        cx: Context?,
        scope: Scriptable?,
        thisObj: Scriptable?,
        args: Array<out Any>?,
    ): Any {
        return when (val ret = super.doTopCall(callable, cx, scope, thisObj, args)) {
            is Wrapper -> ret.unwrap()
            else -> ret
        }
    }
}