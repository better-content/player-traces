package com.bettercontent.playertraces

import com.bettercontent.playertraces.storage.TraceSerializer
import com.bettercontent.playertraces.storage.ensureTraceSchema
import com.bettercontent.playertraces.storage.TraceShardState
import com.bettercontent.playertraces.storage.TraceShardLruCache
import com.bettercontent.playertraces.domain.FootTrace
import com.bettercontent.playertraces.domain.MovementClass
import com.bettercontent.playertraces.domain.TraceAnnotation
import com.bettercontent.playertraces.domain.GLOBAL_TEAM
import com.bettercontent.playertraces.domain.TraceSupport
import com.bettercontent.playertraces.util.Geometry
import com.bettercontent.playertraces.dto.VisibleAnnotationDto
import com.bettercontent.playertraces.dto.VisibleTraceDto
import com.bettercontent.playertraces.dto.GuidancePointDto
import com.bettercontent.playertraces.logic.GuidanceEngine
import com.bettercontent.playertraces.logic.GuidanceProgressTracker
import com.bettercontent.playertraces.client.TracesClientRenderer
import com.bettercontent.playertraces.client.GuidancePathModel
import com.bettercontent.playertraces.client.SurfaceAnchorResolver
import com.bettercontent.playertraces.client.TraceVisualModel
import com.bettercontent.playertraces.client.TraceSightOverlayModel
import com.bettercontent.playertraces.client.TraceSightOverlayTransition
import com.bettercontent.playertraces.client.OpeningKeyInputGuard
import com.bettercontent.playertraces.client.FootprintRenderCache
import com.bettercontent.playertraces.client.TraceRecencyPalette
import com.bettercontent.playertraces.domain.TraceKind
import com.bettercontent.playertraces.network.TraceQueryResponsePacket
import com.bettercontent.playertraces.network.TraceAnnotationsSeenPacket
import net.minecraft.core.BlockPos
import net.minecraft.network.FriendlyByteBuf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import io.netty.buffer.Unpooled
import java.nio.file.Path
import java.nio.file.Files
import java.nio.ByteBuffer
import java.util.UUID
import java.util.zip.CRC32
import com.bettercontent.playertraces.util.TraceShardId
import com.bettercontent.playertraces.config.TracesConfig
import com.bettercontent.playertraces.storage.TraceStorageManager
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.phys.Vec3

class TraceCoreTest {
    private val guidanceViewer: UUID = UUID.nameUUIDFromBytes("guidance-viewer".toByteArray())
    private val guidanceAuthor: UUID = UUID.nameUUIDFromBytes("guidance-author".toByteArray())

    private fun routeTrace(id: String, sequence: String, pos: BlockPos, index: Int): FootTrace = FootTrace(
        id = UUID.nameUUIDFromBytes(id.toByteArray()),
        levelKey = "minecraft:overworld",
        blockPos = pos,
        movementClass = MovementClass.WALK,
        strength = 1f,
        sequenceId = UUID.nameUUIDFromBytes(sequence.toByteArray()),
        sequenceIndex = index,
        createdAt = 1,
        sequenceEpoch = 1,
        surviving = true,
        sourcePlayerInternal = guidanceAuthor,
    )

    private fun routeNote(id: String, pos: BlockPos, creator: UUID = guidanceAuthor, revision: Int = 1): TraceAnnotation = TraceAnnotation(
        id = UUID.nameUUIDFromBytes(id.toByteArray()),
        text = id,
        icon = "pin",
        color = 0xFFFFFF,
        position = pos,
        targetBlock = pos,
        team = GLOBAL_TEAM,
        revision = revision,
        createdByInternal = creator,
    )

    @Test
    fun dimensionStorageRetainsNamespaceAndLivesInsideWorld(@TempDir dir: Path) {
        val first = TraceStorageManager.dimensionRoot(dir, ResourceLocation("moda", "moon"))
        val second = TraceStorageManager.dimensionRoot(dir, ResourceLocation("modb", "moon"))
        assertEquals(dir.resolve("data/traces/moda/moon").toAbsolutePath().normalize(), first)
        assertEquals(dir.resolve("data/traces/modb/moon").toAbsolutePath().normalize(), second)
        assertFalse(first == second)
    }

