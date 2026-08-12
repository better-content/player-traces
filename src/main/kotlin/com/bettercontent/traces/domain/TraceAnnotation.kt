package com.bettercontent.traces.domain

import net.minecraft.core.BlockPos
import java.util.UUID

data class TraceAnnotation(
    val id: UUID,
    val text: String,
    val icon: String,
    val color: Int,
    val position: BlockPos,
    val targetBlock: BlockPos,
    val team: TraceTeam,
    val revision: Int,
    val createdByInternal: UUID,
)
