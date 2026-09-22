package io.legado.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * B23 合并账本逆向回归（对齐 1.4.x/角色管理 v50 回滚语义）：
 *  - 'm' 自动合并凭据：并入落在回滚区且保留区无同对 → 剥离别名（不造新记录）；
 *    保留区持有同 from|to 对（并入章节早于回滚点）→ 保留别名；
 *    升级/吸收回退 = 记录名==from 且别名含 to → 改名回退；
 *  - 'u' 改名/手动合并：维持既有可逆语义（剥离 + 被并走记录缺失时重建）；
 *  - 出场清理：≥floor 全清、清空即删。
 */
class MergeRollbackCoreTest {

    private fun rec(name: String, aliases: String = "", chapters: List<Int> = emptyList()) =
        CharacterRecord(
            name = name,
            aliases = aliases,
            appearanceChapters = chapters.toMutableList(),
            appearanceCount = chapters.size,
            lastAppearanceChapter = chapters.maxOrNull() ?: -1,
        )

    private fun mOp(chapter: Int, from: String, to: String, aliases: List<String> = emptyList()) =
        MergeRollbackCore.OpView("m${chapter}_${from}", "active", from, to, aliases)

    private fun uOp(chapter: Int, from: String, to: String) =
        MergeRollbackCore.OpView("u${chapter}_${from}", "active", from, to, emptyList())

    @Test
    fun autoMergeOpIsReversedWhenNoKeptPair() {
        val list = mutableListOf(rec("张三", aliases = "小张", chapters = listOf(1, 2, 3)))
        MergeRollbackCore.reverseOps(
            list,
            removedOps = listOf(mOp(3, "小张", "张三")),
            keptOps = emptyList(),
        )
        assertEquals(listOf("张三"), list.map { it.name })
        assertEquals("", list[0].aliases)
    }

    @Test
    fun autoMergeOpIsKeptWhenMergeChapterEarlierThanRollback() {
        val list = mutableListOf(rec("张三", aliases = "小张", chapters = listOf(1, 2, 3)))
        MergeRollbackCore.reverseOps(
            list,
            removedOps = listOf(mOp(3, "小张", "张三")),
            keptOps = listOf(mOp(2, "小张", "张三")),
        )
        assertEquals("小张", list[0].aliases)
    }

    @Test
    fun autoMergeUpgradeRenamesBack() {
        val list = mutableListOf(rec("刀刀", aliases = "矮冬瓜", chapters = listOf(1, 2)))
        MergeRollbackCore.reverseOps(
            list,
            removedOps = listOf(mOp(5, "刀刀", "矮冬瓜")),
            keptOps = emptyList(),
        )
        assertEquals("矮冬瓜", list[0].name)
        assertEquals("", list[0].aliases)
        assertEquals(1, list.size)
    }

    @Test
    fun autoMergeStripsViaOpAliasesTokens() {
        val list = mutableListOf(rec("张三", aliases = "小张|阿张"))
        MergeRollbackCore.reverseOps(
            list,
            removedOps = listOf(mOp(1, "小张", "张三", aliases = listOf("阿张"))),
            keptOps = emptyList(),
        )
        assertEquals("", list[0].aliases)
    }

    @Test
    fun manualMergeOpStillReversibleAndRecreates() {
        val list = mutableListOf(rec("张三", aliases = "小张"))
        MergeRollbackCore.reverseOps(
            list,
            removedOps = listOf(uOp(4, "小张", "张三")),
            keptOps = emptyList(),
        )
        assertEquals(setOf("张三", "小张"), list.map { it.name }.toSet())
        assertEquals("", list.first { it.name == "张三" }.aliases)
        assertEquals("", list.first { it.name == "小张" }.aliases)
    }

    @Test
    fun inactiveOpIsIgnored() {
        val list = mutableListOf(rec("张三", aliases = "小张"))
        MergeRollbackCore.reverseOps(
            list,
            removedOps = listOf(mOp(3, "小张", "张三").copy(status = "released")),
            keptOps = emptyList(),
        )
        assertEquals("小张", list[0].aliases)
    }

    @Test
    fun appearancePurgeDropsEmptyAndKeepsPartial() {
        val list = mutableListOf(
            rec("甲", chapters = listOf(1, 2, 3)),
            rec("乙", chapters = listOf(3, 4)),
            rec("丙", chapters = listOf(1, 2)),
        )
        MergeRollbackCore.purgeAppearances(list, floor = 3)
        assertEquals(listOf("甲", "丙"), list.map { it.name })
        assertEquals(listOf(1, 2), list[0].appearanceChapters)
        assertEquals(2, list[0].appearanceCount)
        assertEquals(2, list[0].lastAppearanceChapter)
    }
}
