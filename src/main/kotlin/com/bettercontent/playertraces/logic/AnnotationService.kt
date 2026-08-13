package com.bettercontent.playertraces.logic

import com.bettercontent.playertraces.api.AnnotationApi
import com.bettercontent.playertraces.api.SeenStateService
import com.bettercontent.playertraces.domain.*
import com.bettercontent.playertraces.storage.TraceStorageManager
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
        validateAnnotationFields(text, icon, color, false)
        requireTargetInReach(viewer, target)
        require(storage.queryAnnotations(target, target).none {
            it.targetBlock == target && it.createdByInternal == viewer.getUUID()
        }) { "you already have an annotation on this block" }
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

    override fun update(level: Level, viewer: Player, id: UUID, expectedRevision: Int, text: String?, icon: String?, color: Int?): TraceAnnotation {
        val current = storage.annotationById(id) ?: throw IllegalArgumentException("annotation not found")
        if (current.createdByInternal != viewer.getUUID() && !canModerate(viewer)) {
            throw IllegalStateException("not permitted")
        }
        requireTargetInReach(viewer, current.targetBlock)
        require(current.revision == expectedRevision) { "annotation changed; reopen it before saving" }
        validateAnnotationFields(text ?: current.text, icon ?: current.icon, color ?: current.color, false)
        return storage.updateAnnotation(id, text, icon, color) ?: throw IllegalStateException("annotation update failed")
    }

    fun createComponents(level: Level, viewer: Player, text: String, icon: String, color: Int, target: BlockPos, hasEcho: Boolean): TraceAnnotation {
        validateAnnotationFields(text, icon, color, hasEcho)
        requireTargetInReach(viewer, target)
        require(storage.queryAnnotations(target, target).none {
            it.targetBlock == target && it.createdByInternal == viewer.uuid
        }) { "you already have an annotation on this block" }
        return TraceAnnotation(
            UUID.randomUUID(), text, icon, color, target, target, GLOBAL_TEAM, 1, viewer.uuid,
        ).also(storage::addAnnotation)
    }

    fun updateComponents(
        level: Level,
        viewer: Player,
        id: UUID,
        expectedRevision: Int,
        text: String,
        icon: String,
        color: Int,
        hasEchoAfterMutation: Boolean,
    ): TraceAnnotation {
        val current = storage.annotationById(id) ?: throw IllegalArgumentException("annotation not found")
        if (current.createdByInternal != viewer.uuid && !canModerate(viewer)) throw IllegalStateException("not permitted")
        requireTargetInReach(viewer, current.targetBlock)
        require(current.revision == expectedRevision) { "annotation changed; reopen it before saving" }
        validateAnnotationFields(text, icon, color, hasEchoAfterMutation)
        return storage.updateAnnotation(id, text, icon, color) ?: throw IllegalStateException("annotation update failed")
    }

    override fun delete(level: Level, viewer: Player, id: UUID, expectedRevision: Int): Boolean {
        val current = storage.annotationById(id) ?: return false
        if (current.createdByInternal != viewer.getUUID() && !canModerate(viewer)) {
            return false
        }
        requireTargetInReach(viewer, current.targetBlock)
        require(current.revision == expectedRevision) { "annotation changed; reopen it before deleting" }
        storage.setSeen(viewer.getUUID(), id, current.revision)
        return storage.removeAnnotation(id)
    }

    override fun seenRevision(player: UUID, annotationId: UUID): Int = storage.getSeen(player, annotationId)

    override fun setSeen(player: UUID, annotationId: UUID, revision: Int) {
        storage.setSeen(player, annotationId, revision)
    }

    fun acknowledgeViewed(viewer: Player, annotationId: UUID, revision: Int): Boolean {
        val annotation = storage.annotationById(annotationId) ?: return false
        if (annotation.revision != revision) return false
        if (viewer.distanceToSqr(
                annotation.position.x + 0.5,
                annotation.position.y + 0.5,
                annotation.position.z + 0.5,
            ) > 100.0
        ) return false
        storage.setSeen(viewer.uuid, annotationId, revision)
        return true
    }

    private fun canModerate(viewer: Player): Boolean = (viewer as? ServerPlayer)?.hasPermissions(2) == true

    private fun requireTargetInReach(viewer: Player, target: BlockPos) {
        require(viewer.distanceToSqr(target.x + 0.5, target.y + 0.5, target.z + 0.5) <= 64.0) {
            "annotation target is out of reach"
        }
    }

    private fun validateAnnotationFields(text: String, icon: String, color: Int, hasEcho: Boolean) =
        AnnotationComponents.validate(text, icon, color, hasEcho)
}
