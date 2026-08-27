package com.bettercontent.playertraces.client

import com.bettercontent.playertraces.dto.VisibleAnnotationDto
import com.bettercontent.playertraces.dto.VisibleTraceDto
import com.bettercontent.playertraces.domain.TraceKind
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import kotlin.math.cos
import kotlin.math.sin

data class SurfaceSample(val position: Vec3, val supportingBlock: BlockPos, val packedLight: Int)
data class SurfaceVertex(val position: Vec3, val u: Float, val v: Float, val packedLight: Int)
data class SurfaceQuad(val vertices: List<SurfaceVertex>)

object SurfaceAnchorResolver {
    private const val SURFACE_OFFSET = 0.003
    internal const val FOOTPRINT_WIDTH = 0.25
    internal const val FOOTPRINT_LENGTH = 0.25
    internal const val ANNOTATION_SIZE = 0.30
    internal const val ANNOTATION_ELEVATION = 0.004

    fun sample(level: Level, x: Double, originY: Double, z: Double, searchDepth: Double = 2.5): SurfaceSample? {
        val hit = level.clip(ClipContext(
            Vec3(x, originY + 1.25, z), Vec3(x, originY - searchDepth, z),
            ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, null,
        ))
        if (hit.type != net.minecraft.world.phys.HitResult.Type.BLOCK || hit.direction != Direction.UP) return null
        val support = hit.blockPos
        if (!level.getBlockState(support).fluidState.isEmpty) return null
        return SurfaceSample(hit.location.add(0.0, SURFACE_OFFSET, 0.0), support, LevelRenderer.getLightColor(level, support.above()))
    }

    fun footprintQuad(
        level: Level,
        trace: VisibleTraceDto,
        angle: Float,
        lateralOffset: Float = 0f,
        longitudinalOffset: Float = 0f,
        width: Double = FOOTPRINT_WIDTH,
        length: Double = FOOTPRINT_LENGTH,
    ): SurfaceQuad? {
        val forwardX = cos(angle.toDouble())
        val forwardZ = sin(angle.toDouble())
        val rightX = -sin(angle.toDouble())
        val rightZ = cos(angle.toDouble())
        val centerX = trace.x + rightX * lateralOffset + forwardX * longitudinalOffset
        val centerZ = trace.z + rightZ * lateralOffset + forwardZ * longitudinalOffset
        val support = trace.support
        if (support == null) {
            if (trace.kind == TraceKind.FOOTPRINT) return null
            val light = LevelRenderer.getLightColor(level, BlockPos.containing(centerX, trace.y, centerZ))
            return flatQuad(centerX, trace.y + SURFACE_OFFSET, centerZ, angle, width, length, light)
        }
        val state = level.getBlockState(support.position)
        if (state.isAir || BuiltInRegistries.BLOCK.getKey(state.block) != support.blockId) return null
        val shape = state.getCollisionShape(level, support.position)
        if (shape.isEmpty) return null
        val light = LevelRenderer.getLightColor(level, support.position.above())
        return flatQuad(centerX, trace.y + SURFACE_OFFSET, centerZ, angle, width, length, light)
    }

    fun annotationQuad(level: Level, annotation: VisibleAnnotationDto): SurfaceQuad? =
        annotationQuad(level, BlockPos(annotation.x, annotation.y, annotation.z))

    fun annotationQuad(level: Level, target: BlockPos): SurfaceQuad? {
        val state = level.getBlockState(target)
        val shape = state.getCollisionShape(level, target)
        val surfaceY = if (shape.isEmpty) target.y + 1.0 else target.y + shape.max(Direction.Axis.Y)
        val light = LevelRenderer.getLightColor(level, target.above())
        return flatQuad(
            target.x + 0.5,
            surfaceY + SURFACE_OFFSET + ANNOTATION_ELEVATION,
            target.z + 0.5,
            0f,
            ANNOTATION_SIZE,
            ANNOTATION_SIZE,
            light,
        )
    }

    private fun quad(
        level: Level,
        x: Double,
        y: Double,
        z: Double,
        angle: Float,
        width: Double,
        length: Double,
        elevation: Double = 0.0,
    ): SurfaceQuad? {
        val anchor = sample(level, x, y, z) ?: return null
        return flatQuad(x, anchor.position.y + elevation, z, angle, width, length, anchor.packedLight)
    }

    private fun flatQuad(
        x: Double,
        y: Double,
        z: Double,
        angle: Float,
        width: Double,
        length: Double,
        packedLight: Int,
    ): SurfaceQuad? {
        val forwardX = cos(angle.toDouble())
        val forwardZ = sin(angle.toDouble())
        val rightX = -forwardZ
        val rightZ = forwardX
        val halfW = width / 2.0
        val halfL = length / 2.0
        val corners = listOf(
            Vec3(x - rightX * halfW - forwardX * halfL, y, z - rightZ * halfW - forwardZ * halfL),
            Vec3(x + rightX * halfW - forwardX * halfL, y, z + rightZ * halfW - forwardZ * halfL),
            Vec3(x + rightX * halfW + forwardX * halfL, y, z + rightZ * halfW + forwardZ * halfL),
            Vec3(x - rightX * halfW + forwardX * halfL, y, z - rightZ * halfW + forwardZ * halfL),
        )
        if (corners.any { !it.x.isFinite() || !it.y.isFinite() || !it.z.isFinite() }) return null
        val uv = listOf(0f to 1f, 1f to 1f, 1f to 0f, 0f to 0f)
        return SurfaceQuad(corners.zip(uv).map { (position, texture) ->
            SurfaceVertex(position, texture.first, texture.second, packedLight)
        })
    }
}
