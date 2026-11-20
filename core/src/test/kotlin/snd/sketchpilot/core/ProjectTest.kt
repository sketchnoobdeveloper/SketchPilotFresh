package snd.sketchpilot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import snd.sketchpilot.core.project.OfflineProjectRepository
import snd.sketchpilot.core.project.ProjectValidator
import snd.sketchpilot.core.sketchware.SketchwareCrypto
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

class ProjectTest {
    private fun fixture(): File {
        val root = Files.createTempDirectory("sp-project").toFile()
        val sc = "601"
        fun encrypted(name: String, text: String) {
            val f = File(root, "data/$sc/$name")
            f.parentFile.mkdirs()
            f.writeBytes(SketchwareCrypto.encrypt(text))
        }
        fun plain(name: String, text: String) {
            val f = File(root, "data/$sc/$name")
            f.parentFile.mkdirs()
            f.writeBytes(text.toByteArray(StandardCharsets.UTF_8))
        }
        encrypted("project", "{\"sc_id\":\"601\",\"my_ws_name\":\"Demo\",\"my_sc_pkg_name\":\"com.demo\"}")
        plain("project_config", "{\"min_sdk\":\"24\"}")
        encrypted("file", "@activity\n{\"fileName\":\"main\",\"fileType\":0,\"options\":1,\"orientation\":0}\n@customview\n")
        encrypted(
            "view",
            "@main.xml\n" +
                "{\"id\":\"linear1\",\"type\":0,\"parent\":\"root\",\"index\":0}\n" +
                "{\"id\":\"btn1\",\"type\":3,\"parent\":\"linear1\",\"index\":0}\n",
        )
        encrypted(
            "logic",
            "@MainActivity.java_components\n" +
                "{\"componentId\":\"intent1\",\"type\":1}\n" +
                "@MainActivity.java_onCreate_initializeLogic\n" +
                "{\"id\":\"1\",\"opCode\":\"setText\",\"nextBlock\":-1,\"parameters\":[\"tv1\",\"hi\"],\"subStack1\":-1,\"subStack2\":-1}\n",
        )
        encrypted("library", "@compat\n{\"useYn\":true}\n")
        encrypted("resource", "@images\n@fonts\n")
        return root
    }

    @Test
    fun readsAndValidatesSnapshot() {
        val repo = OfflineProjectRepository(fixture())
        assertEquals(listOf("601"), repo.list().map { it.scId })
        val snapshot = repo.read("601")
        assertEquals("Demo", snapshot.name)
        assertEquals("com.demo", snapshot.packageName)
        assertEquals(1, snapshot.screens.size)
        assertEquals(2, snapshot.screens[0].views.size)
        assertEquals(1, snapshot.components.size)
        assertTrue(snapshot.libraries.contains("compat"))
        assertEquals(emptyList<String>(), ProjectValidator.validate(snapshot))
    }

    @Test
    fun detectsDanglingBlock() {
        val root = fixture()
        val f = File(root, "data/601/logic")
        f.writeBytes(SketchwareCrypto.encrypt("@MainActivity.java_onCreate_initializeLogic\n{\"id\":\"1\",\"opCode\":\"x\",\"nextBlock\":99}\n"))
        val issues = OfflineProjectRepository(root).validate("601")
        assertTrue(issues.any { it.contains("dangling") || it.contains("missing") })
    }
}
