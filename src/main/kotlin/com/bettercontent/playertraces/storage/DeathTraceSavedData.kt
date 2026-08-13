package com.bettercontent.playertraces.storage

import com.bettercontent.playertraces.domain.BloodPoolRecord
import com.bettercontent.playertraces.domain.DeathEchoRecord
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.saveddata.SavedData
import java.util.UUID

class DeathTraceSavedData : SavedData() {
    private val pools = mutableListOf<BloodPoolRecord>()
    private val echoes = mutableListOf<DeathEchoRecord>()

    fun addPool(record: BloodPoolRecord, maxTotal: Int) {
        pools.removeAll { it.id == record.id }
        pools += record
        trimOldest(pools, maxTotal)
        setDirty()
    }

    fun addEcho(record: DeathEchoRecord, maxTotal: Int, maxPerPlayer: Int) {
        echoes.removeAll { it.id == record.id || it.bloodPoolId == record.bloodPoolId }
        echoes += record
        val owned = echoes.filter { it.ownerId == record.ownerId }.sortedByDescending { it.createdAt }
        if (owned.size > maxPerPlayer) {
            val removeIds = owned.drop(maxPerPlayer).mapTo(HashSet()) { it.id }
            echoes.removeAll { it.id in removeIds }
        }
        trimOldest(echoes, maxTotal)
        setDirty()
    }

    fun poolsWithin(minX: Double, maxX: Double, minZ: Double, maxZ: Double): List<BloodPoolRecord> =
        pools.filter { it.x in minX..maxX && it.z in minZ..maxZ }

    fun echoesWithin(minX: Double, maxX: Double, minZ: Double, maxZ: Double): List<DeathEchoRecord> =
        echoes.filter { it.x in minX..maxX && it.z in minZ..maxZ }

    fun poolCount(): Int = pools.size
    fun echoCount(): Int = echoes.size

    override fun save(tag: CompoundTag): CompoundTag {
        tag.putInt("schema", SCHEMA)
        tag.put("pools", ListTag().also { list -> pools.forEach { list.add(savePool(it)) } })
        tag.put("echoes", ListTag().also { list -> echoes.forEach { list.add(saveEcho(it)) } })
        return tag
    }

    companion object {
        private const val KEY = "player_traces_death_traces"
        private const val SCHEMA = 1

        fun get(level: ServerLevel): DeathTraceSavedData =
            level.dataStorage.computeIfAbsent(::load, ::DeathTraceSavedData, KEY)

        fun load(tag: CompoundTag): DeathTraceSavedData = DeathTraceSavedData().also { data ->
            require(tag.getInt("schema") == SCHEMA) { "Unsupported Traces death-store schema ${tag.getInt("schema")}; expected $SCHEMA" }
            if (tag.contains("pools", Tag.TAG_LIST.toInt())) {
                tag.getList("pools", Tag.TAG_COMPOUND.toInt()).forEach { raw ->
                    runCatching { loadPool(raw as CompoundTag) }.getOrNull()?.let(data.pools::add)
                }
            }
            if (tag.contains("echoes", Tag.TAG_LIST.toInt())) {
                tag.getList("echoes", Tag.TAG_COMPOUND.toInt()).forEach { raw ->
                    runCatching { loadEcho(raw as CompoundTag) }.getOrNull()?.let(data.echoes::add)
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

        private fun <T> trimOldest(records: MutableList<T>, maximum: Int) {
            while (records.size > maximum) records.removeAt(0)
        }
    }
}
