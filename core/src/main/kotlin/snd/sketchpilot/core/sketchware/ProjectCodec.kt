package snd.sketchpilot.core.sketchware

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import snd.sketchpilot.core.model.*
import java.io.File

/**
 * Reads Sketchware Pro project data without modifying it.
 * Only formats confirmed from the host source and real fixtures are decoded;
 * unsupported payloads are preserved as opaque section names.
 */
class ProjectCodec(private val root: File) {
    private val json = Json { ignoreUnknownKeys = true }

    fun listIds(): List<String> = (File(root, "data").listFiles() ?: emptyArray())
        .filter { it.isDirectory && it.name.all(Char::isDigit) }
        .map { it.name }
        .sorted()

    fun read(scId: String): Snapshot {
        val metaText = payload(scId, "project")
            .ifEmpty { readFile(File(root, "mysc/list/$scId/project")) }
        val meta = obj(metaText)
        val settings = obj(payload(scId, "project_config"))

        val fileSections = SectionedCodec.parse(payload(scId, "file"))
        val activities = fileSections.filter { it.name == "activity" }
            .flatMap { it.lines }.map(::obj)
        val screenNames = activities.map { str(it, "fileName") }
        val fileTypes = activities.associate { str(it, "fileName") to int(it, "fileType", 0) }

        val viewsBySection = SectionedCodec.parse(payload(scId, "view"))
            .associate { section ->
                section.name to section.lines.map(::obj).map { row ->
                    ViewNode(
                        id = str(row, "id"),
                        type = int(row, "type", -1),
                        parent = str(row, "parent").ifEmpty { null },
                        properties = row.mapValues { (_, v) -> asText(v) },
                    )
                }
            }

        val screens = screenNames.map { name ->
            ScreenSnapshot(
                fileName = name,
                fileType = fileTypes[name] ?: 0,
                views = viewsBySection[name + ".xml"] ?: emptyList(),
            )
        }

        val logicSections = SectionedCodec.parse(payload(scId, "logic")).map { section ->
            LogicSectionSnapshot(
                name = section.name,
                blocks = section.lines.map(::obj)
                    .filter { it.containsKey("opCode") }
                    .map { row ->
                        BlockSnapshot(
                            id = str(row, "id"),
                            opCode = str(row, "opCode"),
                            nextBlock = int(row, "nextBlock", -1),
                            parameters = (row["parameters"] as? JsonArray)?.map { asText(it) }
                                ?: emptyList(),
                            subStack1 = int(row, "subStack1", -1),
                            subStack2 = int(row, "subStack2", -1),
                        )
                    },
            )
        }

        val components = logicSections.filter { it.name.endsWith("_components") }
            .flatMap { section -> section.lines.map(::obj) }
            .map { row ->
                ComponentSnapshot(
                    id = str(row, "componentId"),
                    type = int(row, "type", -1),
                    parameters = listOf("param1", "param2", "param3").map { str(row, it) },
                )
            }

        val libraries = SectionedCodec.parse(payload(scId, "library")).map { it.name }
        val resources = SectionedCodec.parse(payload(scId, "resource"))
            .flatMap { it.lines.filter(String::isNotBlank) }

        return Snapshot(
            scId = str(meta, "sc_id").ifEmpty { scId },
            name = str(meta, "my_ws_name"),
            packageName = str(meta, "my_sc_pkg_name"),
            metadata = meta.mapValues { (_, v) -> asText(v) },
            settings = settings.mapValues { (_, v) -> asText(v) },
            screens = screens,
            logic = logicSections.filter { it.blocks.isNotEmpty() },
            components = components,
            libraries = libraries,
            resources = resources,
        )
    }

    private fun payload(scId: String, name: String): String =
        readFile(File(root, "data/$scId/$name"))

    private fun readFile(file: File): String {
        if (!file.isFile) return ""
        val bytes = file.readBytes()
        return if (SketchwareCrypto.looksEncrypted(bytes)) {
            SketchwareCrypto.decrypt(bytes)
        } else {
            String(bytes, Charsets.UTF_8)
        }
    }

    private fun obj(text: String): JsonObject =
        if (text.isBlank()) JsonObject(emptyMap())
        else runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            ?: JsonObject(emptyMap())

    private fun str(o: JsonObject, key: String): String =
        (o[key] as? JsonPrimitive)?.content ?: ""

    private fun int(o: JsonObject, key: String, default: Int): Int =
        (o[key] as? JsonPrimitive)?.content?.toIntOrNull() ?: default

    private fun asText(element: kotlinx.serialization.json.JsonElement): String = when (element) {
        is JsonPrimitive -> element.content
        else -> element.toString()
    }
}
