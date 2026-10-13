package io.legado.app.feature.reader.core.readaloud

import io.legado.app.domain.model.readaloud.CanonicalSpeechParagraph

data class ReaderReadAloudParagraph(
    val text: String,
    val chapterPosition: Int,
    val isParagraphEnd: Boolean,
) {
    val endPosition: Int get() = chapterPosition + text.length
}

data class ReaderReadAloudChapter(
    val chapterIndex: Int,
    val title: String,
    val pageStarts: List<Int>,
    val paragraphs: List<ReaderReadAloudParagraph>,
    val chapterLength: Int,
) {
    val pageCount: Int get() = pageStarts.size

    fun pageStart(index: Int): Int = pageStarts.getOrElse(index) { pageStarts.lastOrNull() ?: 0 }

    fun pageIndexAt(position: Int): Int = pageStarts
        .indexOfLast { it <= position }
        .coerceAtLeast(0)
        .coerceAtMost((pageStarts.size - 1).coerceAtLeast(0))

    fun paragraphIndexAtOrAfter(position: Int): Int =
        paragraphs.indexOfFirst { it.endPosition >= position }

    fun canonicalSpeechParagraphs(): List<CanonicalSpeechParagraph> = paragraphs.mapIndexed {
        index, paragraph ->
        CanonicalSpeechParagraph(index, paragraph.text.sanitizeForSpeech(), paragraph.chapterPosition)
    }

    companion object {
        fun create(
            chapterIndex: Int,
            title: String,
            semanticContent: String,
            pageStarts: List<Int>,
        ): ReaderReadAloudChapter {
            val normalizedStarts = pageStarts
                .asSequence()
                .filter { it >= 0 }
                .distinct()
                .sorted()
                .toList()
                .ifEmpty { listOf(0) }
            val paragraphs = semanticContent.lineParagraphs()
            return ReaderReadAloudChapter(
                chapterIndex = chapterIndex,
                title = title,
                pageStarts = normalizedStarts,
                paragraphs = paragraphs,
                chapterLength = paragraphs.lastOrNull()?.endPosition ?: 0,
            )
        }

        /**
         * 批215：段落分页兜底——以每个段落起点为「页」边界（真实分页快照未就绪时供朗读先行启动）。
         * 与真实分页同为「章内字符坐标」，快照落地后可由 syncReaderLayout 无损接轨升级。
         */
        fun paragraphPageStarts(semanticContent: String): List<Int> =
            semanticContent.lineParagraphs().map { it.chapterPosition }.ifEmpty { listOf(0) }
    }
}

private fun String.lineParagraphs(): List<ReaderReadAloudParagraph> = buildList {
    var start = 0
    this@lineParagraphs.forEachIndexed { index, character ->
        if (character == '\n') {
            if (index > start) add(ReaderReadAloudParagraph(substring(start, index), start, true))
            start = index + 1
        }
    }
    if (start < length) add(ReaderReadAloudParagraph(substring(start), start, true))
}

private fun String.sanitizeForSpeech(): String = replace(Regex("[袮祢꧁\uFFFC]"), " ")
