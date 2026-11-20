package snd.sketchpilot.core.sketchware

/** Lossless reader/writer for Sketchware's '@section' line format. */
object SectionedCodec {
    data class Section(val name: String, val lines: List<String>)

    fun parse(text: String): List<Section> {
        if (text.isEmpty()) return emptyList()
        val result = mutableListOf<Section>()
        var current: MutableList<String>? = null
        var name = ""
        text.removeSuffix("\n").split("\n").forEach { line ->
            if (line.startsWith("@")) {
                current = mutableListOf()
                name = line.removePrefix("@")
                result.add(Section(name, current!!))
            } else if (current != null) {
                current!!.add(line)
            } else {
                current = mutableListOf(line)
                result.add(Section("", current!!))
            }
        }
        return result
    }

    fun serialize(sections: List<Section>): String = buildString {
        sections.forEach { section ->
            append('@').append(section.name).append('\n')
            section.lines.forEach { append(it).append('\n') }
        }
    }
}
