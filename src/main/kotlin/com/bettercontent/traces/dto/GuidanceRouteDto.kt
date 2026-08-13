package com.bettercontent.traces.dto

data class GuidancePointDto(val x: Double, val y: Double, val z: Double)

data class GuidanceRouteDto(
    val targetAnnotationId: String,
    val targetRevision: Int,
    val path: List<GuidancePointDto>,
)

data class GuidanceBuildResult(
    val totalReachable: Int,
    val routes: List<GuidanceRouteDto>,
    val truncated: Boolean,
)
