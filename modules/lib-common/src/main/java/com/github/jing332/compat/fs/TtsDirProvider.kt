package com.github.jing332.compat.fs

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import java.io.File

/**
 * TTS-Server 数据目录策略（与补丁版兼容）：
 *  1) 首选：/storage/emulated/0/Download/chajian/<pluginId>（授权"所有文件访问"时）
 *  2) 回退：context.filesDir/ttsrv/<pluginId>（未授权或不可写时）
 * 规则与补丁版一致：路径以 "/" 开头时直接拼在引擎根之后（见 JsExtensions.getFile）。
 */
object TtsDirProvider {
    private const val PREFERRED_SEGMENT = "Download/chajian"
    private const val FALLBACK_SEGMENT = "ttsrv"

    @Volatile
    private var cachedBase: File? = null

    fun baseDir(context: Context): File {
        cachedBase?.let { return it }
        synchronized(this) {
            cachedBase?.let { return it }
            val base = resolveBase(context)
            cachedBase = base
            return base
        }
    }

    fun engineDir(context: Context, engineId: String): File {
        val dir = File(baseDir(context), engineId)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** 仅测试/调试用：清除缓存后重新探测 */
    fun reset() {
        cachedBase = null
    }

    private fun resolveBase(context: Context): File {
        val preferred = File(Environment.getExternalStorageDirectory(), PREFERRED_SEGMENT)
        val usable = runCatching {
            canWriteExternal(context) && ensureWritable(preferred)
        }.getOrDefault(false)
        if (usable) return preferred
        return File(context.filesDir, FALLBACK_SEGMENT).apply { mkdirs() }
    }

    private fun canWriteExternal(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                    PackageManager.PERMISSION_GRANTED
        }
    }

    private fun ensureWritable(dir: File): Boolean {
        if (!dir.exists()) dir.mkdirs()
        if (!dir.exists() || !dir.canWrite()) return false
        return runCatching {
            val probe = File(dir, ".ttsrv_write_probe")
            probe.writeText("ok")
            probe.delete()
            true
        }.getOrDefault(false)
    }
}
