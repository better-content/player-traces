package com.bettercontent.playertraces

import com.bettercontent.playertraces.domain.FootTrace
import com.bettercontent.playertraces.domain.GLOBAL_TEAM
import com.bettercontent.playertraces.domain.MovementClass
import com.bettercontent.playertraces.domain.TraceAnnotation
import com.bettercontent.playertraces.domain.TraceKind
import com.bettercontent.playertraces.domain.TraceSupport
import com.bettercontent.playertraces.storage.SeenStateRecord
import com.bettercontent.playertraces.storage.TraceSerializer
import com.bettercontent.playertraces.storage.TraceShardState
import com.bettercontent.playertraces.util.Geometry
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.zip.CRC32

class TraceStorageV3Test {
    @Test
    fun v3RoundTripPreservesKindsAndSupport(@TempDir dir: Path) {
        val state = TraceShardState()
        val support = TraceSupport(BlockPos(3, 63, 4), ResourceLocation("minecraft", "stone"))
        state.addFootTrace(trace("foot", 3.25, 64.0, 4.75, TraceKind.FOOTPRINT, support))
        state.addFootTrace(trace("arrival", 8.0, 70.0, 9.0, TraceKind.ARRIVAL, null))
        val revision = state.tileRevision(0, 0)

        val path = dir.resolve("r.0.0.traces")
        TraceSerializer.write(path, state, Geometry.shardToBounds(0, 0))
        val loaded = TraceSerializer.read(path).footTracesSnapshot().associateBy { it.kind }

        assertEquals(support, loaded.getValue(TraceKind.FOOTPRINT).support)
        assertEquals(null, loaded.getValue(TraceKind.ARRIVAL).support)
        assertEquals(revision, TraceSerializer.read(path).tileRevision(0, 0))
    }

    @Test
    fun v2MigrationDiscardsFeetButRetainsAnnotationsAndSeenState(@TempDir dir: Path) {
        val path = dir.resolve("r.0.0.traces")
        val annotationId = UUID.nameUUIDFromBytes("v2-annotation".toByteArray())
        val playerId = UUID.nameUUIDFromBytes("v2-player".toByteArray())
        Files.write(path, version2Shard(annotationId, playerId))

        val migrated = TraceSerializer.read(path)
        assertTrue(migrated.dirty)
        assertTrue(migrated.footTracesSnapshot().isEmpty())
        assertEquals(annotationId, migrated.annotationsSnapshot().single().id)
        assertEquals(7, migrated.seenStatesSnapshot().single().highestRevision)

        TraceSerializer.write(path, migrated, Geometry.shardToBounds(0, 0))
        val rewritten = TraceSerializer.read(path)
        assertTrue(rewritten.footTracesSnapshot().isEmpty())
        assertEquals(annotationId, rewritten.annotationsSnapshot().single().id)
        assertEquals(7, rewritten.seenStatesSnapshot().single().highestRevision)
        assertTrue(Files.isRegularFile(path.resolveSibling("r.0.0.traces.bak")))
    }

    @Test
    fun tileRevisionsChangeOnlyForAffectedTilesAndSurviveEmptying() {
        val state = TraceShardState()
        val firstSupport = TraceSupport(BlockPos(1, 63, 1), ResourceLocation("minecraft", "stone"))
        val secondSupport = TraceSupport(BlockPos(20, 63, 1), ResourceLocation("minecraft", "dirt"))
        state.addFootTrace(trace("first", 1.5, 64.0, 1.5, TraceKind.FOOTPRINT, firstSupport))
        state.addFootTrace(trace("second", 20.5, 64.0, 1.5, TraceKind.FOOTPRINT, secondSupport))
        val firstRevision = state.tileRevision(0, 0)
        val secondRevision = state.tileRevision(1, 0)

        assertEquals(1, state.queryTraceTile(0, 0).size)
        assertEquals(1, state.removeBySupport(firstSupport.position))
        assertTrue(state.queryTraceTile(0, 0).isEmpty())
        assertTrue(state.tileRevision(0, 0) > firstRevision)
        assertEquals(secondRevision, state.tileRevision(1, 0))

        assertEquals(1, state.pruneInvalidSupports(1, 0) { it.blockId.path == "stone" })
        assertTrue(state.queryTraceTile(1, 0).isEmpty())
        assertTrue(state.tileRevision(1, 0) > secondRevision)
    }

    private fun trace(
        seed: String,
        x: Double,
        y: Double,
        z: Double,
        kind: TraceKind,
        support: TraceSupport?,
    ) = FootTrace(
        id = UUID.nameUUIDFromBytes(seed.toByteArray()),
        levelKey = "minecraft:overworld",
        x = x,
        y = y,
        z = z,
        facingYaw = 0f,
        movementClass = MovementClass.WALK,
        strength = 1f,
        sequenceId = UUID.nameUUIDFromBytes("sequence-$seed".toByteArray()),
        sequenceIndex = 0,
        createdAt = 20,
        sequenceEpoch = 1,
        surviving = true,
        sourcePlayerInternal = UUID.nameUUIDFromBytes("player-$seed".toByteArray()),
        kind = kind,
        support = support,
    )

    private fun version2Shard(annotationId: UUID, playerId: UUID): ByteArray {
        val bodyBytes = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeInt(0x54524143)
                out.writeShort(2)
                out.writeShort(0)
                repeat(8) { out.writeInt(0) }

                out.writeByte(1)
                out.writeInt(1)
                writeVersion2Foot(out, playerId)

                out.writeByte(2)
                out.writeInt(1)
                out.writeUTF(annotationId.toString())
                out.writeUTF("preserved")
                out.writeUTF("pin")
                out.writeInt(0x35E7FF)
                repeat(2) {
                    out.writeInt(4)
                    out.writeInt(64)
                    out.writeInt(5)
                }
                out.writeUTF(GLOBAL_TEAM.id)
                out.writeInt(7)
                out.writeLong(playerId.mostSignificantBits)
                out.writeLong(playerId.leastSignificantBits)

                out.writeByte(3)
                out.writeInt(1)
                out.writeLong(annotationId.mostSignificantBits)
                out.writeLong(annotationId.leastSignificantBits)
                out.writeLong(playerId.mostSignificantBits)
                out.writeLong(playerId.leastSignificantBits)
                out.writeInt(7)

                out.writeByte(4)
                out.writeInt(1)
            }
        }.toByteArray()
        val crc = CRC32().also { it.update(bodyBytes) }
        val footer = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeInt(3)
                out.writeLong(crc.value)
            }
        }.toByteArray()
        return bodyBytes + footer
    }

    private fun writeVersion2Foot(out: DataOutputStream, playerId: UUID) {
        out.writeUTF(UUID.nameUUIDFromBytes("old-foot".toByteArray()).toString())
        out.writeUTF("minecraft:overworld")
        out.writeDouble(1.5)
        out.writeDouble(64.0)
        out.writeDouble(1.5)
        out.writeFloat(0f)
        out.writeByte(MovementClass.WALK.ordinal)
        out.writeFloat(1f)
        val sequence = UUID.nameUUIDFromBytes("old-sequence".toByteArray())
        out.writeLong(sequence.mostSignificantBits)
        out.writeLong(sequence.leastSignificantBits)
        out.writeInt(0)
        out.writeLong(1)
        out.writeLong(1)
        out.writeBoolean(true)
        out.writeLong(playerId.mostSignificantBits)
        out.writeLong(playerId.leastSignificantBits)
    }
}
