package com.bettercontent.traces.logic

import com.bettercontent.traces.dto.GuidancePointDto
import com.bettercontent.traces.dto.GuidanceRouteDto
import net.minecraft.world.phys.Vec3
import java.util.UUID
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sqrt

data class GuidanceProgressResult(val experience: Int, val advancedDistance: Double)

/** Tracks forward route progress so backtracking and repeated queries cannot generate experience. */
class GuidanceProgressTracker {
    private data class RouteKey(val playerId: UUID, val annotationId: String, val revision: Int)
    private data class Progress(
        var shortestRemaining: Double,
        var uncreditedDistance: Double,
        var lastPosition: Vec3,
    )

    private val progress = HashMap<RouteKey, Progress>()

    fun observe(playerId: UUID, position: Vec3, route: GuidanceRouteDto?): GuidanceProgressResult {
        if (route == null || route.path.size < 2) return GuidanceProgressResult(0, 0.0)
        val remaining = pathLength(route.path)
        if (!remaining.isFinite()) return GuidanceProgressResult(0, 0.0)
        val key = RouteKey(playerId, route.targetAnnotationId, route.targetRevision)
        val state = progress.getOrPut(key) { Progress(remaining, 0.0, position) }
        val moved = state.lastPosition.distanceTo(position)
        state.lastPosition = position

        val rawAdvance = (state.shortestRemaining - remaining).coerceAtLeast(0.0)
        state.shortestRemaining = min(state.shortestRemaining, remaining)
        if (rawAdvance <= 0.0 || moved > MAX_OBSERVATION_MOVEMENT || distance(position, route.path.first()) > ROUTE_REACH) {
            return GuidanceProgressResult(0, 0.0)
        }

        // A winding route can shrink much faster than the player moved. Credit only plausible travel.
        val advance = min(rawAdvance, moved + MOVEMENT_TOLERANCE)
        state.uncreditedDistance += advance
        val experience = floor(state.uncreditedDistance / BLOCKS_PER_EXPERIENCE).toInt()
        if (experience > 0) state.uncreditedDistance -= experience * BLOCKS_PER_EXPERIENCE
        return GuidanceProgressResult(experience, advance)
    }

    fun clear(playerId: UUID) {
        progress.keys.removeIf { it.playerId == playerId }
    }

    companion object {
        const val BLOCKS_PER_EXPERIENCE = 3.0
        const val ROUTE_REACH = 2.0
        const val MAX_OBSERVATION_MOVEMENT = 12.0
        const val MOVEMENT_TOLERANCE = 0.75

        internal fun pathLength(path: List<GuidancePointDto>): Double = path.zipWithNext().sumOf { (a, b) ->
            val dx = b.x - a.x
            val dy = b.y - a.y
            val dz = b.z - a.z
            sqrt(dx * dx + dy * dy + dz * dz)
        }

        private fun distance(position: Vec3, point: GuidancePointDto): Double {
            val dx = position.x - point.x
            val dy = position.y - point.y
            val dz = position.z - point.z
            return sqrt(dx * dx + dy * dy + dz * dz)
        }
    }
}
