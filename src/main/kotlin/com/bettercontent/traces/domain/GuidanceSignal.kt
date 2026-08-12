package com.bettercontent.traces.domain

import net.minecraft.core.BlockPos

data class GuidanceSignal(
    val targetAnnotationId: String,
    val path: List<BlockPos>,
    val intensity: Float,
    val phase: Float,
    val shimmer: Float,
    val directionPulse: Float,
)
