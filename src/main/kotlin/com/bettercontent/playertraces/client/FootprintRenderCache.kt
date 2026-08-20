package com.bettercontent.playertraces.client

import com.bettercontent.playertraces.dto.VisibleTraceDto
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import kotlin.math.floor

data class CachedFootprint(val mark: TraceVisualMark, val quad: SurfaceQuad, val bounds: AABB)
data class FootprintCellKey(val x: Int, val z: Int) : Comparable<FootprintCellKey> {
    override fun compareTo(other: FootprintCellKey): Int = compareValuesBy(this, other, FootprintCellKey::x, FootprintCellKey::z)
}
data class CachedFootprintCell(val key: FootprintCellKey, val bounds: AABB, val footprints: List<CachedFootprint>)
data class FootprintRenderData(val cells: List<CachedFootprintCell> = emptyList(), val sourceCount: Int = 0)

/**
 * Stable 64x64-block render cells using one canonical footprint primitive.
 *
 * The cache key deliberately ignores payload ordering: server distance sorting must not invalidate geometry or
 * produce the old clear/rebuild flicker. Tile snapshots can change only affected cells through [invalidateCell].
 */
object FootprintRenderCache {
    internal const val CELL_SIZE_BLOCKS = 64

    private var levelIdentity: Level? = null
    private var contentKey: List<VisibleTraceDto> = emptyList()
    private var data = FootprintRenderData()
    private val cells = mutableMapOf<FootprintCellKey, CachedFootprintCell>()
    private val invalidatedCells = mutableSetOf<FootprintCellKey>()

    fun renderData(level: Level, traces: List<VisibleTraceDto>, @Suppress("UNUSED_PARAMETER") revision: Long): FootprintRenderData {
        val stable = TraceVisualModel.stableOrder(traces)
        if (levelIdentity !== level) {
            rebuildAll(level, stable)
        } else if (contentKey != stable || invalidatedCells.isNotEmpty()) {
            if (invalidatedCells.isEmpty()) rebuildAll(level, stable) else rebuildInvalidated(level, stable)
        }
        return data
    }

    fun invalidateCell(chunkX: Int, chunkZ: Int) {
        invalidatedCells += FootprintCellKey(Math.floorDiv(chunkX, 4), Math.floorDiv(chunkZ, 4))
    }

    fun clear() {
        data = FootprintRenderData()
        contentKey = emptyList()
        cells.clear()
        invalidatedCells.clear()
        levelIdentity = null
    }

    private fun rebuildAll(level: Level, stableTraces: List<VisibleTraceDto>) {
        val started = System.nanoTime()
        levelIdentity = level
        contentKey = stableTraces
        invalidatedCells.clear()
        cells.clear()
        val resolved = resolve(level, stableTraces)
        resolved.groupBy { footprint -> cellKey(footprint.mark.trace.x, footprint.mark.trace.z) }
            .forEach { (key, footprints) -> cells[key] = CachedFootprintCell(key, combinedBounds(footprints), footprints) }
        publish(started)
    }

    private fun rebuildInvalidated(level: Level, stableTraces: List<VisibleTraceDto>) {
        val started = System.nanoTime()
        val dirty = invalidatedCells.toSet()
        invalidatedCells.clear()
        contentKey = stableTraces
        dirty.forEach { key ->
            val resolved = resolve(level, stableTraces.filter { cellKey(it.x, it.z) == key })
            if (resolved.isEmpty()) cells.remove(key)
            else cells[key] = CachedFootprintCell(key, combinedBounds(resolved), resolved)
        }
        publish(started)
    }

    private fun resolve(level: Level, traces: List<VisibleTraceDto>): List<CachedFootprint> =
        TraceVisualModel.marks(
            traces,
            1f,
            0f,
            TracesClientRenderer.MAX_RENDERED_FOOTPRINTS,
            TracesClientState.sessionLoginGameTime,
            com.bettercontent.playertraces.config.TracesConfig.client.recencyWindowMinutes.get().toLong() * 1_200L,
        )
            .mapNotNull { mark ->
                val quad = SurfaceAnchorResolver.footprintQuad(
                    level, mark.trace, mark.angle, mark.lateralOffset, mark.longitudinalOffset,
                    mark.width.toDouble(), mark.length.toDouble(),
                )
                    ?: return@mapNotNull null
                CachedFootprint(mark, quad, bounds(quad))
            }

    private fun publish(started: Long) {
        val ordered = cells.toSortedMap().values.toList()
        data = FootprintRenderData(ordered, ordered.sumOf { it.footprints.size })
        if (com.bettercontent.playertraces.config.TracesConfig.client.visualDiagnostics.get()) {
            TracesClientLog.LOGGER.info(
                "TRACES_FOOTPRINT_CACHE source={} cells={} rebuildMicros={}",
                data.sourceCount,
                data.cells.size,
                (System.nanoTime() - started) / 1_000,
            )
        }
    }

    private fun cellKey(x: Double, z: Double): FootprintCellKey = FootprintCellKey(
        floor(x / CELL_SIZE_BLOCKS).toInt(),
        floor(z / CELL_SIZE_BLOCKS).toInt(),
    )

    private fun bounds(quad: SurfaceQuad): AABB {
        val positions = quad.vertices.map { it.position }
        return AABB(
            positions.minOf { it.x }, positions.minOf { it.y } - 0.01, positions.minOf { it.z },
            positions.maxOf { it.x }, positions.maxOf { it.y } + 0.01, positions.maxOf { it.z },
        )
    }

    private fun combinedBounds(footprints: List<CachedFootprint>): AABB = AABB(
        footprints.minOf { it.bounds.minX }, footprints.minOf { it.bounds.minY }, footprints.minOf { it.bounds.minZ },
        footprints.maxOf { it.bounds.maxX }, footprints.maxOf { it.bounds.maxY }, footprints.maxOf { it.bounds.maxZ },
    )
}
