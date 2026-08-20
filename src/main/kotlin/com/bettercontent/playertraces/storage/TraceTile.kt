package com.bettercontent.playertraces.storage

import com.bettercontent.playertraces.domain.FootTrace
import net.minecraft.core.BlockPos

data class TraceTileId(val chunkX: Int, val chunkZ: Int) {
    companion object {
        fun containing(position: BlockPos): TraceTileId = TraceTileId(
            Math.floorDiv(position.x, 16),
            Math.floorDiv(position.z, 16),
        )
    }
}

data class TraceTileSnapshot(
    val id: TraceTileId,
    val revision: Long,
    val traces: List<FootTrace>,
)