    @Test
    fun rendererGeometryContractUsesFiniteQuads() {
        assertEquals(1_000_000_000, TracesClientRenderer.MAX_RENDERED_FOOTPRINTS)
        assertEquals(64, FootprintRenderCache.CELL_SIZE_BLOCKS)
        assertTrue(TraceVisualModel.validPrimitiveCount(TraceVisualModel.PIN_VERTEX_COUNT, TraceVisualModel.FOOTPRINT_VERTICES_PER_PRIMITIVE))
        assertTrue(TraceVisualModel.validPrimitiveCount(220 * TraceVisualModel.GUIDANCE_VERTICES_PER_PRIMITIVE, TraceVisualModel.GUIDANCE_VERTICES_PER_PRIMITIVE))
        assertTrue(!TraceVisualModel.validPrimitiveCount(47, TraceVisualModel.FOOTPRINT_VERTICES_PER_PRIMITIVE))
        assertEquals(0.25, SurfaceAnchorResolver.FOOTPRINT_WIDTH, 0.0001)
        assertEquals(0.25, SurfaceAnchorResolver.FOOTPRINT_LENGTH, 0.0001)
        assertEquals(0.30, SurfaceAnchorResolver.ANNOTATION_SIZE, 0.0001)
        assertEquals(0.004, SurfaceAnchorResolver.ANNOTATION_ELEVATION, 0.0001)
        assertTrue(TracesClientRenderer.LABEL_SCALE >= 0.02f)
        assertTrue(TracesClientRenderer.LABEL_HEIGHT >= 0.4)
        assertEquals(0.50f, TracesClientRenderer.GUIDANCE_BASE_ALPHA)
        assertEquals(0.90f, TracesClientRenderer.GUIDANCE_PULSE_ALPHA)
        assertEquals(0.07, TracesClientRenderer.GUIDANCE_RADIUS, 0.0001)
        assertEquals(8, TracesClientRenderer.GUIDANCE_CYLINDER_SIDES)
        assertEquals(0.085, TracesClientRenderer.GUIDANCE_ELEVATION, 0.0001)
        assertEquals(0.90f, TracesClientRenderer.guidanceAlpha(0f, 0.0), 0.0001f)
        assertEquals(0.50f, TracesClientRenderer.guidanceAlpha(0.17f, 0.0), 0.0001f)
        val arrival = 0.10 / 0.22
        assertEquals(0.50f, TracesClientRenderer.guidanceAlpha(0.10f, 0.0), 0.0001f)
        assertEquals(0.90f, TracesClientRenderer.guidanceAlpha(0.10f, arrival), 0.0001f)
    }

    @Test
    fun traceRecencyPaletteUsesLoginAsItsStableDivergencePoint() {
        val login = 48_000L
        assertEquals(TraceRecencyPalette.BEFORE_RGB, TraceRecencyPalette.color(0L, login))
        assertEquals(TraceRecencyPalette.LOGIN_RGB, TraceRecencyPalette.color(login, login))
        assertEquals(TraceRecencyPalette.AFTER_RGB, TraceRecencyPalette.color(login + 24_000L, login))
        assertEquals(TraceRecencyPalette.BEFORE_RGB, TraceRecencyPalette.color(-1_000L, login))
        assertEquals(TraceRecencyPalette.AFTER_RGB, TraceRecencyPalette.color(login + 100_000L, login))
    }

    @Test
    fun visibleTraceMarksKeepAReadableDirectGeometryFloor() {
        val traces = (0..2).map {
            VisibleTraceDto("visible-$it", "own-route", MovementClass.WALK, it, 64, 0, 1f, it)
        }
        val marks = TraceVisualModel.marks(traces, referenceDensity = 8f, minimumAlpha = 0.07f, limit = 20)
        assertTrue(marks.isNotEmpty())
        assertTrue(marks.all { it.alpha == 0.50f })
    }

    @Test
    fun traceSightOverlayUsesExactEasedTransitionDurations() {
        val hidden = TraceSightOverlayTransition()
        val entering = hidden.retarget(active = true, nowMillis = 1_000)
        assertEquals(0f, entering.valueAt(1_000), 0.0001f)
        assertEquals(0.5f, entering.valueAt(1_100), 0.0001f)
        assertEquals(1f, entering.valueAt(1_200), 0.0001f)

        val leaving = entering.retarget(active = false, nowMillis = 1_200)
        assertEquals(0.5f, leaving.valueAt(1_275), 0.0001f)
        assertEquals(0f, leaving.valueAt(1_350), 0.0001f)
    }

