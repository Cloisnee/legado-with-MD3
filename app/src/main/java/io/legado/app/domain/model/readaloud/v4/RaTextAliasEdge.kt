package io.legado.app.domain.model.readaloud.v4

/** 两端都是原文中出现的归一称呼。边本身不指定哪一端是人物的独立锚点。 */
internal data class RaTextAliasEdge(val subject: String, val other: String) {
    fun reason() = "$TEXT_ALIAS_PROOF|${subject.length}|$subject$other"

    companion object {
        fun fromReason(reason: String): RaTextAliasEdge? {
            val prefix = "$TEXT_ALIAS_PROOF|"
            if (!reason.startsWith(prefix)) return null
            val payload = reason.removePrefix(prefix)
            val length = payload.substringBefore('|').toIntOrNull() ?: return null
            val names = payload.substringAfter('|', "")
            if (length <= 0 || length >= names.length) return null
            return RaTextAliasEdge(names.substring(0, length), names.substring(length))
        }
    }
}

/** 引文必须另有一处称呼；不能拿客体子串（如「沈七」与「沈七郎」）充当主体。 */
internal fun RaEvidence.quotedSubjects(names: Collection<String>): List<String> {
    val other = RaNameUtils.normalize(objectText)
    if (other.isEmpty()) return emptyList()
    val quote = RaNameUtils.normalize(excerpt)
    val first = quote.indexOf(other)
    if (first < 0) return emptyList()
    val last = quote.lastIndexOf(other)
    val subjects = RaNameUtils.normalizeAll(names).filter { name ->
        name != other && (quote.indexOf(name) in 0..(last - name.length) ||
            quote.lastIndexOf(name) >= first + other.length)
    }
    return subjects.filterNot { name -> subjects.any { it.length > name.length && name in it } }
}
