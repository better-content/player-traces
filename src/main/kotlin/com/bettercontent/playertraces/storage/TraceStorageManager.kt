package com.bettercontent.playertraces.storage

import com.bettercontent.playertraces.config.TracesConfig
import com.bettercontent.playertraces.domain.FootTrace
import com.bettercontent.playertraces.domain.TraceAnnotation
import com.bettercontent.playertraces.domain.TraceSupport
import com.bettercontent.playertraces.util.Geometry
import com.bettercontent.playertraces.util.TraceShardId
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.storage.LevelResource
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.channels.FileChannel
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

internal const val TRACE_SCHEMA_MARKER = "traces-v1\n"

internal enum class DeferredReplayQueue {
    CAPTURE, ANNOTATION_ADD, ANNOTATION_EDIT, ANNOTATION_REMOVE, BLOCK_CLEANUP, FOOTPRINT_EROSION, SUPPORT_PRUNE,
    NEIGHBORHOOD_WEAKENING, SEEN_STATE,
}

internal fun mayReplaySeenState(hasQueuedAnnotationAdds: Boolean): Boolean = !hasQueuedAnnotationAdds

internal fun mergeDeferredAnnotations(
    base: List<TraceAnnotation>,
    additions: List<DeferredAnnotation>,
    edits: List<DeferredAnnotationEdit>,
    removals: Set<UUID>,
): List<TraceAnnotation> {
    val byId = base.associateByTo(LinkedHashMap()) { it.id }
    additions.forEach { byId.putIfAbsent(it.annotation.id, it.annotation) }
    edits.forEach { byId[it.annotation.id] = it.annotation }
    removals.forEach(byId::remove)
    return byId.values.toList()
}

private fun TraceAnnotation.isInside(boundsMin: BlockPos, boundsMax: BlockPos): Boolean =
    position.x in boundsMin.x..boundsMax.x && position.y in boundsMin.y..boundsMax.y && position.z in boundsMin.z..boundsMax.z

internal fun neighborhoodShardRadius(radius: Int): Int = (radius.coerceAtLeast(0) / 256).coerceAtLeast(1)

internal fun neighborhoodShardCount(radius: Int): Long {
    val diameter = 2L * neighborhoodShardRadius(radius) + 1L
    return diameter * diameter
}

internal fun neighborhoodFitsReplayBudget(radius: Int, budget: Int): Boolean =
    budget > 0 && neighborhoodShardCount(radius) <= budget

internal fun isInsideTraceCleanupBounds(position: BlockPos, min: BlockPos, max: BlockPos): Boolean =
    position.x in min.x..max.x && position.y in min.y..max.y && position.z in min.z..max.z

internal fun blockCleanupAffectsTile(cleanup: DeferredBlockCleanup, chunkX: Int, chunkZ: Int): Boolean = when (cleanup.kind) {
    BlockCleanupKind.TRACE_POSITION, BlockCleanupKind.SUPPORT_POSITION ->
        (cleanup.position.x shr 4) == chunkX && (cleanup.position.z shr 4) == chunkZ
    BlockCleanupKind.TRACE_BOUNDS -> {
        val min = requireNotNull(cleanup.boundsMin)
        val max = requireNotNull(cleanup.boundsMax)
        val tileMinX = chunkX.toLong() * 16L
        val tileMinZ = chunkZ.toLong() * 16L
        tileMinX <= max.x && tileMinX + 15L >= min.x && tileMinZ <= max.z && tileMinZ + 15L >= min.z
    }
}

internal fun overlayCleanupRevision(baseRevision: Long, pendingCleanups: Int): Long {
    require(pendingCleanups >= 0)
    return if (baseRevision > Long.MAX_VALUE - pendingCleanups) Long.MAX_VALUE
    else baseRevision + pendingCleanups
}

internal fun shouldPruneInvalidSupport(
    support: TraceSupport,
    isChunkLoaded: (BlockPos) -> Boolean,
    isValid: (TraceSupport) -> Boolean,
): Boolean = isChunkLoaded(support.position) && !isValid(support)

internal fun ensureTraceSchema(tracesRoot: Path) {
    val schemaFile = tracesRoot.resolve("schema")
    Files.createDirectories(schemaFile.parent)
    if (Files.exists(schemaFile)) {
        val existing = Files.readString(schemaFile, Charsets.UTF_8)
        require(existing == TRACE_SCHEMA_MARKER) {
            "Unsupported Traces root schema '${existing.trim()}'; expected '${TRACE_SCHEMA_MARKER.trim()}'. Refusing to rewrite world data."
        }
        return
    }
    FileChannel.open(schemaFile, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
        val bytes = java.nio.ByteBuffer.wrap(TRACE_SCHEMA_MARKER.toByteArray(Charsets.UTF_8))
        while (bytes.hasRemaining()) channel.write(bytes)
        channel.force(true)
    }
}

