package io.legado.app.domain.model.readaloud

/**
 * 声线池类型（= 配置列表一级分组的 `group.roleType`）。
 *
 * **冻结口径（v4-B5）**：类型在**第一次确定时落盘**（新建条目选定 / 导入时推断 / 首次加载回填），
 * 此后不再随组内标签变化而漂移——避免「核心池里不慎加了一条路人声线，整池就变成路人池」。
 *
 * 推断优先级：**组名关键词 → 条目标签前缀 → 兜底核心**。
 * 标签命名口径（见 §4-4）：旁白=`旁白01…`；默认对话=`duihuaA01…`(男)/`duihuaB01…`(女)；
 * 特殊=`特殊男01…`/`特殊女01…`；路人=`路人{年龄}01…`；核心=`{年龄}01…`。
 */
object VoiceBankRoleType {

    const val NARRATOR = "旁白"
    const val DEFAULT_DIALOG = "默认对话"
    const val CORE = "核心"
    const val SIDE = "路人"
    const val SPECIAL = "特殊"

    /** 全部合法类型（顺序即推断优先级：具体 → 泛化） */
    val ALL = listOf(NARRATOR, DEFAULT_DIALOG, SPECIAL, SIDE, CORE)

    /** 同类型池仅允许启用一个的管控范围 = 全部类型（含旁白/默认对话） */
    val SINGLE_ENABLED = ALL

    /** 规范化：非法/空值 → 空串（表示「尚未冻结」） */
    fun normalize(value: String?): String {
        val v = value?.trim().orEmpty()
        return if (v in ALL) v else ""
    }

    /** 条目标签前缀 → 类型；无法识别返回 null */
    fun fromTag(tag: String): String? = when {
        tag.startsWith(NARRATOR) -> NARRATOR
        tag.startsWith("duihuaA") || tag.startsWith("duihuaB") -> DEFAULT_DIALOG
        tag.startsWith(SPECIAL) -> SPECIAL
        tag.startsWith(SIDE) -> SIDE
        else -> null
    }

    /** 分组名关键词 → 类型；无法识别返回 null */
    fun fromGroupName(name: String): String? = when {
        name.contains(NARRATOR) -> NARRATOR
        name.contains(DEFAULT_DIALOG) || name.contains("duihua", ignoreCase = true) -> DEFAULT_DIALOG
        name.contains(SPECIAL) -> SPECIAL
        name.contains(SIDE) -> SIDE
        name.contains(CORE) -> CORE
        else -> null
    }

    /**
     * 推断池类型：组名优先，其次按标签顺序取首个能识别的前缀，都识别不出则兜底「核心」。
     * 调用方需保证 [tags] 非空（空分组不应被冻结成核心池）。
     */
    fun infer(groupName: String, tags: List<String>): String =
        fromGroupName(groupName)
            ?: tags.firstNotNullOfOrNull { fromTag(it) }
            ?: CORE

    /**
     * 池类型 + 性别/年龄 → 标签前缀（新建条目生成 `前缀01…` 用）。
     * 旁白/默认对话的前缀由 v4-B6 补齐（旁白 / duihuaA|duihuaB）。
     */
    fun tagPrefix(roleType: String, gender: String, age: String): String = when (normalize(roleType)) {
        SPECIAL -> if (gender == "女") "特殊女" else "特殊男"
        SIDE -> "路人$age"
        else -> age
    }
}
