package com.bettercontent.playertraces.storage

import com.bettercontent.playertraces.domain.TraceAnnotation
import com.bettercontent.playertraces.domain.FootTrace
import com.bettercontent.playertraces.domain.GLOBAL_TEAM
import com.bettercontent.playertraces.domain.TraceKind
import net.minecraft.core.BlockPos

class TraceShardState {
    internal val footTraces: MutableList<FootTrace> = mutableListOf()
    internal val annotations: MutableList<TraceAnnotation> = mutableListOf()
    internal val seenStates: MutableList<SeenStateRecord> = mutableListOf()
    private val tileRevisions: MutableMap<TraceTileId, Long> = mutableMapOf()
    @Volatile
    var dirty: Boolean = false
        private set
    private var generation: Long = 0
    private var nextTileRevision: Long = 1

    @Synchronized
    fun markDirty() {
        generation++
        dirty = true
    }

    @Synchronized
    fun snapshot(): Pair<TraceShardState, Long> {
        val copy = TraceShardState()
        copy.footTraces.addAll(footTraces)
        copy.annotations.addAll(annotations)
        copy.seenStates.addAll(seenStates)
        copy.tileRevisions.putAll(tileRevisions)
        copy.nextTileRevision = nextTileRevision
        return copy to generation
    }

    @Synchronized
    fun clearDirtyIfUnchanged(snapshotGeneration: Long) {
        if (generation == snapshotGeneration) dirty = false
    }

    @Synchronized
    fun footTracesSnapshot(): List<FootTrace> = footTraces.toList()

    @Synchronized
    internal fun addLoadedFootTrace(trace: FootTrace) {
        footTraces += trace
        tileRevisions.putIfAbsent(TraceTileId.containing(trace.blockPos), nextTileRevision++)
    }

    @Synchronized
    internal fun replaceLoadedTileRevisions(revisions: Map<TraceTileId, Long>) {
        tileRevisions.clear()
        tileRevisions.putAll(revisions)
        nextTileRevision = (revisions.values.maxOrNull() ?: 0L) + 1L
    }

    @Synchronized
    internal fun tileRevisionsSnapshot(): Map<TraceTileId, Long> = tileRevisions.toMap()

    @Synchronized
    fun addFootTrace(trace: FootTrace) {
        require(trace.kind != TraceKind.FOOTPRINT || trace.support != null) { "footprint trace has no supporting block" }
        footTraces += trace
        markTraceTilesDirty(setOf(TraceTileId.containing(trace.blockPos)))
    }

    @Synchronized
    fun tileRevision(chunkX: Int, chunkZ: Int): Long = tileRevisions[TraceTileId(chunkX, chunkZ)] ?: 0L

    @Synchronized
    fun queryTraceTile(chunkX: Int, chunkZ: Int): List<FootTrace> {
        val tile = TraceTileId(chunkX, chunkZ)
        return footTraces.filter { it.surviving && TraceTileId.containing(it.blockPos) == tile }
    }

    @Synchronized
    fun traceTileSnapshot(chunkX: Int, chunkZ: Int): TraceTileSnapshot = TraceTileSnapshot(
        TraceTileId(chunkX, chunkZ),
        tileRevision(chunkX, chunkZ),
        queryTraceTile(chunkX, chunkZ),
    )

    @Synchronized
    fun annotationsSnapshot(): List<TraceAnnotation> = annotations.toList()

    @Synchronized
    internal fun seenStatesSnapshot(): List<SeenStateRecord> = seenStates.toList()

    @Synchronized
    fun counts(): Triple<Int, Int, Int> = Triple(footTraces.size, annotations.size, seenStates.size)

    @Synchronized
    fun nearbyFootTraces(boundsMin: BlockPos, boundsMax: BlockPos): List<FootTrace> {
        return footTraces.filter { t ->
            val p = t.blockPos
            p.x in boundsMin.x..boundsMax.x && p.y in boundsMin.y..boundsMax.y && p.z in boundsMin.z..boundsMax.z && t.surviving
        }
    }