class TraceStorageManager(
    private val level: ServerLevel,
    private val config: TracesConfig.Common,
) {
    companion object {
        internal fun dimensionRoot(worldRoot: Path, dimension: ResourceLocation): Path =
            worldRoot.toAbsolutePath().normalize().resolve("data/traces")
                .resolve(dimension.namespace).resolve(dimension.path)
    }
    private val log = LoggerFactory.getLogger(TraceStorageManager::class.java)
    private val cache = TraceShardLruCache(config.shardCacheSize.get())
    private val dirtyExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "traces-flush-${level.dimension().location()}").apply {
            isDaemon = true
        }
    }
    private data class IndexedAnnotation(val shardId: TraceShardId, val annotation: TraceAnnotation)
    private val annotationIndex = java.util.concurrent.ConcurrentHashMap<UUID, IndexedAnnotation>()
    private val pendingFlushes = mutableListOf<Future<*>>()
    private val queuedFlushes = java.util.concurrent.ConcurrentHashMap.newKeySet<TraceShardId>()
    @Volatile private var closed = false

    private val worldRoot: Path = level.server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize()
    private val tracesRoot: Path = worldRoot.resolve("data").resolve("player_traces")
    private val dimensionId = level.dimension().location()
    private val levelKey = dimensionId.toString()
    private val dimensionRoot = dimensionRoot(worldRoot, dimensionId)
    private val journal = DeferredOperationJournal(dimensionRoot.resolve("deferred-operations.v1"))
    private val authorityLock = Any()
    private val evictedPending = EvictedShardAuthority(config.saveQueueMax.get()).apply {
        restoreJournalState(journal.load())
    }

    init {
        ensureSchema()
        warnLegacyStorage()
    }

    private fun ensureSchema() {
        ensureTraceSchema(tracesRoot)
    }

    private fun warnLegacyStorage() {
        val legacy = level.server.getServerDirectory().toPath().toAbsolutePath().normalize().resolve("data/traces")
        if (legacy != tracesRoot && Files.isDirectory(legacy)) {
            log.warn("Legacy Traces storage at {} is intentionally not imported; active world storage is {}", legacy, tracesRoot)
        }
    }

    private fun worldDimensionPath(): String = levelKey

    private fun shardPath(id: TraceShardId): Path =
        dimensionRoot.resolve("r.${id.regionX}.${id.regionZ}.traces")

    private fun loadShard(id: TraceShardId): TraceShardState {
        val existing = cache.get(id)
        if (existing != null) return existing
        // An evicted shard remains authoritative until its queued snapshot has reached disk.
        // Never reload the older on-disk version while that state is still in flight.
        val pending = evictedPending.pendingSnapshot(id)
        if (pending != null) {
            val (pendingState, serial) = pending
            try {
                writeSnapshot(id, pendingState)
            } catch (error: Exception) {
                throw StorageBackpressureException("pending trace snapshot could not be recovered durably: ${error.message}")
            }
            check(persistAuthorityMutation { evictedPending.completePendingSnapshot(id, serial) }) {
                "recovered trace snapshot could not be removed from the durable journal"
            }
            check(persistEvictionCandidate(id)) { "cache eviction could not be journaled" }
            val evicted = cache.put(id, pendingState)
            indexShard(id, pendingState)
            if (evicted != null && evicted.second.dirty) {
                val (snapshot, _) = evicted.second.snapshot()
                check(queueEvicted(evicted.first, snapshot)) { "reclaimed eviction buffer was not available" }
            }
            return pendingState
        }
        // Never evict a dirty authority until there is reserved bounded space for
        // its immutable snapshot.  This check happens before a caller mutates the
        // newly requested shard, so rejection cannot leave a partial annotation edit.
        val candidate = cache.evictionCandidateFor(id)
        // A capture reservation is not reusable for the dirty snapshot that
        // eviction would create. Preflight both authorities before cache.put.
        if (candidate?.second?.dirty == true && !evictedPending.canOffer(candidate.first)) {
            throw StorageBackpressureException("Trace storage is temporarily full while writes are unavailable")
        }
        val path = shardPath(id)
        val loaded = if (Files.exists(path) || TraceArchive.hasManifest(path)) {
            val state = TraceSerializer.read(path)
            val counts = state.counts()
            log.debug(
                "Loaded trace shard {} from {} => foot={}, annotations={}, seen={}",
                id,
                path,
                counts.first,
                counts.second,
                counts.third,
            )
            state
        } else {
            TraceShardState()
        }
        check(persistEvictionCandidate(id)) { "cache eviction could not be journaled" }
        val evicted = cache.put(id, loaded)
        indexShard(id, loaded)
        if (evicted != null && evicted.second.dirty) {
            val (snapshot, _) = evicted.second.snapshot()
            check(queueEvicted(evicted.first, snapshot)) { "reserved eviction buffer disappeared" }
        }
        return loaded
    }

    private fun loadOrDefer(id: TraceShardId, kind: StorageOutageFrontier.Kind, recordFailure: Boolean = true): TraceShardState? = try {
        loadShard(id)
    } catch (_: StorageBackpressureException) {
        if (recordFailure) evictedPending.recordRejected(id, kind)
        null
    }

    private fun loadAllOrDefer(ids: Iterable<TraceShardId>, kind: StorageOutageFrontier.Kind): List<Pair<TraceShardId, TraceShardState>>? {
        val loaded = mutableListOf<Pair<TraceShardId, TraceShardState>>()
        for (id in ids) loaded += id to (loadOrDefer(id, kind) ?: return null)
        return loaded
    }

    private fun indexShard(id: TraceShardId, state: TraceShardState) {
        state.annotationsSnapshot().forEach {
            annotationIndex[it.id] = IndexedAnnotation(id, it)
        }
    }

    /**
     * Retains one immutable authority per evicted shard.  It deliberately does
     * not submit a task: tickFlush applies the configured task cap to retries.
     */
    private fun queueEvicted(id: TraceShardId, snapshot: TraceShardState): Boolean =
        persistAcceptedQueueMutation { evictedPending.offer(id, snapshot) }

    private fun persistEvictionCandidate(id: TraceShardId): Boolean {
        val candidate = cache.evictionCandidateFor(id) ?: return true
        if (!candidate.second.dirty) return true
        val snapshot = candidate.second.snapshot().first
        return queueEvicted(candidate.first, snapshot)
    }

    private fun persistAcceptedQueueMutation(change: () -> Boolean): Boolean = synchronized(authorityLock) {
        val before = evictedPending.exportJournalState()
        if (!change()) return@synchronized false
        try {
            journal.persist(evictedPending.exportJournalState())
            true
        } catch (error: Exception) {
            evictedPending.restoreJournalState(before)
            log.warn("Could not durably accept a deferred trace mutation", error)
            false
        }
    }

    private fun persistAuthorityMutation(change: () -> Unit): Boolean = synchronized(authorityLock) {
        val before = evictedPending.exportJournalState()
        try {
            change()
            journal.persist(evictedPending.exportJournalState())
            true
        } catch (error: Exception) {
            evictedPending.restoreJournalState(before)
            log.warn("Could not durably advance the deferred trace journal", error)
            false
        }
    }

    /** Atomically changes a journaled operation into durable shard snapshots before publishing them to cache. */
    private fun commitDeferredReplay(
        replacements: List<Pair<TraceShardId, TraceShardState>>,
        complete: () -> Unit,
    ): Boolean {
        val immutableSnapshots = replacements.map { (id, state) -> id to state.snapshot().first }
        if (!persistAuthorityMutation {
                complete()
                immutableSnapshots.forEach { (id, snapshot) ->
                    check(evictedPending.offer(id, snapshot)) { "deferred replay exceeded its durable authority capacity" }
                }
            }) return false
        replacements.forEach { (id, state) ->
            cache.put(id, state)
            annotationIndex.entries.removeIf { it.value.shardId == id }
            indexShard(id, state)
        }
        return true
    }

    private fun allShardIds(): Sequence<TraceShardId> {
        if (!Files.isDirectory(dimensionRoot)) return emptySequence()
        val ids = mutableListOf<TraceShardId>()
        return runCatching {
            Files.list(dimensionRoot).use { paths ->
                paths.filter { path ->
                    val name = path.fileName.toString()
                    name.startsWith("r.") && name.endsWith(".traces")
                }.forEach { path ->
                    val base = path.fileName.toString()
                    val parts = base.removePrefix("r.").removeSuffix(".traces").split(".")
                    val rx = parts.getOrNull(0)?.toIntOrNull() ?: return@forEach
                    val rz = parts.getOrNull(1)?.toIntOrNull() ?: return@forEach
                    ids += TraceShardId(worldDimensionPath(), rx, rz)
                }
                ids.asSequence()
            }
        }.getOrElse { emptySequence() }
    }

    private fun scanForAnnotation(annotationId: UUID, kind: StorageOutageFrontier.Kind = StorageOutageFrontier.Kind.READ): TraceShardId? {
        annotationIndex[annotationId]?.let { return it.shardId }
        for (id in allShardIds()) {
            val state = loadOrDefer(id, kind) ?: continue
            val annotation = state.annotationById(annotationId)
            if (annotation != null) {
                annotationIndex[annotationId] = IndexedAnnotation(id, annotation)
                return id
            }
        }
        return null
    }

    private fun locateSeenStateShard(annotation: UUID): TraceShardId? {
        annotationIndex[annotation]?.let { return it.shardId }
        return scanForAnnotation(annotation, StorageOutageFrontier.Kind.SEEN_STATE)
    }

    fun queryTraces(boundsMin: BlockPos, boundsMax: BlockPos): List<FootTrace> {
        val (minSX, minSZ) = Geometry.worldToShard(boundsMin)
        val (maxSX, maxSZ) = Geometry.worldToShard(boundsMax)
        val out = mutableListOf<FootTrace>()
        for (sx in minSX..maxSX) {
            for (sz in minSZ..maxSZ) {
                val state = loadOrDefer(TraceShardId(worldDimensionPath(), sx, sz), StorageOutageFrontier.Kind.READ) ?: continue
                out += state.nearbyFootTraces(boundsMin, boundsMax).filter { it.levelKey == levelKey && !isPendingBlockCleanup(it) }
            }
        }
        return out
    }

    /** Return-summary query: use each loaded shard's temporal index before exact bounds filtering. */
    fun queryTracesAfter(boundsMin: BlockPos, boundsMax: BlockPos, createdAfter: Long): List<FootTrace> {
        val (minSX, minSZ) = Geometry.worldToShard(boundsMin)
        val (maxSX, maxSZ) = Geometry.worldToShard(boundsMax)
        val out = mutableListOf<FootTrace>()
        for (sx in minSX..maxSX) {
            for (sz in minSZ..maxSZ) {
                val state = loadOrDefer(TraceShardId(worldDimensionPath(), sx, sz), StorageOutageFrontier.Kind.READ) ?: continue
                out += state.nearbyFootTracesAfter(boundsMin, boundsMax, createdAfter).filter { it.levelKey == levelKey && !isPendingBlockCleanup(it) }
            }
        }
        return out
    }

    fun tileRevision(chunkX: Int, chunkZ: Int): Long {
        val id = shardIdForChunk(chunkX, chunkZ)
        val base = loadOrDefer(id, StorageOutageFrontier.Kind.READ)?.tileRevision(chunkX, chunkZ) ?: 0L
        return overlayCleanupRevision(base, evictedPending.blockCleanupCountForTile(id, chunkX, chunkZ))
    }

    fun pendingBlockCleanupCount(chunkX: Int, chunkZ: Int): Int {
        val id = shardIdForChunk(chunkX, chunkZ)
        return evictedPending.blockCleanupCountForTile(id, chunkX, chunkZ)
    }

    fun queryTraceTile(chunkX: Int, chunkZ: Int): List<FootTrace> {
        val id = shardIdForChunk(chunkX, chunkZ)
        return loadOrDefer(id, StorageOutageFrontier.Kind.READ)?.queryTraceTile(chunkX, chunkZ)
            ?.filter { it.levelKey == levelKey && !isPendingBlockCleanup(it) }.orEmpty()
    }

    fun traceTileSnapshot(chunkX: Int, chunkZ: Int): TraceTileSnapshot {
        val id = shardIdForChunk(chunkX, chunkZ)
        val snapshot = loadOrDefer(id, StorageOutageFrontier.Kind.READ)?.traceTileSnapshot(chunkX, chunkZ)
            ?: TraceTileSnapshot(TraceTileId(chunkX, chunkZ), 0L, emptyList())
        val pending = evictedPending.blockCleanupCountForTile(id, chunkX, chunkZ)
        return snapshot.copy(
            revision = overlayCleanupRevision(snapshot.revision, pending),
            traces = snapshot.traces.filter { it.levelKey == levelKey && !isPendingBlockCleanup(it) },
        )
    }

    fun pruneInvalidSupports(chunkX: Int, chunkZ: Int, isValid: (TraceSupport) -> Boolean): Int {
        val id = shardIdForChunk(chunkX, chunkZ)
        val state = loadOrDefer(id, StorageOutageFrontier.Kind.EVENT_REMOVAL, recordFailure = false)
        if (state == null) {
            if (persistAcceptedQueueMutation { evictedPending.deferSupportPrune(id, chunkX, chunkZ) }) {
                evictedPending.recordDeferred(id, StorageOutageFrontier.Kind.EVENT_REMOVAL)
            } else {
                evictedPending.recordRejected(id, StorageOutageFrontier.Kind.EVENT_REMOVAL)
            }
            return 0
        }
        val removed = state.pruneInvalidSupports(chunkX, chunkZ) { support ->
            !shouldPruneInvalidSupport(support, { position -> level.hasChunkAt(position) }, isValid)
        }
        if (removed > 0) markDirty(id)
        return removed
    }

    fun queryAnnotations(boundsMin: BlockPos, boundsMax: BlockPos): List<TraceAnnotation> {
        val (minSX, minSZ) = Geometry.worldToShard(boundsMin)
        val (maxSX, maxSZ) = Geometry.worldToShard(boundsMax)
        val out = mutableListOf<TraceAnnotation>()
        for (sx in minSX..maxSX) {
            for (sz in minSZ..maxSZ) {
                val state = loadOrDefer(TraceShardId(worldDimensionPath(), sx, sz), StorageOutageFrontier.Kind.READ) ?: continue
                out += state.nearbyAnnotations(boundsMin, boundsMax)
            }
        }
        val additions = evictedPending.annotationSnapshot().filter { it.annotation.isInside(boundsMin, boundsMax) }
        val edits = evictedPending.annotationEditSnapshot().filter { it.annotation.isInside(boundsMin, boundsMax) }
        val removals = evictedPending.annotationRemovalSnapshot().mapTo(HashSet()) { it.annotationId }
        return mergeDeferredAnnotations(out, additions, edits, removals)
    }

    fun addFootTrace(trace: FootTrace): Boolean {
        check(!closed) { "Traces storage is closed" }
        require(trace.levelKey == levelKey) { "trace belongs to ${trace.levelKey}, expected $levelKey" }
        require(trace.kind != com.bettercontent.playertraces.domain.TraceKind.FOOTPRINT || trace.support != null) {
            "footprint trace has no supporting block"
        }
        val (sx, sz) = Geometry.worldToShard(trace.blockPos)
        val id = TraceShardId(worldDimensionPath(), sx, sz)
        when (evictedPending.reserveCapture(trace)) {
            CaptureAdmission.ALREADY_QUEUED -> return false
            CaptureAdmission.REJECTED -> {
                evictedPending.recordRejected(id, StorageOutageFrontier.Kind.CAPTURE)
                return false
            }
            CaptureAdmission.RESERVED -> Unit
        }
        val shard = loadOrDefer(id, StorageOutageFrontier.Kind.CAPTURE, recordFailure = false)
        if (shard == null) {
            // Capture records are the only world evidence eligible for bounded replay.
            // Destructive edits and annotations remain rejected during an outage.
            if (persistAcceptedQueueMutation { evictedPending.offerCapture(trace) }) {
                evictedPending.recordDeferred(id, StorageOutageFrontier.Kind.CAPTURE)
            } else {
                evictedPending.releaseCapture(trace.id)
                evictedPending.recordRejected(id, StorageOutageFrontier.Kind.CAPTURE)
            }
            return false
        }
        evictedPending.releaseCapture(trace.id)
        shard.addFootTrace(trace)
        markDirty(id)
        return true
    }

    fun addAnnotation(annotation: TraceAnnotation): Boolean {
        check(!closed) { "Traces storage is closed" }
        val (sx, sz) = Geometry.worldToShard(annotation.position)
        val id = TraceShardId(worldDimensionPath(), sx, sz)
        val shard = loadOrDefer(id, StorageOutageFrontier.Kind.ANNOTATION, recordFailure = false)
        if (shard == null) {
            if (persistAcceptedQueueMutation { evictedPending.deferAnnotation(id, annotation) }) {
                evictedPending.recordDeferred(id, StorageOutageFrontier.Kind.ANNOTATION)
                annotationIndex[annotation.id] = IndexedAnnotation(id, annotation)
                return true
            } else {
                evictedPending.recordRejected(id, StorageOutageFrontier.Kind.ANNOTATION)
            }
            return false
        }
        shard.putAnnotation(annotation)
        annotationIndex[annotation.id] = IndexedAnnotation(id, annotation)
        shard.markDirty()
        markDirty(id)
        return true
    }

    fun removeAnnotation(annotationId: UUID): Boolean {
        if (evictedPending.annotation(annotationId) != null &&
            persistAuthorityMutation { evictedPending.cancelAnnotation(annotationId) }) {
            annotationIndex.remove(annotationId)
            return true
        }
        if (evictedPending.annotationRemoval(annotationId) != null) return true
        val id = scanForAnnotation(annotationId, StorageOutageFrontier.Kind.ANNOTATION) ?: return false
        val state = loadOrDefer(id, StorageOutageFrontier.Kind.ANNOTATION, recordFailure = false)
        if (state == null) {
            if (persistAcceptedQueueMutation { evictedPending.deferAnnotationRemoval(id, annotationId) }) {
                evictedPending.recordDeferred(id, StorageOutageFrontier.Kind.ANNOTATION)
                return true
            }
            evictedPending.recordRejected(id, StorageOutageFrontier.Kind.ANNOTATION)
            return false
        }
        val removed = state.removeAnnotation(annotationId)
        if (removed) {
            evictedPending.cancelAnnotationEdit(annotationId)
            annotationIndex.remove(annotationId)
            state.markDirty()
            markDirty(id)
            return true
        }
        return false
    }

    fun annotationById(annotationId: UUID): TraceAnnotation? {
        if (evictedPending.annotationRemoval(annotationId) != null) return null
        evictedPending.annotation(annotationId)?.let { return it }
        evictedPending.annotationEdit(annotationId)?.let { return it }
        annotationIndex[annotationId]?.annotation?.let { return it }
        val id = scanForAnnotation(annotationId) ?: return null
        val annotation = loadOrDefer(id, StorageOutageFrontier.Kind.READ)?.annotationById(annotationId)
        if (annotation != null) annotationIndex[annotationId] = IndexedAnnotation(id, annotation)
        return annotation
    }

    fun updateAnnotation(id: UUID, text: String?, icon: String?, color: Int?): TraceAnnotation? {
        if (evictedPending.annotationRemoval(id) != null) return null
        val queued = evictedPending.annotation(id)
        if (queued != null) {
            val updated = queued.copy(
                text = text ?: queued.text,
                icon = icon ?: queued.icon,
                color = color ?: queued.color,
                revision = queued.revision + 1,
            )
            return if (persistAcceptedQueueMutation { evictedPending.replaceAnnotation(id, updated) }) updated else null
        }
        val queuedEditEntry = evictedPending.annotationEditEntry(id)
        if (queuedEditEntry != null) {
            val queuedEdit = queuedEditEntry.annotation
            val updated = queuedEdit.copy(
                text = text ?: queuedEdit.text,
                icon = icon ?: queuedEdit.icon,
                color = color ?: queuedEdit.color,
                revision = queuedEdit.revision + 1,
            )
            if (!persistAcceptedQueueMutation { evictedPending.replaceAnnotation(id, updated) }) return null
            annotationIndex[id] = IndexedAnnotation(queuedEditEntry.shardId, updated)
            return updated
        }
        val shardId = annotationIndex[id]?.shardId ?: scanForAnnotation(id, StorageOutageFrontier.Kind.ANNOTATION) ?: return null
        val current = annotationIndex[id]?.annotation
            ?: loadOrDefer(shardId, StorageOutageFrontier.Kind.ANNOTATION)?.annotationById(id)?.also { annotationIndex[id] = IndexedAnnotation(shardId, it) }
            ?: return null
        val updated = current.copy(
            text = text ?: current.text,
            icon = icon ?: current.icon,
            color = color ?: current.color,
            revision = current.revision + 1,
        )
        val shard = loadOrDefer(shardId, StorageOutageFrontier.Kind.ANNOTATION, recordFailure = false)
        if (shard == null) {
            if (!persistAcceptedQueueMutation { evictedPending.deferAnnotationEdit(shardId, updated) }) {
                evictedPending.recordRejected(shardId, StorageOutageFrontier.Kind.ANNOTATION)
                return null
            }
            annotationIndex[id] = IndexedAnnotation(shardId, updated)
            evictedPending.recordDeferred(shardId, StorageOutageFrontier.Kind.ANNOTATION)
            return updated
        }
        if (!shard.replaceAnnotation(updated)) return null
        shard.markDirty()
        markDirty(shardId)
        annotationIndex[id] = IndexedAnnotation(shardId, updated)
        return updated
    }

    /** Ephemeral outage data; it is intentionally not claimed durable during disk failure. */
    internal fun outageFrontier(): StorageOutageFrontier = evictedPending.frontierSnapshot()

    fun setSeen(player: UUID, annotation: UUID, revision: Int) {
        if (closed || revision < 0) return
        val shardId = locateSeenStateShard(annotation) ?: return
        val shard = loadOrDefer(shardId, StorageOutageFrontier.Kind.SEEN_STATE, recordFailure = false)
        if (shard == null) {
            val record = SeenStateRecord(annotation, player, revision)
            if (persistAcceptedQueueMutation { evictedPending.deferSeen(shardId, record) }) {
                evictedPending.recordDeferred(shardId, StorageOutageFrontier.Kind.SEEN_STATE)
            } else {
                evictedPending.recordRejected(shardId, StorageOutageFrontier.Kind.SEEN_STATE)
            }
            return
        }
        applySeen(shardId, shard, SeenStateRecord(annotation, player, revision))
    }

    private fun applySeen(shardId: TraceShardId, shard: TraceShardState, record: SeenStateRecord) {
        if (shard.annotationById(record.annotationId) == null) return
        synchronized(shard) {
            val index = shard.seenStates.indexOfFirst {
                it.playerId == record.playerId && it.annotationId == record.annotationId
            }
            if (index >= 0) {
                if (record.highestRevision > shard.seenStates[index].highestRevision) {
                    shard.seenStates[index] = record
                    shard.markDirty()
                    markDirty(shardId)
                }
            } else {
                shard.seenStates += record
                shard.markDirty()
                markDirty(shardId)
            }
        }
    }

    fun getSeen(player: UUID, annotation: UUID): Int {
        val shardId = annotationIndex[annotation]?.shardId ?: return 0
        return loadOrDefer(shardId, StorageOutageFrontier.Kind.READ)?.seenStatesSnapshot()
            ?.firstOrNull { it.annotationId == annotation && it.playerId == player }
            ?.highestRevision ?: 0
    }

    fun removeByPosition(pos: BlockPos) {
        val (sx, sz) = Geometry.worldToShard(pos)
        val id = TraceShardId(worldDimensionPath(), sx, sz)
        val state = loadOrDefer(id, StorageOutageFrontier.Kind.EVENT_REMOVAL, recordFailure = false)
        if (state == null) {
            if (persistAcceptedQueueMutation { evictedPending.deferBlockCleanup(DeferredBlockCleanup(id, pos.immutable(), BlockCleanupKind.TRACE_POSITION)) }) {
                evictedPending.recordDeferred(id, StorageOutageFrontier.Kind.EVENT_REMOVAL)
            } else evictedPending.recordRejected(id, StorageOutageFrontier.Kind.EVENT_REMOVAL)
            return
        }
        if (state.removeAtPosition(pos) > 0) markDirty(id)
    }

    fun removeBySupport(pos: BlockPos): Int {
        val (sx, sz) = Geometry.worldToShard(pos)
        val id = TraceShardId(worldDimensionPath(), sx, sz)
        val state = loadOrDefer(id, StorageOutageFrontier.Kind.EVENT_REMOVAL, recordFailure = false)
        if (state == null) {
            if (persistAcceptedQueueMutation { evictedPending.deferBlockCleanup(DeferredBlockCleanup(id, pos.immutable(), BlockCleanupKind.SUPPORT_POSITION)) }) {
                evictedPending.recordDeferred(id, StorageOutageFrontier.Kind.EVENT_REMOVAL)
            } else evictedPending.recordRejected(id, StorageOutageFrontier.Kind.EVENT_REMOVAL)
            return 0
        }
        val removed = state.removeBySupport(pos)
        if (removed > 0) markDirty(id)
        return removed
    }

    fun removeFootTraces(boundsMin: BlockPos, boundsMax: BlockPos): Int {
        require(boundsMin.x <= boundsMax.x && boundsMin.y <= boundsMax.y && boundsMin.z <= boundsMax.z) {
            "trace cleanup bounds must be ordered"
        }
        val (minSX, minSZ) = Geometry.worldToShard(boundsMin)
        val (maxSX, maxSZ) = Geometry.worldToShard(boundsMax)
        val shardCount = (maxSX.toLong() - minSX + 1L) * (maxSZ.toLong() - minSZ + 1L)
        require(shardCount <= config.saveQueueMax.get()) {
            "trace cleanup spans $shardCount shards; per-tick replay budget is ${config.saveQueueMax.get()}"
        }
        val ids = (minSX..maxSX).flatMap { sx -> (minSZ..maxSZ).map { sz -> TraceShardId(worldDimensionPath(), sx, sz) } }
        val loaded = mutableListOf<Pair<TraceShardId, TraceShardState>>()
        for (id in ids) {
            val state = loadOrDefer(id, StorageOutageFrontier.Kind.EVENT_REMOVAL, recordFailure = false)
            if (state == null) {
                val cleanups = ids.map { shardId ->
                    DeferredBlockCleanup(
                        shardId, BlockPos.ZERO, BlockCleanupKind.TRACE_BOUNDS,
                        boundsMin.immutable(), boundsMax.immutable(),
                    )
                }
                if (persistAcceptedQueueMutation { evictedPending.deferBlockCleanups(cleanups) }) {
                    ids.forEach { evictedPending.recordDeferred(it, StorageOutageFrontier.Kind.EVENT_REMOVAL) }
                } else {
                    evictedPending.recordRejected(id, StorageOutageFrontier.Kind.EVENT_REMOVAL)
                }
                return 0
            }
            loaded += id to state
        }
        var removed = 0
        for ((id, state) in loaded) {
            val shardRemoved = state.removeFootTraces(boundsMin, boundsMax)
            if (shardRemoved > 0) {
                removed += shardRemoved
                markDirty(id)
            }
        }
        return removed
    }

    fun weakenAround(pos: BlockPos, radius: Int, factor: Double) {
        require(factor.isFinite() && factor >= 0.0) { "erosion factor must be finite and non-negative" }
        val (sx, sz) = Geometry.worldToShard(pos)
        val shardRadius = neighborhoodShardRadius(radius)
        val shardCount = neighborhoodShardCount(radius)
        require(neighborhoodFitsReplayBudget(radius, config.saveQueueMax.get())) {
            "neighborhood weakening spans $shardCount shards; per-tick replay budget is ${config.saveQueueMax.get()}"
        }
        val center = TraceShardId(worldDimensionPath(), sx, sz)
        if (!applyNeighborhoodWeakness(center, shardRadius, pos, factor)) {
            if (persistAcceptedQueueMutation { evictedPending.deferNeighborhoodWeakening(center, pos, radius, factor) }) {
                evictedPending.recordDeferred(center, StorageOutageFrontier.Kind.EROSION)
            } else evictedPending.recordRejected(center, StorageOutageFrontier.Kind.EROSION)
        }
    }

    private fun neighborhoodIds(center: TraceShardId, shardRadius: Int): List<TraceShardId> =
        (-shardRadius..shardRadius).flatMap { dx ->
            (-shardRadius..shardRadius).map { dz ->
                center.copy(regionX = center.regionX + dx, regionZ = center.regionZ + dz)
            }
        }

    private fun applyNeighborhoodWeakness(center: TraceShardId, shardRadius: Int, pos: BlockPos, factor: Double): Boolean {
        val states = mutableListOf<Pair<TraceShardId, TraceShardState>>()
        for (id in neighborhoodIds(center, shardRadius)) {
            val state = loadOrDefer(id, StorageOutageFrontier.Kind.EROSION, recordFailure = false) ?: return false
            states += id to state
        }
        for ((id, state) in states) {
            state.updateWeakness(pos, factor)
            if (state.dirty) markDirty(id)
        }
        return true
    }

    /** Applies rain erosion to one previously qualified footprint only. */
    fun weakenFootprint(traceId: UUID, position: BlockPos, factor: Double): Boolean {
        val (sx, sz) = Geometry.worldToShard(position)
        val id = TraceShardId(worldDimensionPath(), sx, sz)
        val state = loadOrDefer(id, StorageOutageFrontier.Kind.EROSION, recordFailure = false)
        if (state == null) {
            if (persistAcceptedQueueMutation { evictedPending.deferFootprintErosion(id, traceId, position.immutable(), factor) }) {
                evictedPending.recordDeferred(id, StorageOutageFrontier.Kind.EROSION)
            } else evictedPending.recordRejected(id, StorageOutageFrontier.Kind.EROSION)
            return false
        }
        val changed = state.updateTraceWeakness(traceId, factor)
        if (changed) markDirty(id)
        return changed
    }

    fun allStorageShards(): Sequence<TraceShardState> = cache.valuesSnapshot().asSequence()

    fun shardIdsWithLivingTraces(radiusLimit: Int = 64): List<TraceShardId> {
        val chunkRadius = (radiusLimit / 256).coerceAtLeast(1)
        val origin = Geometry.worldToShard(level.sharedSpawnPos)
        val ids = mutableListOf<TraceShardId>()
        for (sx in origin.first - chunkRadius..origin.first + chunkRadius) {
            for (sz in origin.second - chunkRadius..origin.second + chunkRadius) {
                val id = TraceShardId(worldDimensionPath(), sx, sz)
                val state = loadOrDefer(id, StorageOutageFrontier.Kind.READ) ?: continue
                if (state.footTracesSnapshot().isNotEmpty()) ids += id
            }
        }
        return ids
    }

    fun allSurvivingFootTraces(levelKey: String): List<FootTrace> {
        val seenIds = HashSet<java.util.UUID>()
        val out = mutableListOf<FootTrace>()
        for (state in allStorageShards()) {
            for (trace in state.footTracesSnapshot()) {
                if (trace.levelKey == levelKey && seenIds.add(trace.id)) {
                    out += trace
                }
            }
        }
        for (id in allShardIds()) {
            val state = loadOrDefer(id, StorageOutageFrontier.Kind.READ) ?: continue
            for (trace in state.footTracesSnapshot()) {
                if (trace.levelKey == levelKey && seenIds.add(trace.id)) {
                    out += trace
                }
            }
        }
        return out
    }

    fun allAnnotations(): List<TraceAnnotation> {
        val out = mutableListOf<TraceAnnotation>()
        val seen = HashSet<UUID>()
        allStorageShards().forEach { state -> state.annotationsSnapshot().filterTo(out) { seen.add(it.id) } }
        allShardIds().forEach { id -> loadOrDefer(id, StorageOutageFrontier.Kind.READ)?.annotationsSnapshot()?.filterTo(out) { seen.add(it.id) } }
        return mergeDeferredAnnotations(
            out,
            evictedPending.annotationSnapshot(),
            evictedPending.annotationEditSnapshot(),
            evictedPending.annotationRemovalSnapshot().mapTo(HashSet()) { it.annotationId },
        )
    }

    fun allLivingFootTraces(): List<FootTrace> {
        return allStorageShards().flatMap { state ->
            state.footTracesSnapshot().filter { it.surviving && !isPendingBlockCleanup(it) }
        }.toList()
    }

    private fun isPendingBlockCleanup(trace: FootTrace): Boolean = evictedPending.blockCleanupSnapshot().any { pending ->
        when (pending.kind) {
            BlockCleanupKind.TRACE_POSITION -> trace.blockPos == pending.position
            BlockCleanupKind.SUPPORT_POSITION -> trace.support?.position == pending.position
            BlockCleanupKind.TRACE_BOUNDS -> {
                isInsideTraceCleanupBounds(
                    trace.blockPos, requireNotNull(pending.boundsMin), requireNotNull(pending.boundsMax),
                )
            }
        }
    }

    private fun markDirty(id: TraceShardId) {
        val shard = cache.get(id) ?: return
        shard.markDirty()
    }

    private fun shardIdForChunk(chunkX: Int, chunkZ: Int): TraceShardId {
        val block = BlockPos(chunkX * 16, 0, chunkZ * 16)
        val (sx, sz) = Geometry.worldToShard(block)
        return TraceShardId(worldDimensionPath(), sx, sz)
    }

    private fun flush(id: TraceShardId) {
        val shard = cache.get(id) ?: return
        if (!shard.dirty) return
        val (snapshot, generation) = shard.snapshot()
        try {
            writeSnapshot(id, snapshot)
            shard.clearDirtyIfUnchanged(generation)
        } catch (error: Exception) {
            log.warn("Failed to flush trace shard {}", id, error)
        }
    }

    private fun writeSnapshot(id: TraceShardId, snapshot: TraceShardState) {
        val path = shardPath(id)
        val payload = TraceSerializer.encodeV3(snapshot, Geometry.shardToBounds(id.regionX, id.regionZ))
        TraceArchive.publish(path, payload, snapshot.archiveRevision())
        log.debug("Flushed trace shard {} to {}", id, path)
    }

    fun tickFlush() {
        if (closed) return
        pruneCompletedFlushes()
        if (pendingFlushes.size >= config.saveQueueMax.get()) return
        // Replay at most the configured bounded amount per server tick. Rotate
        // the first queue each tick so a continuously busy producer cannot starve
        // other accepted deferred mutations.
        var replayed = 0
        val replayLimit = config.saveQueueMax.get()
        fun replayCaptures() {
            for ((traceId, trace) in evictedPending.captureSnapshot()) {
                if (replayed >= replayLimit) break
                val (sx, sz) = Geometry.worldToShard(trace.blockPos)
                val id = TraceShardId(worldDimensionPath(), sx, sz)
                val current = loadOrDefer(id, StorageOutageFrontier.Kind.CAPTURE, recordFailure = false) ?: continue
                val updated = current.snapshot().first
                val inserted = updated.addFootTraceIfAbsent(trace)
                if (!commitDeferredReplay(if (inserted) listOf(id to updated) else emptyList()) {
                        evictedPending.completeCapture(traceId)
                    }) break
                replayed++
            }
        }
        fun replayAnnotationAdds() {
            for (pending in evictedPending.annotationSnapshot()) {
                if (replayed >= replayLimit) break
                val current = loadOrDefer(pending.shardId, StorageOutageFrontier.Kind.ANNOTATION, recordFailure = false)
                    ?: continue
                val updated = current.snapshot().first
                if (!updated.putAnnotationIfAbsent(pending.annotation)) {
                    if (!commitDeferredReplay(emptyList()) { evictedPending.completeAnnotation(pending) }) break
                    annotationIndex.remove(pending.annotation.id)
                    evictedPending.recordRejected(pending.shardId, StorageOutageFrontier.Kind.ANNOTATION)
                    replayed++
                    continue
                }
                if (!commitDeferredReplay(listOf(pending.shardId to updated)) {
                        evictedPending.completeAnnotation(pending)
                    }) break
                replayed++
            }
        }
        fun replayAnnotationEdits() {
            for (pending in evictedPending.annotationEditSnapshot()) {
                if (replayed >= replayLimit) break
                val current = loadOrDefer(pending.shardId, StorageOutageFrontier.Kind.ANNOTATION, recordFailure = false)
                    ?: continue
                val updated = current.snapshot().first
                if (!updated.replaceAnnotation(pending.annotation)) {
                    if (!commitDeferredReplay(emptyList()) { evictedPending.completeAnnotationEdit(pending) }) break
                    annotationIndex.remove(pending.annotation.id)
                    evictedPending.recordRejected(pending.shardId, StorageOutageFrontier.Kind.ANNOTATION)
                    replayed++
                    continue
                }
                if (!commitDeferredReplay(listOf(pending.shardId to updated)) {
                        evictedPending.completeAnnotationEdit(pending)
                    }) break
                replayed++
            }
        }
        fun replayAnnotationRemovals() {
            for (pending in evictedPending.annotationRemovalSnapshot()) {
                if (replayed >= replayLimit) break
                val current = loadOrDefer(pending.shardId, StorageOutageFrontier.Kind.ANNOTATION, recordFailure = false)
                    ?: continue
                val updated = current.snapshot().first
                val removed = updated.removeAnnotation(pending.annotationId)
                if (!commitDeferredReplay(if (removed) listOf(pending.shardId to updated) else emptyList()) {
                        evictedPending.completeAnnotationRemoval(pending)
                    }) break
                annotationIndex.remove(pending.annotationId)
                replayed++
            }
        }
        fun replayBlockCleanups() {
            for (pending in evictedPending.blockCleanupSnapshot()) {
                if (replayed >= replayLimit) break
                val current = loadOrDefer(pending.shardId, StorageOutageFrontier.Kind.EVENT_REMOVAL, recordFailure = false)
                    ?: continue
                val shard = current.snapshot().first
                val removed = when (pending.kind) {
                    BlockCleanupKind.TRACE_POSITION -> shard.removeAtPosition(pending.position)
                    BlockCleanupKind.SUPPORT_POSITION -> shard.removeBySupport(pending.position)
                    BlockCleanupKind.TRACE_BOUNDS -> shard.removeFootTraces(
                        requireNotNull(pending.boundsMin), requireNotNull(pending.boundsMax),
                    )
                }
                if (!commitDeferredReplay(if (removed > 0) listOf(pending.shardId to shard) else emptyList()) {
                        evictedPending.completeBlockCleanup(pending)
                    }) break
                replayed++
            }
        }
        fun replayFootprintErosion() {
            for (pending in evictedPending.footprintErosionSnapshot()) {
                if (replayed >= replayLimit) break
                val current = loadOrDefer(pending.shardId, StorageOutageFrontier.Kind.EROSION, recordFailure = false)
                    ?: continue
                val updated = current.snapshot().first
                val changed = updated.updateTraceWeakness(pending.traceId, pending.factor)
                if (!commitDeferredReplay(if (changed) listOf(pending.shardId to updated) else emptyList()) {
                        evictedPending.completeFootprintErosion(pending)
                    }) break
                replayed++
            }
        }
        fun replaySupportPrunes() {
            for (pending in evictedPending.supportPruneSnapshot()) {
                if (replayed >= replayLimit) break
                val tileOrigin = BlockPos(pending.chunkX shl 4, level.minBuildHeight, pending.chunkZ shl 4)
                if (!level.hasChunkAt(tileOrigin)) continue
                val current = loadOrDefer(pending.shardId, StorageOutageFrontier.Kind.EVENT_REMOVAL, recordFailure = false)
                    ?: continue
                val shard = current.snapshot().first
                val removed = shard.pruneInvalidSupports(pending.chunkX, pending.chunkZ) { support ->
                    // A stale support can be removed only when its chunk is already loaded.
                    // This callback must never make a cleanup replay load world chunks.
                    !shouldPruneInvalidSupport(support, { position -> level.hasChunkAt(position) }) { candidate ->
                        val state = level.getBlockState(candidate.position)
                        !state.isAir && net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.block) == candidate.blockId
                    }
                }
                if (!commitDeferredReplay(if (removed > 0) listOf(pending.shardId to shard) else emptyList()) {
                        evictedPending.completeSupportPrune(pending)
                    }) break
                replayed++
            }
        }
        fun replayNeighborhoodWeakening() {
            for (pending in evictedPending.neighborhoodWeakeningSnapshot()) {
                val cost = neighborhoodShardCount(pending.radius)
                if (replayed + cost > replayLimit) break
                val ids = neighborhoodIds(pending.centerShard, neighborhoodShardRadius(pending.radius))
                val loaded = loadAllOrDefer(ids, StorageOutageFrontier.Kind.EROSION) ?: continue
                val updates = loaded.map { (id, state) -> id to state.snapshot().first }
                updates.forEach { (_, state) -> state.updateWeakness(pending.position, pending.factor) }
                val changed = updates.filter { it.second.dirty }
                if (!commitDeferredReplay(changed) { evictedPending.completeNeighborhoodWeakening(pending) }) break
                replayed += cost.toInt()
            }
        }
        fun replaySeenState() {
            for (pending in evictedPending.seenSnapshot()) {
                if (replayed >= replayLimit) break
                val current = loadOrDefer(pending.shardId, StorageOutageFrontier.Kind.SEEN_STATE, recordFailure = false)
                    ?: continue
                val updated = current.snapshot().first
                applySeen(pending.shardId, updated, pending.record)
                val changed = updated.dirty
                if (!commitDeferredReplay(if (changed) listOf(pending.shardId to updated) else emptyList()) {
                        evictedPending.completeSeen(pending)
                    }) break
                replayed++
            }
        }
        val replayers: Map<DeferredReplayQueue, () -> Unit> = mapOf(
            DeferredReplayQueue.CAPTURE to ::replayCaptures,
            DeferredReplayQueue.ANNOTATION_ADD to ::replayAnnotationAdds,
            DeferredReplayQueue.ANNOTATION_EDIT to ::replayAnnotationEdits,
            DeferredReplayQueue.ANNOTATION_REMOVE to ::replayAnnotationRemovals,
            DeferredReplayQueue.BLOCK_CLEANUP to ::replayBlockCleanups,
            DeferredReplayQueue.FOOTPRINT_EROSION to ::replayFootprintErosion,
            DeferredReplayQueue.SUPPORT_PRUNE to ::replaySupportPrunes,
            DeferredReplayQueue.NEIGHBORHOOD_WEAKENING to ::replayNeighborhoodWeakening,
            DeferredReplayQueue.SEEN_STATE to ::replaySeenState,
        )
        for (queue in evictedPending.nextReplayOrder()) {
            // A seen revision for a queued annotation would otherwise be dropped by
            // applySeen before that annotation has been replayed.
            if (queue == DeferredReplayQueue.SEEN_STATE &&
                !mayReplaySeenState(evictedPending.annotationSnapshot().isNotEmpty())) continue
            replayers.getValue(queue).invoke()
        }
        for ((id, snapshot) in evictedPending.snapshot()) {
            if (pendingFlushes.size >= config.saveQueueMax.get()) break
            if (!queuedFlushes.add(id)) continue
            pendingFlushes += dirtyExecutor.submit {
                try {
                    writeSnapshot(id, snapshot)
                    if (!persistAuthorityMutation { evictedPending.complete(id, snapshot) }) {
                        log.error("Trace shard {} reached the archive but its journal acknowledgement failed", id)
                    }
                } catch (error: Exception) {
                    log.warn("Failed to flush evicted trace shard {}", id, error)
                } finally {
                    queuedFlushes.remove(id)
                }
            }
        }
        for (id in cache.takeAllDirty()) {
            if (pendingFlushes.size >= config.saveQueueMax.get()) break
            if (!queuedFlushes.add(id)) continue
            pendingFlushes += dirtyExecutor.submit {
                try {
                    flush(id)
                } finally {
                    queuedFlushes.remove(id)
                }
            }
        }
    }

    private fun pruneCompletedFlushes() {
        val iterator = pendingFlushes.iterator()
        while (iterator.hasNext()) {
            val future = iterator.next()
            if (future.isDone) {
                iterator.remove()
            }
        }
    }

    private fun waitForPendingFlushes(timeoutMs: Long): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (true) {
            pruneCompletedFlushes()
            if (pendingFlushes.isEmpty()) return true
            if (System.nanoTime() >= deadline) return false
            try {
                Thread.sleep(10)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
    }

    fun close() {
        if (closed) return
        closed = true
        pruneCompletedFlushes()
        for (id in cache.takeAllDirty()) {
            if (!queuedFlushes.add(id)) continue
            pendingFlushes += dirtyExecutor.submit {
                try { flush(id) } finally { queuedFlushes.remove(id) }
            }
        }
        dirtyExecutor.shutdown()
        if (!dirtyExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
            dirtyExecutor.shutdownNow()
            log.error("Timed out draining Traces flush executor for {}", levelKey)
        }
        waitForPendingFlushes(2000)
        val failures = mutableListOf<Exception>()
        for (id in cache.takeAllDirty()) {
            val (snapshot, generation) = cache.get(id)?.snapshot() ?: continue
            try {
                writeSnapshot(id, snapshot)
                cache.get(id)?.clearDirtyIfUnchanged(generation)
            } catch (error: Exception) {
                failures += error
            }
        }
        for ((id, snapshot) in evictedPending.snapshot()) {
                try {
                    writeSnapshot(id, snapshot)
                    if (!persistAuthorityMutation { evictedPending.complete(id, snapshot) }) {
                        failures += IllegalStateException("Could not acknowledge durable trace shard $id")
                    }
            } catch (error: Exception) {
                failures += error
            }
        }
        if (failures.isNotEmpty()) throw IllegalStateException("Failed to persist ${failures.size} Traces shard(s) during shutdown", failures.first())
    }
}

