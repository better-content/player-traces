package com.bettercontent.traces.echo

class RollingPoseBuffer<T>(private val capacity: Int) {
    private val values = ArrayDeque<T>()
    init { require(capacity > 0) }

    fun add(value: T) {
        values.addLast(value)
        while (values.size > capacity) values.removeFirst()
    }

    fun snapshot(): List<T> = values.toList()
    fun clear() = values.clear()
    val size: Int get() = values.size
}
