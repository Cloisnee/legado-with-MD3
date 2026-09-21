package io.legado.app.data.repository

import android.app.Application
import com.github.jing332.compat.fs.TtsDirProvider
import io.legado.app.help.readaloud.playback.ReadAloudAudioCacheKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class AudioChapterStats(
    val chapterIndex: Int,
    val cached: Int,
    val total: Int,
    val sizeBytes: Long,
) {
    val missing: Int get() = (total - cached).coerceAtLeast(0)
}

data class AudioBookStats(
    val book: String,
    val chapters: List<AudioChapterStats>,
) {
    val cached: Int get() = chapters.sumOf { it.cached }
    val total: Int get() = chapters.sumOf { it.total }
    val sizeBytes: Long get() = chapters.sumOf { it.sizeBytes }
}

/**
 * 朗读音频缓存仓库（持久化于 `<数据根>/data/audio/<书名>/<章序号+1>/…`）。
 *
 * 设计要点：
 *  - **不写任何索引文件**：书/章/条目由目录结构与文件名表达，文本由本地剧本（chapter_cache）提供；
 *  - 条目总数 = 该章剧本行数（剧本与分句一一对应），已合成 = 目录内出现的条目序号去重计数；
 *  - 失败条目不落缓存（播放时用临时静音文件占位），因此「x/y」反映真实合成情况。
 */
class ReadAloudAudioCacheRepository(private val app: Application) {

    companion object {
        private const val DIR_NAME = "audio"
    }

    fun rootDir(): File = File(File(TtsDirProvider.baseDir(app), "data"), DIR_NAME)

    fun bookDir(book: String): File = File(rootDir(), book)

    fun chapterDir(book: String, chapterIndex: Int): File =
        File(bookDir(book), (chapterIndex + 1).toString())

    fun cueFile(book: String, chapterIndex: Int, segIndex: Int, hash: String): File =
        File(chapterDir(book, chapterIndex), ReadAloudAudioCacheKeys.cueFileName(segIndex, hash))

    fun titleFile(book: String, chapterIndex: Int, hash: String): File =
        File(chapterDir(book, chapterIndex), ReadAloudAudioCacheKeys.titleFileName(hash))

    /** 目录内已合成条目序号（不含标题） */
    fun cachedSegIndices(book: String, chapterIndex: Int): Set<Int> {
        val files = chapterDir(book, chapterIndex).listFiles() ?: return emptySet()
        return files.asSequence()
            .filter { it.isFile }
            .mapNotNull { ReadAloudAudioCacheKeys.parseSegIndex(it.name) }
            .filter { it != ReadAloudAudioCacheKeys.TITLE_SEG_INDEX }
            .toSet()
    }

    /** 扫描缓存根下的书名列表 */
    fun listBooks(): List<String> =
        rootDir().listFiles()
            ?.filter { it.isDirectory }
            ?.map { it.name }
            ?.sorted()
            ?: emptyList()

    fun chapterSizeBytes(book: String, chapterIndex: Int): Long =
        chapterDir(book, chapterIndex).listFiles()
            ?.filter { it.isFile }
            ?.sumOf { it.length() }
            ?: 0L

    fun chapterStats(book: String, chapterIndex: Int, total: Int): AudioChapterStats {
        val cached = cachedSegIndices(book, chapterIndex).count { it in 0 until total }
        return AudioChapterStats(
            chapterIndex = chapterIndex,
            cached = cached,
            total = total,
            sizeBytes = chapterSizeBytes(book, chapterIndex),
        )
    }

    suspend fun deleteChapter(book: String, chapterIndex: Int): Boolean =
        withContext(Dispatchers.IO) {
            val dir = chapterDir(book, chapterIndex)
            if (dir.exists()) dir.deleteRecursively() else false
        }

    suspend fun deleteBook(book: String): Boolean = withContext(Dispatchers.IO) {
        val dir = bookDir(book)
        if (dir.exists()) dir.deleteRecursively() else false
    }

    /**
     * 过期清理：删除非当前章节、且最后修改早于 [keepMinutes] 的章节目录。
     * keepMinutes <= 0 表示不清理（持久化缓存由音频管理页负责删除）。
     */
    suspend fun cleanupExpired(
        book: String,
        keepChapterIndex: Int,
        keepMinutes: Int,
    ): Int = withContext(Dispatchers.IO) {
        if (keepMinutes <= 0) return@withContext 0
        val deadline = System.currentTimeMillis() - keepMinutes * 60_000L
        var removed = 0
        bookDir(book).listFiles()?.forEach { dir ->
            if (!dir.isDirectory) return@forEach
            val chapterIndex = dir.name.toIntOrNull()?.minus(1) ?: return@forEach
            if (chapterIndex == keepChapterIndex) return@forEach
            if (dir.lastModified() < deadline) {
                if (dir.deleteRecursively()) removed++
            }
        }
        removed
    }
}
