package com.bettercontent.traces.api

import com.bettercontent.traces.domain.TraceQueryResult
import com.bettercontent.traces.domain.TrafficPotential
import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level

interface TraceQueryApi {
    fun tracesWithin(level: Level, boundsMin: BlockPos, boundsMax: BlockPos): TraceQueryResult
    fun trafficPotential(level: Level, pos: BlockPos): TrafficPotential
}
