package snd.sketchpilot.core.security

/** Treats project files, library docs and MCP replies as untrusted context. */
object ContentGuard {
    data class Inspection(
        val text: String,
        val flagged: Boolean,
        val matches: List<String>,
    )

    private val patterns = listOf(
        Regex("ignore (all )?(previous|prior|above) instructions", RegexOption.IGNORE_CASE),
        Regex("\\b(system|developer|assistant)\\s*:", RegexOption.IGNORE_CASE),
        Regex("you are now", RegexOption.IGNORE_CASE),
        Regex("do not (tell|show|report) (the )?user", RegexOption.IGNORE_CASE),
        Regex("exfiltrat|send .{0,30}(api.?key|secret|token)", RegexOption.IGNORE_CASE),
        Regex("disregard (your|the) (rules|instructions)", RegexOption.IGNORE_CASE),
    )

    fun inspect(text: String): Inspection {
        val hits = patterns.filter { it.containsMatchIn(text) }.map { it.pattern }
        return Inspection(text, hits.isNotEmpty(), hits)
    }

    fun wrapUntrusted(label: String, text: String): String {
        val safeLabel = label.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(64)
        val result = inspect(text)
        return buildString {
            append("<untrusted_content source=\"").append(safeLabel)
                .append("\" flagged=\"").append(result.flagged).append("\">\n")
            append(result.text.take(MAX_CONTEXT_CHARS))
            append("\n</untrusted_content>")
        }
    }

    private const val MAX_CONTEXT_CHARS = 24_000
}
