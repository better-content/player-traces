package com.bettercontent.playertraces.client

import com.bettercontent.playertraces.dto.GuidancePointDto
import net.minecraft.world.phys.Vec3

object GuidancePathModel {
    const val CLIP_REACH = 2.0

    /** Removes route geometry behind the closest point the player has reached. */
    fun remainingPath(path: List<GuidancePointDto>, player: Vec3, reach: Double = CLIP_REACH): List<GuidancePointDto> {
        if (path.size < 2) return path
        var closestDistanceSquared = Double.POSITIVE_INFINITY
        var closestSegment = -1
        var closestPoint: GuidancePointDto? = null
        path.zipWithNext().forEachIndexed { index, (from, to) ->
            val dx = to.x - from.x
            val dy = to.y - from.y
            val dz = to.z - from.z
            val lengthSquared = dx * dx + dy * dy + dz * dz
            if (lengthSquared <= 1.0e-9) return@forEachIndexed
            val amount = (((player.x - from.x) * dx + (player.y - from.y) * dy + (player.z - from.z) * dz) / lengthSquared)
                .coerceIn(0.0, 1.0)
            val projected = GuidancePointDto(from.x + dx * amount, from.y + dy * amount, from.z + dz * amount)
            val px = player.x - projected.x
            val py = player.y - projected.y
            val pz = player.z - projected.z
            val distanceSquared = px * px + py * py + pz * pz
            if (distanceSquared < closestDistanceSquared) {
                closestDistanceSquared = distanceSquared
                closestSegment = index
                closestPoint = projected
            }
        }
        if (closestSegment < 0 || closestDistanceSquared > reach * reach) return path
        return buildList {
            add(closestPoint!!)
            addAll(path.drop(closestSegment + 1))
        }.distinctBy { Triple(it.x, it.y, it.z) }
    }
}