internal data class SeenStateRecord(
    val annotationId: UUID,
    val playerId: UUID,
    val highestRevision: Int,
)

internal data class DeferredSeenState(
    val shardId: TraceShardId,
    val record: SeenStateRecord,
)

internal data class DeferredAnnotation(
    val shardId: TraceShardId,
    val annotation: TraceAnnotation,
)

internal data class DeferredAnnotationRemoval(
    val shardId: TraceShardId,
    val annotationId: UUID,
)

internal data class DeferredAnnotationEdit(
    val shardId: TraceShardId,
    val annotation: TraceAnnotation,
)

internal enum class BlockCleanupKind { TRACE_POSITION, SUPPORT_POSITION, TRACE_BOUNDS }

internal data class DeferredBlockCleanup(
    val shardId: TraceShardId,
    val position: BlockPos,
    val kind: BlockCleanupKind,
    val boundsMin: BlockPos? = null,
    val boundsMax: BlockPos? = null,
)

internal data class DeferredFootprintErosion(
    val serial: Long,
    val shardId: TraceShardId,
    val traceId: UUID,
    val position: BlockPos,
    val factor: Double,
)

internal data class DeferredSupportPrune(
    val shardId: TraceShardId,
    val chunkX: Int,
    val chunkZ: Int,
)

