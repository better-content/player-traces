package com.bettercontent.traces.logic

import com.bettercontent.traces.api.AnnotationApi
import com.bettercontent.traces.api.SeenStateService
import com.bettercontent.traces.domain.*
import com.bettercontent.traces.storage.TraceStorageManager
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.server.level.ServerPlayer
import java.util.UUID

class AnnotationService(private val storage: TraceStorageManager) : AnnotationApi, SeenStateService {
    override fun annotationsWithin(level: Level, boundsMin: BlockPos, boundsMax: BlockPos, viewer: Player?): AnnotationViewResult {
        val annotations = storage.queryAnnotations(boundsMin, boundsMax)
        val viewerUuid = viewer?.getUUID() ?: return AnnotationViewResult(annotations, 0, null)
        val unseen = annotations.count { annotation -> annotation.revision > storage.getSeen(viewerUuid, annotation.id) }
        return AnnotationViewResult(annotations, unseen, viewerUuid)
    }

    override fun create(level: Level, viewer: Player, text: String, icon: String, color: Int, target: BlockPos): TraceAnnotation {
        val record = TraceAnnotation(
            id = UUID.randomUUID(),
            text = text,
            icon = icon,
            color = color,
            position = target,
            targetBlock = target,
            team = GLOBAL_TEAM,
            revision = 1,
            createdByInternal = viewer.getUUID()
        )
        storage.addAnnotation(record)
        return record
    }

    override fun update(level: Level, viewer: Player, id: UUID, text: String?, icon: String?, color: Int?): TraceAnnotation {
        val current = storage.annotationById(id) ?: throw IllegalArgumentException("annotation not found")
        if (current.createdByInternal != viewer.getUUID() && !canModerate(viewer)) {
            throw IllegalStateException("not permitted")
        }
        return storage.updateAnnotation(id, text, icon, color) ?: throw IllegalStateException("annotation update failed")
    }

    override fun delete(level: Level, viewer: Player, id: UUID): Boolean {
        val current = storage.annotationById(id) ?: return false
        if (current.createdByInternal != viewer.getUUID() && !canModerate(viewer)) {
            return false
        }
        storage.setSeen(viewer.getUUID(), id, current.revision)
        return storage.removeAnnotation(id)
    }

    override fun seenRevision(player: UUID, annotationId: UUID): Int = storage.getSeen(player, annotationId)

    override fun setSeen(player: UUID, annotationId: UUID, revision: Int) {
        storage.setSeen(player, annotationId, revision)
    }

    private fun canModerate(viewer: Player): Boolean = (viewer as? ServerPlayer)?.hasPermissions(2) == true
}
