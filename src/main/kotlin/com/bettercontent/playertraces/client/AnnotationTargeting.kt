package com.bettercontent.playertraces.client

import com.bettercontent.playertraces.dto.VisibleAnnotationDto
import net.minecraft.client.player.LocalPlayer
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3

/** Selects rendered notes independently of whether their original block still exists. */
object AnnotationTargeting {
    private const val MAX_REACH = 8.0
    private const val HIT_THICKNESS = 0.08

    fun pick(
        level: Level,
        player: LocalPlayer,
        annotations: List<VisibleAnnotationDto>,
        blockHit: BlockHitResult?,
    ): VisibleAnnotationDto? {
        val eye = player.eyePosition
        val end = eye.add(player.lookAngle.scale(MAX_REACH))
        val blockingDistance = blockHit?.location?.distanceToSqr(eye) ?: MAX_REACH * MAX_REACH
        return annotations.asSequence()
            .mapNotNull { annotation ->
                val quad = SurfaceAnchorResolver.annotationQuad(level, annotation) ?: return@mapNotNull null
                val box = bounds(quad).inflate(0.02, HIT_THICKNESS, 0.02)
                val intersection = box.clip(eye, end).orElse(null) ?: return@mapNotNull null
                val distance = intersection.distanceToSqr(eye)
                if (distance > blockingDistance + 1.0e-4) null else annotation to distance
            }
            .minByOrNull { it.second }
            ?.first
    }

    private fun bounds(quad: SurfaceQuad): AABB {
        val positions = quad.vertices.map { it.position }
        return AABB(
            positions.minOf { it.x }, positions.minOf { it.y }, positions.minOf { it.z },
            positions.maxOf { it.x }, positions.maxOf { it.y }, positions.maxOf { it.z },
        )
    }
}
