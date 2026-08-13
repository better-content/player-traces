package com.bettercontent.playertraces.storage

import com.bettercontent.playertraces.util.TraceShardId

class TraceShardLruCache(maxEntries: Int) {
    private val capacity = maxEntries
    private val delegate = object : LinkedHashMap<TraceShardId, TraceShardState>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TraceShardId, TraceShardState>?): Boolean {
            return size > maxEntries
        }
    }

    @Synchronized
    fun get(id: TraceShardId): TraceShardState? = delegate[id]

    @Synchronized
    fun put(id: TraceShardId, state: TraceShardState): Pair<TraceShardId, TraceShardState>? {
        val eldest = if (!delegate.containsKey(id) && delegate.size >= capacity) delegate.entries.first() else null
        delegate[id] = state
        if (eldest != null) delegate.remove(eldest.key)
        return eldest?.key?.let { it to eldest.value }
    }

    @Synchronized
    fun takeAllDirty(): List<TraceShardId> = delegate.filter { it.value.dirty }.map { it.key }

    @Synchronized
    fun valuesSnapshot(): List<TraceShardState> = delegate.values.toList()

    @Synchronized
    fun keyFor(state: TraceShardState): TraceShardId? =
        delegate.entries.firstOrNull { it.value === state }?.key
}
