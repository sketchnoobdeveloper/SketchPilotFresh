package snd.sketchpilot.core.ai

data class ProviderConfig(
    val id: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val timeoutMs: Int = 15_000,
    val maxRetries: Int = 2,
)

data class ChatMessage(val role: String, val content: String)

data class CompletionRequest(
    val messages: List<ChatMessage>,
    val tools: List<String> = emptyList(),
    val temperature: Double? = null,
)

data class Usage(val promptTokens: Int, val completionTokens: Int)

data class CompletionResult(
    val text: String,
    val toolCalls: List<String> = emptyList(),
    val usage: Usage? = null,
)

interface ModelProvider {
    val id: String
    fun complete(config: ProviderConfig, request: CompletionRequest): CompletionResult
    fun testConnection(config: ProviderConfig): Boolean
}
