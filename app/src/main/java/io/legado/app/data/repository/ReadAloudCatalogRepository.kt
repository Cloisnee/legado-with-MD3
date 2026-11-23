package io.legado.app.data.repository

import io.legado.app.constant.BookType
import io.legado.app.data.dao.BookChapterDao
import io.legado.app.data.dao.BookDao
import io.legado.app.data.dao.readaloud.RaBuildDao
import io.legado.app.data.entities.Book
import io.legado.app.domain.gateway.RaBuildGateway
import io.legado.app.domain.model.readaloud.v4.RaBookSummary
import io.legado.app.help.book.isLocal
import io.legado.app.help.config.AppConfigStore
import io.legado.app.utils.ChapterLabels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 书籍身份键对应的书源标识。 */
fun sourceKeyOf(book: Book): String = if (book.isLocal) BookType.localTag else book.origin

/**
 * 朗读分析 UI 的书籍目录层：当前书、书列表、章节标题/行数。
 * 所有朗读分析数据仍由 [RaBuildGateway] 提供。
 */
class ReadAloudCatalogRepository(
    private val bookDao: BookDao,
    private val bookChapterDao: BookChapterDao,
    private val identityDao: RaBuildDao,
    private val buildGateway: RaBuildGateway,
) {

    companion object {
        const val KEY_CURRENT_BOOK = "ra_current_book_key"
        const val KEY_CHARACTER_FILTER = "ra_character_filter"
        const val DEFAULT_BOOK_LABEL = "默认"
    }

    suspend fun booksWithData(): List<RaBookSummary> = withContext(Dispatchers.IO) {
        val identities = identityDao.allIdentities()
        if (identities.isEmpty()) return@withContext emptyList()
        val byKey = identities.groupBy { it.bookKey }
        byKey.mapNotNull { (bookKey, rows) ->
            val book = rows.firstNotNullOfOrNull { bookDao.getBook(it.bookUrl) }
            val source = rows.firstOrNull { bookDao.getBook(it.bookUrl) != null } ?: rows.first()
            RaBookSummary(
                bookKey = bookKey,
                bookUrl = source.bookUrl,
                sourceKey = source.sourceKey,
                name = book?.name ?: source.bookUrl,
                author = book?.author.orEmpty(),
                chapterCount = book?.totalChapterNum ?: 0,
            )
        }.sortedBy { it.name }
    }

    suspend fun bookByKey(bookKey: String): Book? = withContext(Dispatchers.IO) {
        identityDao.identitiesOf(bookKey).firstNotNullOfOrNull { bookDao.getBook(it.bookUrl) }
    }

    suspend fun bookKeyByBookUrl(bookUrl: String): String? = withContext(Dispatchers.IO) {
        identityDao.getIdentity(bookUrl)?.bookKey
    }

    suspend fun sourceKeyByBookUrl(bookUrl: String): String? = withContext(Dispatchers.IO) {
        identityDao.getIdentity(bookUrl)?.sourceKey
    }

    suspend fun chapterTitles(bookUrl: String): Map<Int, String> = withContext(Dispatchers.IO) {
        runCatching {
            bookChapterDao.getChapterList(bookUrl)
                .associate { it.index to it.title }
        }.getOrDefault(emptyMap())
    }

    suspend fun chapterLabel(bookUrl: String, chapterIndex: Int): String =
        withContext(Dispatchers.IO) {
            val title = runCatching {
                bookChapterDao.getChapter(bookUrl, chapterIndex)?.title
            }.getOrNull()
            ChapterLabels.of(title, chapterIndex)
        }

    suspend fun lineCounts(bookKey: String): Map<Int, Int> = buildGateway.lineCounts(bookKey)

    fun currentBookKey(): String = AppConfigStore.getString(KEY_CURRENT_BOOK) ?: ""

    fun setCurrentBookKey(bookKey: String) {
        AppConfigStore.putString(KEY_CURRENT_BOOK, bookKey)
    }

    fun characterFilter(): String =
        AppConfigStore.getString(KEY_CHARACTER_FILTER)?.ifBlank { "全部" } ?: "全部"

    fun setCharacterFilter(value: String) {
        AppConfigStore.putString(KEY_CHARACTER_FILTER, value)
    }
}
