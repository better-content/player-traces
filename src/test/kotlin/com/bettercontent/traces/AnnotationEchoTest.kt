package com.bettercontent.traces

import com.bettercontent.traces.client.AnnotationEchoPlayback
import com.bettercontent.traces.domain.AnnotationComponents
import com.bettercontent.traces.domain.AnnotationEchoRecord
import com.bettercontent.traces.domain.EchoMutation
import com.bettercontent.traces.echo.EchoClip
import com.bettercontent.traces.echo.EchoClipCodec
import com.bettercontent.traces.echo.EchoEncoding
import com.bettercontent.traces.echo.EchoFrame
import com.bettercontent.traces.echo.EchoRoot
import com.bettercontent.traces.echo.RollingPoseBuffer
import com.bettercontent.traces.logic.AnnotationClipTools
import com.bettercontent.traces.logic.AnnotationEchoValidation
import com.bettercontent.traces.logic.ClipAnchor
import com.bettercontent.traces.network.AnnotationCreatePacket
import com.bettercontent.traces.network.AnnotationEchoResponsePacket
import com.bettercontent.traces.network.AnnotationMutationResultPacket
import com.bettercontent.traces.network.AnnotationUpdatePacket
import com.bettercontent.traces.storage.AnnotationEchoSavedData
import io.netty.buffer.Unpooled
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.nbt.CompoundTag
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.random.Random

class AnnotationEchoTest {
    private fun frame(x: Float, channel: Float = x): EchoFrame = EchoFrame(
        EchoRoot(x, x / 2f, -x, x / 10f, -x / 10f), FloatArray(EchoClip.BONE_CHANNEL_COUNT) { channel },
    )

    private fun clip(vararg frames: EchoFrame): EchoClip = EchoClip(EchoEncoding.BONE, 20, intArrayOf(), frames.toList())

    @Test fun rollingRecorderKeepsExactlyTheLatestThreeSeconds() {
        val buffer = RollingPoseBuffer<Int>(60)
        repeat(75) { buffer.add(it) }
        assertEquals(60, buffer.size)
        assertEquals((15 until 75).toList(), buffer.snapshot())
        val frozen = buffer.snapshot()
        buffer.add(75)
        assertEquals(74, frozen.last())
    }

    @Test fun recentAndSelectedClipsUseOppositeAnchors() {
        val source = clip(frame(2f), frame(4f), frame(7f))
        val recent = AnnotationClipTools.normalize(source, ClipAnchor.END)
        assertEquals(-5f, recent.frames.first().root.x)
        assertEquals(0f, recent.frames.last().root.x)
        val selected = AnnotationClipTools.normalize(source, ClipAnchor.START)
        assertEquals(0f, selected.frames.first().root.x)
        assertEquals(5f, selected.frames.last().root.x)
    }

    @Test fun staticPaddingIsTrimmedButMotionRemains() {
        val source = clip(frame(0f, 0f), frame(0f, 0f), frame(1f, 1f), frame(2f, 2f), frame(2f, 2f))
        val trimmed = AnnotationClipTools.trimStaticPadding(source)
        assertEquals(3, trimmed.frames.size)
        assertEquals(0f, trimmed.frames.first().root.x)
        assertEquals(2f, trimmed.frames.last().root.x)
    }

    @Test fun componentsAllowEveryNonEmptyCombination() {
        AnnotationComponents.validate("text", "", 0, false)
        AnnotationComponents.validate("", "pin", AnnotationComponents.colors.getValue("cyan"), false)
        AnnotationComponents.validate("", "", 0, true)
        AnnotationComponents.validate("text", "warning", AnnotationComponents.colors.getValue("red"), true)
        assertThrows(IllegalArgumentException::class.java) { AnnotationComponents.validate("", "", 0, false) }
        assertThrows(IllegalArgumentException::class.java) { AnnotationComponents.validate("x", "skull", 0xFFFFFF, false) }
        assertThrows(IllegalArgumentException::class.java) { AnnotationComponents.validate("x", "pin", 0x123456, false) }
    }

