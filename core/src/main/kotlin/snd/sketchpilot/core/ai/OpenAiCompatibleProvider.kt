package snd.sketchpilot.core.ai

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/** Provider adapter for OpenAI-compatible HTTP APIs. No credentials are stored here. */
class OpenAiCompatibleProvider : ModelProvider {
    override val id: String = "openai-compatible"
    private val json = Json { ignoreUnknownKeys = true }

    override fun complete(config: ProviderConfig, request: CompletionRequest): CompletionResult {
        val body = buildJsonObject {
            put("model", config.model)
            put("messages", JsonArray(request.messages.map { message ->
                buildJsonObject {
                    put("role", message.role)
                    put("content", message.content)
                }
            }))
            request.temperature?.let { put("temperature", it) }
        }
        val response = post(config, "/chat/completions", json.encodeToString(body))
        val root = json.parseToJsonElement(response) as? JsonObject ?: error("invalid provider response")
        val choices = root["choices"] as? JsonArray ?: error("no choices in response")
        val first = choices.firstOrNull() as? JsonObject ?: error("empty choices")
        val message = first["message"] as? JsonObject ?: error("no message")
        val text = (message["content"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
        return CompletionResult(text = text)
    }

    override fun testConnection(config: ProviderConfig): Boolean = runCatching {
        post(config, "/models", "{}")
        true
    }.getOrDefault(false)

    private fun post(config: ProviderConfig, path: String, body: String): String {
        var lastError: Throwable? = null
        repeat(config.maxRetries.coerceAtLeast(1)) { attempt ->
            runCatching {
                val connection = (URL(config.baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = config.timeoutMs
                    readTimeout = config.timeoutMs
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Authorization", "Bearer " + config.apiKey)
                    doOutput = true
                }
                connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val text = stream.bufferedReader(Charsets.UTF_8).readText()
                connection.disconnect()
                if (code !in 200..299) error("provider HTTP $code: ${text.take(200)}")
                text
            }.fold(
                onSuccess = { return it },
                onFailure = { lastError = it; if (attempt == 0) Unit },
            )
        }
        throw lastError ?: error("provider request failed")
    }
}
