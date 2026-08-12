package com.bettercontent.traces.logic

import com.bettercontent.traces.domain.GuidanceSignal
import com.bettercontent.traces.dto.VisibleAnnotationDto
import com.bettercontent.traces.dto.VisibleTraceDto
import net.minecraft.core.BlockPos

object GuidanceEngine {
    private const val MAX_STEP = 5

    fun buildSignals(traces: List<VisibleTraceDto>, annotations: List<VisibleAnnotationDto>, player: BlockPos): List<GuidanceSignal> {
        if (traces.isEmpty() || annotations.isEmpty()) return emptyList()
        val target = annotations.minByOrNull {
            val dx = it.x - player.x
            val dz = it.z - player.z
            (dx * dx + dz * dz)
        } ?: return emptyList()

        val targetPos = BlockPos(target.x, target.y, target.z)
        val startIdx = nearestIndex(traces, player, MAX_STEP * MAX_STEP)
        val endIdx = nearestIndex(traces, targetPos, MAX_STEP * MAX_STEP)
        if (startIdx < 0 || endIdx < 0 || startIdx == endIdx) return emptyList()

        val graph = buildAdjacency(traces)
        val path = shortestPath(graph, startIdx, endIdx)
        if (path.isEmpty()) return emptyList()

        val pathPositions = path.map { traces[it] }.map { BlockPos(it.x, it.y, it.z) }
        val t = ((System.nanoTime() % 1_000_000_000L).toFloat() / 1_000_000_000f)
        return listOf(
            GuidanceSignal(
                targetAnnotationId = target.id,
                path = pathPositions,
                intensity = 1f,
                phase = t,
                shimmer = 0.5f,
                directionPulse = 0.9f
            )
        )
    }

    private fun buildAdjacency(traces: List<VisibleTraceDto>): List<List<Int>> {
        val out = MutableList(traces.size) { mutableListOf<Int>() }
        val bySequenceIndex = traces.withIndex().associateBy { it.value.sequenceId to it.value.sequenceIndex }
        for ((index, trace) in traces.withIndex()) {
            val previous = bySequenceIndex[trace.sequenceId to (trace.sequenceIndex - 1)]?.index
            val next = bySequenceIndex[trace.sequenceId to (trace.sequenceIndex + 1)]?.index
            listOfNotNull(previous, next).forEach { adjacent ->
                val other = traces[adjacent]
                val dx = trace.x - other.x
                val dz = trace.z - other.z
                if (dx * dx + dz * dz <= MAX_STEP * MAX_STEP && adjacent !in out[index]) {
                    out[index].add(adjacent)
                    out[adjacent].add(index)
                }
            }
        }
        return out
    }

    private fun nearestIndex(traces: List<VisibleTraceDto>, pos: BlockPos, maxDistanceSquared: Int): Int {
        var bestIdx = -1
        var best = Int.MAX_VALUE
        for (i in traces.indices) {
            val t = traces[i]
            val dx = t.x - pos.x
            val dz = t.z - pos.z
            val dy = t.y - pos.y
            val dist = dx * dx + dy * dy + dz * dz
            if (dist < best) {
                best = dist
                bestIdx = i
            }
        }
        return if (best <= maxDistanceSquared) bestIdx else -1
    }

    private fun shortestPath(graph: List<List<Int>>, start: Int, goal: Int): List<Int> {
        val q = ArrayDeque<Int>()
        val prev = IntArray(graph.size) { -1 }
        val seen = BooleanArray(graph.size)
        q.add(start)
        seen[start] = true
        while (q.isNotEmpty()) {
            val u = q.removeFirst()
            if (u == goal) break
            for (v in graph[u]) {
                if (seen[v]) continue
                seen[v] = true
                prev[v] = u
                q.add(v)
            }
        }
        if (!seen[goal]) return emptyList()
        val path = ArrayList<Int>()
        var cur = goal
        while (cur >= 0) {
            path.add(cur)
            cur = prev[cur]
        }
        path.reverse()
        return path
    }
}
