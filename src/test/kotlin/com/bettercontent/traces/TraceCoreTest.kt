package com.bettercontent.traces

import com.bettercontent.traces.storage.TraceSerializer
import com.bettercontent.traces.storage.TraceShardState
import com.bettercontent.traces.storage.TraceShardLruCache
import com.bettercontent.traces.domain.FootTrace
import com.bettercontent.traces.domain.MovementClass
import com.bettercontent.traces.domain.TraceAnnotation
import com.bettercontent.traces.domain.GLOBAL_TEAM
import com.bettercontent.traces.util.Geometry
import com.bettercontent.traces.dto.VisibleAnnotationDto
import com.bettercontent.traces.dto.VisibleTraceDto
import com.bettercontent.traces.logic.GuidanceEngine
import com.bettercontent.traces.client.TraceVisualModel
import com.bettercontent.traces.network.TraceQueryResponsePacket
import net.minecraft.core.BlockPos
import net.minecraft.network.FriendlyByteBuf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import io.netty.buffer.Unpooled
import java.nio.file.Path
import java.util.UUID
import com.bettercontent.traces.util.TraceShardId
import com.bettercontent.traces.config.TracesConfig

class TraceCoreTest {

    @Test
    fun visualPaletteAndDesaturationContractRemainStable() {
        assertEquals(0.8, TracesConfig.client.worldDesaturation.get(), 0.0001)
        assertTrue(TraceVisualModel.validPrimitiveCount(TraceVisualModel.PIN_VERTEX_COUNT, TraceVisualModel.FOOTPRINT_VERTICES_PER_PRIMITIVE))
        assertTrue(TraceVisualModel.validPrimitiveCount(220 * TraceVisualModel.GUIDANCE_VERTICES_PER_PRIMITIVE, TraceVisualModel.GUIDANCE_VERTICES_PER_PRIMITIVE))
        assertTrue(!TraceVisualModel.validPrimitiveCount(47, TraceVisualModel.FOOTPRINT_VERTICES_PER_PRIMITIVE))
    }
    @Test
    fun geometryUses16x16RegionBuckets() {
        val (sx, sz) = Geometry.worldToShard(BlockPos(255, 64, 255))
        assertEquals(0, sx)
        assertEquals(0, sz)

        val (sx2, sz2) = Geometry.worldToShard(BlockPos(256, 64, -1))
        assertEquals(1, sx2)
        assertEquals(-1, sz2)
    }

    @Test
    fun stationaryBlockPositionsHaveZeroCaptureDistance() {
        val position = BlockPos(4, 70, -3)
        assertEquals(0.0, kotlin.math.sqrt(position.distSqr(position).toDouble()), 0.0001)
        assertEquals(1.0, kotlin.math.sqrt(position.offset(1, 0, 0).distSqr(position).toDouble()), 0.0001)
    }

    @Test
    fun serializerRoundTrip(@TempDir dir: Path) {
        val state = TraceShardState()
        val traceId = UUID.randomUUID()
        val annotationId = UUID.randomUUID()
        val player = UUID.randomUUID()
        state.footTraces += FootTrace(
            id = traceId,
            levelKey = "minecraft:overworld",
            blockPos = BlockPos(0, 64, 0),
            movementClass = MovementClass.WALK,
            strength = 1.0f,
            sequenceId = UUID.randomUUID(),
            sequenceIndex = 0,
            createdAt = 1,
            sequenceEpoch = 1,
            surviving = true,
            sourcePlayerInternal = player,
        )
        state.annotations.add(
            TraceAnnotation(
                id = annotationId,
                text = "test",
                icon = "pin",
                color = 0xFF0000,
                position = BlockPos(5, 64, 5),
                targetBlock = BlockPos(5, 64, 5),
                team = GLOBAL_TEAM,
                revision = 1,
                createdByInternal = player,
            )
        )

        val shardPath = dir.resolve("r.0.0.traces")
        val bounds = Geometry.shardToBounds(0, 0)
        TraceSerializer.write(shardPath, state, bounds)
        val loaded = TraceSerializer.read(shardPath)

        assertEquals(1, loaded.footTraces.size)
        assertEquals(1, loaded.annotations.size)
        assertNotNull(loaded.footTraces.firstOrNull { it.id == traceId })
        assertNotNull(loaded.annotations.firstOrNull { it.id == annotationId })
    }

    @Test
    fun corruptedShardFallsBack(@TempDir dir: Path) {
        val shardPath = dir.resolve("r.0.0.traces")
        java.nio.file.Files.writeString(shardPath, "not a shard")
        val loaded = TraceSerializer.read(shardPath)
        assertTrue(loaded.footTraces.isEmpty())
        assertTrue(loaded.annotations.isEmpty())
        assertTrue(loaded.seenStates.isEmpty())
    }

    @Test
    fun guidanceReturnsNoRouteForDisconnectedTraceGraph() {
        val traces = listOf(
            VisibleTraceDto("a", "seq", MovementClass.WALK, 0, 64, 0, 1.0f, 0),
            VisibleTraceDto("b", "seq", MovementClass.WALK, 50, 64, 0, 1.0f, 1),
        )
        val annotations = listOf(
            VisibleAnnotationDto("target", "far", "pin", 0xFFFFFF, 50, 64, 0, GLOBAL_TEAM.id, 1, false),
        )

        val signals = GuidanceEngine.buildSignals(traces, annotations, BlockPos(0, 64, 0))
        assertTrue(signals.isEmpty())
    }

