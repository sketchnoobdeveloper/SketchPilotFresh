package snd.sketchpilot.core.tools

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import snd.sketchpilot.core.project.ChangeSet
import snd.sketchpilot.core.project.Diff
import snd.sketchpilot.core.project.ProjectRepository
import snd.sketchpilot.core.project.TransactionManager
import snd.sketchpilot.core.project.ViewEditor
import snd.sketchpilot.core.security.AuditLog
import snd.sketchpilot.core.security.Capability
import snd.sketchpilot.core.security.PermissionPolicy
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

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
    private val transactions: TransactionManager? = null,
) {
    private val json = Json
    private val staged = ConcurrentHashMap<String, ChangeSet>()

    private fun schema(vararg properties: String): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            properties.forEach { put(it, buildJsonObject { put("type", "string") }) }
        })
    }

    private val tools = listOf(
        ToolSpec("sketchpilot.project.list", "List discoverable Sketchware projects", schema(), Capability.READ),
        ToolSpec("sketchpilot.project.inspect", "Inspect a project snapshot", schema("sc_id"), Capability.READ),
        ToolSpec("sketchpilot.ui.list_screens", "List screens", schema("sc_id"), Capability.READ),
        ToolSpec("sketchpilot.ui.inspect_screen", "Inspect one screen", schema("sc_id", "file_name"), Capability.READ),
        ToolSpec("sketchpilot.logic.inspect", "Inspect logic sections", schema("sc_id"), Capability.READ),
        ToolSpec("sketchpilot.component.list", "List components", schema("sc_id"), Capability.READ),
        ToolSpec("sketchpilot.library.inspect", "List libraries", schema("sc_id"), Capability.READ),
        ToolSpec("sketchpilot.resource.list", "List resources", schema("sc_id"), Capability.READ),
        ToolSpec(
            "sketchpilot.ui.set_properties",
            "Stage an in-place view property change; returns a diff and change-set id",
            schema("sc_id", "screen", "view_id", "property", "value"),
            Capability.WRITE,
        ),
        ToolSpec(
            "sketchpilot.change.preview",
            "Preview a staged change set",
            schema("change_set_id"),
            Capability.READ,
        ),
        ToolSpec(
            "sketchpilot.change.commit",
            "Commit a staged change set after explicit approval",
            schema("change_set_id", "approved"),
            Capability.WRITE,
        ),
        ToolSpec(
            "sketchpilot.change.rollback",
            "Roll back a committed change set from its verified backup",
            schema("change_set_id"),
            Capability.WRITE,
        ),
        ToolSpec(
            "sketchpilot.project.history",
            "Show change history for a project",
            schema("sc_id"),
            Capability.READ,
        ),
    )

    fun list(): List<ToolSpec> = tools.filter { policy.has(it.capability) }

    fun call(
        name: String,
        args: Map<String, String> = emptyMap(),
        actor: String = "local",
    ): ToolResult {
        val spec = tools.firstOrNull { it.name == name }
            ?: return ToolResult(false, "unknown tool: $name").also {
                audit.record(actor, name, false)
            }
        if (!policy.has(spec.capability)) {
            audit.record(actor, name, false)
            return ToolResult(false, "permission denied: ${spec.capability}")
        }
        val result = runCatching { execute(name, args, actor) }
            .fold(onSuccess = { it }, onFailure = { ToolResult(false, it.message ?: "tool failed") })
        audit.record(actor, name, result.ok)
        return result
    }

    private fun execute(name: String, args: Map<String, String>, actor: String): ToolResult = when (name) {
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
                    put(
                        "issues",
                        json.encodeToString(JsonArray(repository.validate(s.scId).map { JsonPrimitive(it) })),
                    )
                }.toString(),
            )
        }

        "sketchpilot.ui.list_screens" -> {
            val s = repository.read(requireId(args))
            ToolResult(true, json.encodeToString(JsonArray(s.screens.map { screen ->
                buildJsonObject {
                    put("file_name", screen.fileName)
                    put("file_type", screen.fileType)
                    put("views", screen.views.size)
                }
            })))
        }

        "sketchpilot.ui.inspect_screen" -> {
            val s = repository.read(requireId(args))
            val file = args["file_name"] ?: return ToolResult(false, "file_name is required")
            val screen = s.screens.firstOrNull { it.fileName == file }
                ?: return ToolResult(false, "screen not found: $file")
            ToolResult(true, json.encodeToString(JsonArray(screen.views.map { view ->
                buildJsonObject {
                    put("id", view.id)
                    put("type", view.type)
                    put("parent", view.parent ?: "root")
                }
            })))
        }

        "sketchpilot.logic.inspect" -> {
            val s = repository.read(requireId(args))
            ToolResult(true, json.encodeToString(JsonArray(s.logic.map { section ->
                buildJsonObject {
                    put("section", section.name)
                    put("blocks", section.blocks.size)
                    put(
                        "opcodes",
                        json.encodeToString(JsonArray(section.blocks.map { it.opCode }
                            .distinct().map { JsonPrimitive(it) })),
                    )
                }
            })))
        }

        "sketchpilot.component.list" -> {
            val s = repository.read(requireId(args))
            ToolResult(true, json.encodeToString(JsonArray(s.components.map { component ->
                buildJsonObject { put("id", component.id); put("type", component.type) }
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

        "sketchpilot.ui.set_properties" -> {
            val tx = transactions ?: return ToolResult(false, "transaction engine unavailable")
            val scId = requireId(args)
            val screen = args["screen"] ?: return ToolResult(false, "screen is required")
            val viewId = args["view_id"] ?: return ToolResult(false, "view_id is required")
            val property = args["property"] ?: return ToolResult(false, "property is required")
            val value = args["value"] ?: return ToolResult(false, "value is required")
            val changeSet = tx.stage(scId, actor) { project ->
                val payload = project.payloads["view"]
                    ?: error("no view payload")
                project.payloads["view"] = ViewEditor.updateProperty(
                    payload, screen, viewId, property, value,
                )
            }
            staged[changeSet.id] = changeSet
            ToolResult(true, "STAGED ${changeSet.id}\n" + Diff.render(changeSet))
        }

        "sketchpilot.change.preview" -> {
            val changeSet = staged[args["change_set_id"]]
                ?: return ToolResult(false, "unknown change set")
            ToolResult(true, Diff.render(changeSet))
        }

        "sketchpilot.change.commit" -> {
            val tx = transactions ?: return ToolResult(false, "transaction engine unavailable")
            val changeSet = staged[args["change_set_id"]]
                ?: return ToolResult(false, "unknown change set")
            val approved = args["approved"]?.equals("true", ignoreCase = true) == true
            val result = tx.commit(changeSet, approved)
            ToolResult(result.ok, result.message + " ${changeSet.id}")
        }

        "sketchpilot.change.rollback" -> {
            val tx = transactions ?: return ToolResult(false, "transaction engine unavailable")
            val changeSet = staged[args["change_set_id"]]
                ?: return ToolResult(false, "unknown change set")
            val result = tx.rollback(changeSet)
            ToolResult(result.ok, result.message)
        }

        "sketchpilot.project.history" -> {
            val tx = transactions ?: return ToolResult(false, "transaction engine unavailable")
            ToolResult(true, tx.history(requireId(args)).joinToString("\n").ifEmpty { "no history" })
        }

        else -> ToolResult(false, "unsupported tool: $name")
    }

    private fun requireId(args: Map<String, String>): String =
        args["sc_id"]?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("sc_id is required")
}
