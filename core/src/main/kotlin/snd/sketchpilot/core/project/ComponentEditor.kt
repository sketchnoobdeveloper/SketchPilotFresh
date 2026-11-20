package snd.sketchpilot.core.project

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import snd.sketchpilot.core.sketchware.SectionedCodec

/** Edits native component rows in place: IDs, order and unrelated sections stay intact. */
object ComponentEditor {
    private val json = Json

    fun add(
        logicPayload: String,
        section: String,
        componentId: String,
        type: Int,
        params: List<String> = listOf("", "", ""),
    ): String = update(logicPayload, section, componentId) { rows, index ->
        require(rows.none { it.containsKey("componentId") &&
            (it["componentId"] as? JsonPrimitive)?.content == componentId }) {
            "component already exists: $componentId"
        }
        val values = (params + listOf("", "", "")).take(3)
        rows.add(json.encodeToString(buildJsonObject {
            put("componentId", componentId)
            put("param1", values[0])
            put("param2", values[1])
            put("param3", values[2])
            put("type", type)
        }))
    }

    fun configure(
        logicPayload: String,
        section: String,
        componentId: String,
        param: String,
        value: String,
    ): String {
        require(param in setOf("param1", "param2", "param3")) { "invalid component parameter: $param" }
        return update(logicPayload, section, componentId) { rows, index ->
            require(index >= 0) { "component not found: $componentId" }
            val row = rows[index]
            val mutable = row.toMutableMap()
            mutable[param] = JsonPrimitive(value)
            rows[index] = JsonObject(mutable)
        }
    }

    fun remove(
        logicPayload: String,
        section: String,
        componentId: String,
    ): String = update(logicPayload, section, componentId) { rows, index ->
        require(index >= 0) { "component not found: $componentId" }
        rows.removeAt(index)
    }

    private inline fun update(
        logicPayload: String,
        section: String,
        componentId: String,
        action: (MutableList<String>, Int) -> Unit,
    ): String {
        val sections = SectionedCodec.parse(logicPayload).toMutableList()
        val sectionIndex = sections.indexOfFirst { it.name == section }
        require(sectionIndex >= 0) { "component section not found: $section" }
        val rows = sections[sectionIndex].lines.toMutableList()
        val rowIndex = rows.indexOfFirst { line ->
            runCatching {
                (json.parseToJsonElement(line) as? JsonObject)?.get("componentId")?.let {
                    (it as? JsonPrimitive)?.content
                } == componentId
            }.getOrDefault(false)
        }
        action(rows, rowIndex)
        sections[sectionIndex] = sections[sectionIndex].copy(lines = rows)
        return SectionedCodec.serialize(sections)
    }
}
