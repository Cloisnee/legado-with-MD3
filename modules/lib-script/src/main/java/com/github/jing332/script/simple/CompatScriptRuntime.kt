package com.github.jing332.script.simple

import com.github.jing332.compat.fs.TtsDirProvider
import com.github.jing332.script.runtime.Environment
import com.github.jing332.script.runtime.RhinoScriptRuntime
import com.github.jing332.script.simple.ext.JsExtensions

/**
 * 移植版：数据根由 externalCacheDir 改为 TtsDirProvider（Download/chajian 优先，回退私有目录），
 * 与补丁版 TTS-Server 的目录策略保持一致。
 */
class CompatScriptRuntime(val ttsrv: JsExtensions) :
    RhinoScriptRuntime(
        environment = Environment(
            TtsDirProvider.baseDir(ttsrv.context).absolutePath,
            ttsrv.engineId
        )
    ) {
    override fun init() {
        super.init()
        globalScope.defineGetter("ttsrv", ::ttsrv)
    }
}
