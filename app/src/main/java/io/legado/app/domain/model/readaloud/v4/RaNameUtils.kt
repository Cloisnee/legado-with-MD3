package io.legado.app.domain.model.readaloud.v4

import java.text.Normalizer

/** 称呼归一：去空白、全角转半角、小写；只用于索引，不改变原文。 */
object RaNameUtils {

    fun normalize(raw: String): String =
        Normalizer.normalize(raw.trim(), Normalizer.Form.NFKC)
            .replace(Regex("\\s+"), "")
            .lowercase()

    fun normalizeAll(values: Collection<String>): List<String> =
        values.map(::normalize).filter { it.isNotEmpty() }.distinct()
}
