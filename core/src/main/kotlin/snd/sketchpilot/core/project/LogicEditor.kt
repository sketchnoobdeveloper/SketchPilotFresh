package snd.sketchpilot.core.project

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import snd.sketchpilot.core.sketchware.SectionedCodec

/** Appends a native-compatible block to an event section without touching other blocks. */
object LogicEditor {
    private val json = Json

    fun appendBlock(
        logicPayload: String,
        sectionName: String,
        opCode: String,
        spec: String,
        type: String = " ",
        parameters: List<String> = emptyList(),
    ): String {
        require(opCode.isNotBlank()) { "opCode is required" }
        val sections = SectionedCodec.parse(logicPayload).toMutableList()
        val index = sections.indexOfFirst { it.name == sectionName }
        require(index >= 0) { "logic section not found: $sectionName" }

        val existing = sections[index].lines.toMutableList()
        var maxId = 0
        existing.forEach { line ->
            runCatching {
                val row = json.parseToJsonElement(line) as? JsonObject
                val id = (row?.get("id") as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
                if (id > maxId) maxId = id
            }
        }
        val block = buildJsonObject {
            put("color", -16354097)
            put("id", (maxId + 10).toString())
            put("nextBlock", -1)
            put("opCode", opCode)
            put("parameters", JsonArray(parameters.map { JsonPrimitive(it) }))
            put("spec", spec.ifBlank { opCode })
            put("subStack1", -1)
            put("subStack2", -1)
            put("type", type)
            put("typeName", opCode)
        }
        existing.add(json.encodeToString(block))
        sections[index] = sections[index].copy(lines = existing)
        return SectionedCodec.serialize(sections)
    }
}
