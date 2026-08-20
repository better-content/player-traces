package com.bettercontent.playertraces.logic

import com.bettercontent.playertraces.domain.TraceSupport
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import kotlin.math.floor

data class ResolvedTraceSurface(
    val position: Vec3,
    val support: TraceSupport,
)

object TraceSupportResolver {
    fun resolve(level: ServerLevel, point: Vec3, maxDepth: Int): ResolvedTraceSurface? {
        if (!point.x.isFinite() || !point.y.isFinite() || !point.z.isFinite()) return null
        val x = floor(point.x).toInt()
        val z = floor(point.z).toInt()
        val startY = floor(point.y + 0.25).toInt().coerceIn(level.minBuildHeight, level.maxBuildHeight - 1)
        val localX = point.x - x
        val localZ = point.z - z
        for (y in startY downTo maxOf(level.minBuildHeight, startY - maxDepth.coerceAtLeast(0))) {
            val blockPos = BlockPos(x, y, z)
            val state = level.getBlockState(blockPos)
            if (state.isAir) continue
            val shape = state.getCollisionShape(level, blockPos, CollisionContext.empty())
            val top = shape.toAabbs().asSequence()
                .filter { localX >= it.minX - EPSILON && localX <= it.maxX + EPSILON }
                .filter { localZ >= it.minZ - EPSILON && localZ <= it.maxZ + EPSILON }
                .maxOfOrNull { it.maxY }
                ?: continue
            return ResolvedTraceSurface(
                Vec3(point.x, blockPos.y + top + SURFACE_OFFSET, point.z),
                TraceSupport(blockPos.immutable(), BuiltInRegistries.BLOCK.getKey(state.block)),
            )
        }
        return null
    }

    private const val EPSILON = 1.0e-6
    private const val SURFACE_OFFSET = 0.012
}