    @Test
    fun traceSightOverlayStyleIsBoundedAndFallsTowardCenter() {
        assertEquals(58, TraceSightOverlayModel.vignetteSpan(640, 360))
        assertEquals(112, TraceSightOverlayModel.vignetteSpan(1_280, 720))
        val alphas = (0 until TraceSightOverlayModel.VIGNETTE_BANDS).map(TraceSightOverlayModel::vignetteAlpha)
        assertEquals(TraceSightOverlayModel.VIGNETTE_OUTER_ALPHA, alphas.first(), 0.0001f)
        assertEquals(0f, alphas.last(), 0.0001f)
        assertTrue(alphas.zipWithNext().all { (outer, inner) -> outer >= inner })
        assertEquals(0, TraceSightOverlayModel.alphaByte(TraceSightOverlayModel.CENTER_DIM_ALPHA, 0f))
        assertEquals(15, TraceSightOverlayModel.alphaByte(TraceSightOverlayModel.CENTER_DIM_ALPHA, 1f))
        assertEquals(82, TraceSightOverlayModel.alphaByte(TraceSightOverlayModel.VIGNETTE_OUTER_ALPHA, 1f))
        assertEquals(0.25f, TraceSightOverlayModel.scaledAlpha(0.5f, 0.5f), 0.0001f)
        assertEquals(0f, TraceSightOverlayModel.scaledAlpha(0.5f, 0f), 0.0001f)
        assertEquals(0.5f, TraceSightOverlayModel.scaledAlpha(0.5f, 1f), 0.0001f)
    }

