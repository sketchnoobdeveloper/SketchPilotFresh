package snd.sketchpilot.core.project

import snd.sketchpilot.core.sketchware.ProjectStore
import snd.sketchpilot.core.sketchware.StoredProject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.concurrent.withLock

data class TxResult(val ok: Boolean, val message: String, val changeSetId: String? = null)

/** Snapshot → staged diff → approval → atomic commit → reread verification → rollback. */
class TransactionManager(
    private val store: ProjectStore,
    private val backupDir: File,
) {
    private val locks = ConcurrentHashMap<String, ReentrantLock>()
    private val backups = ConcurrentHashMap<String, File>()
    private val history = ConcurrentHashMap<String, MutableList<String>>()

    private fun lock(scId: String): ReentrantLock =
        locks.computeIfAbsent(scId) { ReentrantLock() }

    fun stage(
        scId: String,
        actor: String,
        mutate: (StoredProject) -> Unit,
    ): ChangeSet = lock(scId).withLock {
        val before = store.load(scId)
        require(before.payloads.isNotEmpty()) { "project not found: $scId" }
        val after = before.copy()
        mutate(after)
        require(before.payloads.keys == after.payloads.keys) { "mutation changed payload set" }
        require(before.payloads != after.payloads) { "mutation produced no changes" }
        ChangeSet(
            id = UUID.randomUUID().toString(),
            scId = scId,
            actor = actor,
            before = LinkedHashMap(before.payloads),
            after = LinkedHashMap(after.payloads),
            encrypted = before.encrypted,
            createdAtMillis = System.currentTimeMillis(),
        )
    }

    fun preview(changeSet: ChangeSet): String = Diff.render(changeSet)

    fun commit(changeSet: ChangeSet, approved: Boolean): TxResult = lock(changeSet.scId).withLock {
        if (!approved) return@withLock TxResult(false, "approval required", changeSet.id)
        val current = store.load(changeSet.scId)
        if (current.payloads != changeSet.before) {
            return@withLock TxResult(false, "stale change set: project changed since staging", changeSet.id)
        }

        val backup = runCatching { createBackup(current) }
            .getOrElse { return@withLock TxResult(false, "backup failed: ${it.message}", changeSet.id) }

        val intended = LinkedHashMap(changeSet.after)
        val writeResult = runCatching {
            store.save(StoredProject(changeSet.scId, intended, changeSet.encrypted))
            val verified = store.load(changeSet.scId)
            if (verified.payloads != intended) error("reread verification failed")
        }
        if (writeResult.isFailure) {
            runCatching { restore(backup, changeSet.scId) }
            return@withLock TxResult(
                false,
                "commit failed and original restored: ${writeResult.exceptionOrNull()?.message}",
                changeSet.id,
            )
        }

        backups[changeSet.id] = backup
        record(changeSet.scId, "commit ${changeSet.id}: ${changeSet.changes.size} file(s) ok")
        TxResult(true, "committed and verified", changeSet.id)
    }

    fun rollback(changeSet: ChangeSet): TxResult = lock(changeSet.scId).withLock {
        val backup = backups[changeSet.id]
            ?: return@withLock TxResult(false, "no verified backup for ${changeSet.id}", changeSet.id)
        val restored = runCatching { restore(backup, changeSet.scId) }
            .getOrElse { return@withLock TxResult(false, "rollback failed: ${it.message}", changeSet.id) }
        if (restored.payloads != changeSet.before) {
            return@withLock TxResult(false, "rollback verification failed", changeSet.id)
        }
        record(changeSet.scId, "rollback ${changeSet.id} ok")
        TxResult(true, "rolled back and verified", changeSet.id)
    }

    fun backup(scId: String): String = lock(scId).withLock {
        createBackup(store.load(scId)).absolutePath
    }

    fun history(scId: String): List<String> = history[scId]?.toList() ?: emptyList()

    private fun record(scId: String, entry: String) {
        history.computeIfAbsent(scId) { mutableListOf() }.add(entry)
    }

    private fun createBackup(project: StoredProject): File {
        backupDir.mkdirs()
        val file = File(backupDir, "${project.scId}-${System.currentTimeMillis()}-${UUID.randomUUID()}.zip")
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            val manifest = StringBuilder()
            project.payloads.forEach { (name, text) ->
                val bytes = text.toByteArray(Charsets.UTF_8)
                val hash = sha256(bytes)
                manifest.append(name).append(' ').append(hash).append(' ')
                    .append(bytes.size).append(' ').append(if (name in project.encrypted) 1 else 0)
                    .append('\n')
                zip.putNextEntry(ZipEntry("payloads/$name"))
                zip.write(bytes)
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("MANIFEST.txt"))
            zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return file
    }

    private fun restore(backup: File, scId: String): StoredProject {
        val payloads = LinkedHashMap<String, String>()
        val encrypted = mutableSetOf<String>()
        ZipFile(backup).use { zip ->
            val manifest = zip.getEntry("MANIFEST.txt") ?: error("backup has no manifest")
            val lines = zip.getInputStream(manifest).bufferedReader().readLines()
            lines.forEach { line ->
                val parts = line.split(' ')
                require(parts.size == 4) { "invalid manifest line: $line" }
                val (name, expectedHash, size, flag) = parts
                val entry = zip.getEntry("payloads/$name") ?: error("missing payload: $name")
                val bytes = zip.getInputStream(entry).readBytes()
                require(sha256(bytes) == expectedHash && bytes.size.toString() == size) {
                    "integrity check failed: $name"
                }
                payloads[name] = String(bytes, Charsets.UTF_8)
                if (flag == "1") encrypted += name
            }
        }
        val project = StoredProject(scId, payloads, encrypted)
        store.save(project)
        return project
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