    @Synchronized
    fun nearbyAnnotations(boundsMin: BlockPos, boundsMax: BlockPos): List<TraceAnnotation> {
        return annotations.filter { a ->
            val p = a.position
            p.x in boundsMin.x..boundsMax.x && p.y in boundsMin.y..boundsMax.y && p.z in boundsMin.z..boundsMax.z && a.team == GLOBAL_TEAM
        }
    }

    @Synchronized
    fun removeAtPosition(position: BlockPos) {
        removeMatching { it.blockPos == position }
    }

    @Synchronized
    fun removeBySupport(position: BlockPos): Int = removeMatching { it.support?.position == position }

    @Synchronized
    fun pruneInvalidSupports(chunkX: Int, chunkZ: Int, isValid: (com.bettercontent.playertraces.domain.TraceSupport) -> Boolean): Int {
        val tile = TraceTileId(chunkX, chunkZ)
        return removeMatching { trace ->
            TraceTileId.containing(trace.blockPos) == tile && trace.support?.let { !isValid(it) } == true
        }
    }

    @Synchronized
    fun removeFootTraces(boundsMin: BlockPos, boundsMax: BlockPos): Int {
        return removeMatching { trace ->
            val pos = trace.blockPos
            pos.x in boundsMin.x..boundsMax.x && pos.y in boundsMin.y..boundsMax.y && pos.z in boundsMin.z..boundsMax.z
        }
    }

    @Synchronized
    fun updateWeakness(position: BlockPos, factor: Double) {
        var changed = false
        val changedTiles = mutableSetOf<TraceTileId>()
        for (i in footTraces.indices) {
            val trace = footTraces[i]
            val p = trace.blockPos
            val dx = p.x - position.x
            val dz = p.z - position.z
            if (dx * dx + dz * dz > 80) continue
            val next = trace.strength * factor.toFloat()
            if (next <= 0.04f) {
                if (trace.surviving) {
                    footTraces[i] = trace.copy(surviving = false)
                    changed = true
                    changedTiles += TraceTileId.containing(trace.blockPos)
                }
            } else {
                footTraces[i] = trace.copy(strength = next)
                changed = true
                changedTiles += TraceTileId.containing(trace.blockPos)
            }
        }
        if (changed) markTraceTilesDirty(changedTiles)
    }

    @Synchronized
    fun annotationById(id: java.util.UUID): TraceAnnotation? = annotations.firstOrNull { it.id == id }

    @Synchronized
    fun putAnnotation(annotation: TraceAnnotation) {
        annotations.add(annotation)
        markDirty()
    }

    @Synchronized
    fun removeAnnotation(id: java.util.UUID): Boolean {
        val removed = annotations.removeIf { it.id == id }
        if (removed) markDirty()
        return removed
    }

    @Synchronized
    fun updateAnnotation(id: java.util.UUID, text: String?, icon: String?, color: Int?): TraceAnnotation? {
        val index = annotations.indexOfFirst { it.id == id }
        if (index < 0) return null
        val current = annotations[index]
        val updated = current.copy(
            text = text ?: current.text,
            icon = icon ?: current.icon,
            color = color ?: current.color,
            revision = current.revision + 1,
        )
        annotations[index] = updated
        markDirty()
        return updated
    }

    private fun removeMatching(predicate: (FootTrace) -> Boolean): Int {
        val matches = footTraces.asSequence()
            .filter(predicate)
            .toList()
        if (matches.isEmpty()) return 0
        val ids = matches.mapTo(HashSet()) { it.id }
        footTraces.removeIf { it.id in ids }
        markTraceTilesDirty(matches.mapTo(mutableSetOf()) { TraceTileId.containing(it.blockPos) })
        return matches.size
    }

    private fun markTraceTilesDirty(tiles: Set<TraceTileId>) {
        if (tiles.isEmpty()) return
        tiles.forEach { tileRevisions[it] = nextTileRevision++ }
        markDirty()
    }
}
