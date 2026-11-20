package snd.sketchpilot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import snd.sketchpilot.core.mcp.McpDispatcher
import snd.sketchpilot.core.project.DebugAnalyzer
import snd.sketchpilot.core.project.TransactionManager
import snd.sketchpilot.core.project.OfflineProjectRepository
import snd.sketchpilot.core.security.AuditLog
import snd.sketchpilot.core.security.Capability
import snd.sketchpilot.core.security.PermissionPolicy
import snd.sketchpilot.core.sketchware.ProjectStore
import snd.sketchpilot.core.sketchware.SketchwareCrypto
import snd.sketchpilot.core.tools.ToolRegistry
import java.io.File
import java.nio.file.Files

class ExpansionTest {
    private fun fixture(): Pair<File, ProjectStore> {
        val root = Files.createTempDirectory("sp-expand").toFile()
        val sc = "601"
        fun enc(name: String, text: String) {
            val f = File(root, "data/$sc/$name"); f.parentFile.mkdirs()
            f.writeBytes(SketchwareCrypto.encrypt(text))
        }
        enc("project", "{\"sc_id\":\"601\",\"my_ws_name\":\"Demo\",\"my_sc_pkg_name\":\"com.demo\"}")
        enc("file", "@activity\n{\"fileName\":\"main\",\"fileType\":0}\n")
        enc("view", "@main.xml\n{\"id\":\"btn1\",\"type\":3,\"parent\":\"root\"}\n")
        enc("logic", "@MainActivity.java_onCreate_initializeLogic\n")
        enc("library", "@compat\n")
        enc("resource", "@images\n")
        enc("compile_log", "error: cannot find symbol\nwarning: deprecated API\nBUILD FAILED\n")
        val store = ProjectStore(root)
        return root to store
    }

    @Test
    fun addBlockStagesCommitsAndIsVisibleInSnapshot() {
        val (root, store) = fixture()
        val tx = TransactionManager(store, File(root, "backups"))
        val registry = ToolRegistry(
            OfflineProjectRepository(root),
            PermissionPolicy(setOf(Capability.READ, Capability.WRITE)),
            AuditLog(),
            tx,
            store,
        )
        val staged = registry.call(
            "sketchpilot.logic.add_block",
            mapOf(
                "sc_id" to "601",
                "section" to "MainActivity.java_onCreate_initializeLogic",
                "op_code" to "showMessage",
                "spec" to "showMessage %s.message",
                "parameters" to "hello",
            ),
        )
        assertTrue(staged.ok)
        val id = staged.content.lines().first().removePrefix("STAGED ")
        assertTrue(registry.call("sketchpilot.change.preview", mapOf("change_set_id" to id)).ok)
        assertTrue(registry.call("sketchpilot.change.commit", mapOf("change_set_id" to id, "approved" to "true")).ok)
        val logic = store.load("601").payloads["logic"]!!
        assertTrue(logic.contains("showMessage"))
        assertTrue(logic.contains("\"id\":\"10\""))
        assertEquals(1, OfflineProjectRepository(root).read("601").logic.size)
    }

    @Test
    fun debugAnalyzerCategorizesHostErrors() {
        val report = DebugAnalyzer.analyze("error: cannot find symbol\nwarning: old api\nBuild failed\n")
        assertEquals(2, report.errors.size)
        assertEquals(1, report.warnings.size)
        assertTrue(report.categories.containsKey("java"))
    }

    @Test
    fun mcpExposesResourcesAndPrompts() {
        val (root, store) = fixture()
        val registry = ToolRegistry(
            OfflineProjectRepository(root),
            PermissionPolicy(setOf(Capability.READ)),
            AuditLog(),
            null,
            store,
        )
        val dispatcher = McpDispatcher(registry)
        val resources = dispatcher.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"resources/read\",\"params\":{\"uri\":\"sketchpilot://projects\"}}")
        assertTrue(resources.contains("sketchpilot.project.list") || resources.contains("601"))
        val prompts = dispatcher.handle("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"prompts/get\",\"params\":{\"name\":\"inspect-project\",\"arguments\":{\"sc_id\":\"601\"}}}")
        assertTrue(prompts.contains("Do not modify anything"))
    }
}
