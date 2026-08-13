package com.bettercontent.playertraces.client

import kotlin.random.Random

data class NotePlaybackState(
    var scheduledAt: Long = Long.MAX_VALUE,
    var cooldownUntil: Long = 0,
    var approachArmed: Boolean = true,
    var leftAt: Long = Long.MIN_VALUE,
)

class AnnotationEchoPlayback(private val random: Random = Random.Default) {
    private val states = mutableMapOf<String, NotePlaybackState>()

    fun onSightOpened(ids: Collection<String>, nowMillis: Long) {
        ids.forEach { id ->
            val state = states.getOrPut(id, ::NotePlaybackState)
            if (state.cooldownUntil <= nowMillis && state.scheduledAt == Long.MAX_VALUE) {
                state.scheduledAt = nowMillis + random.nextLong(750, 2_501)
            }
        }
    }

    fun due(id: String, nowMillis: Long, activeCount: Int): Boolean {
        val state = states.getOrPut(id, ::NotePlaybackState)
        if (activeCount >= MAX_SIMULTANEOUS || nowMillis < state.scheduledAt) return false
        state.scheduledAt = Long.MAX_VALUE
        state.cooldownUntil = nowMillis + random.nextLong(25_000, 40_001)
        return true
    }

    fun approach(id: String, inside: Boolean, nowMillis: Long): Boolean {
        val state = states.getOrPut(id, ::NotePlaybackState)
        if (inside && state.approachArmed) {
            state.approachArmed = false
            state.leftAt = Long.MIN_VALUE
            return true
        }
        if (inside && !state.approachArmed && state.leftAt != Long.MIN_VALUE) state.leftAt = Long.MIN_VALUE
        if (!inside) {
            if (state.leftAt == Long.MIN_VALUE) state.leftAt = nowMillis
            if (!state.approachArmed && nowMillis - state.leftAt >= REARM_MILLIS) state.approachArmed = true
        }
        return false
    }

    fun invalidate(id: String) { states.remove(id) }
    fun retain(ids: Set<String>) { states.keys.retainAll(ids) }

    companion object {
        const val MAX_SIMULTANEOUS = 3
        const val APPROACH_RADIUS = 1.25
        const val MAX_DISTANCE = 18.0
        const val REARM_MILLIS = 10_000L
    }
}
