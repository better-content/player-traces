package com.bettercontent.playertraces.storage

import com.bettercontent.playertraces.domain.AnnotationEchoRecord
import com.bettercontent.playertraces.logic.AnnotationEchoValidation
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.saveddata.SavedData
import java.util.UUID

class AnnotationEchoSavedData : SavedData() {
    private val echoes = linkedMapOf<UUID, AnnotationEchoRecord>()

    @Synchronized fun get(annotationId: UUID): AnnotationEchoRecord? = echoes[annotationId]
    @Synchronized fun all(): List<AnnotationEchoRecord> = echoes.values.toList()
    @Synchronized fun count(): Int = echoes.size

    @Synchronized
    fun requireCapacity(annotationId: UUID, ownerId: UUID) {
        if (annotationId in echoes) return
        require(echoes.size < AnnotationEchoRecord.MAX_PER_DIMENSION) { "this dimension already contains 2,048 note gestures" }
        require(echoes.values.count { it.ownerId == ownerId } < AnnotationEchoRecord.MAX_PER_PLAYER) {
            "you already own 64 note gestures in this dimension"
        }
    }

    @Synchronized
    fun replace(record: AnnotationEchoRecord) {
        AnnotationEchoValidation.decode(record.encodedClip)
        requireCapacity(record.annotationId, record.ownerId)
        echoes[record.annotationId] = record.copy(encodedClip = record.encodedClip.copyOf())
        setDirty()
    }

    @Synchronized
    fun remove(annotationId: UUID): Boolean {
        val removed = echoes.remove(annotationId) != null
        if (removed) setDirty()
        return removed
    }

    @Synchronized
    fun prune(validAnnotations: Map<UUID, Int>): Int {
        val before = echoes.size
        echoes.entries.removeIf { (id, echo) -> validAnnotations[id] != echo.annotationRevision }
        val removed = before - echoes.size
        if (removed > 0) setDirty()
        return removed
    }

    override fun save(tag: CompoundTag): CompoundTag {
        tag.putInt("schema", SCHEMA)
        tag.put("echoes", ListTag().also { list ->
            echoes.values.forEach { record -> list.add(CompoundTag().also {
                it.putUUID("annotation", record.annotationId)
                it.putInt("revision", record.annotationRevision)
                it.putUUID("owner", record.ownerId)
                it.putByteArray("clip", record.encodedClip)
            }) }
        })
        return tag
    }

    companion object {
        private const val KEY = "player_traces_annotation_echoes"
        private const val SCHEMA = 1

        fun get(level: ServerLevel): AnnotationEchoSavedData =
            level.dataStorage.computeIfAbsent(::load, ::AnnotationEchoSavedData, KEY)

        fun load(tag: CompoundTag): AnnotationEchoSavedData = AnnotationEchoSavedData().also { data ->
            require(tag.getInt("schema") == SCHEMA) { "Unsupported Traces annotation-store schema ${tag.getInt("schema")}; expected $SCHEMA" }
            if (!tag.contains("echoes", Tag.TAG_LIST.toInt())) return@also
            tag.getList("echoes", Tag.TAG_COMPOUND.toInt()).forEach { raw ->
                runCatching {
                    val item = raw as CompoundTag
                    AnnotationEchoRecord(item.getUUID("annotation"), item.getInt("revision"), item.getUUID("owner"), item.getByteArray("clip"))
                }.mapCatching { record -> AnnotationEchoValidation.decode(record.encodedClip); record }
                    .getOrNull()?.let { record ->
                        if (data.echoes.size < AnnotationEchoRecord.MAX_PER_DIMENSION &&
                            data.echoes.values.count { it.ownerId == record.ownerId } < AnnotationEchoRecord.MAX_PER_PLAYER
                        ) data.echoes.putIfAbsent(record.annotationId, record)
                    }
            }
        }
    }
}
