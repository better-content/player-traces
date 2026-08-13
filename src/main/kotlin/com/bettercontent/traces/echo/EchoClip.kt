package com.bettercontent.traces.echo

import kotlin.math.PI

enum class EchoEncoding { BONE, GEOMETRY }

data class EchoRoot(
    val x: Float,
    val y: Float,
    val z: Float,
    val bodyYaw: Float,
    val headYaw: Float,
) {
    init {
        require(listOf(x, y, z, bodyYaw, headYaw).all(Float::isFinite)) { "echo root is not finite" }
    }
}

class EchoFrame(val root: EchoRoot, val channels: FloatArray) {
    init {
        require(channels.all(Float::isFinite)) { "echo frame contains a non-finite channel" }
    }
}

class EchoClip(
    val encoding: EchoEncoding,
    val sampleRate: Int,
    val topology: IntArray,
    val frames: List<EchoFrame>,
) {
    val channelCount: Int = frames.firstOrNull()?.channels?.size ?: 0
    val durationSeconds: Float = if (frames.size <= 1) 0f else (frames.size - 1).toFloat() / sampleRate

    init {
        require(sampleRate in 1..MAX_SAMPLE_RATE) { "echo sample rate is invalid" }
        require(frames.size in 1..sampleRate * MAX_DURATION_SECONDS) { "echo duration is invalid" }
        require(channelCount > 0 && frames.all { it.channels.size == channelCount }) { "echo channels are inconsistent" }
        require(frames.all { frame ->
            frame.root.x in -MAX_ROOT_DISPLACEMENT..MAX_ROOT_DISPLACEMENT &&
                frame.root.y in -MAX_ROOT_DISPLACEMENT..MAX_ROOT_DISPLACEMENT &&
                frame.root.z in -MAX_ROOT_DISPLACEMENT..MAX_ROOT_DISPLACEMENT
        }) { "echo root displacement is excessive" }
        when (encoding) {
            EchoEncoding.BONE -> {
                require(channelCount == BONE_CHANNEL_COUNT) { "bone echo channel count is invalid" }
                require(topology.isEmpty()) { "bone echo must not carry geometry topology" }
            }
            EchoEncoding.GEOMETRY -> {
                require(channelCount % 3 == 0 && channelCount <= MAX_GEOMETRY_CHANNELS) { "geometry channel count is invalid" }
                require(topology.size % 2 == 0 && topology.size <= MAX_TOPOLOGY_INDICES) { "geometry topology is invalid" }
                val vertexCount = channelCount / 3
                require(topology.all { it in 0 until vertexCount }) { "geometry topology references an invalid vertex" }
            }
        }
    }

    companion object {
        const val SAMPLE_RATE = 20
        const val MAX_SAMPLE_RATE = 40
        const val MAX_DURATION_SECONDS = 5
        const val MAX_ROOT_DISPLACEMENT = 32f
        const val BONE_PART_COUNT = 12
        const val CHANNELS_PER_BONE = 9
        const val BONE_CHANNEL_COUNT = BONE_PART_COUNT * CHANNELS_PER_BONE
        const val MAX_GEOMETRY_VERTICES = 8_192
        const val MAX_GEOMETRY_CHANNELS = MAX_GEOMETRY_VERTICES * 3
        const val MAX_TOPOLOGY_INDICES = 32_768
    }
}

object EchoPlayback {
    fun sample(clip: EchoClip, seconds: Float): EchoFrame {
        if (clip.frames.size == 1) return clip.frames.first()
        val position = (seconds.coerceIn(0f, clip.durationSeconds) * clip.sampleRate)
        val firstIndex = position.toInt().coerceAtMost(clip.frames.lastIndex)
        val secondIndex = (firstIndex + 1).coerceAtMost(clip.frames.lastIndex)
        val amount = position - firstIndex
        val first = clip.frames[firstIndex]
        val second = clip.frames[secondIndex]
        val channels = FloatArray(clip.channelCount) { index ->
            lerp(first.channels[index], second.channels[index], amount)
        }
        return EchoFrame(
            EchoRoot(
                lerp(first.root.x, second.root.x, amount),
                lerp(first.root.y, second.root.y, amount),
                lerp(first.root.z, second.root.z, amount),
                lerpAngle(first.root.bodyYaw, second.root.bodyYaw, amount),
                lerpAngle(first.root.headYaw, second.root.headYaw, amount),
            ),
            channels,
        )
    }

    private fun lerp(from: Float, to: Float, amount: Float): Float = from + (to - from) * amount

    internal fun lerpAngle(from: Float, to: Float, amount: Float): Float {
        var delta = (to - from) % (PI.toFloat() * 2f)
        if (delta > PI) delta -= PI.toFloat() * 2f
        if (delta < -PI) delta += PI.toFloat() * 2f
        return from + delta * amount
    }
}
