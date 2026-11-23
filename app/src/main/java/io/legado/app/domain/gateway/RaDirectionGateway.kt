package io.legado.app.domain.gateway


interface RaDirectionGateway {

    suspend fun plan(analysisId: String, module: String): RaDirectionPlan?

    suspend fun latestPlan(bookKey: String, chapterIndex: Int, module: String): RaDirectionPlan?

    suspend fun savePlan(plan: RaDirectionPlan, lines: List<RaDirectionLine>, audio: List<RaDirectionAudio>)

    suspend fun lines(planId: String): List<RaDirectionLine>

    suspend fun audio(planId: String): List<RaDirectionAudio>

    suspend fun markPlaybackStale(analysisId: String)
}

data class RaDirectionPlan(
    val id: String,
    val analysisId: String,
    val bookKey: String,
    val chapterIndex: Int,
    val module: String,
    val status: String,
    val directorRevision: Long = 1,
    val identityRevisionSnapshot: Long = 0,
    val origin: String = "FOREGROUND",
    val failReason: String = "",
)

data class RaDirectionLine(
    val segmentId: String,
    val emotionTag: String = "",
    val instruction: String = "",
    val voiceStateHint: String = "",
)

data class RaDirectionAudio(
    val idx: Int,
    val type: String,
    val para: Int = 0,
    val frag: Int = 0,
    val posRatio: Float = 0f,
    val tag: String = "",
    val desc: String = "",
    val delayMs: Int = 0,
    val hold: Boolean = false,
    val theme: String = "",
    val scene: String = "",
    val mood: String = "",
    val speed: String = "",
    val anchor: String = "",
    val sceneKey: String = "",
)
