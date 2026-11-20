package snd.sketchpilot.core.model

data class ViewNode(
    val id: String,
    val type: Int,
    val parent: String?,
    val properties: Map<String, String>,
)

data class ScreenSnapshot(
    val fileName: String,
    val fileType: Int,
    val views: List<ViewNode>,
)

data class BlockSnapshot(
    val id: String,
    val opCode: String,
    val nextBlock: Int,
    val parameters: List<String>,
    val subStack1: Int,
    val subStack2: Int,
)

data class LogicSectionSnapshot(
    val name: String,
    val blocks: List<BlockSnapshot>,
)

data class ComponentSnapshot(
    val id: String,
    val type: Int,
    val parameters: List<String>,
)

data class Snapshot(
    val scId: String,
    val name: String,
    val packageName: String,
    val metadata: Map<String, String>,
    val settings: Map<String, String>,
    val screens: List<ScreenSnapshot>,
    val logic: List<LogicSectionSnapshot>,
    val components: List<ComponentSnapshot>,
    val libraries: List<String>,
    val resources: List<String>,
)

data class ProjectSummary(
    val scId: String,
    val name: String,
    val packageName: String,
    val screenCount: Int,
)
