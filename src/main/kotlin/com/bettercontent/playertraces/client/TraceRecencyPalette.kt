package com.bettercontent.playertraces.client

import kotlin.math.roundToInt

/** Session-stable diverging trace colors keyed to the viewer's latest login game time. */
object TraceRecencyPalette {
    const val DEFAULT_WINDOW_TICKS = 24_000L
    const val BEFORE_RGB = 0x8B6CFF
    const val LOGIN_RGB = 0x35E7FF
    const val AFTER_RGB = 0xFFB347

    fun color(createdAt: Long, loginGameTime: Long, windowTicks: Long = DEFAULT_WINDOW_TICKS): Int {
        val window = windowTicks.coerceAtLeast(1L)
        return if (createdAt <= loginGameTime) {
            val amount = ((createdAt - (loginGameTime - window)).toDouble() / window).coerceIn(0.0, 1.0)
            interpolate(BEFORE_RGB, LOGIN_RGB, amount)
        } else {
            val amount = ((createdAt - loginGameTime).toDouble() / window).coerceIn(0.0, 1.0)
            interpolate(LOGIN_RGB, AFTER_RGB, amount)
        }
    }

    internal fun interpolate(from: Int, to: Int, amount: Double): Int {
        fun channel(shift: Int): Int {
            val start = (from shr shift) and 0xff
            val end = (to shr shift) and 0xff
            return (start + (end - start) * amount.coerceIn(0.0, 1.0)).roundToInt().coerceIn(0, 255)
        }
        return (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
}
