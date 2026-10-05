package io.legado.app.help.readaloud.audio

import android.content.Context
import com.github.jing332.compat.fs.TtsDirProvider
import java.io.File

/**
 * B33 四轨（音效/BGM/环境）· 轨道标识层。
 *
 * - [DemoLanes]：轨标识枚举 [Lane] 仅存（示例规则已随 P1.2 词网接管退役——选材链=用户条目规则 + 词网）；
 * - [TmDemoAssets]：素材库根目录/查找工具（供解析链与预合成共用）。
 */
object DemoLanes {

    enum class Lane { AMBIENCE, SFX, BGM }

}

object TmDemoAssets {

    fun libRoot(context: Context): File =
        File(File(TtsDirProvider.baseDir(context), "data"), "audio_lib")

    /** 可识别的音频扩展名（B33.3c：过滤 .json sidecar 等非音频文件，防误命中） */
    private val AUDIO_EXTS = setOf("mp3", "m4a", "wav", "ogg", "flac", "aac")

    /** 在库内按文件名找素材（精确名优先，其次包含匹配；B33.2 换 registry 检索） */
    fun findFile(context: Context, keyword: String): File? {
        val root = libRoot(context)
        if (!root.exists()) return null
        val files = walkFiles(root, 0)
            .filter { it.extension.lowercase() in AUDIO_EXTS }
            .toList()
        return files.firstOrNull { it.nameWithoutExtension == keyword }
            ?: files.firstOrNull { it.name.contains(keyword, ignoreCase = true) }
    }

    /** P1.5 · 按轨兜底查找（同栏严格）：路径归属轨过滤后，再按文件名匹配（跨栏素材不看） */
    fun findFileForLane(context: Context, keyword: String, lane: SynthLane): File? {
        val root = libRoot(context)
        if (!root.exists()) return null
        val files = walkFiles(root, 0)
            .filter { it.extension.lowercase() in AUDIO_EXTS }
            .filter { f ->
                val rel = f.relativeTo(root).path.replace(File.separatorChar, '/')
                AudioLibrary.laneSynthOfRelPath(rel) == lane
            }
            .toList()
        return files.firstOrNull { it.nameWithoutExtension == keyword }
            ?: files.firstOrNull { it.name.contains(keyword, ignoreCase = true) }
    }

    private fun walkFiles(dir: File, depth: Int): Sequence<File> {
        if (depth > 4) return emptySequence()
        return dir.listFiles().orEmpty().asSequence().flatMap { f ->
            if (f.isDirectory) walkFiles(f, depth + 1) else sequenceOf(f)
        }
    }
}