internal data class DeferredNeighborhoodWeakening(
    val serial: Long,
    val centerShard: TraceShardId,
    val position: BlockPos,
    val radius: Int,
    val factor: Double,
)

private data class SeenStateKey(val playerId: UUID, val annotationId: UUID)
private data class AnnotationKey(val annotationId: UUID)

/**
 * Holds an evicted snapshot until it is either reclaimed by a cache miss or durably written.
 * Identity-aware completion prevents an older queued writer from deleting a newer eviction.
 */
internal data class StorageOutageFrontier(
    val rejectedCaptures: Long,
    val rejectedAnnotations: Long,
    val affectedShards: Set<TraceShardId>,
    val deferredByKind: Map<Kind, Long> = emptyMap(),
) {
    enum class Kind { CAPTURE, ANNOTATION, EVENT_REMOVAL, EROSION, SEEN_STATE, READ }
}

/** Text for operators; counters remain process-local, accepted mutations are journaled durably. */
internal object StorageOutageStatus {
    fun format(frontier: StorageOutageFrontier): String =
            "storage outage frontier: rejected captures=${frontier.rejectedCaptures} " +
            "annotations=${frontier.rejectedAnnotations} affected regions=${frontier.affectedShards.size} " +
            "deferred=${frontier.deferredByKind.entries.sortedBy { it.key.name }.joinToString(",") { "${it.key.name.lowercase()}=${it.value}" }} " +
            "accepted deferred mutations durably journaled; diagnostic counters are memory-only"
}

