package com.bettercontent.traces.client.death

import kotlin.math.sin

/**
 * Gives each wire independently timed, continuously changing visibility.
 * Cubing a sinusoid mapped to 0..1 preserves the full range while averaging 5/16 (31.25%).
 */
internal fun deathEchoThreadOpacity(edgeIndex: Int, segmentPosition: Double, animationSeconds: Double): Float {
    val phase = edgeIndex * GOLDEN_ANGLE +
        segmentPosition.coerceIn(0.0, 1.0) * FULL_WAVE +
        animationSeconds * (BASE_SPEED + (edgeIndex % 7) * SPEED_STEP)
    val wave = 0.5 + 0.5 * sin(phase)
    return (wave * wave * wave).toFloat()
}

private const val GOLDEN_ANGLE = 2.399963229728653
private const val FULL_WAVE = Math.PI * 2.0
private const val BASE_SPEED = 3.05
private const val SPEED_STEP = 0.13
