package io.legado.app.utils

import org.junit.Assert.assertEquals
import org.junit.Test

/** B24：别名拆分收敛（原三套同构实现合并为 AliasTokens.of）回归。 */
class AliasTokensTest {

    @Test
    fun splitsTrimsAndDropsEmpty() {
        assertEquals(listOf("张三", "小张"), AliasTokens.of("张三 | 小张"))
        assertEquals(listOf("a", "b", "c"), AliasTokens.of("a|b|c"))
        assertEquals(emptyList<String>(), AliasTokens.of(""))
        assertEquals(listOf("x"), AliasTokens.of("||x||"))
    }
}
