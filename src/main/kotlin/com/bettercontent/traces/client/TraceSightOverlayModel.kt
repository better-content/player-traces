package com.bettercontent.traces.client

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

data class TraceSightOverlayTransition(
    val from: Float = 0f,
    val target: Float = 0f,
    val startedAtMillis: Long = 0L,
    val durationMillis: Long = 1L,
) {
    fun valueAt(nowMillis: Long): Float {
        if (from == target) return target
        val linear = ((nowMillis - startedAtMillis).toDouble() / durationMillis.toDouble()).coerceIn(0.0, 1.0)
        val eased = linear * linear * (3.0 - 2.0 * linear)
        return (from + (target - from) * eased).toFloat().coerceIn(0f, 1f)
    }

    fun retarget(active: Boolean, nowMillis: Long): TraceSightOverlayTransition {
        val nextTarget = if (active) 1f else 0f
        if (target == nextTarget) return this
        val current = valueAt(nowMillis)
        val fullDuration = if (active) TraceSightOverlayModel.FADE_IN_MILLIS else TraceSightOverlayModel.FADE_OUT_MILLIS
        val duration = (fullDuration * abs(nextTarget - current)).roundToLong().coerceAtLeast(1L)
        return TraceSightOverlayTransition(current, nextTarget, nowMillis, duration)
    }
}

object TraceSightOverlayModel {
    const val FADE_IN_MILLIS = 200L
    const val FADE_OUT_MILLIS = 150L
    const val CENTER_DIM_ALPHA = 0.06f
    const val VIGNETTE_OUTER_ALPHA = 0.32f
    const val CYAN_KEYLINE_ALPHA = 0.28f
    const val VIGNETTE_BANDS = 12
    const val VIGNETTE_FRACTION = 0.16
    const val MIN_VIGNETTE_SPAN = 36
    const val MAX_VIGNETTE_SPAN = 112
    const val VIGNETTE_RGB = 0x080A0C
    const val KEYLINE_RGB = 0x35E7FF

    fun vignetteSpan(width: Int, height: Int): Int {
        val shorter = min(width.coerceAtLeast(1), height.coerceAtLeast(1))
        return (shorter * VIGNETTE_FRACTION).roundToInt()
            .coerceIn(MIN_VIGNETTE_SPAN.coerceAtMost(shorter / 2), MAX_VIGNETTE_SPAN.coerceAtMost(shorter / 2))
    }

    fun vignetteAlpha(band: Int): Float {
        require(band in 0 until VIGNETTE_BANDS) { "vignette band is out of range" }
        val remaining = 1f - band.toFloat() / (VIGNETTE_BANDS - 1).toFloat()
        return VIGNETTE_OUTER_ALPHA * remaining * remaining
    }

    fun alphaByte(maxAlpha: Float, visibility: Float): Int =
        (maxAlpha.coerceIn(0f, 1f) * visibility.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)

    fun argb(rgb: Int, maxAlpha: Float, visibility: Float): Int =
        (alphaByte(maxAlpha, visibility) shl 24) or (rgb and 0xFFFFFF)
}
