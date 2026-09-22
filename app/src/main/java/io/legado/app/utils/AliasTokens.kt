package io.legado.app.utils

/**
 * B24：别名拆分（`a|b|c` → 列表；trim + 去空）。
 * 收敛自三套同构实现：分析管线 aliasTokensOf / 角色管理 parseAliases / 合并账本逆向 MergeRollbackCore.tokens。
 */
object AliasTokens {
    fun of(alias: String): List<String> =
        alias.split("|").map { it.trim() }.filter { it.isNotEmpty() }
}
