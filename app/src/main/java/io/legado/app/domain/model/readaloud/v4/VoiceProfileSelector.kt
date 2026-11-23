package io.legado.app.domain.model.readaloud.v4

import com.google.gson.JsonParser

/**
 * 每句台词的声线方案选择（01 §6.2 的纯函数部分；用户台词级覆盖由调用方先行处理）。
 */
object VoiceProfileSelector {

    fun select(profiles: List<RaVoiceProfile>, chapterIndex: Int, paragraphIndex: Int): RaVoiceProfile? {
        if (profiles.isEmpty()) return null
        val ranged = profiles
            .filter { it.kind != RaVoiceProfileKind.DEFAULT }
            .filter { rangeContains(it.rangeJson, chapterIndex, paragraphIndex) }
            .sortedWith(
                compareByDescending<RaVoiceProfile> { it.locked }
                    .thenByDescending { RaVoiceProfileKind.priorityOf(it.kind) },
            )
        return ranged.firstOrNull()
            ?: profiles.firstOrNull { it.kind == RaVoiceProfileKind.DEFAULT }
            ?: profiles.firstOrNull()
    }

    fun rangeContains(rangeJson: String, chapterIndex: Int, paragraphIndex: Int): Boolean {
        if (rangeJson.isBlank()) return false
        return runCatching {
            val root = JsonParser.parseString(rangeJson).asJsonObject
            val from = root.getAsJsonObject("from")
            val to = root.getAsJsonObject("to")
            val fromChapter = from?.get("chapter")?.asInt ?: 0
            val fromParagraph = from?.get("paragraph")?.asInt ?: 0
            val toChapter = to?.get("chapter")?.asInt ?: -1
            val toParagraph = to?.get("paragraph")?.asInt ?: -1
            val afterStart = chapterIndex > fromChapter ||
                (chapterIndex == fromChapter && paragraphIndex >= fromParagraph)
            val beforeEnd = toChapter < 0 || chapterIndex < toChapter ||
                (chapterIndex == toChapter &&
                    (toParagraph < 0 || paragraphIndex <= toParagraph))
            afterStart && beforeEnd
        }.getOrDefault(false)
    }
}
