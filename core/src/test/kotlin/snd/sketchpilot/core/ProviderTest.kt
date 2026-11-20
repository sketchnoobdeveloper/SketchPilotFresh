package snd.sketchpilot.core

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import snd.sketchpilot.core.ai.ChatMessage
import snd.sketchpilot.core.ai.CompletionRequest
import snd.sketchpilot.core.ai.OpenAiCompatibleProvider
import snd.sketchpilot.core.ai.ProviderConfig
import snd.sketchpilot.core.project.OfflineProjectRepository
import snd.sketchpilot.core.security.AuditLog
import snd.sketchpilot.core.security.Capability
import snd.sketchpilot.core.security.PermissionPolicy
import snd.sketchpilot.core.sketchware.ProjectStore
import snd.sketchpilot.core.sketchware.SketchwareCrypto
import snd.sketchpilot.core.tools.ToolRegistry
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Base64

class ProviderTest {
    @Test
    fun openAiCompatibleProviderParsesRealHttpResponse() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            val body = """{"choices":[{"message":{"content":"Hello from provider"}}]}"""
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val config = ProviderConfig(
                id = "test",
                baseUrl = "http://127.0.0.1:${server.address.port}/v1",
                apiKey = "test-key",
                model = "test-model",
            )
            val result = OpenAiCompatibleProvider().complete(
                config,
                CompletionRequest(listOf(ChatMessage("user", "hi"))),
            )
            assertEquals("Hello from provider", result.text)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun assetImportRejectsTraversalAndAcceptsValidFile() {
        val root = Files.createTempDirectory("sp-asset").toFile()
        val sc = "601"
        fun enc(name: String, text: String) {
            val f = File(root, "data/$sc/$name"); f.parentFile.mkdirs()
            f.writeBytes(SketchwareCrypto.encrypt(text))
        }
        enc("project", "{\"sc_id\":\"601\",\"my_ws_name\":\"D\",\"my_sc_pkg_name\":\"com.d\"}")
        enc("file", "@activity\n{\"fileName\":\"main\",\"fileType\":0}\n")
        enc("view", "@main.xml\n{\"id\":\"v1\",\"type\":4,\"parent\":\"root\"}\n")
        enc("logic", "@MainActivity.java_onCreate_initializeLogic\n")
        enc("library", "@compat\n")
        enc("resource", "@images\n")

        val store = ProjectStore(root)
        val registry = ToolRegistry(
            OfflineProjectRepository(root),
            PermissionPolicy(setOf(Capability.READ, Capability.WRITE)),
            AuditLog(),
            null,
            store,
        )
        val content = Base64.getEncoder().encodeToString("drawable".toByteArray())
        val valid = registry.call(
            "sketchpilot.resource.import",
            mapOf("sc_id" to sc, "path" to "resource/drawable/ic_test.txt", "content_base64" to content),
        )
        assertTrue(valid.content, valid.ok)
        assertTrue(File(root, "data/$sc/files/resource/drawable/ic_test.txt").isFile)

        val traversal = registry.call(
            "sketchpilot.resource.import",
            mapOf("sc_id" to sc, "path" to "../escape.txt", "content_base64" to content),
        )
        assertFalse(traversal.ok)

        val listed = registry.call("sketchpilot.resource.list_files", mapOf("sc_id" to sc))
        assertTrue(listed.content.contains("ic_test.txt"))
    }
}
