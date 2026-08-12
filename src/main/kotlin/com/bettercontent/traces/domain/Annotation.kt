package com.bettercontent.traces.domain

import net.minecraft.core.BlockPos
import java.util.UUID

data class Annotation(
    val id: UUID,
    val text: String,
    val icon: String,
    val color: Int,
    val position: BlockPos,
    val team: TraceTeam,
    val revision: Int,
    val targetBlock: BlockPos,
    val createdByInternal: UUID
)