    @Test
    fun lifecycleMarksUseDistinctCanonicalSizes() {
        val traces = TraceKind.entries.mapIndexed { index, kind ->
            VisibleTraceDto(
                id = kind.name,
                sequenceId = "lifecycle",
                movementClass = MovementClass.WALK,
                x = index.toDouble(), y = 64.0, z = 0.0,
                facingYaw = 0f, strength = 1f, sequenceIndex = index,
                kind = kind, createdAt = 1L,
            )
        }
        val marks = TraceVisualModel.marks(traces, 8f, 0.07f, 3).associateBy { it.trace.kind }
        assertEquals(0.25f, marks.getValue(TraceKind.FOOTPRINT).width)
        assertEquals(0.44f, marks.getValue(TraceKind.ARRIVAL).width)
        assertEquals(0.42f, marks.getValue(TraceKind.DEPARTURE).width)
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
    fun movementCaptureUsesPreciseThreeQuarterBlockSpacing() {
        assertEquals(0.74, com.bettercontent.playertraces.logic.CaptureService.horizontalDistance(0.74, 0.0, 0.0, 0.0), 0.0001)
        assertEquals(0.75, com.bettercontent.playertraces.logic.CaptureService.horizontalDistance(0.45, 0.60, 0.0, 0.0), 0.0001)
        val sampled = com.bettercontent.playertraces.logic.CaptureService.sampleSegment(
            Vec3(0.0, 64.0, 0.0), Vec3(2.25, 64.3, 2.25), 0.0,
        )
        assertEquals(4, sampled.points.size)
        assertTrue(sampled.points.zipWithNext().all { (a, b) ->
            kotlin.math.abs(com.bettercontent.playertraces.logic.CaptureService.horizontalDistance(a.x, a.z, b.x, b.z) - 0.75) < 0.0001
        })
        assertTrue(sampled.points.all { it.x.isFinite() && it.y.isFinite() && it.z.isFinite() })
    }

    @Test
    fun revealedFootprintsRefreshContinuouslyWhileWalking() {
        assertEquals(5, com.bettercontent.playertraces.client.TracesClientHandlers.queryIntervalTicks(true))
        assertEquals(100, com.bettercontent.playertraces.client.TracesClientHandlers.queryIntervalTicks(false))
    }

    @Test
    fun annotationOpeningKeyIsIgnoredUntilItIsReleased() {
        val guard = OpeningKeyInputGuard(78)
        assertTrue(guard.suppressCharacterInput())
        guard.onKeyReleased(65)
        assertTrue(guard.suppressCharacterInput())
        guard.onKeyReleased(78)
        assertFalse(guard.suppressCharacterInput())
        assertFalse(OpeningKeyInputGuard(null).suppressCharacterInput())
    }

    @Test
    fun ownMarksArePrioritizedAndKeepStrongOpacity() {
        val traces = listOf(
            VisibleTraceDto("other", "route", MovementClass.WALK, 0, 64, 0, 1f, 0, false),
            VisibleTraceDto("own", "route", MovementClass.WALK, 1, 64, 0, 1f, 1, true),
        )
        val marks = TraceVisualModel.marks(traces, 8f, 0.07f, 1)
        assertEquals("own", marks.single().trace.id)
        assertEquals(0.50f, marks.single().alpha)
        assertEquals(0x35E7FF, marks.single().color)
    }

    @Test
    fun footprintRotationPreservesArbitraryPlayerYaw() {
        val trace = VisibleTraceDto(
            "yaw-37", "route", MovementClass.WALK, 0.25, 64.0, 0.75, 37f, 1f, 0, true,
        )
        val mark = TraceVisualModel.marks(listOf(trace), 8f, 0.07f, 1).single()
        val expected = Math.toRadians(37.0).toFloat() + (Math.PI / 2.0).toFloat()
        assertEquals(expected, mark.angle, 0.00001f)
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
            support = TraceSupport(BlockPos(0, 63, 0), ResourceLocation("minecraft", "stone")),
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
    fun oversizedShardIsRejectedWithoutReadingIt(@TempDir dir: Path) {
        val shardPath = dir.resolve("r.0.0.traces")
        java.io.RandomAccessFile(shardPath.toFile(), "rw").use { it.setLength(64L * 1024L * 1024L + 1L) }
        val loaded = TraceSerializer.read(shardPath)
        assertTrue(loaded.footTraces.isEmpty())
        assertFalse(Files.exists(shardPath))
        Files.list(dir).use { paths ->
            assertTrue(paths.anyMatch { it.fileName.toString().startsWith("r.0.0.traces.corrupt.") })
        }
    }

    @Test
    fun repeatedLoadsIgnoreInterruptedSiblingWrite(@TempDir dir: Path) {
        val shardPath = dir.resolve("r.0.0.traces")
        val state = stateWithTrace("durable")
        TraceSerializer.write(shardPath, state, Geometry.shardToBounds(0, 0))
        Files.writeString(dir.resolve(".r.0.0.traces.tmp"), "interrupted replacement")

        repeat(3) {
            assertEquals(state.footTraces.single().id, TraceSerializer.read(shardPath).footTraces.single().id)
        }
    }

    @Test
    fun responseDecoderRejectsInvalidCounts() {
        val buffer = FriendlyByteBuf(Unpooled.buffer())
        buffer.writeInt(-1)
        assertThrows(IllegalArgumentException::class.java) { TraceQueryResponsePacket.decode(buffer) }
    }

    @Test
    fun responseDecoderRejectsInvalidMovementStrengthAndSequence() {
        fun traceBuffer(movement: Int = 0, strength: Float = 1f, sequenceIndex: Int = 0): FriendlyByteBuf {
            return FriendlyByteBuf(Unpooled.buffer()).apply {
                writeInt(1)
                writeUtf(UUID.randomUUID().toString(), 36)
                writeUtf(UUID.randomUUID().toString(), 36)
                writeByte(movement)
                writeDouble(0.25)
                writeDouble(64.0)
                writeDouble(0.75)
                writeFloat(15f)
                writeFloat(strength)
                writeInt(sequenceIndex)
                writeBoolean(false)
                writeInt(0)
            }
        }

        assertThrows(IllegalArgumentException::class.java) {
            TraceQueryResponsePacket.decode(traceBuffer(movement = 255))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TraceQueryResponsePacket.decode(traceBuffer(strength = Float.NaN))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TraceQueryResponsePacket.decode(traceBuffer(sequenceIndex = -1))
        }
    }

    @Test
    fun responseAndAcknowledgementDecodersRejectNegativeRevisions() {
        val response = FriendlyByteBuf(Unpooled.buffer()).apply {
            writeInt(0)
            writeInt(1)
            writeUtf(UUID.randomUUID().toString(), 36)
            writeUtf("annotation", 256)
            writeUtf("pin", 64)
            writeInt(0xFFFFFF)
            writeInt(0)
            writeInt(64)
            writeInt(0)
            writeUtf(GLOBAL_TEAM.id, 64)
            writeInt(-1)
            writeBoolean(false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TraceQueryResponsePacket.decode(response)
        }

        val acknowledgement = FriendlyByteBuf(Unpooled.buffer()).apply {
            writeVarInt(1)
            writeUtf(UUID.randomUUID().toString(), 36)
            writeVarInt(-1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TraceAnnotationsSeenPacket.decode(acknowledgement)
        }
    }

    @Test
    fun serializerRejectsNegativeSeenRevision(@TempDir dir: Path) {
        val path = dir.resolve("r.0.0.traces")
        val state = TraceShardState()
        state.seenStates += com.bettercontent.playertraces.storage.SeenStateRecord(
            UUID.randomUUID(),
            UUID.randomUUID(),
            -1,
        )
        assertThrows(IllegalArgumentException::class.java) {
            TraceSerializer.write(path, state, Geometry.shardToBounds(0, 0))
        }
        assertFalse(Files.exists(path))
    }

    @Test
    fun corruptPrimaryRecoversLastVerifiedBackup(@TempDir dir: Path) {
        val shardPath = dir.resolve("r.0.0.traces")
        val prior = stateWithTrace("prior")
        TraceSerializer.write(shardPath, prior, Geometry.shardToBounds(0, 0))
        TraceSerializer.write(shardPath, stateWithTrace("replacement"), Geometry.shardToBounds(0, 0))
        assertTrue(Files.isRegularFile(dir.resolve("r.0.0.traces.bak")))

        Files.writeString(shardPath, "corrupt primary")
        val recovered = TraceSerializer.read(shardPath)
        assertEquals(prior.footTraces.single().id, recovered.footTraces.single().id)
        assertEquals(prior.footTraces.single().id, TraceSerializer.read(shardPath).footTraces.single().id)
    }

    @Test
    fun unknownNewerMajorIsRejectedWithoutRewrite(@TempDir dir: Path) {
        val shardPath = dir.resolve("r.0.0.traces")
        TraceSerializer.write(shardPath, stateWithTrace("future"), Geometry.shardToBounds(0, 0))
        val future = Files.readAllBytes(shardPath)
        ByteBuffer.wrap(future).putShort(4, 4)
        val bodySize = future.size - 12
        val crc = CRC32().also { it.update(future, 0, bodySize) }
        ByteBuffer.wrap(future).putLong(bodySize + 4, crc.value)
        Files.write(shardPath, future)
        val before = Files.readAllBytes(shardPath)

        assertThrows(TraceSerializer.UnsupportedShardVersionException::class.java) { TraceSerializer.read(shardPath) }
        assertTrue(before.contentEquals(Files.readAllBytes(shardPath)))
        assertTrue(Files.notExists(dir.resolve("r.0.0.traces.bak")))
    }

    @Test
    fun unknownRootSchemaIsRejectedWithoutRewrite(@TempDir dir: Path) {
        val root = dir.resolve("data/traces")
        Files.createDirectories(root)
        val marker = root.resolve("schema")
        Files.writeString(marker, "traces-v2\n")

        assertThrows(IllegalArgumentException::class.java) { ensureTraceSchema(root) }
        assertEquals("traces-v2\n", Files.readString(marker))
    }

    private fun stateWithTrace(seed: String): TraceShardState = TraceShardState().also { state ->
        state.footTraces += FootTrace(
            id = UUID.nameUUIDFromBytes(seed.toByteArray()),
            levelKey = "minecraft:overworld",
            blockPos = BlockPos(1, 64, 1),
            movementClass = MovementClass.WALK,
            strength = 1.0f,
            sequenceId = UUID.nameUUIDFromBytes("sequence-$seed".toByteArray()),
            sequenceIndex = 0,
            createdAt = 1,
            sequenceEpoch = 1,
            surviving = true,
            sourcePlayerInternal = UUID.nameUUIDFromBytes("player-$seed".toByteArray()),
            support = TraceSupport(BlockPos(1, 63, 1), ResourceLocation("minecraft", "stone")),
        )
    }

    @Test
    fun guidanceReturnsNoRouteForDisconnectedTraceGraph() {
        val traces = listOf(
            routeTrace("a", "seq", BlockPos(0, 64, 0), 0),
            routeTrace("b", "seq", BlockPos(50, 64, 0), 1),
        )
        val annotations = listOf(routeNote("target", BlockPos(50, 64, 0)))

        val result = GuidanceEngine.buildRoutes(traces, annotations, guidanceViewer, BlockPos(0, 64, 0), { 0 })
        assertTrue(result.routes.isEmpty())
    }

    @Test
    fun guidancePathDropsGeometryBehindThePlayer() {
        val path = listOf(
            GuidancePointDto(0.0, 64.0, 0.0),
            GuidancePointDto(5.0, 64.0, 0.0),
            GuidancePointDto(10.0, 64.0, 0.0),
        )
        val remaining = GuidancePathModel.remainingPath(path, Vec3(4.0, 64.0, 0.25))
        assertEquals(4.0, remaining.first().x, 0.0001)
        assertEquals(listOf(5.0, 10.0), remaining.drop(1).map { it.x })
        assertFalse(remaining.any { it.x < 4.0 })
        assertEquals(path, GuidancePathModel.remainingPath(path, Vec3(4.0, 70.0, 0.0)))
    }

    @Test
    fun guidanceProgressAwardsForwardDistanceOnceAndRejectsTeleportProgress() {
        val tracker = GuidanceProgressTracker()
        val playerId = UUID.randomUUID()
        fun route(start: Int) = com.bettercontent.playertraces.dto.GuidanceRouteDto(
            "target", 2, (start..9 step 3).map { GuidancePointDto(it.toDouble(), 64.0, 0.0) },
        )

        assertEquals(0, tracker.observe(playerId, Vec3(0.0, 64.0, 0.0), route(0)).experience)
        assertEquals(1, tracker.observe(playerId, Vec3(3.0, 64.0, 0.0), route(3)).experience)
        assertEquals(0, tracker.observe(playerId, Vec3(3.0, 64.0, 0.0), route(3)).experience)
        assertEquals(0, tracker.observe(playerId, Vec3(0.0, 64.0, 0.0), route(0)).experience)

        val teleportTracker = GuidanceProgressTracker()
        val longRoute = com.bettercontent.playertraces.dto.GuidanceRouteDto(
            "far", 1, listOf(GuidancePointDto(0.0, 64.0, 0.0), GuidancePointDto(30.0, 64.0, 0.0)),
        )
        val shortRoute = longRoute.copy(path = listOf(GuidancePointDto(15.0, 64.0, 0.0), GuidancePointDto(30.0, 64.0, 0.0)))
        teleportTracker.observe(playerId, Vec3(0.0, 64.0, 0.0), longRoute)
        assertEquals(0, teleportTracker.observe(playerId, Vec3(15.0, 64.0, 0.0), shortRoute).experience)
    }

    @Test
    fun guidanceReturnsPathOnlyThroughAdjacentTraces() {
        val traces = listOf(
            routeTrace("start", "seq", BlockPos(0, 64, 0), 0),
            routeTrace("left", "seq", BlockPos(3, 64, 0), 1),
            routeTrace("right", "seq", BlockPos(3, 64, 4), 2),
            routeTrace("goal", "seq", BlockPos(6, 64, 4), 3),
        )
        val annotations = listOf(routeNote("target", BlockPos(6, 64, 4)))

        val result = GuidanceEngine.buildRoutes(traces, annotations, guidanceViewer, BlockPos(0, 64, 0), { 0 })
        assertTrue(result.routes.isNotEmpty())

        val path = result.routes.first().path
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
            routeTrace("start", "seq", BlockPos(0, 64, 0), 0),
            routeTrace("goal", "seq", BlockPos(6, 64, 0), 1),
        )
        val annotations = listOf(routeNote("target", BlockPos(6, 64, 0)))

        val result = GuidanceEngine.buildRoutes(traces, annotations, guidanceViewer, BlockPos(0, 64, 0), { 0 })
        assertTrue(result.routes.isEmpty())
    }

    @Test
    fun guidanceDoesNotInventAttachmentToFarDestination() {
        val traces = (0..4).map {
            routeTrace("route-$it", "grounded", BlockPos(it, 64, 0), it)
        }
        val flightOnlyDestination = listOf(routeNote("air", BlockPos(20, 72, 20)))
        assertTrue(GuidanceEngine.buildRoutes(traces, flightOnlyDestination, guidanceViewer, BlockPos(0, 64, 0), { 0 }).routes.isEmpty())
    }

    @Test
    fun guidanceReturnsEveryChangedForeignRouteButExcludesOwnAndSeenNotes() {
        val traces = (0..10).map {
            routeTrace("multi-$it", "multi", BlockPos(it, 64, 0), it)
        }
        val changed = routeNote("changed", BlockPos(6, 64, 0), revision = 2)
        val second = routeNote("second", BlockPos(10, 64, 0), revision = 1)
        val own = routeNote("own", BlockPos(8, 64, 0), creator = guidanceViewer)
        val seen = routeNote("seen", BlockPos(9, 64, 0), revision = 3)

        val result = GuidanceEngine.buildRoutes(
            traces,
            listOf(changed, second, own, seen),
            guidanceViewer,
            BlockPos.ZERO.offset(0, 64, 0),
            { id -> if (id == seen.id) 3 else 0 },
        )

        assertEquals(2, result.totalReachable)
        assertEquals(setOf(changed.id.toString(), second.id.toString()), result.routes.map { it.targetAnnotationId }.toSet())
        assertFalse(result.truncated)
    }

    @Test
    fun guidanceMatchesTheFixedCameraVisualFixture() {
        val points = buildList<Pair<GuidancePointDto, Float>> {
            var x = -1.5
            var z = -3.0
            add(GuidancePointDto(x, 101.0, z) to 180f)
            repeat(4) { z += 0.75; add(GuidancePointDto(x, 101.0, z) to 180f) }
            val diagonalStep = 0.75 / kotlin.math.sqrt(2.0)
            repeat(8) {
                x += diagonalStep; z += diagonalStep
                add(GuidancePointDto(x, 101.0, z) to 135f)
            }
            repeat(6) { z += 0.75; add(GuidancePointDto(x, 101.0, z) to 0f) }
        }
        val sequence = UUID.nameUUIDFromBytes("fixture".toByteArray())
        val traces = points.mapIndexed { index, (point, yaw) -> FootTrace(
            UUID.nameUUIDFromBytes("fixture-$index".toByteArray()), "minecraft:overworld",
            point.x, point.y, point.z, yaw, MovementClass.WALK, 1f, sequence, index, 1, 1, true, guidanceAuthor,
        ) }
        val target = routeNote("fixture-note", BlockPos(3, 101, 9))

        val result = GuidanceEngine.buildRoutes(traces, listOf(target), guidanceViewer, BlockPos(0, 101, -8), { 0 })

        assertEquals(1, result.totalReachable)
        assertEquals(points.map { it.first }, result.routes.single().path)
        assertTrue(points.zipWithNext().all { (a, b) ->
            kotlin.math.abs(kotlin.math.hypot(b.first.x - a.first.x, b.first.z - a.first.z) - 0.75) < 0.0001
        })
        assertTrue(points.any { it.second == 180f } && points.any { it.second == 135f })
    }

    @Test
    fun traceQueryResponsePacketCarriesAnnotationSeenFlag() {
        val packet = TraceQueryResponsePacket(
            traces = listOf(
                VisibleTraceDto(
                    "t1", "seq", MovementClass.WALK, 0.5, 64.0, 0.5, 0f, 1f, 0, true,
                    kind = TraceKind.ARRIVAL, createdAt = 11L,
                ),
            ),
            annotations = listOf(
                VisibleAnnotationDto("ann", "label", "pin", 0xFF00FF, 5, 64, 6, GLOBAL_TEAM.id, 2, true, true),
            ),
            guidanceRoutes = listOf(
                com.bettercontent.playertraces.dto.GuidanceRouteDto("ann", 2, listOf(GuidancePointDto(0.25, 64.0, 0.75), GuidancePointDto(5.5, 64.0, 6.5))),
            ),
            subscriptionGeneration = 7L,
            dimension = "minecraft:overworld",
            loginGameTime = 12L,
        )
        val buffer = FriendlyByteBuf(Unpooled.buffer())
        packet.encode(buffer)
        val decoded = TraceQueryResponsePacket.decode(buffer)
        assertEquals(1, decoded.annotations.size)
        assertEquals(true, decoded.annotations.first().seen)
        assertEquals("seq", decoded.traces.first().sequenceId)
        assertTrue(decoded.traces.first().own)
        assertEquals(TraceKind.ARRIVAL, decoded.traces.first().kind)
        assertEquals(11L, decoded.traces.first().createdAt)
        assertTrue(decoded.annotations.first().canEdit)
        assertEquals(1, decoded.guidanceRoutes.size)
        assertEquals(GuidancePointDto(5.5, 64.0, 6.5), decoded.guidanceRoutes.single().path.last())
        assertEquals(7L, decoded.subscriptionGeneration)
        assertEquals("minecraft:overworld", decoded.dimension)
        assertEquals(12L, decoded.loginGameTime)
    }

    @Test
    fun traceTilePacketCarriesCompactLifecycleAndSupportMetadata() {
        val support = TraceSupport(BlockPos(4, 63, -3), ResourceLocation("minecraft", "stone"))
        val packet = com.bettercontent.playertraces.network.TraceTileSnapshotPacket(
            generation = 9L,
            dimension = "minecraft:overworld",
            chunkX = 0,
            chunkZ = -1,
            revision = 14L,
            pageIndex = 0,
            pageCount = 1,
            traces = listOf(
                VisibleTraceDto(
                    id = "server-private-id",
                    sequenceId = "server-private-sequence",
                    movementClass = MovementClass.SPRINT,
                    x = 4.25,
                    y = 64.012,
                    z = -2.75,
                    facingYaw = 37f,
                    strength = 1.25f,
                    sequenceIndex = 3,
                    kind = TraceKind.FOOTPRINT,
                    createdAt = 1_234L,
                    support = support,
                ),
            ),
        )
        val buffer = FriendlyByteBuf(Unpooled.buffer())
        packet.encode(buffer)
        val decoded = com.bettercontent.playertraces.network.TraceTileSnapshotPacket.decode(buffer)
        assertEquals(9L, decoded.generation)
        assertEquals(14L, decoded.revision)
        assertEquals("", decoded.traces.single().id)
        assertEquals(TraceKind.FOOTPRINT, decoded.traces.single().kind)
        assertEquals(1_234L, decoded.traces.single().createdAt)
        assertEquals(support, decoded.traces.single().support)
    }

    @Test
    fun annotationMutationPacketsAreBoundedAndRevisionChecked() {
        val oversized = "x".repeat(257)
        assertThrows(IllegalArgumentException::class.java) {
            com.bettercontent.playertraces.network.AnnotationCreatePacket(BlockPos.ZERO.asLong(), oversized)
                .encode(FriendlyByteBuf(Unpooled.buffer()))
        }
        val negative = FriendlyByteBuf(Unpooled.buffer()).apply {
            writeUtf(UUID.randomUUID().toString(), 36)
            writeVarInt(-1)
            writeUtf("text", 256)
        }
        assertThrows(IllegalArgumentException::class.java) {
            com.bettercontent.playertraces.network.AnnotationUpdatePacket.decode(negative)
        }
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
            routeTrace("start", "one", BlockPos(0, 64, 0), 0),
            routeTrace("goal", "two", BlockPos(1, 64, 0), 0),
        )
        val target = listOf(routeNote("target", BlockPos(1, 64, 0)))
        assertTrue(GuidanceEngine.buildRoutes(traces, target, guidanceViewer, BlockPos(0, 64, 0), { 0 }).routes.isEmpty())
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
        assertTrue(first.all {
            it.alpha.isFinite() && it.radius.isFinite() && it.angle.isFinite() &&
                it.lateralOffset.isFinite() && it.longitudinalOffset.isFinite()
        })
        assertTrue(first.all { kotlin.math.abs(it.lateralOffset) in 0.15f..0.17f })
        val bySequence = first.sortedBy { it.trace.sequenceIndex }
        assertTrue(bySequence.zipWithNext().all { (a, b) -> a.lateralOffset * b.lateralOffset < 0f })
        val byAge = first.sortedBy { it.trace.sequenceIndex }
        assertTrue(byAge.all { it.alpha == 0.50f })
    }

    @Test
    fun preciseStepsNeedNoSyntheticLongitudinalOffset() {
        val traces = listOf(
            VisibleTraceDto("step-0", "stride", MovementClass.WALK, 0, 64, 0, 1f, 0),
            VisibleTraceDto("step-1", "stride", MovementClass.WALK, 0, 64, 0, 1f, 1),
        )
        val marks = TraceVisualModel.marks(traces, 8f, 0.07f, 10).sortedBy { it.trace.sequenceIndex }
        assertTrue(marks.all { it.longitudinalOffset == 0f })
        assertEquals(-TraceVisualModel.STEP_LATERAL_OFFSET, marks[0].lateralOffset, 0.0001f)
        assertEquals(TraceVisualModel.STEP_LATERAL_OFFSET, marks[1].lateralOffset, 0.0001f)
    }
}