    @Test
    fun guidanceReturnsPathOnlyThroughAdjacentTraces() {
        val traces = listOf(
            VisibleTraceDto("start", "seq", MovementClass.WALK, 0, 64, 0, 1.0f, 0),
            VisibleTraceDto("left", "seq", MovementClass.WALK, 3, 64, 0, 1.0f, 1),
            VisibleTraceDto("right", "seq", MovementClass.WALK, 3, 64, 4, 1.0f, 2),
            VisibleTraceDto("goal", "seq", MovementClass.WALK, 6, 64, 4, 1.0f, 3),
        )
        val annotations = listOf(
            VisibleAnnotationDto("target", "goal", "pin", 0xFFFFFF, 6, 64, 4, GLOBAL_TEAM.id, 1, false),
        )

        val signals = GuidanceEngine.buildSignals(traces, annotations, BlockPos(0, 64, 0))
        assertTrue(signals.isNotEmpty())

        val path = signals.first().path
        assertTrue(path.size >= 2)
        for (i in 0 until path.size - 1) {
            val a = path[i]
            val b = path[i + 1]
            val dx = a.x - b.x
            val dz = a.z - b.z
            assertTrue((dx * dx + dz * dz) <= 25, "path must follow adjacency within 5 blocks")
        }
    }

    @Test
    fun guidanceEmptyWhenTraceSequenceIsInterrupted() {
        val traces = listOf(
            VisibleTraceDto("start", "seq", MovementClass.WALK, 0, 64, 0, 1.0f, 0),
            VisibleTraceDto("goal", "seq", MovementClass.WALK, 6, 64, 0, 1.0f, 1),
        )
        val annotations = listOf(
            VisibleAnnotationDto("target", "goal", "pin", 0xFFFFFF, 6, 64, 0, GLOBAL_TEAM.id, 1, false),
        )

        val signals = GuidanceEngine.buildSignals(traces, annotations, BlockPos(0, 64, 0))
        assertTrue(signals.isEmpty())
    }

    @Test
    fun guidanceDoesNotInventAttachmentToFarDestination() {
        val traces = (0..4).map {
            VisibleTraceDto("route-$it", "grounded", MovementClass.WALK, it, 64, 0, 1f, it)
        }
        val flightOnlyDestination = listOf(
            VisibleAnnotationDto("air", "unreachable", "pin", 0xFFFFFF, 20, 72, 20, GLOBAL_TEAM.id, 1, false),
        )
        assertTrue(GuidanceEngine.buildSignals(traces, flightOnlyDestination, BlockPos(0, 64, 0)).isEmpty())
    }

    @Test
    fun traceQueryResponsePacketCarriesAnnotationSeenFlag() {
        val packet = TraceQueryResponsePacket(
            traces = listOf(
                VisibleTraceDto("t1", "seq", MovementClass.WALK, 0, 64, 0, 1f, 0),
            ),
            annotations = listOf(
                VisibleAnnotationDto("ann", "label", "pin", 0xFF00FF, 5, 64, 6, GLOBAL_TEAM.id, 2, true),
            ),
        )
        val buffer = FriendlyByteBuf(Unpooled.buffer())
        packet.encode(buffer)
        val decoded = TraceQueryResponsePacket.decode(buffer)
        assertEquals(1, decoded.annotations.size)
        assertEquals(true, decoded.annotations.first().seen)
        assertEquals("seq", decoded.traces.first().sequenceId)
    }

    @Test
    fun lruReturnsDirtyEvictionForPersistence() {
        val cache = TraceShardLruCache(1)
        val first = TraceShardState().also { it.markDirty() }
        cache.put(TraceShardId("overworld", 0, 0), first)
        val evicted = cache.put(TraceShardId("overworld", 1, 0), TraceShardState())
        assertNotNull(evicted)
        assertEquals(first, evicted!!.second)
        assertTrue(evicted.second.dirty)
    }

    @Test
    fun serializerRejectsFooterCountMismatch(@TempDir dir: Path) {
        val path = dir.resolve("r.0.0.traces")
        TraceSerializer.write(path, TraceShardState(), Geometry.shardToBounds(0, 0))
        val bytes = java.nio.file.Files.readAllBytes(path)
        val footerOffset = bytes.size - 12
        java.nio.ByteBuffer.wrap(bytes).putInt(footerOffset, 99)
        java.nio.file.Files.write(path, bytes)
        val loaded = TraceSerializer.read(path)
        assertTrue(loaded.footTraces.isEmpty())
        assertTrue(loaded.annotations.isEmpty())
    }

    @Test
    fun guidanceDoesNotJoinNearbyDifferentSequences() {
        val traces = listOf(
            VisibleTraceDto("start", "one", MovementClass.WALK, 0, 64, 0, 1f, 0),
            VisibleTraceDto("goal", "two", MovementClass.WALK, 1, 64, 0, 1f, 0),
        )
        val target = listOf(VisibleAnnotationDto("target", "goal", "pin", 0xFFFFFF, 1, 64, 0, GLOBAL_TEAM.id, 1, false))
        assertTrue(GuidanceEngine.buildSignals(traces, target, BlockPos(0, 64, 0)).isEmpty())
    }

    @Test
    fun visualSamplingIsDeterministicBoundedAndFinite() {
        val traces = (0 until 40).map {
            VisibleTraceDto("trace-$it", "sequence", MovementClass.WALK, it, 64, 0, 1f, it)
        }
        val first = TraceVisualModel.marks(traces, 8f, 0.07f, 12)
        val second = TraceVisualModel.marks(traces, 8f, 0.07f, 12)
        assertEquals(first, second)
        assertTrue(first.size <= 12)
        assertTrue(first.all { it.alpha.isFinite() && it.radius.isFinite() && it.angle.isFinite() })
    }
}
