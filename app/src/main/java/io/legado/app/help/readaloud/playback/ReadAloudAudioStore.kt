package io.legado.app.help.readaloud.playback

import io.legado.app.data.appDb
import io.legado.app.data.entities.ReadAloudAudioDownload
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

/**
 * 听书下载的「完成标记」。
 *
 * 自 v1.2 起音频只有一套文件：直接落在朗读缓存目录
 * （`<数据根>/data/audio/<书名>/<章>/`，命名见 [ReadAloudAudioCacheKeys]），
 * 不再复制到独立下载区。本对象只维护 `read_aloud_audio_downloads` 标记表：
 * 章是否被主动下载过、下载了多少句——听书下载列表与目录页图标都读它。
 *
 * 音频的删除统一到「缓存管理 → 书籍音频」；删章 / 删书 / 清空 / 自动清理时，
 * 由调用方调 [deleteChapterMarker] / [deleteBookMarkers] / [clearAll] 同步摘标记。
 */
object ReadAloudAudioStore {

    /** 下载进度：章节和句子两条，界面要同时显示。 */
    data class Progress(
        val running: Boolean = false,
        val bookUrl: String = "",
        val chapterTotal: Int = 0,
        val chapterDone: Int = 0,
        val currentChapter: String = "",
        val sentenceTotal: Int = 0,
        val sentenceDone: Int = 0,
        val failed: Int = 0,
        /** 无剧本被跳过的章数（听书下载的剧本门禁） */
        val skipped: Int = 0,
    ) {
        val chapterFraction: Float
            get() = if (chapterTotal <= 0) 0f else chapterDone.toFloat() / chapterTotal
        val sentenceFraction: Float
            get() = if (sentenceTotal <= 0) 0f else sentenceDone.toFloat() / sentenceTotal
    }

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress

    fun updateProgress(value: Progress) {
        _progress.value = value
    }

    fun finishProgress() {
        _progress.value = _progress.value.copy(running = false)
    }

    suspend fun list(bookUrl: String): List<ReadAloudAudioDownload> =
        appDb.readAloudAudioDownloadDao.getForBook(bookUrl)

    suspend fun downloadedChapters(bookUrl: String): Set<Int> =
        appDb.readAloudAudioDownloadDao.getDownloadedChapters(bookUrl).toSet()

    /** 订阅：目录页的听书下载图标要跟着下载进度亮起来。 */
    fun flowDownloadedChapters(bookUrl: String): Flow<Set<Int>> =
        appDb.readAloudAudioDownloadDao.flowDownloadedChapters(bookUrl).map { it.toSet() }

    suspend fun record(bookUrl: String, chapterIndex: Int, title: String, files: List<String>) {
        appDb.readAloudAudioDownloadDao.upsert(
            ReadAloudAudioDownload(
                bookUrl = bookUrl,
                chapterIndex = chapterIndex,
                title = title,
                files = files.joinToString(","),
                createTime = System.currentTimeMillis(),
            )
        )
    }

    /** 摘掉一章的下载标记（音频本体在缓存目录，由缓存管理删除）。 */
    suspend fun deleteChapterMarker(bookUrl: String, chapterIndex: Int) {
        appDb.readAloudAudioDownloadDao.delete(bookUrl, chapterIndex)
    }

    /** 摘掉整本书的下载标记（缓存管理删书时用）。 */
    suspend fun deleteBookMarkers(bookUrl: String) {
        appDb.readAloudAudioDownloadDao.deleteForBook(bookUrl)
    }

    /** 清空全部下载标记（清空音频缓存时用）。 */
    suspend fun clearAll() {
        appDb.readAloudAudioDownloadDao.deleteAll()
    }
}
