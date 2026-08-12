package com.bettercontent.traces.client

import com.bettercontent.traces.dto.VisibleTraceDto
import kotlin.math.atan2
import kotlin.math.sqrt

data class TraceVisualMark(
    val trace: VisibleTraceDto,
    val angle: Float,
    val alpha: Float,
    val radius: Float,
    val color: Int,
)

object TraceVisualModel {
    const val FOOTPRINT_VERTICES_PER_PRIMITIVE = 3
    const val GUIDANCE_VERTICES_PER_PRIMITIVE = 3
    const val PIN_VERTEX_COUNT = 48

    fun validPrimitiveCount(vertexCount: Int, verticesPerPrimitive: Int): Boolean =
        vertexCount >= 0 && verticesPerPrimitive > 0 && vertexCount % verticesPerPrimitive == 0

    fun marks(traces: List<VisibleTraceDto>, referenceDensity: Float, minimumAlpha: Float, limit: Int): List<TraceVisualMark> {
        if (traces.isEmpty()) return emptyList()
        val sampled = traces
            .asSequence()
            .filter { exposure(traces, it, referenceDensity) >= minimumAlpha }
            .sortedBy { stableKey(it.id) }
            .take(limit.coerceAtLeast(1))
            .toList()
        return sampled.map { trace ->
            val previous = traces.firstOrNull { it.sequenceId == trace.sequenceId && it.sequenceIndex == trace.sequenceIndex - 1 }
            val next = traces.firstOrNull { it.sequenceId == trace.sequenceId && it.sequenceIndex == trace.sequenceIndex + 1 }
            val dx = (next?.x ?: trace.x) - (previous?.x ?: trace.x)
            val dz = (next?.z ?: trace.z) - (previous?.z ?: trace.z)
            val angle = if (dx == 0 && dz == 0) 0f else atan2(dz.toFloat(), dx.toFloat())
            val alpha = (0.18f + exposure(traces, trace, referenceDensity) * 0.82f).coerceIn(minimumAlpha, 1f)
            val radius = (0.11f + trace.strength * 0.07f).coerceIn(0.08f, 0.22f)
            val color = if (trace.movementClass.name == "SPRINT") 0x8DFFE1 else 0x5CF4F0
            TraceVisualMark(trace, angle, alpha, radius, color)
        }
    }

    fun exposure(traces: List<VisibleTraceDto>, trace: VisibleTraceDto, referenceDensity: Float): Float {
        var density = 0
        for (other in traces) {
            if (trace.id == other.id) continue
            val dx = trace.x - other.x
            val dz = trace.z - other.z
            if (dx * dx + dz * dz <= 16) density++
        }
        val denom = referenceDensity.coerceAtLeast(0.1f)
        val falloff = 1f / sqrt(1f + density.toFloat() / denom)
        return (falloff * trace.strength).coerceIn(0f, 1f)
    }

    private fun stableKey(id: String): Long = id.fold(1125899906842597L) { hash, char -> hash * 31 + char.code }
}
