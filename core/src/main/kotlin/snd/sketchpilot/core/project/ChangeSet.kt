package snd.sketchpilot.core.project

data class FileChange(val name: String, val before: String, val after: String)

data class ChangeSet(
    val id: String,
    val scId: String,
    val actor: String,
    val before: Map<String, String>,
    val after: Map<String, String>,
    val encrypted: Set<String>,
    val createdAtMillis: Long,
) {
    val changes: List<FileChange> = before.keys
        .filter { before[it] != after[it] }
        .map { FileChange(it, before[it].orEmpty(), after[it].orEmpty()) }
}

object Diff {
    fun render(change: FileChange): String = buildString {
        val beforeLines = change.before.removeSuffix("\n").split("\n")
        val afterLines = change.after.removeSuffix("\n").split("\n")
        append("--- a/").append(change.name).append('\n')
        append("+++ b/").append(change.name).append('\n')

        var start = 0
        while (start < beforeLines.size && start < afterLines.size &&
            beforeLines[start] == afterLines[start]
        ) start++
        var endBefore = beforeLines.size - 1
        var endAfter = afterLines.size - 1
        while (endBefore >= start && endAfter >= start &&
            beforeLines[endBefore] == afterLines[endAfter]
        ) {
            endBefore--
            endAfter--
        }
        append("@@ -${start + 1},${endBefore - start + 1} +${start + 1},${endAfter - start + 1} @@\n")
        for (i in start..endBefore) append('-').append(beforeLines[i]).append('\n')
        for (i in start..endAfter) append('+').append(afterLines[i]).append('\n')
    }

    fun render(set: ChangeSet): String =
        if (set.changes.isEmpty()) "no changes" else set.changes.joinToString("\n") { render(it) }
}
