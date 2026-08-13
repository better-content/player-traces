package com.bettercontent.playertraces

import com.bettercontent.playertraces.client.death.deathEchoThreadOpacity
import com.bettercontent.playertraces.domain.BloodPoolRecord
import com.bettercontent.playertraces.domain.DeathEchoRecord
import com.bettercontent.playertraces.dto.VisibleBloodPoolDto
import com.bettercontent.playertraces.dto.VisibleDeathEchoDto
import com.bettercontent.playertraces.echo.EchoClip
import com.bettercontent.playertraces.echo.EchoClipCodec
import com.bettercontent.playertraces.echo.EchoEncoding
import com.bettercontent.playertraces.echo.EchoFrame
import com.bettercontent.playertraces.echo.EchoRoot
import com.bettercontent.playertraces.logic.DeathEchoValidation
import com.bettercontent.playertraces.network.DeathEchoSubmitPacket
import com.bettercontent.playertraces.network.TraceQueryResponsePacket
import com.bettercontent.playertraces.storage.DeathTraceSavedData
import io.netty.buffer.Unpooled
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.FriendlyByteBuf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class DeathTraceTest {
    @Test
    fun `death echo wires span full opacity while averaging near thirty percent`() {
        val samples = (0 until 144).flatMap { edge ->
            (0 until 120).flatMap { tick ->
                (0..24).map { position -> deathEchoThreadOpacity(edge, position / 24.0, tick / 20.0) }
            }
        }

        assertTrue(samples.min() < 0.001f)
        assertTrue(samples.max() > 0.999f)
        assertEquals(0.3125, samples.average(), 0.015)
    }

    @Test
    fun `opacity changes continuously inside each death echo wire`() {
        val alongWire = (0..64).map { position -> deathEchoThreadOpacity(17, position / 64.0, 2.25) }
        val beforeBoundary = deathEchoThreadOpacity(17, 0.5 - 1.0e-5, 2.25)
        val afterBoundary = deathEchoThreadOpacity(17, 0.5 + 1.0e-5, 2.25)

        assertTrue(alongWire.max() - alongWire.min() > 0.995f)
        assertTrue(kotlin.math.abs(beforeBoundary - afterBoundary) < 0.001f)
    }

    @Test
    fun `death trace saved data persists records and enforces retention`() {
        val owner = UUID.randomUUID()
        val data = DeathTraceSavedData()
        repeat(3) { index ->
            data.addPool(pool(owner, index.toLong()), 2)
        }
        repeat(3) { index ->
            val pool = pool(owner, index.toLong())
            data.addEcho(echo(owner, pool, index.toLong()), maxTotal = 4, maxPerPlayer = 2)
        }

        val loaded = DeathTraceSavedData.load(data.save(CompoundTag()))

        assertEquals(2, loaded.poolCount())
        assertEquals(2, loaded.echoCount())
        assertEquals(listOf(1L, 2L), loaded.poolsWithin(-10.0, 10.0, -10.0, 10.0).map { it.createdAt })
    }

    @Test
    fun `query packet round trips blood pools and compact echoes`() {
        val encoded = validEncodedClip(24)
        val packet = TraceQueryResponsePacket(
            traces = emptyList(),
            annotations = emptyList(),
            bloodPools = listOf(VisibleBloodPoolDto(UUID.randomUUID().toString(), "Dev", 1.25, 64.01, -2.5, 80)),
            deathEchoes = listOf(VisibleDeathEchoDto(UUID.randomUUID().toString(), "Dev", 1.25, 64.0, -2.5, 80, encoded)),
        )
        val buffer = FriendlyByteBuf(Unpooled.buffer())
        packet.encode(buffer)

        val decoded = TraceQueryResponsePacket.decode(buffer)

        assertEquals(packet.bloodPools, decoded.bloodPools)
        assertEquals(1, decoded.deathEchoes.size)
        assertTrue(encoded.contentEquals(decoded.deathEchoes.single().encodedClip))
    }

    @Test
    fun `death submission packet rejects oversized payload`() {
        val packet = DeathEchoSubmitPacket(UUID.randomUUID(), ByteArray(DeathEchoRecord.MAX_ENCODED_ECHO_BYTES + 1))
        assertThrows(IllegalArgumentException::class.java) { packet.encode(FriendlyByteBuf(Unpooled.buffer())) }
    }

    @Test
    fun `server validation requires a short bone clip ending near death`() {
        assertEquals(60, DeathEchoValidation.decodeSubmission(validEncodedClip(60)).frames.size)
        assertThrows(IllegalArgumentException::class.java) { DeathEchoValidation.decodeSubmission(validEncodedClip(61)) }
        assertThrows(IllegalArgumentException::class.java) { DeathEchoValidation.decodeSubmission(validEncodedClip(20, finalX = 9f)) }
        val geometry = EchoClip(
            EchoEncoding.GEOMETRY, 20, intArrayOf(0, 1),
            listOf(EchoFrame(EchoRoot(0f, 0f, 0f, 0f, 0f), floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f))),
        )
        assertThrows(IllegalArgumentException::class.java) {
            DeathEchoValidation.decodeSubmission(EchoClipCodec.encodeQuantized(geometry))
        }
    }

    private fun pool(owner: UUID, created: Long): BloodPoolRecord = BloodPoolRecord(
        UUID.randomUUID(), owner, "Dev", created.toDouble(), 64.01, 0.0, created, "generic",
    )

    private fun echo(owner: UUID, pool: BloodPoolRecord, created: Long): DeathEchoRecord = DeathEchoRecord(
        UUID.randomUUID(), pool.id, owner, "Dev", pool.x, pool.y, pool.z, created, validEncodedClip(20),
    )

    private fun validEncodedClip(frameCount: Int, finalX: Float = 0f): ByteArray {
        val frames = (0 until frameCount).map { index ->
            val channels = FloatArray(EchoClip.BONE_CHANNEL_COUNT)
            repeat(EchoClip.BONE_PART_COUNT) { part ->
                channels[part * EchoClip.CHANNELS_PER_BONE + 6] = 1f
                channels[part * EchoClip.CHANNELS_PER_BONE + 7] = 1f
                channels[part * EchoClip.CHANNELS_PER_BONE + 8] = 1f
            }
            EchoFrame(
                EchoRoot(if (index == frameCount - 1) finalX else -1f, 0f, 0f, 0f, 0f),
                channels,
            )
        }
        return EchoClipCodec.encodeQuantized(EchoClip(EchoEncoding.BONE, EchoClip.SAMPLE_RATE, intArrayOf(), frames))
    }
}
