package snd.sketchpilot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import snd.sketchpilot.core.project.Diff
import snd.sketchpilot.core.project.TransactionManager
import snd.sketchpilot.core.project.ViewEditor
import snd.sketchpilot.core.sketchware.ProjectStore
import snd.sketchpilot.core.sketchware.SketchwareCrypto
import java.io.File
import java.nio.file.Files

class TransactionTest {
    private fun fixture(): File {
        val root = Files.createTempDirectory("sp-tx").toFile()
        val sc = "601"
        fun enc(name: String, text: String) {
            val f = File(root, "data/$sc/$name"); f.parentFile.mkdirs()
            f.writeBytes(SketchwareCrypto.encrypt(text))
        }
        enc("project", "{\"sc_id\":\"601\",\"my_ws_name\":\"Demo\",\"my_sc_pkg_name\":\"com.demo\"}")
        enc("file", "@activity\n{\"fileName\":\"main\",\"fileType\":0}\n")
        enc("view", "@main.xml\n{\"id\":\"btn1\",\"type\":3,\"parent\":\"root\",\"index\":0}\n")
        enc("logic", "@MainActivity.java_onCreate_initializeLogic\n")
        enc("library", "@compat\n")
        enc("resource", "@images\n")
        return root
    }

    @Test
    fun stagesPreviewsCommitsAndRollsBack() {
        val root = fixture()
        val store = ProjectStore(root)
        val tx = TransactionManager(store, File(root, "backups"))

        val changeSet = tx.stage("601", "test") { project ->
            val view = project.payloads["view"] ?: error("view missing")
            project.payloads["view"] = ViewEditor.updateProperty(view, "main", "btn1", "text", "Saved")
        }
        assertTrue(Diff.render(changeSet).contains("+"))
        assertFalse(tx.commit(changeSet, approved = false).ok)

        assertTrue(tx.commit(changeSet, approved = true).ok)
        assertTrue(store.load("601").payloads["view"]!!.contains("Saved"))

        val result = tx.rollback(changeSet)
        assertTrue(result.message, result.ok)
        assertEquals(changeSet.before, store.load("601").payloads)
    }

    @Test
    fun rejectsStaleChangeSet() {
        val root = fixture()
        val store = ProjectStore(root)
        val tx = TransactionManager(store, File(root, "backups"))
        val changeSet = tx.stage("601", "test") { project ->
            project.payloads["view"] = project.payloads["view"]!! + "@extra.xml\n"
        }
        val current = store.load("601")
        current.payloads["project"] = current.payloads["project"]!! + " "
        store.save(current)

        val result = tx.commit(changeSet, approved = true)
        assertFalse(result.ok)
        assertTrue(result.message.contains("stale"))
    }
}
