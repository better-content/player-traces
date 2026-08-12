package com.bettercontent.traces.client

import com.bettercontent.traces.dto.VisibleAnnotationDto
import com.bettercontent.traces.dto.VisibleTraceDto
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.cos
import kotlin.math.sin

data class SurfaceSample(
    val position: Vec3,
    val supportingBlock: BlockPos,
    val packedLight: Int,
)

data class SurfaceVertex(
    val position: Vec3,
    val u: Float,
    val v: Float,
    val packedLight: Int,
)

data class SurfaceTriangle(
    val a: SurfaceVertex,
    val b: SurfaceVertex,
    val c: SurfaceVertex,
)

object SurfaceAnchorResolver {
    private const val FOOT_WIDTH = 0.18
    private const val FOOT_LENGTH = 0.36
    private const val SURFACE_OFFSET = 0.0025
    private const val GRID_COLUMNS = 3
    private const val GRID_ROWS = 5

    fun sample(level: Level, x: Double, originY: Double, z: Double, searchDepth: Double = 2.5): SurfaceSample? {
        val from = Vec3(x, originY + 1.25, z)
        val to = Vec3(x, originY - searchDepth, z)
        val hit = level.clip(ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, null))
        if (hit.type != net.minecraft.world.phys.HitResult.Type.BLOCK || hit.direction != Direction.UP) {
            return null
        }
        val support = hit.blockPos
        val state = level.getBlockState(support)
        if (state.fluidState.isEmpty.not()) return null
        val light = LevelRenderer.getLightColor(level, support)
        return SurfaceSample(hit.location.add(0.0, SURFACE_OFFSET, 0.0), support, light)
    }

    fun annotationAnchor(level: Level, annotation: VisibleAnnotationDto): SurfaceSample? {
        return sample(level, annotation.x + 0.5, annotation.y.toDouble(), annotation.z + 0.5, 2.0)
    }

    fun footprintMesh(level: Level, trace: VisibleTraceDto, angle: Float): List<SurfaceTriangle> {
        val result = ArrayList<SurfaceTriangle>((GRID_COLUMNS - 1) * (GRID_ROWS - 1) * 4)
        val forwardX = cos(angle.toDouble())
        val forwardZ = sin(angle.toDouble())
        val rightX = -forwardZ
        val rightZ = forwardX
        val baseX = trace.x + 0.5
        val baseZ = trace.z + 0.5
        val traceY = trace.y.toDouble()

        appendFoot(
            level,
            baseX + rightX * 0.10 + forwardX * 0.09,
            baseZ + rightZ * 0.10 + forwardZ * 0.09,
            0.0f,
            0.5f,
            forwardX,
            forwardZ,
            rightX,
            rightZ,
            traceY,
            result,
        )
        appendFoot(
            level,
            baseX - rightX * 0.10 - forwardX * 0.09,
            baseZ - rightZ * 0.10 - forwardZ * 0.09,
            0.5f,
            1.0f,
            forwardX,
            forwardZ,
            rightX,
            rightZ,
            traceY,
            result,
        )
        return result
    }

    private fun appendFoot(
        level: Level,
        centerX: Double,
        centerZ: Double,
        u0: Float,
        u1: Float,
        forwardX: Double,
        forwardZ: Double,
        rightX: Double,
        rightZ: Double,
        traceY: Double,
        output: MutableList<SurfaceTriangle>,
    ) {
        val vertices = arrayOfNulls<SurfaceVertex>(GRID_COLUMNS * GRID_ROWS)
        for (row in 0 until GRID_ROWS) {
            val along = (row.toDouble() / (GRID_ROWS - 1) - 0.5) * FOOT_LENGTH
            for (column in 0 until GRID_COLUMNS) {
                val across = (column.toDouble() / (GRID_COLUMNS - 1) - 0.5) * FOOT_WIDTH
                val x = centerX + rightX * across + forwardX * along
                val z = centerZ + rightZ * across + forwardZ * along
                val surface = sample(level, x, traceY, z) ?: continue
                val u = u0 + (u1 - u0) * column.toFloat() / (GRID_COLUMNS - 1)
                val v = 0.06f + 0.88f * (1f - row.toFloat() / (GRID_ROWS - 1))
                vertices[row * GRID_COLUMNS + column] = SurfaceVertex(surface.position, u, v, surface.packedLight)
            }
        }
        for (row in 0 until GRID_ROWS - 1) {
            for (column in 0 until GRID_COLUMNS - 1) {
                val a = vertices[row * GRID_COLUMNS + column] ?: continue
                val b = vertices[row * GRID_COLUMNS + column + 1] ?: continue
                val c = vertices[(row + 1) * GRID_COLUMNS + column + 1] ?: continue
                val d = vertices[(row + 1) * GRID_COLUMNS + column] ?: continue
                if (stableHeight(a, b, c)) output += SurfaceTriangle(a, b, c)
                if (stableHeight(a, c, d)) output += SurfaceTriangle(a, c, d)
            }
        }
    }

    private fun stableHeight(a: SurfaceVertex, b: SurfaceVertex, c: SurfaceVertex): Boolean {
        val min = minOf(a.position.y, b.position.y, c.position.y)
        val max = maxOf(a.position.y, b.position.y, c.position.y)
        return max - min <= 0.26 && listOf(a, b, c).all { it.position.x.isFinite() && it.position.y.isFinite() && it.position.z.isFinite() }
    }
}
