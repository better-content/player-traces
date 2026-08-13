package com.bettercontent.traces.logic

import com.bettercontent.traces.domain.DeathEchoRecord
import com.bettercontent.traces.echo.EchoClip
import com.bettercontent.traces.echo.EchoClipCodec
import com.bettercontent.traces.echo.EchoEncoding

object DeathEchoValidation {
    fun decodeSubmission(encoded: ByteArray): EchoClip {
        require(encoded.size in 1..DeathEchoRecord.MAX_ENCODED_ECHO_BYTES) { "death echo payload is too large" }
        val clip = EchoClipCodec.decodeQuantized(encoded)
        require(clip.encoding == EchoEncoding.BONE) { "automatic death echoes must use bone encoding" }
        require(clip.sampleRate == EchoClip.SAMPLE_RATE && clip.frames.size in 1..MAX_DEATH_FRAMES) {
            "automatic death echo duration is invalid"
        }
        val finalRoot = clip.frames.last().root
        require(finalRoot.x * finalRoot.x + finalRoot.y * finalRoot.y + finalRoot.z * finalRoot.z <= MAX_FINAL_DISTANCE_SQUARED) {
            "death echo does not end near the confirmed death"
        }
        return clip
    }

    private const val MAX_DEATH_FRAMES = EchoClip.SAMPLE_RATE * 3
    private const val MAX_FINAL_DISTANCE_SQUARED = 64f
}
