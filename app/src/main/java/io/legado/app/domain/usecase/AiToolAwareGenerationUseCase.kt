package io.legado.app.domain.usecase

import io.legado.app.domain.gateway.AiStreamEvent
import io.legado.app.domain.gateway.AiTextGateway
import io.legado.app.domain.gateway.AiToolGateway
import io.legado.app.domain.model.AiGenerateRequest
import io.legado.app.domain.model.AiMessage
import io.legado.app.domain.model.AiMessageRole
import io.legado.app.domain.model.AiToolContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class AiToolAwareGenerationUseCase(
    private val aiTextGateway: AiTextGateway,
    private val aiToolGateway: AiToolGateway,
) {

    suspend fun generate(request: AiGenerateRequest): String {
        val output = StringBuilder()
        generateStream(request).collect { event ->
            if (event is AiStreamEvent.Content) {
                output.append(event.text)
            }
        }
        return output.toString().trim()
            .ifEmpty { error("AI returned empty text") }
    }

    fun generateStream(request: AiGenerateRequest): Flow<AiStreamEvent> = flow {
        var currentRequest = request.withReadOnlyTools()
        while (true) {
            val toolTrace = ToolTraceBuilder()
            val roundContent = StringBuilder()
            toolTrace.beginResponse()

            aiTextGateway.generateStream(currentRequest).collect { event ->
                when (event) {
                    is AiStreamEvent.Content -> {
                        roundContent.append(event.text)
                        emit(event)
                    }

                    is AiStreamEvent.Reasoning -> emit(event)
                    is AiStreamEvent.ToolCallDelta -> {
                        toolTrace.append(event)
                        emit(event)
                    }
                }
            }

            val toolCalls = toolTrace.pendingToolCalls()
            if (toolCalls.isEmpty()) {
                return@flow
            }

            val toolResultMessages = toolCalls.map { toolCall ->
                val result = aiToolGateway.execute(toolCall)
                AiMessage(
                    role = AiMessageRole.TOOL,
                    content = result.content.truncateToolOutput(),
                    toolCallId = result.callId,
                    name = result.name,
                )
            }
            currentRequest = currentRequest.copy(
                messages = currentRequest.messages +
                    AiMessage(
                        role = AiMessageRole.ASSISTANT,
                        content = roundContent.toString(),
                        toolCalls = toolCalls,
                    ) +
                    toolResultMessages,
            )
        }
    }

    private fun AiGenerateRequest.withReadOnlyTools(): AiGenerateRequest {
        val readOnlyTools = aiToolGateway.availableTools()
            .filterNot { aiToolGateway.requiresConfirmation(it.name) }
        if (readOnlyTools.isEmpty()) return this
        return copy(
            messages = buildList {
                add(TOOL_CONTEXT_MESSAGE)
                toolContext?.toMessage()?.let { add(it) }
                addAll(messages)
            },
            tools = readOnlyTools,
        )
    }

    private fun AiToolContext.toMessage(): AiMessage? {
        val lines = buildList {
            bookUrl?.takeIf { it.isNotBlank() }?.let { add("bookUrl: $it") }
            bookName?.takeIf { it.isNotBlank() }?.let { add("bookName: $it") }
            chapterIndex?.let { add("chapterIndex: $it") }
            chapterTitle?.takeIf { it.isNotBlank() }?.let { add("chapterTitle: $it") }
        }
        if (lines.isEmpty()) return null
        return AiMessage(
            role = AiMessageRole.SYSTEM,
            content = "Current local book context for read-only tools:\n" +
                lines.joinToString("\n") +
                "\nWhen calling book tools, prefer these exact identifiers unless the user explicitly asks for another book.",
        )
    }

    companion object {
        const val CACHE_PROMPT_VERSION = "tool-aware-generation-v1"

        private val TOOL_CONTEXT_MESSAGE = AiMessage(
            role = AiMessageRole.SYSTEM,
            content = "Read-only local book tools are available. Use them when needed to inspect the current book, list chapters, search cached chapter text by character or plot keyword, read cached neighboring chapters for continuity, or look up saved character profiles, relationships, world-book entries, and outlines. Use tools silently; the final response must still follow the original task output format.",
        )
    }
}


// ---------------------------------------------------------------------------
// [ttsrv] 以下两件为上游 AiChatGenerationUseCase 内定义的辅助（ToolTraceBuilder /
// truncateToolOutput）的精简复刻：只保留「工具调用循环」所需部分，去掉聊天 UI
// 投影（toParts/bookResults 等）。上游聊天链已随 AI 前台剔除，故在此本地化。
// ---------------------------------------------------------------------------

/** 工具调用轨迹收集器：合并流式 ToolCallDelta，产出待执行的调用列表。 */
internal class ToolTraceBuilder {
    private val calls = linkedMapOf<String, ToolCallTrace>()
    private val indexKeys = mutableMapOf<Int, String>()

    fun beginResponse() {
        indexKeys.clear()
    }

    fun append(event: io.legado.app.domain.gateway.AiStreamEvent.ToolCallDelta) {
        val eventId = event.id?.takeIf { it.isNotBlank() }
        if (eventId != null && event.index != null) {
            indexKeys[event.index] = eventId
        }
        val baseId = eventId
            ?: event.index?.let { indexKeys[it] ?: "tool_index_$it" }
            ?: "tool_${calls.size + 1}"
        val id = if (eventId == null && calls[baseId]?.result != null) {
            "${baseId}_${calls.size + 1}"
        } else {
            baseId
        }
        val call = calls.getOrPut(id) { ToolCallTrace(id = id, rawType = event.rawType) }
        event.name?.takeIf { it.isNotBlank() }?.let { call.name = it }
        event.argumentsDelta?.takeIf { it.isNotEmpty() }?.let { call.arguments.append(it) }
        if (call.rawType.isBlank()) call.rawType = event.rawType
    }

    fun pendingToolCalls(): List<io.legado.app.domain.model.AiToolCall> {
        return calls.values.filter { it.result == null }.mapNotNull { call ->
            val name = call.name.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            io.legado.app.domain.model.AiToolCall(
                id = call.id,
                name = name,
                arguments = call.arguments.toString().ifBlank { "{}" },
            )
        }
    }
}

internal data class ToolCallTrace(
    val id: String,
    var name: String = "",
    val arguments: StringBuilder = StringBuilder(),
    var result: String? = null,
    var rawType: String = "",
)

/** 工具输出截断：避免上下文溢出（上限与上游一致，8000 字符）。 */
internal fun String.truncateToolOutput(): String {
    val max = 8_000
    if (length <= max) return this
    return take(max) + "\n...[truncated]"
}
