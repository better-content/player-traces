package com.bettercontent.playertraces.storage

import com.bettercontent.playertraces.domain.TraceAnnotation
import com.bettercontent.playertraces.domain.FootTrace
import com.bettercontent.playertraces.domain.GLOBAL_TEAM
import net.minecraft.core.BlockPos

class TraceShardState {
    internal val footTraces: MutableList<FootTrace> = mutableListOf()
    internal val annotations: MutableList<TraceAnnotation> = mutableListOf()
    internal val seenStates: MutableList<SeenStateRecord> = mutableListOf()
    @Volatile
    var dirty: Boolean = false
        private set
    private var generation: Long = 0

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
        return copy to generation
    }

    @Synchronized
    fun clearDirtyIfUnchanged(snapshotGeneration: Long) {
        if (generation == snapshotGeneration) dirty = false
    }

    @Synchronized
    fun footTracesSnapshot(): List<FootTrace> = footTraces.toList()

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
        val before = footTraces.size
        footTraces.removeIf { it.blockPos.x == position.x && it.blockPos.y == position.y && it.blockPos.z == position.z }
        if (footTraces.size != before) markDirty()
    }

    @Synchronized
    fun removeFootTraces(boundsMin: BlockPos, boundsMax: BlockPos): Int {
        val before = footTraces.size
        footTraces.removeIf { trace ->
            val pos = trace.blockPos
            pos.x in boundsMin.x..boundsMax.x && pos.y in boundsMin.y..boundsMax.y && pos.z in boundsMin.z..boundsMax.z
        }
        val removed = before - footTraces.size
        if (removed > 0) markDirty()
        return removed
    }

    @Synchronized
    fun updateWeakness(position: BlockPos, factor: Double) {
        var changed = false
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
                }
            } else {
                footTraces[i] = trace.copy(strength = next)
                changed = true
            }
        }
        if (changed) markDirty()
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
}
