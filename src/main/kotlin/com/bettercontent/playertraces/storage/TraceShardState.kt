package com.bettercontent.playertraces.storage

import com.bettercontent.playertraces.domain.TraceAnnotation
import com.bettercontent.playertraces.domain.FootTrace
import com.bettercontent.playertraces.domain.GLOBAL_TEAM
import com.bettercontent.playertraces.domain.TraceKind
import net.minecraft.core.BlockPos
import java.util.TreeMap
import java.util.UUID

class TraceShardState {
    internal val footTraces: MutableList<FootTrace> = mutableListOf()
    internal val annotations: MutableList<TraceAnnotation> = mutableListOf()
    internal val seenStates: MutableList<SeenStateRecord> = mutableListOf()
    private val tileRevisions: MutableMap<TraceTileId, Long> = mutableMapOf()
    private val tracesByTile: MutableMap<TraceTileId, MutableList<FootTrace>> = mutableMapOf()
    /** Rebuildable temporal candidates; spatial bounds remain an exact final filter. */
    private val tracesByCreatedAt: NavigableTraceIndex = NavigableTraceIndex()
    private val tracesById: MutableMap<UUID, FootTrace> = mutableMapOf()
    private val annotationsByTile: MutableMap<TraceTileId, MutableList<TraceAnnotation>> = mutableMapOf()
    @Volatile
    var dirty: Boolean = false
        private set
    private var generation: Long = 0
    private var nextTileRevision: Long = 1
    private var archiveRevision: Long = 0

    @Synchronized
    fun markDirty() {
        generation++
        archiveRevision++
        dirty = true
    }

    @Synchronized
    fun snapshot(): Pair<TraceShardState, Long> {
        val copy = TraceShardState()
        copy.footTraces.addAll(footTraces)
        copy.annotations.addAll(annotations)
        copy.seenStates.addAll(seenStates)
        copy.footTraces.forEach(copy::indexTrace)
        copy.annotations.forEach(copy::indexAnnotation)
        copy.tileRevisions.putAll(tileRevisions)
        copy.nextTileRevision = nextTileRevision
        copy.archiveRevision = archiveRevision
        return copy to generation
    }

    @Synchronized
    internal fun archiveRevision(): Long = archiveRevision

    @Synchronized
    internal fun setArchiveRevision(revision: Long) {
        require(revision >= 0) { "negative archive revision" }
        archiveRevision = revision
    }

    @Synchronized
    fun clearDirtyIfUnchanged(snapshotGeneration: Long) {
        if (generation == snapshotGeneration) dirty = false
    }

    @Synchronized
    fun footTracesSnapshot(): List<FootTrace> = footTraces.toList()

    @Synchronized
    internal fun containsFootTrace(id: UUID): Boolean = footTraces.any { it.id == id }

    @Synchronized
    internal fun addLoadedFootTrace(trace: FootTrace) {
        footTraces += trace
        indexTrace(trace)
        tileRevisions.putIfAbsent(TraceTileId.containing(trace.blockPos), nextTileRevision++)
    }

    @Synchronized
    internal fun addLoadedAnnotation(annotation: TraceAnnotation) {
        annotations += annotation
        indexAnnotation(annotation)
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
        indexTrace(trace)
        markTraceTilesDirty(setOf(TraceTileId.containing(trace.blockPos)))
    }

    /** Atomically inserts a capture unless its stable ID is already present. */
    @Synchronized
    internal fun addFootTraceIfAbsent(trace: FootTrace): Boolean {
        if (tracesById.containsKey(trace.id)) return false
        addFootTrace(trace)
        return true
    }

    @Synchronized
    fun tileRevision(chunkX: Int, chunkZ: Int): Long = tileRevisions[TraceTileId(chunkX, chunkZ)] ?: 0L

    @Synchronized
    fun queryTraceTile(chunkX: Int, chunkZ: Int): List<FootTrace> {
        val tile = TraceTileId(chunkX, chunkZ)
        return tracesByTile[tile].orEmpty().filter { it.surviving }
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
        return tracesInTiles(boundsMin, boundsMax).filter { t ->
            val p = t.blockPos
            p.x in boundsMin.x..boundsMax.x && p.y in boundsMin.y..boundsMax.y && p.z in boundsMin.z..boundsMax.z && t.surviving
        }
    }

