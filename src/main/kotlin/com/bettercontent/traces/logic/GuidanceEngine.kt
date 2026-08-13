package com.bettercontent.traces.logic

import com.bettercontent.traces.domain.FootTrace
import com.bettercontent.traces.domain.TraceAnnotation
import com.bettercontent.traces.dto.GuidanceBuildResult
import com.bettercontent.traces.dto.GuidanceRouteDto
import com.bettercontent.traces.dto.GuidancePointDto
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3
import java.util.UUID
import kotlin.math.sqrt

object GuidanceEngine {
    const val ATTACH_DISTANCE = 5
    const val MAX_ROUTES = 256
    const val MAX_TOTAL_POINTS = 4096

    fun buildRoutes(
        traces: List<FootTrace>, annotations: List<TraceAnnotation>, viewer: UUID, player: BlockPos,
        seenRevision: (UUID) -> Int, maxRoutes: Int = MAX_ROUTES, maxPoints: Int = MAX_TOTAL_POINTS,
    ): GuidanceBuildResult = buildRoutes(
        traces, annotations, viewer, Vec3(player.x + 0.5, player.y.toDouble(), player.z + 0.5), seenRevision, maxRoutes, maxPoints,
    )

    fun buildRoutes(
        traces: List<FootTrace>,
        annotations: List<TraceAnnotation>,
        viewer: UUID,
        player: Vec3,
        seenRevision: (UUID) -> Int,
        maxRoutes: Int = MAX_ROUTES,
        maxPoints: Int = MAX_TOTAL_POINTS,
    ): GuidanceBuildResult {
        if (traces.isEmpty() || annotations.isEmpty() || maxRoutes <= 0 || maxPoints <= 0) {
            return GuidanceBuildResult(0, emptyList(), false)
        }

        val sequences = traces.asSequence()
            .filter { it.surviving }
            .groupBy { it.sequenceId }
            .values
            .map { sequence ->
                sequence.sortedWith(compareBy<FootTrace> { it.sequenceIndex }.thenBy { it.id })
                    .distinctBy { it.sequenceIndex }
            }
            .filter { it.size >= 2 }
            .toList()
        val candidates = annotations.filter {
            it.createdByInternal != viewer && it.revision > seenRevision(it.id)
        }

        val routes = candidates.mapNotNull { annotation ->
            bestRoute(sequences, player, annotation)?.let { (path, distance) ->
                distance to GuidanceRouteDto(annotation.id.toString(), annotation.revision, path)
            }
        }.sortedWith(compareBy<Pair<Double, GuidanceRouteDto>> { it.first }.thenBy { it.second.targetAnnotationId })

        val accepted = mutableListOf<GuidanceRouteDto>()
        var acceptedPoints = 0
        for ((_, route) in routes) {
            if (accepted.size >= maxRoutes || acceptedPoints + route.path.size > maxPoints) continue
            accepted += route
            acceptedPoints += route.path.size
        }
        return GuidanceBuildResult(routes.size, accepted, accepted.size != routes.size)
    }

    private fun bestRoute(
        sequences: List<List<FootTrace>>,
        player: Vec3,
        annotation: TraceAnnotation,
    ): Pair<List<GuidancePointDto>, Double>? {
        var bestPath: List<GuidancePointDto>? = null
        var bestDistance = Double.POSITIVE_INFINITY
        for (sequence in sequences) {
            val starts = nearestAttachments(sequence, player)
            if (starts.isEmpty()) continue
            val ends = nearestAttachments(sequence, Vec3(annotation.position.x + 0.5, annotation.position.y.toDouble(), annotation.position.z + 0.5))
            for (start in starts) {
                for (end in ends) {
                    if (start == end) continue
                    val path = contiguousPath(sequence, start, end) ?: continue
                    val distance = path.zipWithNext().sumOf { (a, b) -> distance(a, b) }
                    if (distance < bestDistance) {
                        bestDistance = distance
                        bestPath = path
                    }
                }
            }
        }
        return bestPath?.let { it to bestDistance }
    }

    private fun contiguousPath(sequence: List<FootTrace>, start: Int, end: Int): List<GuidancePointDto>? {
        val low = minOf(start, end)
        val high = maxOf(start, end)
        for (index in low until high) {
            val current = sequence[index]
            val next = sequence[index + 1]
            if (next.sequenceIndex != current.sequenceIndex + 1) return null
            if (distanceSquared(current.x, current.y, current.z, next.x, next.y, next.z) > ATTACH_DISTANCE * ATTACH_DISTANCE) return null
        }
        val path = sequence.subList(low, high + 1).map { GuidancePointDto(it.x, it.y, it.z) }
        return if (start <= end) path else path.asReversed()
    }

    private fun nearestAttachments(sequence: List<FootTrace>, position: Vec3): List<Int> {
        val distances = sequence.indices.map { index ->
            val trace = sequence[index]
            index to distanceSquared(trace.x, trace.y, trace.z, position.x, position.y, position.z)
        }
        val nearest = distances.minOfOrNull { it.second } ?: return emptyList()
        if (nearest > ATTACH_DISTANCE * ATTACH_DISTANCE) return emptyList()
        return distances.filter { kotlin.math.abs(it.second - nearest) < 1.0e-9 }.map { it.first }
    }

    private fun distance(a: GuidancePointDto, b: GuidancePointDto): Double =
        sqrt(distanceSquared(a.x, a.y, a.z, b.x, b.y, b.z))

    private fun distanceSquared(ax: Double, ay: Double, az: Double, bx: Double, by: Double, bz: Double): Double {
        val dx = ax - bx; val dy = ay - by; val dz = az - bz
        return dx * dx + dy * dy + dz * dz
    }
}
