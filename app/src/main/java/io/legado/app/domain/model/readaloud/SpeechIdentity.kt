package io.legado.app.domain.model.readaloud

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

object SpeechIdentity {

    fun voiceId(engineType: String, engineId: String, speakerId: String): String =
        "voice:${sha256("$engineType\u0000$engineId\u0000$speakerId").take(32)}"

    fun chapterContentHash(paragraphs: List<CanonicalSpeechParagraph>): String = sha256(
        paragraphs.joinToString("\u0001") {
            "${it.index}\u0000${it.chapterPosition}\u0000${it.text}"
        }
    )

    fun analysisId(
        bookKey: String,
        sourceKey: String,
        chapterIndex: Int,
        contentHash: String,
        resolverVersion: String,
    ): String = "ra-analysis:${sha256("$bookKey\u0000$sourceKey\u0000$chapterIndex\u0000$contentHash\u0000$resolverVersion").take(32)}"

    fun segmentId(
        analysisId: String,
        paragraphIndex: Int,
        start: Int,
        end: Int,
    ): String = "speech-segment:${sha256("$analysisId\u0000$paragraphIndex\u0000$start\u0000$end").take(32)}"

    fun evidenceId(
        analysisId: String,
        sourceChapter: Int,
        paragraphIndex: Int,
        start: Int,
        type: String,
        subjectLocalId: String,
        objectText: String,
        quote: String,
    ): String = "ev_${sha256(
        "$analysisId\u0000$sourceChapter\u0000$paragraphIndex\u0000$start\u0000$type\u0000$subjectLocalId" +
            "\u0000$objectText\u0000$quote",
    ).take(32)}"

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}
