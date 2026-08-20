package com.bettercontent.playertraces.dto

import com.bettercontent.playertraces.domain.MovementClass
import com.bettercontent.playertraces.domain.TraceKind
import com.bettercontent.playertraces.domain.TraceSupport

data class VisibleTraceDto(
    val id: String,
    val sequenceId: String,
    val movementClass: MovementClass,
    val x: Double,
    val y: Double,
    val z: Double,
    val facingYaw: Float,
    val strength: Float,
    val sequenceIndex: Int,
    val own: Boolean = false,
    val kind: TraceKind = TraceKind.FOOTPRINT,
    val createdAt: Long = 0L,
    val support: TraceSupport? = null,
) {
    constructor(
        id: String, sequenceId: String, movementClass: MovementClass, x: Int, y: Int, z: Int,
        strength: Float, sequenceIndex: Int, own: Boolean = false,
    ) : this(id, sequenceId, movementClass, x + 0.5, y.toDouble(), z + 0.5, 0f, strength, sequenceIndex, own)
}