internal class StorageBackpressureException(message: String) : IllegalStateException(message)

internal enum class CaptureAdmission { RESERVED, ALREADY_QUEUED, REJECTED }

internal class EvictedShardAuthority(private val capacity: Int = Int.MAX_VALUE) {
    private data class Pending(val serial: Long, val snapshot: TraceShardState)
    private val pending = mutableMapOf<TraceShardId, Pending>()
    private val deferredCaptures = LinkedHashMap<UUID, FootTrace>()
    private val deferredSeen = LinkedHashMap<SeenStateKey, DeferredSeenState>()
    private val deferredAnnotations = LinkedHashMap<AnnotationKey, DeferredAnnotation>()
    private val deferredAnnotationEdits = LinkedHashMap<AnnotationKey, DeferredAnnotationEdit>()
    private val deferredAnnotationRemovals = LinkedHashMap<AnnotationKey, DeferredAnnotationRemoval>()
    private val deferredBlockCleanups = LinkedHashMap<DeferredBlockCleanup, DeferredBlockCleanup>()
    private val deferredFootprintErosions = LinkedHashMap<Long, DeferredFootprintErosion>()
    private val deferredSupportPrunes = LinkedHashMap<DeferredSupportPrune, DeferredSupportPrune>()
    private val deferredNeighborhoodWeakenings = LinkedHashMap<Long, DeferredNeighborhoodWeakening>()
    private var replayQueueCursor = 0
    private var nextErosionSerial = 0L
    private var nextNeighborhoodSerial = 0L
    private val frontierShards = LinkedHashSet<TraceShardId>()
    private var nextSerial = 0L
    private var rejectedCaptures = 0L
    private var rejectedAnnotations = 0L
    private val deferredByKind = mutableMapOf<StorageOutageFrontier.Kind, Long>()

