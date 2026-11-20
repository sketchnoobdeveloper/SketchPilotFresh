package snd.sketchpilot.core.ipc

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val IPC_VERSION = 1
const val MAX_IPC_BYTES = 1_048_576

@Serializable
data class IpcMessage(
    val version: Int = IPC_VERSION,
    val kind: String,
    val requestId: String,
    val token: String = "",
    val payload: String = "",
    val chunkIndex: Int = 0,
    val chunkCount: Int = 1,
)

object IpcCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(message: IpcMessage): String {
        val text = json.encodeToString(message)
        require(text.toByteArray().size <= MAX_IPC_BYTES) { "ipc message too large" }
        return text
    }

    fun decode(text: String): IpcMessage? {
        if (text.toByteArray().size > MAX_IPC_BYTES) return null
        val message = runCatching { json.decodeFromString<IpcMessage>(text) }.getOrNull()
            ?: return null
        return if (message.version == IPC_VERSION && message.chunkCount > 0 &&
            message.chunkIndex in 0 until message.chunkCount
        ) message else null
    }
}

interface IpcTransport {
    fun send(message: IpcMessage)
    fun receive(): IpcMessage?
    fun isAlive(): Boolean
    fun close()
}
