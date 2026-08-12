package com.bettercontent.traces.util

import net.minecraft.core.BlockPos

object Geometry {
    fun floorDiv(v: Int, by: Int): Int = if (v >= 0) v / by else -((-v + by - 1) / by)

    fun worldToShard(blockPos: BlockPos): Pair<Int, Int> {
        val rx = floorDiv(blockPos.x, 256)
        val rz = floorDiv(blockPos.z, 256)
        return rx to rz
    }

    fun shardToBounds(rx: Int, rz: Int): Pair<BlockPos, BlockPos> {
        val minX = rx * 256
        val minZ = rz * 256
        val maxX = minX + 255
        val maxZ = minZ + 255
        return BlockPos(minX, 0, minZ) to BlockPos(maxX, 255, maxZ)
    }

    fun clamp01(v: Float): Float = when {
        v < 0f -> 0f
        v > 1f -> 1f
        else -> v
    }
}
