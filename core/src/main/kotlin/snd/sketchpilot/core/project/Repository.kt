package snd.sketchpilot.core.project

import snd.sketchpilot.core.model.ProjectSummary
import snd.sketchpilot.core.model.Snapshot
import snd.sketchpilot.core.sketchware.ProjectCodec
import java.io.File

interface ProjectRepository {
    fun list(): List<ProjectSummary>
    fun read(scId: String): Snapshot
    fun validate(scId: String): List<String>
}

/** Read-only repository over a Sketchware storage root. */
class OfflineProjectRepository(root: File) : ProjectRepository {
    private val codec = ProjectCodec(root)

    override fun list(): List<ProjectSummary> = codec.listIds().map { id ->
        val snapshot = read(id)
        ProjectSummary(
            scId = id,
            name = snapshot.name,
            packageName = snapshot.packageName,
            screenCount = snapshot.screens.size,
        )
    }

    override fun read(scId: String): Snapshot = codec.read(scId)

    override fun validate(scId: String): List<String> = ProjectValidator.validate(read(scId))
}
