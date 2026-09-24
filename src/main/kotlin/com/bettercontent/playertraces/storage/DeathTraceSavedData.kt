package com.bettercontent.playertraces.storage

import com.bettercontent.playertraces.domain.BloodPoolRecord
import com.bettercontent.playertraces.domain.DeathEchoRecord
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.saveddata.SavedData
import kotlin.math.floor
import java.util.TreeMap
import java.util.UUID

class DeathTraceSavedData : SavedData() {
    private val pools = mutableListOf<BloodPoolRecord>()
    private val echoes = mutableListOf<DeathEchoRecord>()
    private val poolsByCreatedAt = TreeMap<Long, MutableList<BloodPoolRecord>>()
    private val echoesByCreatedAt = TreeMap<Long, MutableList<DeathEchoRecord>>()
    private val poolsByChunk = mutableMapOf<Long, MutableSet<BloodPoolRecord>>()
    private val echoesByChunk = mutableMapOf<Long, MutableSet<DeathEchoRecord>>()

    fun addPool(record: BloodPoolRecord, maxTotal: Int) {
        pools.filter { it.id == record.id }.forEach(::removePool)
        pools += record
        indexPool(record)
        indexPoolChunk(record)
        trimOldest(pools, maxTotal, ::removePool)
        setDirty()
    }

    fun addEcho(record: DeathEchoRecord, maxTotal: Int, maxPerPlayer: Int) {
        echoes.filter { it.id == record.id || it.bloodPoolId == record.bloodPoolId }.forEach(::removeEcho)
        echoes += record
        indexEcho(record)
        indexEchoChunk(record)
        val owned = echoes.filter { it.ownerId == record.ownerId }.sortedByDescending { it.createdAt }
        if (owned.size > maxPerPlayer) {
            val removeIds = owned.drop(maxPerPlayer).mapTo(HashSet()) { it.id }
            echoes.filter { it.id in removeIds }.forEach(::removeEcho)
        }
        trimOldest(echoes, maxTotal, ::removeEcho)
        setDirty()
    }

    fun poolsWithin(minX: Double, maxX: Double, minZ: Double, maxZ: Double): List<BloodPoolRecord> =
        within(pools, poolsByChunk, minX, maxX, minZ, maxZ) { it.x to it.z }

    fun echoesWithin(minX: Double, maxX: Double, minZ: Double, maxZ: Double): List<DeathEchoRecord> =
        within(echoes, echoesByChunk, minX, maxX, minZ, maxZ) { it.x to it.z }

    /** Strict temporal candidates for bounded return summaries; callers apply spatial/owner filters. */
    fun poolsAfter(createdAfter: Long): List<BloodPoolRecord> = after(poolsByCreatedAt, createdAfter)

    /** Strict temporal candidates for bounded return summaries; callers apply spatial/owner filters. */
    fun echoesAfter(createdAfter: Long): List<DeathEchoRecord> = after(echoesByCreatedAt, createdAfter)

    fun poolCount(): Int = pools.size
    fun echoCount(): Int = echoes.size

    private fun <T> after(index: TreeMap<Long, MutableList<T>>, createdAfter: Long): List<T> = buildList {
        index.tailMap(createdAfter, false).values.forEach(::addAll)
    }

    private fun indexPool(record: BloodPoolRecord) {
        poolsByCreatedAt.getOrPut(record.createdAt) { mutableListOf() }.add(record)
    }

    private fun indexEcho(record: DeathEchoRecord) {
        echoesByCreatedAt.getOrPut(record.createdAt) { mutableListOf() }.add(record)
    }

    private fun removePool(record: BloodPoolRecord) {
        pools.remove(record)
        removeChunk(poolsByChunk, record.x, record.z, record)
        poolsByCreatedAt[record.createdAt]?.let { bucket ->
            bucket.remove(record)
            if (bucket.isEmpty()) poolsByCreatedAt.remove(record.createdAt)
        }
    }

    private fun removeEcho(record: DeathEchoRecord) {
        echoes.remove(record)
        removeChunk(echoesByChunk, record.x, record.z, record)
        echoesByCreatedAt[record.createdAt]?.let { bucket ->
            bucket.remove(record)
            if (bucket.isEmpty()) echoesByCreatedAt.remove(record.createdAt)
        }
    }

    private fun <T> trimOldest(records: MutableList<T>, maximum: Int, remove: (T) -> Unit) {
        while (records.size > maximum) remove(records.first())
    }

    private fun indexPoolChunk(record: BloodPoolRecord) = addChunk(poolsByChunk, record.x, record.z, record)
    private fun indexEchoChunk(record: DeathEchoRecord) = addChunk(echoesByChunk, record.x, record.z, record)

    private fun <T> addChunk(index: MutableMap<Long, MutableSet<T>>, x: Double, z: Double, record: T) {
        index.getOrPut(chunkKey(chunk(x), chunk(z))) { linkedSetOf() }.add(record)
    }

    private fun <T> removeChunk(index: MutableMap<Long, MutableSet<T>>, x: Double, z: Double, record: T) {
        val key = chunkKey(chunk(x), chunk(z))
        index[key]?.let { bucket ->
            bucket.remove(record)
            if (bucket.isEmpty()) index.remove(key)
        }
    }

