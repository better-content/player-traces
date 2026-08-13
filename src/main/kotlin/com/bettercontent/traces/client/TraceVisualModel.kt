package com.bettercontent.traces.client

import com.bettercontent.traces.dto.VisibleTraceDto

data class TraceVisualMark(
    val trace: VisibleTraceDto,
    val angle: Float,
    val alpha: Float,
    val radius: Float,
    val color: Int,
    val lateralOffset: Float,
    val longitudinalOffset: Float,
)

object TraceVisualModel {
    const val FOOTPRINT_VERTICES_PER_PRIMITIVE = 4
    const val GUIDANCE_VERTICES_PER_PRIMITIVE = 4
    const val PIN_VERTEX_COUNT = 4
    internal const val STEP_LATERAL_OFFSET = 0.16f

    fun validPrimitiveCount(vertexCount: Int, verticesPerPrimitive: Int): Boolean =
        vertexCount >= 0 && verticesPerPrimitive > 0 && vertexCount % verticesPerPrimitive == 0

    fun marks(traces: List<VisibleTraceDto>, referenceDensity: Float, minimumAlpha: Float, limit: Int): List<TraceVisualMark> {
        if (traces.isEmpty()) return emptyList()
        val sampled = traces.asSequence()
            .sortedWith(compareByDescending<VisibleTraceDto> { it.own }.thenBy { stableKey(it.id) })
            .take(limit.coerceAtLeast(1))
            .toList()
        return sampled.map { trace ->
            val angle = Math.toRadians(trace.facingYaw.toDouble()).toFloat() + (Math.PI / 2.0).toFloat()
            val alpha = 0.50f
            val radius = (0.11f + trace.strength * 0.07f).coerceIn(0.08f, 0.22f)
            val color = 0x35E7FF
            val footSide = if (trace.sequenceIndex and 1 == 0) -1f else 1f
            val lateralOffset = footSide * STEP_LATERAL_OFFSET
            TraceVisualMark(trace, angle, alpha, radius, color, lateralOffset, 0f)
        }
    }

    private fun stableKey(id: String): Long = id.fold(1125899906842597L) { hash, char -> hash * 31 + char.code }
}
