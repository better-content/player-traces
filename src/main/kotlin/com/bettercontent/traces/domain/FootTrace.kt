package com.bettercontent.traces.domain

import net.minecraft.core.BlockPos
import java.util.UUID

data class FootTrace(
    val id: UUID,
    val levelKey: String,
    val blockPos: BlockPos,
    val movementClass: MovementClass,
    val strength: Float,
    val sequenceId: UUID,
    val sequenceIndex: Int,
    val createdAt: Long,
    val sequenceEpoch: Long,
    val surviving: Boolean,
    val sourcePlayerInternal: UUID,
)
