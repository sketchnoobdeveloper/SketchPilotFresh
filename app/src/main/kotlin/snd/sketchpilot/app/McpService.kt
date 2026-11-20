package snd.sketchpilot.app

import android.app.Service
import android.content.Intent
import android.os.Environment
import android.os.IBinder
import android.util.Log
import snd.sketchpilot.core.mcp.McpDispatcher
import snd.sketchpilot.core.project.OfflineProjectRepository
import snd.sketchpilot.core.security.AuditLog
import snd.sketchpilot.core.security.Capability
import snd.sketchpilot.core.security.PermissionPolicy
import snd.sketchpilot.core.tools.ToolRegistry
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/** Loopback-only MCP endpoint. Network exposure is off by default. */
class McpService : Service() {
    private var server: ServerSocket? = null
    private var worker: Thread? = null

    override fun onCreate() {
        super.onCreate()
        val registry = ToolRegistry(
            OfflineProjectRepository(Environment.getExternalStorageDirectory()),
            PermissionPolicy(setOf(Capability.READ)),
            AuditLog(),
        )
        val dispatcher = McpDispatcher(registry)
        val token = SecretStore(this).read("mcp_token").orEmpty()

        worker = thread(name = "sketchpilot-mcp") {
            runCatching {
                val socket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress("127.0.0.1", PORT))
                }
                server = socket
                isRunning = true
                while (!socket.isClosed) {
                    val client = socket.accept()
                    thread { handle(client, dispatcher, token) }
                }
            }.onFailure { Log.w(TAG, "MCP server stopped", it) }
            isRunning = false
        }
    }

    private fun handle(client: Socket, dispatcher: McpDispatcher, token: String) {
        runCatching {
            client.use { socket ->
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val headers = mutableMapOf<String, String>()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    val index = line.indexOf(':')
                    if (index > 0) headers[line.substring(0, index).lowercase()] =
                        line.substring(index + 1).trim()
                }
                val length = headers["content-length"]?.toIntOrNull() ?: 0
                val buffer = CharArray(length)
                reader.read(buffer, 0, length)
                val body = String(buffer)

                val authorized = token.isNotBlank() && headers["authorization"] == "Bearer $token"
                val response = if (!authorized) {
                    "{\"jsonrpc\":\"2.0\",\"id\":0,\"error\":{\"code\":-32001,\"message\":\"Unauthorized\"}}"
                } else {
                    dispatcher.handle(body)
                }

                val bytes = response.toByteArray(Charsets.UTF_8)
                val out = socket.getOutputStream()
                val header = ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                    "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n")
                    .toByteArray(Charsets.UTF_8)
                out.write(header)
                out.write(bytes)
                out.flush()
            }
        }.onFailure { Log.w(TAG, "MCP request failed", it) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        server?.close()
        worker?.interrupt()
        isRunning = false
        super.onDestroy()
    }

    companion object {
        const val PORT = 8765
        private const val TAG = "SketchPilotMcp"
        @Volatile var isRunning = false
    }
}
