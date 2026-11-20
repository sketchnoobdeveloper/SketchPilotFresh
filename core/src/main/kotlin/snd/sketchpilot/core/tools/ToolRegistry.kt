package snd.sketchpilot.core.tools

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import snd.sketchpilot.core.project.ChangeSet
import snd.sketchpilot.core.project.ComponentEditor
import snd.sketchpilot.core.project.DebugAnalyzer
import snd.sketchpilot.core.project.LibraryEditor
import snd.sketchpilot.core.project.Diff
import snd.sketchpilot.core.project.LogicEditor
import snd.sketchpilot.core.project.ProjectValidator
import snd.sketchpilot.core.project.ResourceValidator
import snd.sketchpilot.core.project.ProjectRepository
import snd.sketchpilot.core.project.TransactionManager
import snd.sketchpilot.core.project.ViewEditor
import snd.sketchpilot.core.sketchware.ProjectStore
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
    private val store: ProjectStore? = null,
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
        ToolSpec(
            "sketchpilot.logic.add_block",
            "Stage a new native block in an event section",
            schema("sc_id", "section", "op_code", "spec", "parameters"),
            Capability.WRITE,
        ),
        ToolSpec(
            "sketchpilot.ui.validate_layout",
            "Validate project structure and view hierarchy",
            schema("sc_id"),
            Capability.READ,
        ),
        ToolSpec(
            "sketchpilot.debug.analyze",
            "Analyze the host compile log",
            schema("sc_id"),
            Capability.READ,
        ),
        ToolSpec(
            "sketchpilot.resource.validate",
            "Validate resource references in a project",
            schema("sc_id"),
            Capability.READ,
        ),
        ToolSpec(
            "sketchpilot.component.add",
            "Stage a native component registration",
            schema("sc_id", "section", "component_id", "type"),
            Capability.WRITE,
        ),
        ToolSpec(
            "sketchpilot.component.configure",
            "Stage a component parameter update",
            schema("sc_id", "section", "component_id", "param", "value"),
            Capability.WRITE,
        ),
        ToolSpec(
            "sketchpilot.component.remove",
            "Stage a component removal",
            schema("sc_id", "section", "component_id"),
            Capability.WRITE,
        ),
        ToolSpec(
            "sketchpilot.library.install",
            "Stage a library registration",
            schema("sc_id", "name"),
            Capability.WRITE,
        ),
        ToolSpec(
            "sketchpilot.library.remove",
            "Stage a library removal",
            schema("sc_id", "name"),
            Capability.WRITE,
        ),
        ToolSpec(
            "sketchpilot.library.validate",
            "Validate the native library registry",
            schema("sc_id"),
            Capability.READ,
        ),
        ToolSpec(
            "sketchpilot.project.backup",
            "Create a verified backup archive",
            schema("sc_id"),
            Capability.READ,
        ),
        ToolSpec(
            "sketchpilot.build.validate",
            "Validate project structure before a build",
            schema("sc_id"),
            Capability.READ,
        ),
        ToolSpec(
            "sketchpilot.build.inspect_errors",
            "Inspect last host build errors",
            schema("sc_id"),
            Capability.READ,
        ),
        ToolSpec(
            "sketchpilot.build.compile",
            "Host build execution is unavailable until device integration is verified",
            schema("sc_id"),
            Capability.BUILD,
        ),
    )

    fun list(): List<ToolSpec> = tools.filter { policy.has(it.capability) }

    fun stagedChanges(): List<ChangeSet> = staged.values.sortedByDescending { it.createdAtMillis }

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

        "sketchpilot.logic.add_block" -> {
            val tx = transactions ?: return ToolResult(false, "transaction engine unavailable")
            val scId = requireId(args)
            val section = args["section"] ?: return ToolResult(false, "section is required")
            val opCode = args["op_code"] ?: return ToolResult(false, "op_code is required")
            val parameters = args["parameters"].orEmpty()
                .split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val changeSet = tx.stage(scId, actor) { project ->
                val logic = project.payloads["logic"] ?: error("no logic payload")
                project.payloads["logic"] = LogicEditor.appendBlock(
                    logic,
                    section,
                    opCode,
                    args["spec"].orEmpty(),
                    args["type"] ?: " ",
                    parameters,
                )
            }
            staged[changeSet.id] = changeSet
            ToolResult(true, "STAGED ${changeSet.id}\n" + Diff.render(changeSet))
        }

        "sketchpilot.ui.validate_layout" -> {
            val issues = repository.validate(requireId(args))
            ToolResult(true, if (issues.isEmpty()) "valid" else issues.joinToString("\n"))
        }

        "sketchpilot.debug.analyze" -> {
            val projectStore = store ?: return ToolResult(false, "project store unavailable")
            val log = projectStore.load(requireId(args)).payloads["compile_log"].orEmpty()
            val report = DebugAnalyzer.analyze(log)
            ToolResult(
                true,
                buildJsonObject {
                    put("lines", report.totalLines)
                    put("errors", report.errors.size)
                    put("warnings", report.warnings.size)
                    put("categories", json.encodeToString(JsonObject(report.categories
                        .mapValues { JsonPrimitive(it.value) })))
                    put("sample", json.encodeToString(JsonArray(report.errors.take(10)
                        .map { JsonPrimitive(it) })))
                }.toString(),
            )
        }

        "sketchpilot.resource.validate" -> {
            val projectStore = store ?: return ToolResult(false, "project store unavailable")
            val issues = ResourceValidator.validate(projectStore.load(requireId(args)).payloads)
            ToolResult(true, if (issues.isEmpty()) "valid" else issues.joinToString("\n"))
        }

        "sketchpilot.component.add" -> stageLogicChange(args, actor) { payload, section ->
            ComponentEditor.add(
                payload,
                section,
                args["component_id"] ?: error("component_id is required"),
                args["type"]?.toIntOrNull() ?: error("valid type is required"),
                listOf("param1", "param2", "param3").map { args[it].orEmpty() },
            )
        }

        "sketchpilot.component.configure" -> stageLogicChange(args, actor) { payload, section ->
            ComponentEditor.configure(
                payload,
                section,
                args["component_id"] ?: error("component_id is required"),
                args["param"] ?: error("param is required"),
                args["value"] ?: error("value is required"),
            )
        }

        "sketchpilot.component.remove" -> stageLogicChange(args, actor) { payload, section ->
            ComponentEditor.remove(
                payload,
                section,
                args["component_id"] ?: error("component_id is required"),
            )
        }

        "sketchpilot.library.install" -> {
            val tx = transactions ?: return ToolResult(false, "transaction engine unavailable")
            val name = args["name"] ?: return ToolResult(false, "name is required")
            val changeSet = tx.stage(requireId(args), actor) { project ->
                val library = project.payloads["library"] ?: error("no library payload")
                project.payloads["library"] = LibraryEditor.install(library, name)
            }
            staged[changeSet.id] = changeSet
            ToolResult(true, "STAGED ${changeSet.id}\n" + Diff.render(changeSet))
        }

        "sketchpilot.library.remove" -> {
            val tx = transactions ?: return ToolResult(false, "transaction engine unavailable")
            val name = args["name"] ?: return ToolResult(false, "name is required")
            val changeSet = tx.stage(requireId(args), actor) { project ->
                val library = project.payloads["library"] ?: error("no library payload")
                project.payloads["library"] = LibraryEditor.remove(library, name)
            }
            staged[changeSet.id] = changeSet
            ToolResult(true, "STAGED ${changeSet.id}\n" + Diff.render(changeSet))
        }

        "sketchpilot.library.validate" -> {
            val projectStore = store ?: return ToolResult(false, "project store unavailable")
            val issues = LibraryEditor.validate(
                projectStore.load(requireId(args)).payloads["library"].orEmpty(),
            )
            ToolResult(true, if (issues.isEmpty()) "valid" else issues.joinToString("\n"))
        }

        "sketchpilot.project.backup" -> {
            val tx = transactions ?: return ToolResult(false, "transaction engine unavailable")
            ToolResult(true, tx.backup(requireId(args)))
        }

        "sketchpilot.build.validate" -> {
            val issues = ProjectValidator.validate(repository.read(requireId(args)))
            ToolResult(true, if (issues.isEmpty()) "valid" else issues.joinToString("\n"))
        }

        "sketchpilot.build.inspect_errors" -> call("sketchpilot.debug.analyze", args, actor)

        "sketchpilot.build.compile" -> ToolResult(
            false,
            "unavailable: host build execution requires verified device/LSPosed integration",
        )

        else -> ToolResult(false, "unsupported tool: $name")
    }

    private fun stageLogicChange(
        args: Map<String, String>,
        actor: String,
        edit: (String, String) -> String,
    ): ToolResult {
        val tx = transactions ?: return ToolResult(false, "transaction engine unavailable")
        val section = args["section"] ?: return ToolResult(false, "section is required")
        val changeSet = tx.stage(requireId(args), actor) { project ->
            val logic = project.payloads["logic"] ?: error("no logic payload")
            project.payloads["logic"] = edit(logic, section)
        }
        staged[changeSet.id] = changeSet
        return ToolResult(true, "STAGED ${changeSet.id}\n" + Diff.render(changeSet))
    }

    private fun requireId(args: Map<String, String>): String =
        args["sc_id"]?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("sc_id is required")
}
