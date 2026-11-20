package snd.sketchpilot.core.project

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import snd.sketchpilot.core.sketchware.SectionedCodec

/** In-place view property update: IDs, parents, children and handlers stay intact. */
object ViewEditor {
    private val json = Json

    fun updateProperty(
        viewPayload: String,
        screen: String,
        viewId: String,
        property: String,
        value: String,
    ): String {
        val sections = SectionedCodec.parse(viewPayload).toMutableList()
        val sectionIndex = sections.indexOfFirst { it.name == screen + ".xml" }
        require(sectionIndex >= 0) { "screen not found: $screen" }

        val lines = sections[sectionIndex].lines.toMutableList()
        val rowIndex = lines.indexOfFirst { line ->
            runCatching {
                (json.parseToJsonElement(line) as? JsonObject)?.get("id")?.let {
                    (it as? JsonPrimitive)?.content
                } == viewId
            }.getOrDefault(false)
        }
        require(rowIndex >= 0) { "view not found: $viewId" }

        val objectRow = json.parseToJsonElement(lines[rowIndex]) as JsonObject
        val updated = objectRow.toMutableMap()
        when (property) {
            "text", "hint" -> {
                val nested = (updated[property] as? JsonObject ?: JsonObject(emptyMap())).toMutableMap()
                nested["text"] = JsonPrimitive(value)
                nested["textSize"] = JsonPrimitive(16)
                nested["textColor"] = JsonPrimitive(-16777216)
                updated[property] = JsonObject(nested)
            }

            "backgroundColor" -> {
                val layout = (updated["layout"] as? JsonObject ?: JsonObject(emptyMap())).toMutableMap()
                layout[property] = JsonPrimitive(value.toIntOrNull() ?: error("invalid color: $value"))
                updated["layout"] = JsonObject(layout)
            }

            else -> updated[property] = scalar(value)
        }
        lines[rowIndex] = json.encodeToString(JsonObject(updated))
        sections[sectionIndex] = sections[sectionIndex].copy(lines = lines)
        return SectionedCodec.serialize(sections)
    }

    private fun scalar(value: String) = when {
        value.equals("true", ignoreCase = true) -> JsonPrimitive(true)
        value.equals("false", ignoreCase = true) -> JsonPrimitive(false)
        value.toIntOrNull() != null -> JsonPrimitive(value.toInt())
        value.toDoubleOrNull() != null -> JsonPrimitive(value.toDouble())
        else -> JsonPrimitive(value)
    }
}
