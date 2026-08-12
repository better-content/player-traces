package com.bettercontent.traces.domain

data class TrafficPotential(
    val levelKey: String,
    val position: Long,
    val traceCount: Int,
    val aliveCount: Int,
    val maxStrength: Float,
    val meanStrength: Float,
    val sequenceCount: Int,
    val isRainExposed: Boolean,
    val localSurvivingStrength: Float = 0f,
    val regionalSurvivingStrength: Float = 0f,
    val serverSurvivingStrength: Float = 0f,
    val regionalShare: Float = 0f,
    val serverShare: Float = 0f,
    val percentile: Float = 0f,
)
