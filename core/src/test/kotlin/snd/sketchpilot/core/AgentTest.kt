package snd.sketchpilot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import snd.sketchpilot.core.agent.AgentRuntime
import snd.sketchpilot.core.agent.AgentTask
import snd.sketchpilot.core.project.OfflineProjectRepository
import snd.sketchpilot.core.security.AuditLog
import snd.sketchpilot.core.security.Capability
import snd.sketchpilot.core.security.PermissionPolicy
import snd.sketchpilot.core.sketchware.SketchwareCrypto
import snd.sketchpilot.core.tools.ToolRegistry
import java.io.File
import java.nio.file.Files

class AgentTest {
    @Test
    fun plansAndExecutesDeterministically() {
        val root = Files.createTempDirectory("sp-agent").toFile()
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

        val registry = ToolRegistry(
            OfflineProjectRepository(root),
            PermissionPolicy(setOf(Capability.READ)),
            AuditLog(),
        )
        val runtime = AgentRuntime { tool, args -> registry.call(tool, args) }
        val state = runtime.plan(
            AgentTask("task-1", "inspect the ui layout", sc),
            registry.list().map { it.name }.toSet(),
        )
        assertTrue(state.steps.size >= 3)
        val finished = runtime.run(state)
        assertEquals("done", finished.status)
        assertTrue(finished.trace.any { it.contains("project.list") })
        assertTrue(finished.trace.any { it.contains("ui.list_screens") })
    }
}
