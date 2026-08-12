package com.bettercontent.traces.logic

import com.bettercontent.traces.TracesMod
import com.bettercontent.traces.api.TraceQueryApi
import com.bettercontent.traces.domain.TraceQueryResult
import com.bettercontent.traces.domain.TrafficPotential
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import com.bettercontent.traces.util.Geometry

class TraceQueryService : TraceQueryApi {
    override fun tracesWithin(level: Level, boundsMin: BlockPos, boundsMax: BlockPos): TraceQueryResult {
        if (level !is ServerLevel) {
            return TraceQueryResult(emptyList(), emptyList(), boundsMin to boundsMax, trafficPotential(level, boundsMin))
        }
        val storage = TracesMod.getRuntime(level.server).storage(level)
        val traces = storage.queryTraces(boundsMin, boundsMax)
        val annotations = storage.queryAnnotations(boundsMin, boundsMax)
        val potential = trafficPotential(level, boundsMin)
        return TraceQueryResult(traces, annotations, boundsMin to boundsMax, potential)
    }

    override fun trafficPotential(level: Level, pos: BlockPos): TrafficPotential {
        if (level !is ServerLevel) {
            return TrafficPotential(level.toString(), pos.asLong(), 0, 0, 0f, 0f, 0, false)
        }
        val runtime = TracesMod.getRuntime(level.server)
        val levelKey = level.dimension().location().toString()
        val min = BlockPos(pos.x - 48, 0, pos.z - 48)
        val max = BlockPos(pos.x + 48, 255, pos.z + 48)
        val localTraces = runtime.storage(level).queryTraces(min, max)
        val localAlive = localTraces.filter { it.surviving }
        val localSurvivingStrength = localAlive.sumOf { it.strength.toDouble() }.toFloat()

        val shard = Geometry.worldToShard(pos)
        val regionalBounds = Geometry.shardToBounds(shard.first, shard.second)
        val regionalTraces = runtime.storage(level).queryTraces(regionalBounds.first, regionalBounds.second)
        val regionalAlive = regionalTraces.filter { it.surviving }
        val regionalSurvivingStrength = regionalAlive.sumOf { it.strength.toDouble() }.toFloat()

        val serverStrengths = runtime.storage(level).allSurvivingFootTraces(levelKey).filter { it.surviving }
        val serverSurvivingStrength = serverStrengths.sumOf { it.strength.toDouble() }.toFloat()

        val allAliveStrengths = serverStrengths.map { it.strength }
        val serverPercentile = localAlive.maxOfOrNull { it.strength }?.let { localMax ->
            percentileRank(allAliveStrengths, localMax)
        } ?: 0f

        val regionalShare = if (serverSurvivingStrength > 0f) regionalSurvivingStrength / serverSurvivingStrength else 0f
        val alive = localTraces.count { it.surviving }
        val strength = localTraces.map { it.strength }
        return TrafficPotential(
            levelKey = levelKey,
            position = pos.asLong(),
            traceCount = localTraces.size,
            aliveCount = alive,
            maxStrength = strength.maxOrNull() ?: 0f,
            meanStrength = if (strength.isNotEmpty()) strength.average().toFloat() else 0f,
            sequenceCount = localTraces.map { it.sequenceId }.toSet().size,
            isRainExposed = level.isRaining,
            localSurvivingStrength = localSurvivingStrength,
            regionalSurvivingStrength = regionalSurvivingStrength,
            serverSurvivingStrength = serverSurvivingStrength,
            regionalShare = regionalShare,
            serverShare = if (serverSurvivingStrength > 0f) localSurvivingStrength / serverSurvivingStrength else 0f,
            percentile = serverPercentile,
        )
    }
}

private fun percentileRank(sorted: List<Float>, value: Float): Float {
    if (sorted.isEmpty()) return 0f
    val sortedAscending = sorted.sorted()
    val countLe = sortedAscending.count { it <= value }
    return (countLe.toFloat() / sortedAscending.size.toFloat()).coerceIn(0f, 1f)
}
