package com.bettercontent.playertraces.logic

import com.bettercontent.playertraces.config.TracesConfig
import com.bettercontent.playertraces.storage.TraceStorageManager
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.ChunkPos

class ErosionService(
    private val storage: TraceStorageManager,
    private val config: TracesConfig.Common
) {
    private var pendingRain = mutableSetOf<ChunkPos>()

    fun tick(level: ServerLevel, tick: Int) {
        if (tick % 80 == 0) {
            if (level.getRainLevel(0.0f) > 0f) {
                queueRainCandidates(level)
            } else {
                pendingRain.clear()
            }
            processRainQueue(level)
        }

        if (tick % 200 == 0) {
            storage.tickFlush()
        }
        if (tick % 20 == 0) {
            storage.tickFlush()
        }
    }

    fun onFluidTick(blockPos: BlockPos) {
        storage.removeByPosition(blockPos)
        for (dx in -1..1) {
            for (dz in -1..1) {
                val p = BlockPos(blockPos.x + dx, blockPos.y, blockPos.z + dz)
                storage.removeByPosition(p)
            }
        }
    }

    private fun queueRainCandidates(level: ServerLevel) {
        for (state in storage.allStorageShards()) {
            state.footTracesSnapshot().asSequence()
                .filter { it.surviving }
                .map { it.blockPos }
                .filter { level.canSeeSky(it) && level.isRainingAt(it) }
                .map { ChunkPos(it.x shr 4, it.z shr 4) }
                .forEach { pendingRain += it }
        }
    }

    private fun processRainQueue(level: ServerLevel) {
        val queue = pendingRain.toList()
        pendingRain.clear()
        for (chunk in queue) {
            val cx = chunk.x * 16
            val cz = chunk.z * 16
            val rainPos = BlockPos(cx + 8, level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cx + 8, cz + 8), cz + 8)
            storage.weakenAround(rainPos, 24, config.rainExposureFactor.get())
        }
    }
}