    /** Uses chunk buckets for local queries and a linear fallback for world-sized ranges. */
    private fun <T> within(
        records: List<T>,
        index: Map<Long, Set<T>>,
        minX: Double,
        maxX: Double,
        minZ: Double,
        maxZ: Double,
        position: (T) -> Pair<Double, Double>,
    ): List<T> {
        if (records.isEmpty() || minX.isNaN() || maxX.isNaN() || minZ.isNaN() || maxZ.isNaN() ||
            minX > maxX || minZ > maxZ
        ) return emptyList()

        val minChunkX = chunk(minX)
        val maxChunkX = chunk(maxX)
        val minChunkZ = chunk(minZ)
        val maxChunkZ = chunk(maxZ)
        val spanX = maxChunkX.toLong() - minChunkX.toLong() + 1
        val spanZ = maxChunkZ.toLong() - minChunkZ.toLong() + 1
        val scanLimit = records.size.toLong().coerceAtLeast(1L) * MAX_CHUNKS_PER_RECORD
        val candidates = if (spanX > scanLimit || spanZ > scanLimit || spanX > scanLimit / spanZ) {
            records
        } else {
            buildList {
                for (chunkX in minChunkX..maxChunkX) {
                    for (chunkZ in minChunkZ..maxChunkZ) {
                        index[chunkKey(chunkX, chunkZ)]?.let(::addAll)
                    }
                }
            }
        }
        val boundsX = minX..maxX
        val boundsZ = minZ..maxZ
        return candidates.distinct().filter { record ->
            val (x, z) = position(record)
            x in boundsX && z in boundsZ
        }
    }

    private fun chunk(coordinate: Double): Int = floor(coordinate / CHUNK_SIZE).toInt()

    private fun chunkKey(x: Int, z: Int): Long = (x.toLong() shl 32) xor (z.toLong() and 0xffffffffL)

    override fun save(tag: CompoundTag): CompoundTag {
        tag.putInt("schema", SCHEMA)
        tag.put("pools", ListTag().also { list -> pools.forEach { list.add(savePool(it)) } })
        tag.put("echoes", ListTag().also { list -> echoes.forEach { list.add(saveEcho(it)) } })
        return tag
    }

    companion object {
        private const val KEY = "player_traces_death_traces"
        private const val SCHEMA = 1
        private const val CHUNK_SIZE = 16.0
        private const val MAX_CHUNKS_PER_RECORD = 4L

        fun get(level: ServerLevel): DeathTraceSavedData =
            level.dataStorage.computeIfAbsent(::load, ::DeathTraceSavedData, KEY)

        fun load(tag: CompoundTag): DeathTraceSavedData = DeathTraceSavedData().also { data ->
            require(tag.getInt("schema") == SCHEMA) { "Unsupported Traces death-store schema ${tag.getInt("schema")}; expected $SCHEMA" }
            if (tag.contains("pools", Tag.TAG_LIST.toInt())) {
                tag.getList("pools", Tag.TAG_COMPOUND.toInt()).forEach { raw ->
                    runCatching { loadPool(raw as CompoundTag) }.getOrNull()?.let { record ->
                        data.pools += record
                        data.indexPool(record)
                        data.indexPoolChunk(record)
                    }
                }
            }
            if (tag.contains("echoes", Tag.TAG_LIST.toInt())) {
                tag.getList("echoes", Tag.TAG_COMPOUND.toInt()).forEach { raw ->
                    runCatching { loadEcho(raw as CompoundTag) }.getOrNull()?.let { record ->
                        data.echoes += record
                        data.indexEcho(record)
                        data.indexEchoChunk(record)
                    }
                }
            }
        }

        private fun savePool(record: BloodPoolRecord): CompoundTag = CompoundTag().also {
            it.putUUID("id", record.id); it.putUUID("owner", record.ownerId); it.putString("name", record.ownerName)
            it.putDouble("x", record.x); it.putDouble("y", record.y); it.putDouble("z", record.z)
            it.putLong("created", record.createdAt); it.putString("cause", record.cause)
        }

        private fun loadPool(tag: CompoundTag): BloodPoolRecord = BloodPoolRecord(
            tag.getUUID("id"), tag.getUUID("owner"), tag.getString("name"),
            tag.getDouble("x"), tag.getDouble("y"), tag.getDouble("z"), tag.getLong("created"), tag.getString("cause"),
        )

        private fun saveEcho(record: DeathEchoRecord): CompoundTag = CompoundTag().also {
            it.putUUID("id", record.id); it.putUUID("pool", record.bloodPoolId); it.putUUID("owner", record.ownerId)
            it.putString("name", record.ownerName); it.putDouble("x", record.x); it.putDouble("y", record.y); it.putDouble("z", record.z)
            it.putLong("created", record.createdAt); it.putByteArray("clip", record.encodedClip)
        }

        private fun loadEcho(tag: CompoundTag): DeathEchoRecord = DeathEchoRecord(
            tag.getUUID("id"), tag.getUUID("pool"), tag.getUUID("owner"), tag.getString("name"),
            tag.getDouble("x"), tag.getDouble("y"), tag.getDouble("z"), tag.getLong("created"), tag.getByteArray("clip"),
        )

    }
}
