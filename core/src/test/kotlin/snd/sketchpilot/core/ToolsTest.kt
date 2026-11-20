package snd.sketchpilot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import snd.sketchpilot.core.ipc.IpcCodec
import snd.sketchpilot.core.ipc.IpcMessage
import snd.sketchpilot.core.mcp.McpDispatcher
import snd.sketchpilot.core.project.OfflineProjectRepository
import snd.sketchpilot.core.security.AuditLog
import snd.sketchpilot.core.security.Capability
import snd.sketchpilot.core.security.PermissionPolicy
import snd.sketchpilot.core.sketchware.SketchwareCrypto
import snd.sketchpilot.core.tools.ToolRegistry
import java.io.File
import java.nio.file.Files

class ToolsTest {
    private fun registry(grants: Set<Capability>): ToolRegistry {
        val root = Files.createTempDirectory("sp-tools").toFile()
        val sc = "700"
        fun enc(name: String, text: String) {
            val f = File(root, "data/$sc/$name"); f.parentFile.mkdirs()
            f.writeBytes(SketchwareCrypto.encrypt(text))
        }
        enc("project", "{\"sc_id\":\"700\",\"my_ws_name\":\"T\",\"my_sc_pkg_name\":\"com.t\"}")
        enc("file", "@activity\n{\"fileName\":\"main\",\"fileType\":0}\n")
        enc("view", "@main.xml\n{\"id\":\"v1\",\"type\":4,\"parent\":\"root\"}\n")
        enc("logic", "@MainActivity.java_onCreate_initializeLogic\n")
        enc("library", "@compat\n")
        enc("resource", "@images\n")
        return ToolRegistry(
            OfflineProjectRepository(root),
            PermissionPolicy(grants),
            AuditLog(),
        )
    }

    @Test
    fun readToolWorks() {
        val r = registry(setOf(Capability.READ))
        assertTrue(r.call("sketchpilot.project.list").ok)
        val result = r.call("sketchpilot.ui.list_screens", mapOf("sc_id" to "700"))
        assertTrue(result.ok)
        assertTrue(result.content.contains("main"))
        assertTrue(r.list().size >= 8)
    }

    @Test
    fun permissionDenied() {
        val r = registry(emptySet())
        assertFalse(r.call("sketchpilot.project.list").ok)
        assertTrue(r.list().isEmpty())
    }

    @Test
    fun mcpToolsListWorks() {
        val r = registry(setOf(Capability.READ))
        val response = McpDispatcher(r).handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}")
        assertTrue(response.contains("sketchpilot.project.list"))
        assertTrue(response.contains("\"jsonrpc\":\"2.0\""))
    }

    @Test
    fun ipcRoundTrip() {
        val message = IpcMessage(kind = "snapshot", requestId = "r1", token = "t", payload = "{}")
        val encoded = IpcCodec.encode(message)
        assertEquals("snapshot", IpcCodec.decode(encoded)?.kind)
        assertEquals(null, IpcCodec.decode("{\"kind\":\"x\",\"requestId\":\"r\",\"version\":99}"))
    }
}
