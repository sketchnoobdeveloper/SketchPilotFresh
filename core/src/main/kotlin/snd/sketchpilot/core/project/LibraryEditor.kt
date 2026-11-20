package snd.sketchpilot.core.project

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import snd.sketchpilot.core.sketchware.SectionedCodec

/** Edits the native library registry payload using Sketchware's section format. */
object LibraryEditor {
    private val json = Json

    fun install(payload: String, name: String, useYn: String = "true"): String {
        require(name.matches(Regex("[A-Za-z0-9_.-]+"))) { "invalid library name: $name" }
        val sections = SectionedCodec.parse(payload).toMutableList()
        val existing = sections.firstOrNull { it.name == name }
        require(existing == null) { "library already installed: $name" }
        val row = json.encodeToString(buildJsonObject {
            put("useYn", useYn)
            put("libType", "0")
        })
        sections.add(SectionedCodec.Section(name, listOf(row)))
        return SectionedCodec.serialize(sections)
    }

    fun remove(payload: String, name: String): String {
        val sections = SectionedCodec.parse(payload).filter { it.name != name }
        require(sections.size != SectionedCodec.parse(payload).size) { "library not installed: $name" }
        return SectionedCodec.serialize(sections)
    }

    fun validate(payload: String): List<String> {
        val sections = SectionedCodec.parse(payload)
        val issues = mutableListOf<String>()
        val names = sections.map { it.name }
        names.groupingBy { it }.eachCount().filter { it.value > 1 }.keys.forEach {
            issues += "duplicate library section: $it"
        }
        if (names.any { it.isBlank() }) issues += "empty library section name"
        return issues.distinct()
    }
}