    init {
        require(capacity > 0) { "pending authority capacity must be positive" }
    }

    @Synchronized
    fun nextReplayOrder(): List<DeferredReplayQueue> {
        val queues = DeferredReplayQueue.values()
        val start = replayQueueCursor
        replayQueueCursor = (replayQueueCursor + 1) % queues.size
        return List(queues.size) { offset -> queues[(start + offset) % queues.size] }
    }

    @Synchronized
    fun exportJournalState(): DeferredJournalState = DeferredJournalState(
        pendingShards = pending.map { (id, value) ->
            DeferredShardSnapshot(id, value.serial, value.snapshot.snapshot().first)
        },
        captures = deferredCaptures.values.toList(),
        seen = deferredSeen.values.toList(),
        annotations = deferredAnnotations.values.toList(),
        annotationEdits = deferredAnnotationEdits.values.toList(),
        annotationRemovals = deferredAnnotationRemovals.values.toList(),
        blockCleanups = deferredBlockCleanups.values.toList(),
        footprintErosions = deferredFootprintErosions.values.toList(),
        supportPrunes = deferredSupportPrunes.values.toList(),
        neighborhoodWeakenings = deferredNeighborhoodWeakenings.values.toList(),
        nextSerial = nextSerial,
        nextErosionSerial = nextErosionSerial,
        nextNeighborhoodSerial = nextNeighborhoodSerial,
    )

