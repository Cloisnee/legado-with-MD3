package io.legado.app.domain.model

import androidx.annotation.Keep

/**
 * A pair of original text and its translation, used for maintaining
 * consistent terminology across multiple translation chunks.
 */
@Keep
data class DictPair(
    val original: String,
    val translation: String
)

/**
 * Collection of dictionary pairs with metadata.
 */
@Keep
data class BookDictionary(
    val bookUrl: String,
    val pairs: List<DictPair> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis()
)
