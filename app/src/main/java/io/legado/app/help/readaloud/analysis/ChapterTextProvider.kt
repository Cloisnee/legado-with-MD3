package io.legado.app.help.readaloud.analysis

import io.legado.app.data.dao.BookChapterDao
import io.legado.app.data.entities.Book
import io.legado.app.domain.gateway.ReadSettingsGateway
import io.legado.app.domain.model.readaloud.CanonicalSpeechParagraph
import io.legado.app.domain.model.readaloud.SpeechIdentity
import io.legado.app.feature.reader.core.readaloud.ReaderReadAloudChapter
import io.legado.app.feature.reader.core.source.ReaderChapterSourceParser
import io.legado.app.feature.reader.platform.AndroidReaderHtmlSemanticTextResolver
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.isLocal
import io.legado.app.model.CacheBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 章节正文读取（02 §3）。
 *
 * 派生链与播放侧完全一致：ContentProcessor → ReaderChapterSourceParser → ReaderReadAloudChapter。
 * `allowDownload=true` 仅用于前台当前章/预加载章，复用阅读器既有 CacheBook 下载能力；
 * 后台只读 `BookHelp.getContent`，绝不发起下载。
 */
class ChapterTextProvider(
    private val bookChapterDao: BookChapterDao,
    private val readSettingsGateway: ReadSettingsGateway,
) {

    data class Text(
        val chapterIndex: Int,
        val title: String,
        val paragraphs: List<CanonicalSpeechParagraph>,
        val contentHash: String,
        val prevText: String,
        val nextText: String,
    )

    suspend fun load(book: Book, chapterIndex: Int, allowDownload: Boolean): Text? =
        withContext(Dispatchers.IO) {
            val chapter = bookChapterDao.getChapter(book.bookUrl, chapterIndex) ?: return@withContext null
            var content = BookHelp.getContent(book, chapter)
            if (content.isNullOrBlank() && allowDownload && !book.isLocal) {
                content = runCatching {
                    CacheBook.getOrCreate(book.bookUrl)?.downloadAwait(chapter)
                }.getOrNull()?.takeUnless { it.looksLikeError() }
            }
            if (content.isNullOrBlank() || content!!.looksLikeError()) return@withContext null
            val paragraphs = buildReaderParagraphs(book, chapterIndex, content)
            if (paragraphs.isEmpty()) return@withContext null
            Text(
                chapterIndex = chapterIndex,
                title = chapter.title,
                paragraphs = paragraphs,
                contentHash = SpeechIdentity.chapterContentHash(paragraphs),
                prevText = contextText(book, chapterIndex - 1),
                nextText = contextText(book, chapterIndex + 1),
            )
        }

    private fun String.looksLikeError(): Boolean =
        startsWith("获取正文失败") || startsWith("加载正文失败") ||
            startsWith("获取本地书籍内容失败") || this == "download canceled"

    private fun contextText(book: Book, chapterIndex: Int): String {
        if (chapterIndex < 0) return ""
        val chapter = bookChapterDao.getChapter(book.bookUrl, chapterIndex) ?: return ""
        return runCatching { BookHelp.getContent(book, chapter) }.getOrNull().orEmpty()
    }

    private fun buildReaderParagraphs(
        book: Book,
        chapterIndex: Int,
        content: String,
    ): List<CanonicalSpeechParagraph> {
        val chapter = bookChapterDao.getChapter(book.bookUrl, chapterIndex) ?: return emptyList()
        val processed = ContentProcessor.get(book)
            .getContent(book, chapter, content, includeTitle = false)
        val source = ReaderChapterSourceParser.parse(
            chapterIndex = chapterIndex,
            title = "",
            paragraphs = processed.textList,
            includeTitle = false,
            adaptSpecialStyle = readSettingsGateway.currentSettings.adaptSpecialStyle,
            htmlSemanticTextResolver = AndroidReaderHtmlSemanticTextResolver,
        )
        return ReaderReadAloudChapter.create(
            chapterIndex = chapterIndex,
            title = "",
            semanticContent = source.semanticContent,
            pageStarts = emptyList(),
        ).canonicalSpeechParagraphs()
    }
}
