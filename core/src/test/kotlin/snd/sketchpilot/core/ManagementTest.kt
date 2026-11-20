package snd.sketchpilot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import snd.sketchpilot.core.project.OfflineProjectRepository
import snd.sketchpilot.core.project.TransactionManager
import snd.sketchpilot.core.security.AuditLog
import snd.sketchpilot.core.security.Capability
import snd.sketchpilot.core.security.PermissionPolicy
import snd.sketchpilot.core.sketchware.ProjectStore
import snd.sketchpilot.core.sketchware.SketchwareCrypto
import snd.sketchpilot.core.tools.ToolRegistry
import java.io.File
import java.nio.file.Files

class ManagementTest {
    private fun fixture(): Pair<File, ProjectStore> {
        val root = Files.createTempDirectory("sp-manage").toFile()
        val sc = "601"
        fun enc(name: String, text: String) {
            val f = File(root, "data/$sc/$name"); f.parentFile.mkdirs()
            f.writeBytes(SketchwareCrypto.encrypt(text))
        }
        enc("project", "{\"sc_id\":\"601\",\"my_ws_name\":\"D\",\"my_sc_pkg_name\":\"com.d\"}")
        enc("file", "@activity\n{\"fileName\":\"main\",\"fileType\":0}\n")
        enc("view", "@main.xml\n{\"id\":\"v1\",\"type\":4,\"parent\":\"root\"}\n")
        enc(
            "logic",
            "@MainActivity.java_components\n{\"componentId\":\"intent1\",\"param1\":\"\",\"param2\":\"\",\"param3\":\"\",\"type\":1}\n" +
                "@MainActivity.java_onCreate_initializeLogic\n",
        )
        enc("library", "@compat\n{\"useYn\":true}\n")
        enc("resource", "@images\n")
        return root to ProjectStore(root)
    }

    private fun registry(root: File, store: ProjectStore): ToolRegistry {
        val tx = TransactionManager(store, File(root, "backups"))
        return ToolRegistry(
            OfflineProjectRepository(root),
            PermissionPolicy(setOf(Capability.READ, Capability.WRITE, Capability.BUILD)),
            AuditLog(),
            tx,
            store,
        )
    }

    @Test
    fun componentLifecycle() {
        val (root, store) = fixture()
        val registry = registry(root, store)
        val staged = registry.call(
            "sketchpilot.component.add",
            mapOf(
                "sc_id" to "601",
                "section" to "MainActivity.java_components",
                "component_id" to "sp1",
                "type" to "2",
                "param1" to "userData",
            ),
        )
        assertTrue(staged.ok)
        val id = staged.content.lines().first().removePrefix("STAGED ")
        assertTrue(registry.call("sketchpilot.change.commit", mapOf("change_set_id" to id, "approved" to "true")).ok)
        val snapshot = OfflineProjectRepository(root).read("601")
        assertEquals(2, snapshot.components.size)
        assertTrue(snapshot.components.any { it.id == "sp1" && it.type == 2 })

        val removal = registry.call(
            "sketchpilot.component.remove",
            mapOf("sc_id" to "601", "section" to "MainActivity.java_components", "component_id" to "sp1"),
        )
        assertTrue(removal.ok)
        val removalId = removal.content.lines().first().removePrefix("STAGED ")
        assertTrue(registry.call("sketchpilot.change.commit", mapOf("change_set_id" to removalId, "approved" to "true")).ok)
        assertEquals(1, OfflineProjectRepository(root).read("601").components.size)
    }

    @Test
    fun libraryLifecycleAndBackup() {
        val (root, store) = fixture()
        val registry = registry(root, store)
        val installed = registry.call("sketchpilot.library.install", mapOf("sc_id" to "601", "name" to "firebase"))
        assertTrue(installed.ok)
        val id = installed.content.lines().first().removePrefix("STAGED ")
        assertTrue(registry.call("sketchpilot.change.commit", mapOf("change_set_id" to id, "approved" to "true")).ok)
        assertTrue(OfflineProjectRepository(root).read("601").libraries.contains("firebase"))

        val validate = registry.call("sketchpilot.library.validate", mapOf("sc_id" to "601"))
        assertTrue(validate.content == "valid")

        val backup = registry.call("sketchpilot.project.backup", mapOf("sc_id" to "601"))
        assertTrue(backup.ok)
        assertTrue(File(backup.content).isFile)
    }

    @Test
    fun buildCompileIsHonestlyUnavailable() {
        val (root, store) = fixture()
        val registry = registry(root, store)
        val result = registry.call("sketchpilot.build.compile", mapOf("sc_id" to "601"))
        assertFalse(result.ok)
        assertTrue(result.content.contains("unavailable"))
    }
}
