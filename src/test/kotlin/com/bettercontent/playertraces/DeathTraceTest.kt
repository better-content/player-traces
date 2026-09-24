package com.bettercontent.playertraces

import com.bettercontent.playertraces.client.death.deathEchoThreadOpacity
import com.bettercontent.playertraces.client.death.echoWorldVertex
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
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class DeathTraceTest {
    @Test
    fun `confirmed death request round trips actual final position without episode token`() {
        val request = com.bettercontent.playertraces.network.DeathCaptureRequestPacket(UUID.randomUUID(), 12.5, -30.0, 45.25)
        val buffer = net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer())
        try {
            request.encode(buffer)
            assertEquals(request, com.bettercontent.playertraces.network.DeathCaptureRequestPacket.decode(buffer))
            assertEquals(0, buffer.readableBytes())
        } finally {
            buffer.release()
        }
    }

    @Test
    fun `ghost body uses exact player model scale without scaling recorded movement`() {
        val root = EchoRoot(2f, 3f, 4f, 0f, 0f)
        val anchor = Vec3(10.0, 20.0, 30.0)

        val world = echoWorldVertex(Vec3(0.4, 1.0, -0.2), root, anchor)

        assertEquals(12.4, world.x, 0.0001)
        assertEquals(24.0, world.y, 0.0001)
        assertEquals(33.8, world.z, 0.0001)
    }

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
    fun `death history temporal indexes stay strict through replacement retention and reload`() {
        val owner = UUID.randomUUID()
        val data = DeathTraceSavedData()
        val pool10 = pool(owner, 10)
        val pool20 = pool(owner, 20)
        val pool30 = pool(owner, 30)
        data.addPool(pool10, maxTotal = 10)
        data.addPool(pool20, maxTotal = 10)
        data.addPool(pool30, maxTotal = 10)

        assertEquals(listOf(30L), data.poolsAfter(20).map { it.createdAt })
        assertEquals(listOf(20L, 30L), data.poolsAfter(10).map { it.createdAt })

        data.addPool(pool20.copy(createdAt = 40), maxTotal = 10)
        assertEquals(listOf(30L, 40L), data.poolsAfter(20).map { it.createdAt })
        assertEquals(listOf(40L), data.poolsAfter(30).map { it.createdAt })

        val echo10 = echo(owner, pool10, 10)
        val echo20 = echo(owner, pool20, 20)
        val echo30 = echo(owner, pool30, 30)
        data.addEcho(echo10, maxTotal = 10, maxPerPlayer = 10)
        data.addEcho(echo20, maxTotal = 10, maxPerPlayer = 10)
        data.addEcho(echo30, maxTotal = 10, maxPerPlayer = 10)
        assertEquals(listOf(30L), data.echoesAfter(20).map { it.createdAt })

        data.addEcho(echo30.copy(createdAt = 40), maxTotal = 10, maxPerPlayer = 10)
        assertEquals(listOf(40L), data.echoesAfter(30).map { it.createdAt })

        val loaded = DeathTraceSavedData.load(data.save(CompoundTag()))
        assertEquals(data.poolsAfter(20).map { it.id to it.createdAt }, loaded.poolsAfter(20).map { it.id to it.createdAt })
        assertEquals(data.echoesAfter(20).map { it.id to it.createdAt }, loaded.echoesAfter(20).map { it.id to it.createdAt })
    }

    @Test
    fun `death trace spatial buckets preserve exact bounds through mutation trimming and reload`() {
        val owner = UUID.randomUUID()
        val west = pool(owner, 1).copy(x = -16.01, z = -0.01)
        val edge = pool(owner, 2).copy(x = -16.0, z = 0.0)
        val east = pool(owner, 3).copy(x = 16.0, z = 16.0)
        val data = DeathTraceSavedData()
        data.addPool(west, maxTotal = 3)
        data.addPool(edge, maxTotal = 3)
        data.addPool(east, maxTotal = 3)

        assertEquals(listOf(west.id), data.poolsWithin(-16.01, -16.01, -0.01, -0.01).map { it.id })
        assertEquals(listOf(edge.id), data.poolsWithin(-16.0, -16.0, 0.0, 0.0).map { it.id })
        assertEquals(setOf(west.id, edge.id), data.poolsWithin(-16.01, -16.0, -0.01, 0.0).map { it.id }.toSet())

        val moved = edge.copy(x = 64.0, z = -64.0)
        data.addPool(moved, maxTotal = 3)
        assertTrue(data.poolsWithin(-16.0, 0.0, -1.0, 1.0).none { it.id == edge.id })
        assertEquals(listOf(moved.id), data.poolsWithin(64.0, 64.0, -64.0, -64.0).map { it.id })

        data.addPool(pool(owner, 4).copy(x = -160.0, z = 160.0), maxTotal = 2)
        assertEquals(0, data.poolsWithin(-16.01, -16.01, -0.01, -0.01).size)
        val loaded = DeathTraceSavedData.load(data.save(CompoundTag()))
        assertEquals(data.poolsWithin(-200.0, 100.0, -100.0, 200.0).map { it.id }.toSet(),
            loaded.poolsWithin(-200.0, 100.0, -100.0, 200.0).map { it.id }.toSet())

        val pool = pool(owner, 10)
        val echo = echo(owner, pool, 10).copy(x = -32.0, z = 48.0)
        data.addEcho(echo, maxTotal = 2, maxPerPlayer = 2)
        assertEquals(listOf(echo.id), data.echoesWithin(-32.0, -32.0, 48.0, 48.0).map { it.id })
        val loadedEchoes = DeathTraceSavedData.load(data.save(CompoundTag()))
        assertEquals(listOf(echo.id), loadedEchoes.echoesWithin(-32.0, -32.0, 48.0, 48.0).map { it.id })
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
