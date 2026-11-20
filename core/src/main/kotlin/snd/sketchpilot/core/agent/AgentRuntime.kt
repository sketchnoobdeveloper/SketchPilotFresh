package snd.sketchpilot.core.agent

import snd.sketchpilot.core.tools.ToolResult

data class AgentTask(val id: String, val instruction: String, val projectId: String? = null)

data class PlanStep(
    val id: String,
    val tool: String,
    val arguments: Map<String, String>,
    val requiresApproval: Boolean = false,
)

data class ExecutionState(
    val task: AgentTask,
    val steps: List<PlanStep>,
    val cursor: Int = 0,
    val results: MutableMap<String, String> = mutableMapOf(),
    val status: String = "created",
    val trace: MutableList<String> = mutableListOf(),
)

/** Deterministic, stateful orchestrator. Providers may propose plans; this engine executes them. */
class AgentRuntime(
    private val toolExecutor: (String, Map<String, String>) -> ToolResult,
) {
    fun plan(task: AgentTask, availableTools: Set<String>): ExecutionState {
        val instruction = task.instruction.lowercase()
        val steps = mutableListOf<PlanStep>()
        val scId = task.projectId

        fun add(tool: String, args: Map<String, String>, approval: Boolean = false) {
            if (tool in availableTools) {
                steps.add(PlanStep("s${steps.size + 1}", tool, args, approval))
            }
        }

        add("sketchpilot.project.list", emptyMap())
        if (scId != null) {
            add("sketchpilot.project.inspect", mapOf("sc_id" to scId))
            when {
                "ui" in instruction || "layout" in instruction || "screen" in instruction ->
                    add("sketchpilot.ui.list_screens", mapOf("sc_id" to scId))

                "logic" in instruction || "block" in instruction ->
                    add("sketchpilot.logic.inspect", mapOf("sc_id" to scId))

                "component" in instruction ->
                    add("sketchpilot.component.list", mapOf("sc_id" to scId))

                "librar" in instruction ->
                    add("sketchpilot.library.inspect", mapOf("sc_id" to scId))

                "resource" in instruction ->
                    add("sketchpilot.resource.list", mapOf("sc_id" to scId))
            }
        }
        return ExecutionState(task = task, steps = steps, trace = mutableListOf("planned ${steps.size} steps"))
    }

    fun run(state: ExecutionState, maxSteps: Int = 20): ExecutionState {
        val mutable = state.copy(
            results = LinkedHashMap(state.results),
            trace = state.trace.toMutableList(),
        )
        var cursor = state.cursor
        val limit = maxSteps.coerceAtMost(state.steps.size)
        while (cursor < limit) {
            val step = state.steps[cursor]
            val result = toolExecutor(step.tool, step.arguments)
            mutable.results[step.id] = if (result.ok) result.content else "ERROR: ${result.content}"
            mutable.trace.add("${step.id} ${step.tool} ${if (result.ok) "ok" else "failed"}")
            cursor++
            if (!result.ok) {
                return mutable.copy(cursor = cursor, status = "failed")
            }
        }
        return mutable.copy(cursor = cursor, status = if (cursor >= state.steps.size) "done" else "paused")
    }
}
