package snd.sketchpilot.core.project

/** Checks resource references without guessing undeclared resource names. */
object ResourceValidator {
    private val reference = Regex("@(color|drawable|string|font|mipmap|anim)/([^\\s\"'\\\\]*)")

    fun validate(payloads: Map<String, String>): List<String> {
        val issues = mutableListOf<String>()
        payloads.forEach { (name, text) ->
            reference.findAll(text).forEach { match ->
                val type = match.groupValues[1]
                val value = match.groupValues[2]
                if (value.isBlank()) {
                    issues += "$name: incomplete @$type/ reference"
                } else if (value.startsWith("@") || value == "/") {
                    issues += "$name: malformed @$type/ reference: ${match.value}"
                }
            }
        }
        return issues.distinct()
    }
}
