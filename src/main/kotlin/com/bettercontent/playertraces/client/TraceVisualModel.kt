package com.bettercontent.playertraces.client

import com.bettercontent.playertraces.dto.VisibleTraceDto
import com.bettercontent.playertraces.domain.TraceKind

data class TraceVisualMark(
    val trace: VisibleTraceDto,
    val angle: Float,
    val alpha: Float,
    val radius: Float,
    val color: Int,
    val lateralOffset: Float,
    val longitudinalOffset: Float,
    val width: Float,
    val length: Float,
)

object TraceVisualModel {
    const val FOOTPRINT_VERTICES_PER_PRIMITIVE = 4
    const val GUIDANCE_VERTICES_PER_PRIMITIVE = 4
    const val PIN_VERTEX_COUNT = 4
    internal const val STEP_LATERAL_OFFSET = 0.16f

    fun validPrimitiveCount(vertexCount: Int, verticesPerPrimitive: Int): Boolean =
        vertexCount >= 0 && verticesPerPrimitive > 0 && vertexCount % verticesPerPrimitive == 0

    fun marks(
        traces: List<VisibleTraceDto>,
        referenceDensity: Float,
        minimumAlpha: Float,
        limit: Int,
        sessionLoginGameTime: Long = 0L,
        recencyWindowTicks: Long = 72_000L,
    ): List<TraceVisualMark> {
        if (traces.isEmpty()) return emptyList()
        val sampled = stableOrder(traces).asSequence()
            .take(limit.coerceAtLeast(1))
            .toList()
        return sampled.map { trace ->
            val baseAngle = Math.toRadians(trace.facingYaw.toDouble()).toFloat() + (Math.PI / 2.0).toFloat()
            val angle = if (trace.kind == TraceKind.ARRIVAL) baseAngle + (Math.PI / 4.0).toFloat() else baseAngle
            val alpha = 0.50f
            val radius = (0.11f + trace.strength * 0.07f).coerceIn(0.08f, 0.22f)
            val color = if (sessionLoginGameTime > 0L) {
                TraceRecencyPalette.color(
                    trace.createdAt,
                    sessionLoginGameTime,
                    recencyWindowTicks,
                )
            } else {
                TraceRecencyPalette.LOGIN_RGB
            }
            val footSide = if (trace.sequenceIndex and 1 == 0) -1f else 1f
            val lateralOffset = if (trace.kind == TraceKind.FOOTPRINT) footSide * STEP_LATERAL_OFFSET else 0f
            val (width, length) = when (trace.kind) {
                TraceKind.FOOTPRINT -> 0.25f to 0.25f
                TraceKind.ARRIVAL -> 0.44f to 0.44f
                TraceKind.DEPARTURE -> 0.42f to 0.42f
            }
            TraceVisualMark(trace, angle, alpha, radius, color, lateralOffset, 0f, width, length)
        }
    }

    internal fun stableOrder(traces: List<VisibleTraceDto>): List<VisibleTraceDto> = traces.sortedWith(
        compareByDescending<VisibleTraceDto> { it.own }
            .thenBy { if (it.id.isEmpty()) 0L else stableKey(it.id) }
            .thenBy { it.createdAt }
            .thenBy { it.x }
            .thenBy { it.y }
            .thenBy { it.z }
            .thenBy { it.sequenceIndex }
            .thenBy { it.kind.ordinal }
            .thenBy { it.facingYaw },
    )

    private fun stableKey(id: String): Long = id.fold(1125899906842597L) { hash, char -> hash * 31 + char.code }
}
