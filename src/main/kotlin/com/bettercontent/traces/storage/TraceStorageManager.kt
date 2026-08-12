package com.bettercontent.traces.storage

import com.bettercontent.traces.config.TracesConfig
import com.bettercontent.traces.domain.FootTrace
import com.bettercontent.traces.domain.TraceAnnotation
import com.bettercontent.traces.util.Geometry
import com.bettercontent.traces.util.TraceShardId
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

class TraceStorageManager(
    private val level: ServerLevel,
    private val config: TracesConfig.Common,
) {
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

    private val baseRoot: Path = resolveRoot(level)
    private val tracesRoot: Path = baseRoot.resolve("data").resolve("traces")

    init {
        ensureSchema()
    }

    private fun ensureSchema() {
        val schemaFile = tracesRoot.resolve("schema")
        try {
            Files.createDirectories(schemaFile.parent)
            if (Files.exists(schemaFile)) {
                val existing = Files.readString(schemaFile, java.nio.charset.StandardCharsets.UTF_8)
                if (existing != "traces-v1\n") {
                    log.warn("Unexpected Traces schema marker in {}: {}", schemaFile, existing.trim())
                }
            } else {
                Files.writeString(schemaFile, "traces-v1\n", java.nio.charset.StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE_NEW)
            }
        } catch (error: Exception) {
            log.warn("Unable to initialize Traces schema marker at {}", schemaFile, error)
        }
    }

    private fun resolveRoot(level: ServerLevel): Path {
        val server = level.server
        val dir = runCatching { server.javaClass.getMethod("getServerDirectory").invoke(server) as File }
            .getOrNull()
            ?: File(".")
        return dir.toPath()
    }

    private fun worldDimensionPath(): String = level.dimension().location().toString().substringAfterLast(":")

    private fun shardPath(id: TraceShardId): Path =
        tracesRoot.resolve(worldDimensionPath()).resolve("r.${id.regionX}.${id.regionZ}.traces")

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
        if (evicted != null && evicted.second.dirty) {
            val (snapshot, generation) = evicted.second.snapshot()
            writeSnapshot(evicted.first, snapshot)
            evicted.second.clearDirtyIfUnchanged(generation)
        }
        return loaded
    }

    private fun allShardIds(): Sequence<TraceShardId> {
        val dimPath = tracesRoot.resolve(worldDimensionPath())
        if (!Files.isDirectory(dimPath)) return emptySequence()
        val ids = mutableListOf<TraceShardId>()
        return runCatching {
            Files.list(dimPath).use { paths ->
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
        for (id in annotationIndex.keys) {
            annotationIndex[id]?.let { candidate ->
                if (id == annotationId) return candidate
            }
        }
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
                out += state.nearbyFootTraces(boundsMin, boundsMax)
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
        var best = 0
        for (shard in cache.valuesSnapshot()) {
            shard.seenStatesSnapshot()
                .firstOrNull { it.annotationId == annotation && it.playerId == player }
                ?.let { if (it.highestRevision > best) best = it.highestRevision }
        }
        for (id in allShardIds()) {
            val state = loadShard(id)
            state.seenStatesSnapshot()
                .firstOrNull { it.annotationId == annotation && it.playerId == player }
                ?.let { if (it.highestRevision > best) best = it.highestRevision }
        }
        return best
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
        } catch (_: Exception) {
            log.warn("Failed to flush trace shard {}", id)
        }
    }

    private fun writeSnapshot(id: TraceShardId, snapshot: TraceShardState) {
        val path = shardPath(id)
        TraceSerializer.write(path, snapshot, Geometry.shardToBounds(id.regionX, id.regionZ))
        log.debug("Flushed trace shard {} to {}", id, path)
    }

    fun tickFlush() {
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
        tickFlush()
        dirtyExecutor.shutdown()
        dirtyExecutor.awaitTermination(5, TimeUnit.SECONDS)
        waitForPendingFlushes(2000)
    }
}

internal data class SeenStateRecord(
    val annotationId: UUID,
    val playerId: UUID,
    val highestRevision: Int,
)
