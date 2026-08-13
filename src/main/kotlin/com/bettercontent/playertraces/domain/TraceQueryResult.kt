package com.bettercontent.playertraces.domain

import net.minecraft.core.BlockPos

data class TraceQueryResult(
    val traces: List<FootTrace>,
    val annotations: List<TraceAnnotation>,
    val bounds: Pair<BlockPos, BlockPos>,
    val trafficPotential: TrafficPotential,
)
