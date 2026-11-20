package snd.sketchpilot.core.tools

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import snd.sketchpilot.core.project.ProjectRepository
import snd.sketchpilot.core.security.AuditLog
import snd.sketchpilot.core.security.Capability
import snd.sketchpilot.core.security.PermissionPolicy

data class ToolSpec(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val capability: Capability,
)

data class ToolResult(val ok: Boolean, val content: String)

/** Single source of truth for in-app agent and MCP calls. */
class ToolRegistry(
    private val repository: ProjectRepository,
    private val policy: PermissionPolicy,
    private val audit: AuditLog,
) {
    private val json = Json

    private fun schema(vararg properties: String): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            properties.forEach { put(it, buildJsonObject { put("type", "string") }) }
        })
    }

    private val readOnlyTools = listOf(
        ToolSpec(
            "sketchpilot.project.list",
            "List discoverable Sketchware projects",
            schema(),
            Capability.READ,
        ),
        ToolSpec(
            "sketchpilot.project.inspect",
            "Inspect a project snapshot without changing it",
            schema("sc_id"),
            Capability.READ,
        ),
        ToolSpec(
            "sketchpilot.ui.list_screens",
            "List screens of a project",
            schema("sc_id"),
            Capability.READ,
        ),
        ToolSpec(
            "sketchpilot.ui.inspect_screen",
            "Inspect the view tree of one screen",
            schema("sc_id", "file_name"),
            Capability.READ,
        ),
        ToolSpec(
            "sketchpilot.logic.inspect",
            "Inspect logic sections and block summaries",
            schema("sc_id"),
            Capability.READ,
        ),
        ToolSpec(
            "sketchpilot.component.list",
            "List components declared in a project",
            schema("sc_id"),
            Capability.READ,
        ),
        ToolSpec(
            "sketchpilot.library.inspect",
            "List configured libraries",
            schema("sc_id"),
            Capability.READ,
        ),
        ToolSpec(
            "sketchpilot.resource.list",
            "List referenced resources",
            schema("sc_id"),
            Capability.READ,
        ),
    )

    fun list(): List<ToolSpec> = readOnlyTools.filter { policy.has(it.capability) }

    fun call(name: String, args: Map<String, String>, actor: String = "local"): ToolResult {
        val spec = readOnlyTools.firstOrNull { it.name == name }
            ?: return ToolResult(false, "unknown tool: $name").also {
                audit.record(actor, name, false)
            }
        if (!policy.has(spec.capability)) {
            audit.record(actor, name, false)
            return ToolResult(false, "permission denied: ${spec.capability}")
        }
        val result = runCatching { execute(name, args) }
            .fold(onSuccess = { it }, onFailure = { ToolResult(false, it.message ?: "tool failed") })
        audit.record(actor, name, result.ok)
        return result
    }

    private fun execute(name: String, args: Map<String, String>): ToolResult = when (name) {
        "sketchpilot.project.list" -> ToolResult(
            true,
            json.encodeToString(JsonArray(repository.list().map { p ->
                buildJsonObject {
                    put("sc_id", p.scId)
                    put("name", p.name)
                    put("package_name", p.packageName)
                    put("screens", p.screenCount)
                }
            })),
        )

        "sketchpilot.project.inspect" -> {
            val s = repository.read(requireId(args))
            ToolResult(
                true,
                buildJsonObject {
                    put("sc_id", s.scId)
                    put("name", s.name)
                    put("package_name", s.packageName)
                    put("screens", s.screens.size)
                    put("components", s.components.size)
                    put("libraries", s.libraries.size)
                    put("issues", json.encodeToString(JsonArray(repository.validate(s.scId).map { JsonPrimitive(it) })))
                }.toString(),
            )
        }

        "sketchpilot.ui.list_screens" -> {
            val s = repository.read(requireId(args))
            ToolResult(true, json.encodeToString(JsonArray(s.screens.map { buildJsonObject {
                put("file_name", it.fileName)
                put("file_type", it.fileType)
                put("views", it.views.size)
            } })))
        }

        "sketchpilot.ui.inspect_screen" -> {
            val s = repository.read(requireId(args))
            val file = args["file_name"] ?: return ToolResult(false, "file_name is required")
            val screen = s.screens.firstOrNull { it.fileName == file }
                ?: return ToolResult(false, "screen not found: $file")
            ToolResult(true, json.encodeToString(JsonArray(screen.views.map { v ->
                buildJsonObject {
                    put("id", v.id)
                    put("type", v.type)
                    put("parent", v.parent ?: "root")
                }
            })))
        }

        "sketchpilot.logic.inspect" -> {
            val s = repository.read(requireId(args))
            ToolResult(true, json.encodeToString(JsonArray(s.logic.map { section ->
                buildJsonObject {
                    put("section", section.name)
                    put("blocks", section.blocks.size)
                    put("opcodes", json.encodeToString(JsonArray(section.blocks.map { it.opCode }.distinct().map { JsonPrimitive(it) })))
                }
            })))
        }

        "sketchpilot.component.list" -> {
            val s = repository.read(requireId(args))
            ToolResult(true, json.encodeToString(JsonArray(s.components.map { c ->
                buildJsonObject { put("id", c.id); put("type", c.type) }
            })))
        }

        "sketchpilot.library.inspect" -> {
            val s = repository.read(requireId(args))
            ToolResult(true, json.encodeToString(JsonArray(s.libraries.map { JsonPrimitive(it) })))
        }

        "sketchpilot.resource.list" -> {
            val s = repository.read(requireId(args))
            ToolResult(true, json.encodeToString(JsonArray(s.resources.map { JsonPrimitive(it) })))
        }

        else -> ToolResult(false, "unsupported tool: $name")
    }

    private fun requireId(args: Map<String, String>): String =
        args["sc_id"]?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("sc_id is required")
}