    @Synchronized
    fun restoreJournalState(state: DeferredJournalState) {
        pending.clear()
        deferredCaptures.clear()
        deferredSeen.clear()
        deferredAnnotations.clear()
        deferredAnnotationEdits.clear()
        deferredAnnotationRemovals.clear()
        deferredBlockCleanups.clear()
        deferredFootprintErosions.clear()
        deferredSupportPrunes.clear()
        deferredNeighborhoodWeakenings.clear()
        reservations.clear()
        state.pendingShards.forEach { pending[it.id] = Pending(it.serial, it.state) }
        state.captures.forEach { deferredCaptures[it.id] = it }
        state.seen.forEach { deferredSeen[SeenStateKey(it.record.playerId, it.record.annotationId)] = it }
        state.annotations.forEach { deferredAnnotations[AnnotationKey(it.annotation.id)] = it }
        state.annotationEdits.forEach { deferredAnnotationEdits[AnnotationKey(it.annotation.id)] = it }
        state.annotationRemovals.forEach { deferredAnnotationRemovals[AnnotationKey(it.annotationId)] = it }
        state.blockCleanups.forEach { deferredBlockCleanups[it] = it }
        state.footprintErosions.forEach { deferredFootprintErosions[it.serial] = it }
        state.supportPrunes.forEach { deferredSupportPrunes[it] = it }
        state.neighborhoodWeakenings.forEach { deferredNeighborhoodWeakenings[it.serial] = it }
        nextSerial = maxOf(state.nextSerial, state.pendingShards.maxOfOrNull { it.serial } ?: 0L)
        nextErosionSerial = maxOf(state.nextErosionSerial, state.footprintErosions.maxOfOrNull { it.serial } ?: 0L)
        nextNeighborhoodSerial = maxOf(state.nextNeighborhoodSerial, state.neighborhoodWeakenings.maxOfOrNull { it.serial } ?: 0L)
    }

    @Synchronized
    fun pendingSnapshot(id: TraceShardId): Pair<TraceShardState, Long>? =
        pending[id]?.let { it.snapshot.snapshot().first to it.serial }

    @Synchronized
    fun completePendingSnapshot(id: TraceShardId, serial: Long): Boolean {
        val current = pending[id] ?: return false
        if (current.serial != serial) return false
        pending.remove(id)
        return true
    }

    @Synchronized
    fun canOffer(id: TraceShardId): Boolean =
        id in pending || pending.size + deferredCaptures.size + deferredSeen.size + deferredAnnotations.size + deferredAnnotationEdits.size + deferredAnnotationRemovals.size + deferredBlockCleanups.size + deferredFootprintErosions.size + deferredSupportPrunes.size + deferredNeighborhoodWeakenings.size + reservations.size < capacity

    @Synchronized
    fun offer(id: TraceShardId, snapshot: TraceShardState): Boolean {
        if (!canOffer(id)) return false
        pending[id] = Pending(++nextSerial, snapshot)
        return true
    }

    @Synchronized
    fun offerCapture(trace: FootTrace): Boolean {
        if (deferredCaptures.containsKey(trace.id)) {
            reservations.remove(trace.id)
            return true
        }
        val otherReservations = reservations.size - if (trace.id in reservations) 1 else 0
        if (pending.size + deferredCaptures.size + deferredSeen.size + deferredAnnotations.size + deferredAnnotationEdits.size + deferredAnnotationRemovals.size + deferredBlockCleanups.size + deferredFootprintErosions.size + deferredSupportPrunes.size + deferredNeighborhoodWeakenings.size + otherReservations >= capacity) return false
        deferredCaptures[trace.id] = trace
        reservations.remove(trace.id)
        return true
    }

    @Synchronized
    fun deferSeen(id: TraceShardId, record: SeenStateRecord): Boolean {
        val key = SeenStateKey(record.playerId, record.annotationId)
        val current = deferredSeen[key]
        if (current != null) {
            require(current.shardId == id) { "queued seen-state annotation moved shards" }
            if (record.highestRevision > current.record.highestRevision) {
                deferredSeen[key] = DeferredSeenState(id, record)
            }
            return true
        }
        if (pending.size + deferredCaptures.size + deferredSeen.size + deferredAnnotations.size + deferredAnnotationEdits.size + deferredAnnotationRemovals.size + deferredBlockCleanups.size + deferredFootprintErosions.size + deferredSupportPrunes.size + deferredNeighborhoodWeakenings.size + reservations.size >= capacity) return false
        deferredSeen[key] = DeferredSeenState(id, record)
        return true
    }

    @Synchronized
    fun seenSnapshot(): List<DeferredSeenState> = deferredSeen.values.toList()

    @Synchronized
    fun completeSeen(pending: DeferredSeenState) {
        val key = SeenStateKey(pending.record.playerId, pending.record.annotationId)
        if (deferredSeen[key] == pending) deferredSeen.remove(key)
    }

    @Synchronized
    fun deferAnnotation(id: TraceShardId, annotation: TraceAnnotation): Boolean {
        val key = AnnotationKey(annotation.id)
        val current = deferredAnnotations[key]
        if (current != null) return current.shardId == id && current.annotation == annotation
        if (pending.size + deferredCaptures.size + deferredSeen.size + deferredAnnotations.size + deferredAnnotationEdits.size + deferredAnnotationRemovals.size + deferredBlockCleanups.size + deferredFootprintErosions.size + deferredSupportPrunes.size + deferredNeighborhoodWeakenings.size + reservations.size >= capacity) return false
        deferredAnnotations[key] = DeferredAnnotation(id, annotation)
        return true
    }

    @Synchronized
    fun annotationSnapshot(): List<DeferredAnnotation> = deferredAnnotations.values.toList()

    @Synchronized
    fun annotation(id: UUID): TraceAnnotation? = deferredAnnotations[AnnotationKey(id)]?.annotation

    @Synchronized
    fun replaceAnnotation(id: UUID, annotation: TraceAnnotation): Boolean {
        val key = AnnotationKey(id)
        val current = deferredAnnotations[key] ?: return false
        require(annotation.id == id && annotation.position == current.annotation.position) {
            "queued annotation identity and position are immutable"
        }
        deferredAnnotations[key] = current.copy(annotation = annotation)
        return true
    }

    @Synchronized
    fun cancelAnnotation(id: UUID): DeferredAnnotation? = deferredAnnotations.remove(AnnotationKey(id))

    @Synchronized
    fun deferAnnotationEdit(id: TraceShardId, annotation: TraceAnnotation): Boolean {
        val key = AnnotationKey(annotation.id)
        val current = deferredAnnotationEdits[key]
        if (current != null) {
            require(current.shardId == id) { "queued annotation edit moved shards" }
            require(annotation.position == current.annotation.position && annotation.targetBlock == current.annotation.targetBlock
                && annotation.createdByInternal == current.annotation.createdByInternal && annotation.team == current.annotation.team) {
                "queued annotation edit cannot change identity, location, owner, or team"
            }
            require(annotation.revision > current.annotation.revision) { "queued annotation revision must increase" }
            deferredAnnotationEdits[key] = DeferredAnnotationEdit(id, annotation)
            return true
        }
        if (pending.size + deferredCaptures.size + deferredSeen.size + deferredAnnotations.size + deferredAnnotationEdits.size
            + deferredAnnotationRemovals.size + deferredBlockCleanups.size + deferredFootprintErosions.size
            + deferredSupportPrunes.size + deferredNeighborhoodWeakenings.size + reservations.size >= capacity) return false
        deferredAnnotationEdits[key] = DeferredAnnotationEdit(id, annotation)
        return true
    }

    @Synchronized
    fun annotationEdit(id: UUID): TraceAnnotation? = deferredAnnotationEdits[AnnotationKey(id)]?.annotation

    @Synchronized
    fun annotationEditEntry(id: UUID): DeferredAnnotationEdit? = deferredAnnotationEdits[AnnotationKey(id)]

    @Synchronized
    fun annotationEditSnapshot(): List<DeferredAnnotationEdit> = deferredAnnotationEdits.values.toList()

    @Synchronized
    fun cancelAnnotationEdit(id: UUID): DeferredAnnotationEdit? = deferredAnnotationEdits.remove(AnnotationKey(id))

    @Synchronized
    fun completeAnnotationEdit(pending: DeferredAnnotationEdit) {
        val key = AnnotationKey(pending.annotation.id)
        if (deferredAnnotationEdits[key] == pending) deferredAnnotationEdits.remove(key)
    }

