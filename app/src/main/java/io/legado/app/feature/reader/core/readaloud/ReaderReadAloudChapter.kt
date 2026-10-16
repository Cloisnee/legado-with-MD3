package io.legado.app.feature.reader.core.readaloud

import io.legado.app.constant.AppPattern
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

    /**
     * 朗读单元的**可读**视图：空白与纯符号段念不出东西，不进朗读单元集合。
     *
     * 把它们留在集合里，游标会落在不会出声的段上：高亮停在那儿不动，而引擎入队时又跳过它，
     * 回调下标与游标整条错开。整章都没有可发声单元（纯符号页、图片页）时退回原始列表：
     * 由「读完本章」那一套收尾，而不是卡在定位不到段落上。
     * （追更216d，源 fc2146be7 适配版）
     */
    fun readAloudParagraphs(): List<ReaderReadAloudParagraph> =
        paragraphs.filterNot { it.text.isUnspeakableReadAloudUnit() }.ifEmpty { paragraphs }

    /** 定位朗读游标：落在不发声段上时交给它后面的第一个可发声单元。 */
    fun paragraphIndexAtOrAfter(position: Int): Int =
        readAloudParagraphs().indexOfFirst { it.endPosition >= position }

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

/**
 * 朗读单元是否**念不出东西**：去掉不可念字符后只剩空白/控制/分隔/标点/符号。
 *
 * 判据与朗读服务的跳过口径同源（[AppPattern.notReadAloudRegex]）：服务侧送进引擎前会去掉
 * 不可念字符，这里判为不发声的段在那边必然也发不出声。章内常见的 `——`、`……` 分隔行与
 * 空行都落在这条判据里；「①②③」这类序号字符不属于标点/符号类，照旧朗读。
 * （追更216d，源 fc2146be7 适配版：无 cast 标记剥离）
 */
fun String.isUnspeakableReadAloudUnit(): Boolean =
    sanitizeForSpeech().matches(AppPattern.notReadAloudRegex)
