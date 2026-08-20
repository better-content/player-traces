package com.bettercontent.playertraces.domain

enum class TraceKind(val serializedId: Int) {
    FOOTPRINT(0),
    ARRIVAL(1),
    DEPARTURE(2),
    ;

    companion object {
        fun fromSerializedId(id: Int): TraceKind = entries.firstOrNull { it.serializedId == id }
            ?: throw IllegalArgumentException("invalid trace kind $id")
    }
}
