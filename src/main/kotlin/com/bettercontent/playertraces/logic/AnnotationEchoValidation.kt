package com.bettercontent.playertraces.logic

import com.bettercontent.playertraces.domain.AnnotationEchoRecord
import com.bettercontent.playertraces.echo.EchoClip
import com.bettercontent.playertraces.echo.EchoClipCodec
import com.bettercontent.playertraces.echo.EchoEncoding
import com.bettercontent.playertraces.echo.EchoFrame
import com.bettercontent.playertraces.echo.EchoRoot
import kotlin.math.abs

object AnnotationEchoValidation {
    const val MAX_FRAMES = 60
    const val MAX_ROOT_MOVEMENT = 12f

    fun decode(encoded: ByteArray): EchoClip {
        require(encoded.size in 1..AnnotationEchoRecord.MAX_ENCODED_BYTES) { "gesture payload must be between 1 byte and 12 KiB" }
        val clip = EchoClipCodec.decodeQuantized(encoded)
        require(clip.encoding == EchoEncoding.BONE) { "only bone gestures are accepted" }
        require(clip.sampleRate == EchoClip.SAMPLE_RATE) { "gesture must use 20 Hz sampling" }
        require(clip.frames.size in 1..MAX_FRAMES) { "gesture must contain 1 to 60 frames" }
        require(clip.channelCount == EchoClip.BONE_CHANNEL_COUNT) { "gesture bone channel count is invalid" }
        require(clip.frames.all { frame ->
            abs(frame.root.x) <= MAX_ROOT_MOVEMENT && abs(frame.root.y) <= MAX_ROOT_MOVEMENT && abs(frame.root.z) <= MAX_ROOT_MOVEMENT &&
                frame.channels.all { it.isFinite() && it in -2048f..2048f }
        }) { "gesture movement is out of bounds" }
        return clip
    }
}

enum class ClipAnchor { START, END }

object AnnotationClipTools {
    fun normalize(clip: EchoClip, anchor: ClipAnchor): EchoClip {
        require(clip.encoding == EchoEncoding.BONE) { "only bone clips can be normalized" }
        val origin = if (anchor == ClipAnchor.START) clip.frames.first().root else clip.frames.last().root
        return EchoClip(clip.encoding, clip.sampleRate, clip.topology.copyOf(), clip.frames.map { frame ->
            EchoFrame(
                EchoRoot(
                    frame.root.x - origin.x,
                    frame.root.y - origin.y,
                    frame.root.z - origin.z,
                    relativeAngle(frame.root.bodyYaw, origin.bodyYaw),
                    relativeAngle(frame.root.headYaw, origin.headYaw),
                ),
                frame.channels.copyOf(),
            )
        })
    }

    fun trimStaticPadding(clip: EchoClip, epsilon: Float = 1f / 1024f): EchoClip {
        if (clip.frames.size <= 2) return clip
        fun changed(a: EchoFrame, b: EchoFrame): Boolean =
            abs(a.root.x - b.root.x) > epsilon || abs(a.root.y - b.root.y) > epsilon ||
                abs(a.root.z - b.root.z) > epsilon || abs(relativeAngle(a.root.bodyYaw, b.root.bodyYaw)) > epsilon ||
                abs(relativeAngle(a.root.headYaw, b.root.headYaw)) > epsilon ||
                a.channels.indices.any { abs(a.channels[it] - b.channels[it]) > epsilon }

        var first = 0
        while (first + 1 < clip.frames.lastIndex && !changed(clip.frames[first], clip.frames[first + 1])) first++
        var last = clip.frames.lastIndex
        while (last - 1 > first && !changed(clip.frames[last - 1], clip.frames[last])) last--
        return EchoClip(clip.encoding, clip.sampleRate, clip.topology.copyOf(), clip.frames.subList(first, last + 1))
    }

    private fun relativeAngle(value: Float, origin: Float): Float {
        var delta = (value - origin) % (Math.PI.toFloat() * 2f)
        if (delta > Math.PI) delta -= Math.PI.toFloat() * 2f
        if (delta < -Math.PI) delta += Math.PI.toFloat() * 2f
        return delta
    }
}
