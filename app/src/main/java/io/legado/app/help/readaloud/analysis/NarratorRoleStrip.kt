package io.legado.app.help.readaloud.analysis

/**
 * B30：旁白角色剥离。
 *
 * 第2阶段 AI 把话语归并到「旁白」名下（或将「旁白」定义为角色）时，剥夺其角色属性、
 * 转为真旁白：段级 roleType=Narrator、清空 characterName；记录级不新建/不保留「旁白」记录。
 * 静默处理——不判失败、不打回重析；合成与剧本文件层同口径（〖旁白〗 标记 = 旁白叙述，
 * 由旁白声线发声）。
 */
internal object NarratorRoleStrip {

    /** 「旁白【第N章】」式章节消歧后缀（防御性：老数据/异常流程可能产生） */
    private val chapterSuffix = Regex("""^旁白【第\d+章】$""")

    /** 角色名 / 记录名是否为「旁白」角色（应剥夺全部角色属性） */
    fun isNarratorName(raw: String): Boolean {
        val name = raw.trim()
        if (name.isEmpty()) return false
        return name == "旁白" || chapterSuffix.matches(name)
    }

    /** seqmap 中指向旁白角色的话语序号集合 */
    fun narratorSeqs(seqMap: Map<Int, String>): Set<Int> =
        seqMap.filterValues { isNarratorName(it) }.keys.toSet()
}