    @Test fun annotationValidationIsBoneOnlyAndBounded() {
        val valid = EchoClipCodec.encodeQuantized(clip(frame(0f), frame(1f)))
        assertEquals(2, AnnotationEchoValidation.decode(valid).frames.size)
        val geometry = EchoClip(EchoEncoding.GEOMETRY, 20, intArrayOf(0, 1), listOf(
            EchoFrame(EchoRoot(0f, 0f, 0f, 0f, 0f), floatArrayOf(0f, 0f, 0f, 1f, 1f, 1f)),
        ))
        assertThrows(IllegalArgumentException::class.java) { AnnotationEchoValidation.decode(EchoClipCodec.encodeQuantized(geometry)) }
        assertThrows(IllegalArgumentException::class.java) { AnnotationEchoValidation.decode(ByteArray(AnnotationEchoRecord.MAX_ENCODED_BYTES + 1)) }
    }

    @Test fun mutationAndEchoPacketsRoundTrip() {
        val encoded = EchoClipCodec.encodeQuantized(clip(frame(0f), frame(1f)))
        val create = AnnotationCreatePacket(UUID.randomUUID(), 42L, "", "help", 0xFFFFFF, EchoMutation.REPLACE, encoded)
        val createBuffer = FriendlyByteBuf(Unpooled.buffer()).also(create::encode)
        val decodedCreate = AnnotationCreatePacket.decode(createBuffer)
        assertEquals(create.requestId, decodedCreate.requestId)
        assertArrayEquals(encoded, decodedCreate.encodedClip)

        val update = AnnotationUpdatePacket(UUID.randomUUID(), UUID.randomUUID().toString(), 3, "hello", "", 0, EchoMutation.REMOVE, null)
        val updateBuffer = FriendlyByteBuf(Unpooled.buffer()).also(update::encode)
        assertEquals(update, AnnotationUpdatePacket.decode(updateBuffer))

        val result = AnnotationMutationResultPacket(UUID.randomUUID(), false, "", 0, "out of reach")
        val resultBuffer = FriendlyByteBuf(Unpooled.buffer()).also(result::encode)
        assertEquals(result, AnnotationMutationResultPacket.decode(resultBuffer))

        val response = AnnotationEchoResponsePacket(UUID.randomUUID().toString(), 4, encoded)
        val responseBuffer = FriendlyByteBuf(Unpooled.buffer()).also(response::encode)
        val decodedResponse = AnnotationEchoResponsePacket.decode(responseBuffer)
        assertArrayEquals(encoded, decodedResponse.encodedClip)
    }

    @Test fun echoPersistenceRoundTripsAndPrunesOrphans() {
        val encoded = EchoClipCodec.encodeQuantized(clip(frame(0f), frame(1f)))
        val annotation = UUID.randomUUID()
        val record = AnnotationEchoRecord(annotation, 2, UUID.randomUUID(), encoded)
        val data = AnnotationEchoSavedData().also { it.replace(record) }
        val restored = AnnotationEchoSavedData.load(data.save(CompoundTag()))
        assertNotNull(restored.get(annotation))
        assertEquals(0, restored.prune(mapOf(annotation to 2)))
        assertEquals(1, restored.prune(mapOf(annotation to 3)))
        assertEquals(0, restored.count())
    }

    @Test fun playbackStaggersCapsCooldownAndRearmsApproach() {
        val playback = AnnotationEchoPlayback(Random(7))
        playback.onSightOpened(listOf("a", "b", "c", "d"), 0)
        val due = (750L..2_500L).firstNotNullOfOrNull { now ->
            listOf("a", "b", "c", "d").firstOrNull { playback.due(it, now, 0) }?.let { now to it }
        }
        assertNotNull(due)
        assertFalse(playback.due(due!!.second, due.first, 0))
        assertTrue(playback.approach("touch", true, 0))
        assertFalse(playback.approach("touch", false, 5_000))
        assertFalse(playback.approach("touch", true, 12_000))
        assertFalse(playback.approach("touch", false, 12_001))
        assertFalse(playback.approach("touch", false, 22_000))
        assertFalse(playback.approach("touch", false, 22_001))
        assertTrue(playback.approach("touch", true, 22_002))
        assertFalse(playback.due("other", Long.MAX_VALUE - 1, AnnotationEchoPlayback.MAX_SIMULTANEOUS))
    }
}
