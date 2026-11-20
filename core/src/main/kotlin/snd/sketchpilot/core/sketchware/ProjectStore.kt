package snd.sketchpilot.core.sketchware

import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class StoredProject(
    val scId: String,
    val payloads: MutableMap<String, String>,
    val encrypted: Set<String>,
) {
    fun copy(): StoredProject = StoredProject(scId, LinkedHashMap(payloads), encrypted)
}

/** Reads and atomically writes native project payloads. */
class ProjectStore(private val root: File) {
    private val tracked = listOf(
        "project", "file", "view", "logic", "library", "resource",
        "project_config", "view_root", "build_config", "custom_blocks",
        "permission", "import", "local_library", "proguard-rules.pro", "stringfog", "proguard",
    )

    fun load(scId: String): StoredProject {
        val payloads = LinkedHashMap<String, String>()
        val encrypted = mutableSetOf<String>()
        tracked.forEach { name ->
            val file = File(root, "data/$scId/$name")
            if (!file.isFile) return@forEach
            val bytes = file.readBytes()
            if (SketchwareCrypto.looksEncrypted(bytes)) {
                payloads[name] = SketchwareCrypto.decrypt(bytes)
                encrypted += name
            } else {
                payloads[name] = String(bytes, StandardCharsets.UTF_8)
            }
        }
        val listing = File(root, "mysc/list/$scId/project")
        if (listing.isFile) {
            val bytes = listing.readBytes()
            if (SketchwareCrypto.looksEncrypted(bytes)) {
                payloads[META_KEY] = SketchwareCrypto.decrypt(bytes)
                encrypted += META_KEY
            } else {
                payloads[META_KEY] = String(bytes, StandardCharsets.UTF_8)
            }
        }
        return StoredProject(scId, payloads, encrypted)
    }

    fun save(project: StoredProject) {
        project.payloads.forEach { (name, text) ->
            val file = if (name == META_KEY) {
                File(root, "mysc/list/${project.scId}/project")
            } else {
                File(root, "data/${project.scId}/$name")
            }
            val bytes = if (name in project.encrypted) {
                SketchwareCrypto.encrypt(text)
            } else {
                text.toByteArray(StandardCharsets.UTF_8)
            }
            Atomic.write(file, bytes)
        }
    }

    companion object {
        const val META_KEY = "meta/project"
    }
}

object Atomic {
    fun write(file: File, bytes: ByteArray) {
        val dir = file.parentFile ?: error("no parent for ${file.path}")
        dir.mkdirs()
        val tmp = File(dir, "${file.name}.tmp-${System.nanoTime()}")
        try {
            tmp.writeBytes(bytes)
            RandomAccessFile(tmp, "rw").use { it.channel.force(true) }
            try {
                Files.move(
                    tmp.toPath(), file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (error: Throwable) {
            tmp.delete()
            throw error
        }
    }
}
