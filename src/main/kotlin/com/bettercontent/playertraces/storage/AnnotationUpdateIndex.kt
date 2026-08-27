package com.bettercontent.playertraces.storage

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.saveddata.SavedData
import java.util.UUID

/** Auxiliary timestamps for annotations. The shard format deliberately remains unchanged. */
class AnnotationUpdateIndex : SavedData() {
    private val updated = mutableMapOf<UUID, Long>()

    fun touch(id: UUID, gameTime: Long) { updated[id] = gameTime.coerceAtLeast(0); setDirty() }
    fun remove(id: UUID) { if (updated.remove(id) != null) setDirty() }
    fun updatedAt(id: UUID): Long? = updated[id]

    override fun save(tag: CompoundTag): CompoundTag = tag.also { root ->
        root.putInt("schema", 1)
        root.put("entries", ListTag().also { list -> updated.forEach { (id, time) ->
            list.add(CompoundTag().also { it.putUUID("id", id); it.putLong("time", time) })
        } })
    }

    companion object {
        private const val KEY = "player_traces_annotation_updates"
        fun get(level: ServerLevel): AnnotationUpdateIndex = level.dataStorage.computeIfAbsent(::load, ::AnnotationUpdateIndex, KEY)
        fun load(tag: CompoundTag): AnnotationUpdateIndex = AnnotationUpdateIndex().also { index ->
            if (tag.getInt("schema") == 1) tag.getList("entries", Tag.TAG_COMPOUND.toInt()).forEach { raw ->
                val entry = raw as CompoundTag
                if (entry.hasUUID("id") && entry.getLong("time") >= 0) index.updated[entry.getUUID("id")] = entry.getLong("time")
            }
        }
    }
}
