package snd.sketchpilot.core.project

import snd.sketchpilot.core.model.Snapshot

/** Structural checks that never modify a project. */
object ProjectValidator {
    fun validate(snapshot: Snapshot): List<String> {
        val issues = mutableListOf<String>()

        if (snapshot.scId.isBlank()) issues += "missing project id"
        if (snapshot.metadata["my_sc_pkg_name"].isNullOrBlank()) {
            issues += "missing application package name"
        }

        val viewIds = mutableSetOf<String>()
        snapshot.screens.forEach { screen ->
            screen.views.forEach { view ->
                if (view.id.isBlank()) issues += "${screen.fileName}: view with empty id"
                if (!viewIds.add(view.id)) issues += "duplicate view id: ${view.id}"
                if (view.parent != null && view.parent != "root" && view.parent !in viewIds) {
                    // Parent may be declared later in the file; checked again below.
                }
            }
        }
        val allIds = snapshot.screens.flatMap { it.views }.map { it.id }.toSet()
        snapshot.screens.forEach { screen ->
            screen.views.forEach { view ->
                if (view.parent != null && view.parent != "root" && view.parent !in allIds) {
                    issues += "${screen.fileName}: view ${view.id} has unknown parent ${view.parent}"
                }
            }
        }

        snapshot.logic.forEach { section ->
            val ids = section.blocks.map { it.id }.toSet()
            if (ids.size != section.blocks.size) issues += "${section.name}: duplicate block ids"
            section.blocks.forEach { block ->
                if (block.id == "0") issues += "${section.name}: block id 0 is invalid"
                if (block.nextBlock >= 0 && block.nextBlock.toString() !in ids) {
                    issues += "${section.name}: block ${block.id} points to missing ${block.nextBlock}"
                }
                listOf(block.subStack1, block.subStack2).forEach { ref ->
                    if (ref >= 0 && ref.toString() !in ids) {
                        issues += "${section.name}: block ${block.id} has dangling substack $ref"
                    }
                }
            }
        }

        return issues.distinct()
    }
}
