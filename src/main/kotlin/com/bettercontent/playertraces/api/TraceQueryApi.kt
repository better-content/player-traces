package com.bettercontent.playertraces.api

import com.bettercontent.playertraces.domain.TraceQueryResult
import com.bettercontent.playertraces.domain.TrafficPotential
import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level

interface TraceQueryApi {
    fun tracesWithin(level: Level, boundsMin: BlockPos, boundsMax: BlockPos): TraceQueryResult
    fun trafficPotential(level: Level, pos: BlockPos): TrafficPotential
}
