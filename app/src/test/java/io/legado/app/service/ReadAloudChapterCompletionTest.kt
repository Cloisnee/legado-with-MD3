package io.legado.app.service

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadAloudChapterCompletionTest {

    @Test
    fun timerArmedForThisChapterStopsAtChapterEnd() {
        val decision = decideChapterCompletion(
            durChapterIndex = 5,
            finishedChapterIndex = 5,
            finishChapterAtIndex = 5,
            finishChapterSettingEnabled = true,
        )

        assertEquals(ChapterCompletionAction.STOP, decision.action)
        assertEquals(true, decision.clearTimer)
    }

    @Test
    fun noTimerArmedContinuesToNextChapter() {
        val decision = decideChapterCompletion(
            durChapterIndex = 5,
            finishedChapterIndex = 5,
            finishChapterAtIndex = NO_FINISH_CHAPTER,
            finishChapterSettingEnabled = true,
        )

        assertEquals(ChapterCompletionAction.ADVANCE, decision.action)
        assertEquals(false, decision.clearTimer)
    }

    @Test
    fun timerArmedForDifferentChapterClearsAndContinues() {
        val decision = decideChapterCompletion(
            durChapterIndex = 5,
            finishedChapterIndex = 5,
            finishChapterAtIndex = 3,
            finishChapterSettingEnabled = true,
        )

        assertEquals(ChapterCompletionAction.ADVANCE, decision.action)
        assertEquals(true, decision.clearTimer)
    }

    @Test
    fun timerDisabledAfterArmingClearsAndContinues() {
        val decision = decideChapterCompletion(
            durChapterIndex = 5,
            finishedChapterIndex = 5,
            finishChapterAtIndex = 5,
            finishChapterSettingEnabled = false,
        )

        assertEquals(ChapterCompletionAction.ADVANCE, decision.action)
        assertEquals(true, decision.clearTimer)
    }

    @Test
    fun armedChapterFinishingWhileReaderBrowsedAheadStillStops() {
        // 脱离浏览：页面已翻到第 6 章，朗读仍在第 5 章；定时臂标锚定的是
        // 朗读中的第 5 章 —— 本章自然读完必须停止，不能被误判为"章节已推进"
        val decision = decideChapterCompletion(
            durChapterIndex = 6,
            finishedChapterIndex = 5,
            finishChapterAtIndex = 5,
            finishChapterSettingEnabled = true,
        )

        assertEquals(ChapterCompletionAction.STOP, decision.action)
        assertEquals(true, decision.clearTimer)
    }

    @Test
    fun chapterAlreadyAdvancedSkipsWithoutTouchingOtherArms() {
        val decision = decideChapterCompletion(
            durChapterIndex = 6,
            finishedChapterIndex = 5,
            finishChapterAtIndex = 3,
            finishChapterSettingEnabled = true,
        )

        assertEquals(ChapterCompletionAction.SKIP, decision.action)
        assertEquals(false, decision.clearTimer)
    }

    @Test
    fun lateDuplicateCompletionAfterAdvanceSkipsInsteadOfDoubleAdvancing() {
        // 双重触发竞态：首次完结已 ADVANCE 且臂标为空，迟到的同章完结
        // 不得再次推进（否则跳过下一章）
        val decision = decideChapterCompletion(
            durChapterIndex = 6,
            finishedChapterIndex = 5,
            finishChapterAtIndex = NO_FINISH_CHAPTER,
            finishChapterSettingEnabled = true,
        )

        assertEquals(ChapterCompletionAction.SKIP, decision.action)
        assertEquals(false, decision.clearTimer)
    }

    @Test
    fun chapterIndexZeroDoesNotCollideWithSentinel() {
        val decision = decideChapterCompletion(
            durChapterIndex = 0,
            finishedChapterIndex = 0,
            finishChapterAtIndex = NO_FINISH_CHAPTER,
            finishChapterSettingEnabled = true,
        )

        assertEquals(ChapterCompletionAction.ADVANCE, decision.action)
        assertEquals(false, decision.clearTimer)
    }

}
