package io.legado.app.feature.reader.core.readaloud

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderReadAloudChapterTest {
    @Test
    fun buildsParagraphAndPageIndexViewsInCanvasPositionSpace() {
        val chapter = ReaderReadAloudChapter.create(
            chapterIndex = 3,
            title = "第三章",
            semanticContent = "甲乙丙丁\n戊己\n",
            pageStarts = listOf(0, 2, 5),
        )

        assertEquals(listOf("甲乙丙丁", "戊己"), chapter.paragraphs.map { it.text })
        assertEquals(7, chapter.chapterLength)
        assertEquals(1, chapter.pageIndexAt(4))
        assertEquals(0, chapter.paragraphIndexAtOrAfter(4))
    }

    @Test
    fun canonicalSpeechParagraphsUseDisplayedTextPositions() {
        val chapter = ReaderReadAloudChapter.create(0, "", "袮甲\n乙\uFFFC\n", listOf(0))
        assertEquals(" 甲", chapter.canonicalSpeechParagraphs()[0].text)
        assertEquals("乙 ", chapter.canonicalSpeechParagraphs()[1].text)
        assertEquals(3, chapter.canonicalSpeechParagraphs()[1].chapterPosition)
    }

    @Test
    fun paragraphFallbackStartsUseParagraphPositions() {
        // 批215：段落分页兜底——段首为页边界、起点有序、首个为 0；与真实分页同坐标（章内字符位）
        val content = "第一段\n第二段\n\n第三段"
        assertEquals(listOf(0, 4, 9), ReaderReadAloudChapter.paragraphPageStarts(content))
    }

    @Test
    fun paragraphFallbackOnEmptyContentYieldsSingleStart() {
        assertEquals(listOf(0), ReaderReadAloudChapter.paragraphPageStarts(""))
        assertEquals(listOf(0), ReaderReadAloudChapter.paragraphPageStarts("\n\n"))
    }

    @Test
    fun fallbackChapterPageCountMatchesParagraphs() {
        val content = "A段\nB段\nC段"
        val starts = ReaderReadAloudChapter.paragraphPageStarts(content)
        val chapter = ReaderReadAloudChapter.create(0, "t", content, starts)
        assertEquals(chapter.paragraphs.size, chapter.pageCount)
    }
}
