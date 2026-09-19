package com.bettercontent.playertraces.logic

import com.bettercontent.playertraces.config.TracesConfig
import com.bettercontent.playertraces.storage.TraceStorageManager
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel

class ErosionService(
    private val storage: TraceStorageManager,
    private val config: TracesConfig.Common
) {
    private val pendingRain = mutableMapOf<java.util.UUID, BlockPos>()

    fun tick(level: ServerLevel, tick: Int) {
        if (tick % 80 == 0) {
            if (level.getRainLevel(0.0f) > 0f) {
                queueRainCandidates(level)
            } else {
                pendingRain.clear()
            }
            processRainQueue(level)
        }

        if (tick % 20 == 0) {
            storage.tickFlush()
        }
    }

    fun onFluidTick(blockPos: BlockPos) {
        storage.removeBySupport(blockPos)
        storage.removeByPosition(blockPos)
        for (dx in -1..1) {
            for (dz in -1..1) {
                val p = BlockPos(blockPos.x + dx, blockPos.y, blockPos.z + dz)
                storage.removeBySupport(p)
                storage.removeByPosition(p)
            }
        }
    }

    private fun queueRainCandidates(level: ServerLevel) {
        for (state in storage.allStorageShards()) {
            state.footTracesSnapshot().asSequence()
                .filter { it.surviving }
                // Rain exposure is a property of each footprint, including its own Y and
                // canopy. A chunk-centre height sample erodes sheltered corners incorrectly.
                .filter { level.canSeeSky(it.blockPos) && level.isRainingAt(it.blockPos) }
                .forEach { pendingRain[it.id] = it.blockPos }
        }
    }

    private fun processRainQueue(level: ServerLevel) {
        val queue = pendingRain.toMap()
        pendingRain.clear()
        for ((traceId, position) in queue) {
            // The world may have changed since candidate collection; revalidate the same
            // footprint instead of relying on a stale chunk-level observation.
            if (level.canSeeSky(position) && level.isRainingAt(position)) {
                storage.weakenFootprint(traceId, position, config.rainExposureFactor.get())
            }
        }
    }
}