    /**
     * Returns traces newer than [createdAfter] using the per-shard temporal index.
     * The tile index is deliberately not used here because the temporal candidate
     * set is smaller for return summaries; exact spatial and surviving checks stay
     * identical to [nearbyFootTraces].
     */
    @Synchronized
    fun nearbyFootTracesAfter(boundsMin: BlockPos, boundsMax: BlockPos, createdAfter: Long): List<FootTrace> {
        return tracesByCreatedAt.after(createdAfter).filter { t ->
            val p = t.blockPos
            p.x in boundsMin.x..boundsMax.x && p.y in boundsMin.y..boundsMax.y &&
                p.z in boundsMin.z..boundsMax.z && t.surviving
        }
    }

    @Synchronized
    fun nearbyAnnotations(boundsMin: BlockPos, boundsMax: BlockPos): List<TraceAnnotation> {
        return annotationsInTiles(boundsMin, boundsMax).filter { a ->
            val p = a.position
            p.x in boundsMin.x..boundsMax.x && p.y in boundsMin.y..boundsMax.y && p.z in boundsMin.z..boundsMax.z && a.team == GLOBAL_TEAM
        }
    }

    @Synchronized
    fun removeAtPosition(position: BlockPos): Int = removeMatching { it.blockPos == position }

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
        require(factor.isFinite() && factor >= 0.0) { "erosion factor must be finite and non-negative" }
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
                    val updated = trace.copy(surviving = false)
                    footTraces[i] = updated
                    replaceIndexedTrace(trace, updated)
                    changed = true
                    changedTiles += TraceTileId.containing(trace.blockPos)
                }
            } else {
                val updated = trace.copy(strength = next)
                footTraces[i] = updated
                replaceIndexedTrace(trace, updated)
                changed = true
                changedTiles += TraceTileId.containing(trace.blockPos)
            }
        }
        if (changed) markTraceTilesDirty(changedTiles)
    }

    @Synchronized
    fun updateTraceWeakness(traceId: java.util.UUID, factor: Double): Boolean {
        require(factor.isFinite() && factor >= 0.0) { "erosion factor must be finite and non-negative" }
        val index = footTraces.indexOfFirst { it.id == traceId && it.surviving }
        if (index < 0) return false
        val trace = footTraces[index]
        val next = trace.strength * factor.toFloat()
        val updated = if (next <= 0.04f) trace.copy(surviving = false) else trace.copy(strength = next)
        footTraces[index] = updated
        replaceIndexedTrace(trace, updated)
        markTraceTilesDirty(setOf(TraceTileId.containing(trace.blockPos)))
        return true
    }

    @Synchronized
    fun annotationById(id: java.util.UUID): TraceAnnotation? = annotations.firstOrNull { it.id == id }

    @Synchronized
    fun putAnnotation(annotation: TraceAnnotation) {
        annotations.add(annotation)
        indexAnnotation(annotation)
        markDirty()
    }

    @Synchronized
    fun putAnnotationIfAbsent(annotation: TraceAnnotation): Boolean {
        val existing = annotations.firstOrNull { it.id == annotation.id }
        if (existing != null) return existing == annotation
        annotations.add(annotation)
        indexAnnotation(annotation)
        markDirty()
        return true
    }

    @Synchronized
    fun removeAnnotation(id: java.util.UUID): Boolean {
        val removed = annotations.filter { it.id == id }
        if (removed.isEmpty()) return false
        annotations.removeIf { it.id == id }
        removed.forEach(::removeIndexedAnnotation)
        markDirty()
        return true
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
        removeIndexedAnnotation(current)
        indexAnnotation(updated)
        markDirty()
        return updated
    }

    /** Replaces an annotation with its already revisioned outage-overlay value. */
    @Synchronized
    internal fun replaceAnnotation(annotation: TraceAnnotation): Boolean {
        val index = annotations.indexOfFirst { it.id == annotation.id }
        if (index < 0) return false
        val current = annotations[index]
        require(annotation.position == current.position && annotation.targetBlock == current.targetBlock
            && annotation.createdByInternal == current.createdByInternal && annotation.team == current.team) {
            "annotation edit cannot change identity, location, owner, or team"
        }
        if (annotation == current) return true
        if (annotation.revision <= current.revision) return false
        annotations[index] = annotation
        removeIndexedAnnotation(current)
        indexAnnotation(annotation)
        markDirty()
        return true
    }

    private fun removeMatching(predicate: (FootTrace) -> Boolean): Int {
        val matches = footTraces.asSequence()
            .filter(predicate)
            .toList()
        if (matches.isEmpty()) return 0
        val ids = matches.mapTo(HashSet()) { it.id }
        footTraces.removeIf { it.id in ids }
        matches.forEach(::removeIndexedTrace)
        markTraceTilesDirty(matches.mapTo(mutableSetOf()) { TraceTileId.containing(it.blockPos) })
        return matches.size
    }

    private fun tracesInTiles(boundsMin: BlockPos, boundsMax: BlockPos): List<FootTrace> = buildList {
        for (chunkX in Math.floorDiv(boundsMin.x, 16)..Math.floorDiv(boundsMax.x, 16)) {
            for (chunkZ in Math.floorDiv(boundsMin.z, 16)..Math.floorDiv(boundsMax.z, 16)) {
                addAll(tracesByTile[TraceTileId(chunkX, chunkZ)].orEmpty())
            }
        }
    }

    private fun annotationsInTiles(boundsMin: BlockPos, boundsMax: BlockPos): List<TraceAnnotation> = buildList {
        for (chunkX in Math.floorDiv(boundsMin.x, 16)..Math.floorDiv(boundsMax.x, 16)) {
            for (chunkZ in Math.floorDiv(boundsMin.z, 16)..Math.floorDiv(boundsMax.z, 16)) {
                addAll(annotationsByTile[TraceTileId(chunkX, chunkZ)].orEmpty())
            }
        }
    }

    private fun indexTrace(trace: FootTrace) {
        tracesByTile.getOrPut(TraceTileId.containing(trace.blockPos)) { mutableListOf() }.add(trace)
        tracesByCreatedAt.add(trace)
        tracesById[trace.id] = trace
    }

    private fun indexAnnotation(annotation: TraceAnnotation) {
        annotationsByTile.getOrPut(TraceTileId.containing(annotation.position)) { mutableListOf() }.add(annotation)
    }

    private fun removeIndexedAnnotation(annotation: TraceAnnotation) {
        val tile = TraceTileId.containing(annotation.position)
        annotationsByTile[tile]?.let { indexed ->
            indexed.remove(annotation)
            if (indexed.isEmpty()) annotationsByTile.remove(tile)
        }
    }

    private fun replaceIndexedTrace(old: FootTrace, updated: FootTrace) {
        removeIndexedTrace(old)
        indexTrace(updated)
    }

    private fun removeIndexedTrace(trace: FootTrace) {
        val tile = TraceTileId.containing(trace.blockPos)
        tracesByTile[tile]?.let { indexed ->
            indexed.remove(trace)
            if (indexed.isEmpty()) tracesByTile.remove(tile)
        }
        tracesByCreatedAt.remove(trace)
        tracesById.remove(trace.id)
    }

    private class NavigableTraceIndex {
        private val byCreatedAt = TreeMap<Long, MutableList<FootTrace>>()

        fun add(trace: FootTrace) {
            byCreatedAt.getOrPut(trace.createdAt) { mutableListOf() }.add(trace)
        }

        fun remove(trace: FootTrace) {
            byCreatedAt[trace.createdAt]?.let { bucket ->
                bucket.remove(trace)
                if (bucket.isEmpty()) byCreatedAt.remove(trace.createdAt)
            }
        }

        fun after(createdAfter: Long): List<FootTrace> = buildList {
            byCreatedAt.tailMap(createdAfter, false).values.forEach(::addAll)
        }
    }

    private fun markTraceTilesDirty(tiles: Set<TraceTileId>) {
        if (tiles.isEmpty()) return
        tiles.forEach { tileRevisions[it] = nextTileRevision++ }
        markDirty()
    }
}
