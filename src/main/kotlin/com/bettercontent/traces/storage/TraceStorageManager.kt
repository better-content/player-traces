package com.bettercontent.traces.storage

import com.bettercontent.traces.config.TracesConfig
import com.bettercontent.traces.domain.FootTrace
import com.bettercontent.traces.domain.TraceAnnotation
import com.bettercontent.traces.util.Geometry
import com.bettercontent.traces.util.TraceShardId
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
    private val annotationIndex = java.util.concurrent.ConcurrentHashMap<UUID, TraceShardId>()
    private val pendingFlushes = mutableListOf<Future<*>>()
    private val queuedFlushes = java.util.concurrent.ConcurrentHashMap.newKeySet<TraceShardId>()
    private val evictedPending = java.util.concurrent.ConcurrentHashMap<TraceShardId, TraceShardState>()
    @Volatile private var closed = false

    private val worldRoot: Path = level.server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize()
    private val tracesRoot: Path = worldRoot.resolve("data").resolve("traces")
    private val dimensionId = level.dimension().location()
    private val levelKey = dimensionId.toString()
    private val dimensionRoot = dimensionRoot(worldRoot, dimensionId)

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
        val path = shardPath(id)
        val loaded = if (Files.exists(path)) {
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
        val evicted = cache.put(id, loaded)
        indexShard(id, loaded)
        if (evicted != null && evicted.second.dirty) {
            val (snapshot, _) = evicted.second.snapshot()
            queueEvicted(evicted.first, snapshot)
        }
        return loaded
    }

    private fun indexShard(id: TraceShardId, state: TraceShardState) {
        state.annotationsSnapshot().forEach { annotationIndex[it.id] = id }
    }

    private fun queueEvicted(id: TraceShardId, snapshot: TraceShardState) {
        evictedPending[id] = snapshot
        pendingFlushes += dirtyExecutor.submit {
            try {
                writeSnapshot(id, snapshot)
                evictedPending.remove(id, snapshot)
            } catch (error: Exception) {
                log.warn("Failed to flush evicted trace shard {}", id, error)
            }
        }
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

    private fun scanForAnnotation(annotationId: UUID): TraceShardId? {
        annotationIndex[annotationId]?.let { return it }
        for (id in allShardIds()) {
            val state = loadShard(id)
            if (state.annotationById(annotationId) != null) {
                annotationIndex[annotationId] = id
                return id
            }
        }
        return null
    }

    private fun locateSeenStateShard(annotation: UUID): TraceShardId? {
        annotationIndex[annotation]?.let { return it }
        return scanForAnnotation(annotation)
    }

    fun queryTraces(boundsMin: BlockPos, boundsMax: BlockPos): List<FootTrace> {
        val (minSX, minSZ) = Geometry.worldToShard(boundsMin)
        val (maxSX, maxSZ) = Geometry.worldToShard(boundsMax)
        val out = mutableListOf<FootTrace>()
        for (sx in minSX..maxSX) {
            for (sz in minSZ..maxSZ) {
                val state = loadShard(TraceShardId(worldDimensionPath(), sx, sz))
                out += state.nearbyFootTraces(boundsMin, boundsMax).filter { it.levelKey == levelKey }
            }
        }
        return out
    }

    fun queryAnnotations(boundsMin: BlockPos, boundsMax: BlockPos): List<TraceAnnotation> {
        val (minSX, minSZ) = Geometry.worldToShard(boundsMin)
        val (maxSX, maxSZ) = Geometry.worldToShard(boundsMax)
        val out = mutableListOf<TraceAnnotation>()
        for (sx in minSX..maxSX) {
            for (sz in minSZ..maxSZ) {
                val state = loadShard(TraceShardId(worldDimensionPath(), sx, sz))
                out += state.nearbyAnnotations(boundsMin, boundsMax)
            }
        }
        return out
    }

    fun addFootTrace(trace: FootTrace) {
        check(!closed) { "Traces storage is closed" }
        require(trace.levelKey == levelKey) { "trace belongs to ${trace.levelKey}, expected $levelKey" }
        val (sx, sz) = Geometry.worldToShard(trace.blockPos)
        val id = TraceShardId(worldDimensionPath(), sx, sz)
        val shard = loadShard(id)
        synchronized(shard) {
            shard.footTraces += trace
            shard.markDirty()
        }
        markDirty(id)
    }

    fun addAnnotation(annotation: TraceAnnotation) {
        check(!closed) { "Traces storage is closed" }
        val (sx, sz) = Geometry.worldToShard(annotation.position)
        val id = TraceShardId(worldDimensionPath(), sx, sz)
        val shard = loadShard(id)
        shard.putAnnotation(annotation)
        annotationIndex[annotation.id] = id
        shard.markDirty()
        markDirty(id)
    }

    fun removeAnnotation(annotationId: UUID): Boolean {
        val id = scanForAnnotation(annotationId) ?: return false
        val state = loadShard(id)
        val removed = state.removeAnnotation(annotationId)
        if (removed) {
            annotationIndex.remove(annotationId)
            state.markDirty()
            markDirty(id)
            return true
        }
        return false
    }

    fun annotationById(annotationId: UUID): TraceAnnotation? {
        val id = scanForAnnotation(annotationId) ?: return null
        return loadShard(id).annotationById(annotationId)
    }

    fun updateAnnotation(id: UUID, text: String?, icon: String?, color: Int?): TraceAnnotation? {
        val shardId = scanForAnnotation(id) ?: return null
        val shard = loadShard(shardId)
        val updated = shard.updateAnnotation(id, text, icon, color) ?: return null
        shard.markDirty()
        markDirty(shardId)
        annotationIndex[id] = shardId
        return updated
    }

    fun setSeen(player: UUID, annotation: UUID, revision: Int) {
        if (closed || revision < 0) return
        val shardId = locateSeenStateShard(annotation) ?: return
        val shard = loadShard(shardId)
        synchronized(shard) {
            val index = shard.seenStates.indexOfFirst { it.playerId == player && it.annotationId == annotation }
            if (index >= 0) {
                val prior = shard.seenStates[index]
                if (revision > prior.highestRevision) {
                    shard.seenStates[index] = SeenStateRecord(annotation, player, revision)
                    shard.markDirty()
                    markDirty(shardId)
                }
            } else {
                shard.seenStates += SeenStateRecord(annotation, player, revision)
                shard.markDirty()
                markDirty(shardId)
            }
        }
    }

    fun getSeen(player: UUID, annotation: UUID): Int {
        val shardId = annotationIndex[annotation] ?: return 0
        return loadShard(shardId).seenStatesSnapshot()
            .firstOrNull { it.annotationId == annotation && it.playerId == player }
            ?.highestRevision ?: 0
    }

    fun removeByPosition(pos: BlockPos) {
        val (sx, sz) = Geometry.worldToShard(pos)
        for (dx in -1..1) {
            for (dz in -1..1) {
                val id = TraceShardId(worldDimensionPath(), sx + dx, sz + dz)
                val state = loadShard(id)
                state.removeAtPosition(pos)
                if (state.dirty) markDirty(id)
            }
        }
    }

    fun removeFootTraces(boundsMin: BlockPos, boundsMax: BlockPos): Int {
        val (minSX, minSZ) = Geometry.worldToShard(boundsMin)
        val (maxSX, maxSZ) = Geometry.worldToShard(boundsMax)
        var removed = 0
        for (sx in minSX..maxSX) {
            for (sz in minSZ..maxSZ) {
                val id = TraceShardId(worldDimensionPath(), sx, sz)
                val state = loadShard(id)
                val shardRemoved = state.removeFootTraces(boundsMin, boundsMax)
                if (shardRemoved > 0) {
                    removed += shardRemoved
                    markDirty(id)
                }
            }
        }
        return removed
    }

    fun weakenAround(pos: BlockPos, radius: Int, factor: Double) {
        val (sx, sz) = Geometry.worldToShard(pos)
        val shardRadius = (radius.coerceAtLeast(0) / 256).coerceAtLeast(1)
        for (dx in -shardRadius..shardRadius) {
            for (dz in -shardRadius..shardRadius) {
                val id = TraceShardId(worldDimensionPath(), sx + dx, sz + dz)
                val state = loadShard(id)
                state.updateWeakness(pos, factor)
                if (state.dirty) markDirty(id)
            }
        }
    }

    fun allStorageShards(): Sequence<TraceShardState> = cache.valuesSnapshot().asSequence()

    fun shardIdsWithLivingTraces(radiusLimit: Int = 64): List<TraceShardId> {
        val chunkRadius = (radiusLimit / 256).coerceAtLeast(1)
        val origin = Geometry.worldToShard(level.sharedSpawnPos)
        val ids = mutableListOf<TraceShardId>()
        for (sx in origin.first - chunkRadius..origin.first + chunkRadius) {
            for (sz in origin.second - chunkRadius..origin.second + chunkRadius) {
                val id = TraceShardId(worldDimensionPath(), sx, sz)
                val state = loadShard(id)
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
            val state = loadShard(id)
            for (trace in state.footTracesSnapshot()) {
                if (trace.levelKey == levelKey && seenIds.add(trace.id)) {
                    out += trace
                }
            }
        }
        return out
    }

    fun allAnnotations(): List<TraceAnnotation> {
        val seen = HashSet<UUID>()
        val out = mutableListOf<TraceAnnotation>()
        allStorageShards().forEach { state -> state.annotationsSnapshot().filterTo(out) { seen.add(it.id) } }
        allShardIds().forEach { id -> loadShard(id).annotationsSnapshot().filterTo(out) { seen.add(it.id) } }
        return out
    }

    fun allLivingFootTraces(): List<FootTrace> {
        return allStorageShards().flatMap { state ->
            state.footTracesSnapshot().filter { it.surviving }
        }.toList()
    }

    private fun markDirty(id: TraceShardId) {
        val shard = cache.get(id) ?: return
        shard.markDirty()
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
        TraceSerializer.write(path, snapshot, Geometry.shardToBounds(id.regionX, id.regionZ))
        log.debug("Flushed trace shard {} to {}", id, path)
    }

    fun tickFlush() {
        if (closed) return
        pruneCompletedFlushes()
        if (pendingFlushes.size >= config.saveQueueMax.get()) return
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
        for ((id, snapshot) in evictedPending.entries.toList()) {
            try {
                writeSnapshot(id, snapshot)
                evictedPending.remove(id, snapshot)
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
