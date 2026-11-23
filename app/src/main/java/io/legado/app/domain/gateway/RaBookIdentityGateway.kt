package io.legado.app.domain.gateway

interface RaBookIdentityGateway {

    /** 取书籍身份键；不存在则创建。调用方传 Book 的可变字段快照。 */
    suspend fun keyOf(bookUrl: String, sourceKey: String): String

    suspend fun bookKeyOf(bookUrl: String): String?

    suspend fun sourceKeyOf(bookUrl: String): String?

    /** 换源成功、bookUrl 迁移后调用：新 URL 沿用旧 URL 的 bookKey。 */
    suspend fun onSourceChanged(oldBookUrl: String, newBookUrl: String, newSourceKey: String)

    suspend fun identitiesOf(bookKey: String): List<RaBookIdentityRow>

    suspend fun deleteBookIdentity(bookUrl: String)
}

data class RaBookIdentityRow(
    val bookUrl: String,
    val bookKey: String,
    val sourceKey: String,
)