    @Synchronized
    fun completeAnnotation(pending: DeferredAnnotation) {
        val key = AnnotationKey(pending.annotation.id)
        if (deferredAnnotations[key] == pending) deferredAnnotations.remove(key)
    }

    @Synchronized
    fun deferAnnotationRemoval(id: TraceShardId, annotationId: UUID): Boolean {
        val key = AnnotationKey(annotationId)
        deferredAnnotationRemovals[key]?.let { return it.shardId == id }
        if (deferredAnnotations.containsKey(key)) return false
        val queuedEdit = deferredAnnotationEdits[key]
        if (queuedEdit != null && queuedEdit.shardId != id) return false
        val replacesEdit = queuedEdit != null
        if (pending.size + deferredCaptures.size + deferredSeen.size + deferredAnnotations.size + deferredAnnotationEdits.size
            + deferredAnnotationRemovals.size + deferredBlockCleanups.size + deferredFootprintErosions.size
            + deferredSupportPrunes.size + deferredNeighborhoodWeakenings.size + reservations.size
            - (if (replacesEdit) 1 else 0) >= capacity) return false
        if (replacesEdit) deferredAnnotationEdits.remove(key)
        deferredAnnotationRemovals[key] = DeferredAnnotationRemoval(id, annotationId)
        return true
    }

    @Synchronized
    fun annotationRemoval(id: UUID): DeferredAnnotationRemoval? = deferredAnnotationRemovals[AnnotationKey(id)]

    @Synchronized
    fun annotationRemovalSnapshot(): List<DeferredAnnotationRemoval> = deferredAnnotationRemovals.values.toList()

    @Synchronized
    fun completeAnnotationRemoval(pending: DeferredAnnotationRemoval) {
        val key = AnnotationKey(pending.annotationId)
        if (deferredAnnotationRemovals[key] == pending) deferredAnnotationRemovals.remove(key)
    }

    @Synchronized
    fun deferBlockCleanup(cleanup: DeferredBlockCleanup): Boolean {
        return deferBlockCleanups(listOf(cleanup))
    }

    @Synchronized
    fun deferBlockCleanups(cleanups: List<DeferredBlockCleanup>): Boolean {
        val missing = cleanups.distinct().filterNot(deferredBlockCleanups::containsKey)
        if (pending.size + deferredCaptures.size + deferredSeen.size + deferredAnnotations.size + deferredAnnotationEdits.size + deferredAnnotationRemovals.size + deferredBlockCleanups.size + deferredFootprintErosions.size + deferredSupportPrunes.size + deferredNeighborhoodWeakenings.size + reservations.size + missing.size > capacity) return false
        missing.forEach { deferredBlockCleanups[it] = it }
        return true
    }

    @Synchronized
    fun blockCleanupSnapshot(): List<DeferredBlockCleanup> = deferredBlockCleanups.values.toList()

    @Synchronized
    fun blockCleanupCountForTile(id: TraceShardId, chunkX: Int, chunkZ: Int): Int =
        deferredBlockCleanups.values.count { it.shardId == id && blockCleanupAffectsTile(it, chunkX, chunkZ) }

    @Synchronized
    fun completeBlockCleanup(pending: DeferredBlockCleanup) {
        if (deferredBlockCleanups[pending] == pending) deferredBlockCleanups.remove(pending)
    }

    @Synchronized
    fun deferFootprintErosion(id: TraceShardId, traceId: UUID, position: BlockPos, factor: Double): Boolean {
        require(factor.isFinite() && factor >= 0.0) { "erosion factor must be finite and non-negative" }
        if (pending.size + deferredCaptures.size + deferredSeen.size + deferredAnnotations.size + deferredAnnotationEdits.size + deferredAnnotationRemovals.size + deferredBlockCleanups.size + deferredFootprintErosions.size + deferredSupportPrunes.size + deferredNeighborhoodWeakenings.size + reservations.size >= capacity) return false
        val pending = DeferredFootprintErosion(++nextErosionSerial, id, traceId, position.immutable(), factor)
        deferredFootprintErosions[pending.serial] = pending
        return true
    }

    @Synchronized
    fun footprintErosionSnapshot(): List<DeferredFootprintErosion> = deferredFootprintErosions.values.toList()

    @Synchronized
    fun completeFootprintErosion(pending: DeferredFootprintErosion) {
        if (deferredFootprintErosions[pending.serial] == pending) deferredFootprintErosions.remove(pending.serial)
    }

    @Synchronized
    fun deferSupportPrune(id: TraceShardId, chunkX: Int, chunkZ: Int): Boolean {
        val cleanup = DeferredSupportPrune(id, chunkX, chunkZ)
        if (cleanup in deferredSupportPrunes) return true
        if (pending.size + deferredCaptures.size + deferredSeen.size + deferredAnnotations.size + deferredAnnotationEdits.size + deferredAnnotationRemovals.size + deferredBlockCleanups.size + deferredFootprintErosions.size + deferredSupportPrunes.size + deferredNeighborhoodWeakenings.size + reservations.size >= capacity) return false
        deferredSupportPrunes[cleanup] = cleanup
        return true
    }

    @Synchronized
    fun supportPruneSnapshot(): List<DeferredSupportPrune> = deferredSupportPrunes.values.toList()

    @Synchronized
    fun completeSupportPrune(pending: DeferredSupportPrune) {
        if (deferredSupportPrunes[pending] == pending) deferredSupportPrunes.remove(pending)
    }

    @Synchronized
    fun deferNeighborhoodWeakening(
        centerShard: TraceShardId,
        position: BlockPos,
        radius: Int,
        factor: Double,
    ): Boolean {
        require(factor.isFinite() && factor >= 0.0) { "erosion factor must be finite and non-negative" }
        if (pending.size + deferredCaptures.size + deferredSeen.size + deferredAnnotations.size + deferredAnnotationEdits.size + deferredAnnotationRemovals.size + deferredBlockCleanups.size + deferredFootprintErosions.size + deferredSupportPrunes.size + deferredNeighborhoodWeakenings.size + reservations.size >= capacity) return false
        val operation = DeferredNeighborhoodWeakening(
            ++nextNeighborhoodSerial, centerShard, position.immutable(), radius.coerceAtLeast(0), factor,
        )
        deferredNeighborhoodWeakenings[operation.serial] = operation
        return true
    }

    @Synchronized
    fun neighborhoodWeakeningSnapshot(): List<DeferredNeighborhoodWeakening> = deferredNeighborhoodWeakenings.values.toList()

    @Synchronized
    fun completeNeighborhoodWeakening(pending: DeferredNeighborhoodWeakening) {
        if (deferredNeighborhoodWeakenings[pending.serial] == pending) deferredNeighborhoodWeakenings.remove(pending.serial)
    }

    private val reservations = mutableSetOf<UUID>()

    @Synchronized
    fun reserveCapture(trace: FootTrace): CaptureAdmission {
        deferredCaptures[trace.id]?.let { return if (it == trace) CaptureAdmission.ALREADY_QUEUED else CaptureAdmission.REJECTED }
        if (trace.id in reservations) return CaptureAdmission.ALREADY_QUEUED
        if (pending.size + deferredCaptures.size + deferredSeen.size + deferredAnnotations.size + deferredAnnotationEdits.size + deferredAnnotationRemovals.size + deferredBlockCleanups.size + deferredFootprintErosions.size + deferredSupportPrunes.size + deferredNeighborhoodWeakenings.size + reservations.size >= capacity) {
            return CaptureAdmission.REJECTED
        }
        reservations += trace.id
        return CaptureAdmission.RESERVED
    }

    @Synchronized
    fun releaseCapture(id: UUID) { reservations.remove(id) }

    @Synchronized
    fun captureSnapshot(): List<Pair<UUID, FootTrace>> = deferredCaptures.entries.map { it.key to it.value }

    @Synchronized
    fun completeCapture(id: UUID) { deferredCaptures.remove(id) }

    @Synchronized
    fun reclaim(id: TraceShardId): TraceShardState? = pending.remove(id)?.snapshot?.snapshot()?.first

    @Synchronized
    fun complete(id: TraceShardId, snapshot: TraceShardState) {
        if (pending[id]?.snapshot === snapshot) pending.remove(id)
    }

    @Synchronized
    fun snapshot(): List<Pair<TraceShardId, TraceShardState>> = pending.map { it.key to it.value.snapshot }

    @Synchronized
    fun recordRejected(id: TraceShardId, kind: StorageOutageFrontier.Kind) {
        when (kind) {
            StorageOutageFrontier.Kind.CAPTURE -> rejectedCaptures++
            StorageOutageFrontier.Kind.ANNOTATION -> rejectedAnnotations++
            else -> Unit
        }
        deferredByKind[kind] = (deferredByKind[kind] ?: 0L) + 1L
        frontierShards += id
        while (frontierShards.size > capacity) frontierShards.remove(frontierShards.first())
    }

    @Synchronized
    fun recordDeferred(id: TraceShardId, kind: StorageOutageFrontier.Kind) {
        deferredByKind[kind] = (deferredByKind[kind] ?: 0L) + 1L
        frontierShards += id
        while (frontierShards.size > capacity) frontierShards.remove(frontierShards.first())
    }

    @Synchronized
    fun frontierSnapshot(): StorageOutageFrontier = StorageOutageFrontier(
        rejectedCaptures, rejectedAnnotations, frontierShards.toSet(), deferredByKind.toMap(),
    )
}
