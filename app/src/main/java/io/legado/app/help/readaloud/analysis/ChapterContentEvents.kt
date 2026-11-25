package io.legado.app.help.readaloud.analysis

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 章节正文落盘事件（02 §7.5）。
 *
 * `BookHelp.saveText` 是阅读器按需下载与 CacheBook 下载的共同落盘点；
 * 后台建库在 WAITING_CACHE 时由该事件唤醒，不再做 30 秒轮询扫描。
 */
object ChapterContentEvents {

    data class Saved(val bookUrl: String, val chapterIndex: Int)

    private val _events = MutableSharedFlow<Saved>(replay = 32, extraBufferCapacity = 64)
    val events = _events.asSharedFlow()

    fun saved(bookUrl: String, chapterIndex: Int) {
        if (bookUrl.isBlank() || chapterIndex < 0) return
        _events.tryEmit(Saved(bookUrl, chapterIndex))
    }
}
