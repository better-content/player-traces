package com.bettercontent.playertraces

import com.bettercontent.playertraces.echo.EchoClip
import com.bettercontent.playertraces.echo.EchoClipCodec
import com.bettercontent.playertraces.echo.EchoEncoding
import com.bettercontent.playertraces.echo.EchoFrame
import com.bettercontent.playertraces.echo.EchoPlayback
import com.bettercontent.playertraces.echo.EchoRoot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI

class EchoPrototypeTest {
    @Test
    fun `quantized bone clip round trips within one quantization step`() {
        val clip = boneClip(frameCount = 60)
        val decoded = EchoClipCodec.decodeQuantized(EchoClipCodec.encodeQuantized(clip))

        assertEquals(clip.encoding, decoded.encoding)
        assertEquals(clip.frames.size, decoded.frames.size)
        clip.frames.zip(decoded.frames).forEach { (expected, actual) ->
            assertEquals(expected.root.x, actual.root.x, 1f / 4096f)
            expected.channels.zip(actual.channels).forEach { (left, right) ->
                assertEquals(left, right, 1f / 4096f)
            }
        }
    }

    @Test
    fun `delta quantization compresses a slowly moving clip`() {
        val measurement = EchoClipCodec.measure(boneClip(frameCount = 100))

        assertTrue(measurement.quantizedBytes < measurement.rawBytes)
        assertTrue(measurement.deflatedQuantizedBytes < measurement.quantizedBytes)
    }

    @Test
    fun `quantized geometry clip preserves topology and vertex tracks`() {
        val clip = EchoClip(
            EchoEncoding.GEOMETRY,
            EchoClip.SAMPLE_RATE,
            intArrayOf(0, 1, 1, 2, 2, 0),
            (0 until 12).map { frame ->
                EchoFrame(
                    EchoRoot(frame * 0.03f, 0f, frame * -0.02f, frame * 0.01f, 0f),
                    floatArrayOf(
                        0f, frame * 0.004f, 0f,
                        1f, frame * 0.004f, 0f,
                        0.5f, 1f + frame * 0.004f, 0f,
                    ),
                )
            },
        )

        val decoded = EchoClipCodec.decodeQuantized(EchoClipCodec.encodeQuantized(clip))

        assertEquals(EchoEncoding.GEOMETRY, decoded.encoding)
        assertTrue(clip.topology.contentEquals(decoded.topology))
        clip.frames.zip(decoded.frames).forEach { (expected, actual) ->
            expected.channels.zip(actual.channels).forEach { (left, right) ->
                assertEquals(left, right, 1f / 4096f)
            }
        }
    }

    @Test
    fun `geometry topology is bounded and validated`() {
        val frames = listOf(
            EchoFrame(EchoRoot(0f, 0f, 0f, 0f, 0f), floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f)),
        )
        assertThrows(IllegalArgumentException::class.java) {
            EchoClip(EchoEncoding.GEOMETRY, 20, intArrayOf(0, 2), frames)
        }
        assertThrows(IllegalArgumentException::class.java) {
            EchoClip(EchoEncoding.GEOMETRY, 20, intArrayOf(0, 1), listOf(
                EchoFrame(EchoRoot(0f, 0f, 0f, 0f, 0f), floatArrayOf(Float.NaN, 0f, 0f)),
            ))
        }
    }

    @Test
    fun `playback crosses yaw wrap by the shortest arc`() {
        val channels = FloatArray(EchoClip.BONE_CHANNEL_COUNT)
        val clip = EchoClip(
            EchoEncoding.BONE,
            1,
            intArrayOf(),
            listOf(
                EchoFrame(EchoRoot(0f, 0f, 0f, Math.toRadians(170.0).toFloat(), 0f), channels),
                EchoFrame(EchoRoot(1f, 0f, 0f, Math.toRadians(-170.0).toFloat(), 0f), channels),
            ),
        )

        val midpoint = EchoPlayback.sample(clip, 0.5f)
        assertTrue(kotlin.math.abs(kotlin.math.abs(midpoint.root.bodyYaw) - PI.toFloat()) < 0.001f)
        assertEquals(0.5f, midpoint.root.x, 0.001f)
    }

    @Test
    fun `clips reject more than five seconds`() {
        assertThrows(IllegalArgumentException::class.java) { boneClip(frameCount = 101) }
    }

    private fun boneClip(frameCount: Int): EchoClip {
        val frames = (0 until frameCount).map { frame ->
            EchoFrame(
                EchoRoot(frame * 0.01f, 0f, frame * -0.005f, frame * 0.002f, frame * 0.001f),
                FloatArray(EchoClip.BONE_CHANNEL_COUNT) { channel -> channel * 0.01f + frame * 0.0005f },
            )
        }
        return EchoClip(EchoEncoding.BONE, EchoClip.SAMPLE_RATE, intArrayOf(), frames)
    }
}
