package snd.sketchpilot.core.project

/** Reads host compiler diagnostics and groups them without modifying anything. */
object DebugAnalyzer {
    data class Report(
        val totalLines: Int,
        val errors: List<String>,
        val warnings: List<String>,
        val categories: Map<String, Int>,
    )

    fun analyze(compileLog: String): Report {
        val lines = compileLog.lineSequence().filter { it.isNotBlank() }.toList()
        val errors = lines.filter { line ->
            Regex("error:|Exception|FAILURE:|FAILED|Build failed", RegexOption.IGNORE_CASE)
                .containsMatchIn(line)
        }
        val warnings = lines.filter { line ->
            Regex("warning:", RegexOption.IGNORE_CASE).containsMatchIn(line)
        }
        val categories = linkedMapOf<String, Int>()
        errors.forEach { line ->
            val category = when {
                line.contains("AAPT", true) -> "resource"
                line.contains("Manifest", true) -> "manifest"
                line.contains("Kotlin", true) -> "kotlin"
                line.contains("Exception", true) -> "runtime"
                line.contains("error:", true) -> "java"
                else -> "build"
            }
            categories[category] = (categories[category] ?: 0) + 1
        }
        return Report(lines.size, errors, warnings, categories)
    }
}
