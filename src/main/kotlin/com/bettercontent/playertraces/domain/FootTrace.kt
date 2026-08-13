package com.bettercontent.playertraces.domain

import net.minecraft.core.BlockPos
import java.util.UUID

data class FootTrace(
    val id: UUID,
    val levelKey: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val facingYaw: Float,
    val movementClass: MovementClass,
    val strength: Float,
    val sequenceId: UUID,
    val sequenceIndex: Int,
    val createdAt: Long,
    val sequenceEpoch: Long,
    val surviving: Boolean,
    val sourcePlayerInternal: UUID,
) {
    val blockPos: BlockPos
        get() = BlockPos.containing(x, y, z)

    constructor(
        id: UUID, levelKey: String, blockPos: BlockPos, movementClass: MovementClass, strength: Float,
        sequenceId: UUID, sequenceIndex: Int, createdAt: Long, sequenceEpoch: Long, surviving: Boolean,
        sourcePlayerInternal: UUID,
    ) : this(
        id, levelKey, blockPos.x + 0.5, blockPos.y.toDouble(), blockPos.z + 0.5, 0f,
        movementClass, strength, sequenceId, sequenceIndex, createdAt, sequenceEpoch, surviving, sourcePlayerInternal,
    )
}
