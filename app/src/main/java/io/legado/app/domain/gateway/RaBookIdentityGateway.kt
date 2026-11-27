package io.legado.app.domain.gateway

interface RaBookIdentityGateway {

    /** 取或建书籍键；排队前取得 [generation]，被整书删除撤销的请求返回 null。 */
    suspend fun keyOf(bookUrl: String, sourceKey: String, generation: Long): String?

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
