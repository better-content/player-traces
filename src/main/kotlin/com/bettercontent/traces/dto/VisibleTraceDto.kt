package com.bettercontent.traces.dto

import com.bettercontent.traces.domain.MovementClass

data class VisibleTraceDto(
    val id: String,
    val sequenceId: String,
    val movementClass: MovementClass,
    val x: Int,
    val y: Int,
    val z: Int,
    val strength: Float,
    val sequenceIndex: Int,
)
