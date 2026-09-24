package com.bettercontent.playertraces

import com.bettercontent.playertraces.domain.FootTrace
import com.bettercontent.playertraces.domain.GLOBAL_TEAM
import com.bettercontent.playertraces.domain.MovementClass
import com.bettercontent.playertraces.domain.TraceAnnotation
import com.bettercontent.playertraces.domain.TraceKind
import com.bettercontent.playertraces.domain.TraceSupport
import com.bettercontent.playertraces.storage.SeenStateRecord
import com.bettercontent.playertraces.storage.DeferredAnnotation
import com.bettercontent.playertraces.storage.DeferredAnnotationEdit
import com.bettercontent.playertraces.storage.DeferredBlockCleanup
import com.bettercontent.playertraces.storage.BlockCleanupKind
import com.bettercontent.playertraces.storage.DeferredFootprintErosion
import com.bettercontent.playertraces.storage.DeferredSupportPrune
import com.bettercontent.playertraces.storage.DeferredReplayQueue
import com.bettercontent.playertraces.storage.DeferredNeighborhoodWeakening
import com.bettercontent.playertraces.storage.TraceArchive
import com.bettercontent.playertraces.storage.DeferredOperationJournal
import com.bettercontent.playertraces.storage.DeferredJournalState
import com.bettercontent.playertraces.storage.EvictedShardAuthority
import com.bettercontent.playertraces.storage.shouldPruneInvalidSupport
import com.bettercontent.playertraces.storage.mayReplaySeenState
import com.bettercontent.playertraces.storage.neighborhoodFitsReplayBudget
import com.bettercontent.playertraces.storage.neighborhoodShardCount
import com.bettercontent.playertraces.storage.isInsideTraceCleanupBounds
import com.bettercontent.playertraces.storage.blockCleanupAffectsTile
import com.bettercontent.playertraces.storage.overlayCleanupRevision
import com.bettercontent.playertraces.network.nextTraceTileWireRevision
import com.bettercontent.playertraces.storage.mergeDeferredAnnotations
import com.bettercontent.playertraces.storage.CaptureAdmission
import com.bettercontent.playertraces.storage.StorageOutageFrontier
import com.bettercontent.playertraces.storage.StorageOutageStatus
import com.bettercontent.playertraces.storage.TraceSerializer
import com.bettercontent.playertraces.storage.TraceShardState
import com.bettercontent.playertraces.util.Geometry
import com.bettercontent.playertraces.util.TraceShardId
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
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
    fun deferredJournalRestoresEveryAcceptedQueueAndPendingShard(@TempDir dir: Path) {
        val journal = DeferredOperationJournal(dir.resolve("deferred-operations.v1"))
        val shard = TraceShardId("minecraft:overworld", -1, 2)
        val owner = UUID.nameUUIDFromBytes("journal-owner".toByteArray())
        val noteId = UUID.nameUUIDFromBytes("journal-note".toByteArray())
        val note = TraceAnnotation(noteId, "durable", "pin", 0x35E7FF, BlockPos(-2, 63, 34),
            BlockPos(-1, 64, 34), GLOBAL_TEAM, 3, owner)
        val foot = trace("journal-foot", -1.5, 64.0, 34.5, TraceKind.FOOTPRINT,
            TraceSupport(BlockPos(-2, 63, 34), ResourceLocation("minecraft", "stone")))
        val state = TraceShardState().apply { addFootTrace(foot); putAnnotation(note) }
        state.setArchiveRevision(41)
        val authority = EvictedShardAuthority(16)
        assertTrue(authority.offer(shard, state.snapshot().first))
        assertTrue(authority.offerCapture(foot.copy(id = UUID.nameUUIDFromBytes("journal-capture".toByteArray()))))
        assertTrue(authority.deferSeen(shard, SeenStateRecord(noteId, owner, 4)))
        assertTrue(authority.deferAnnotation(shard, note.copy(id = UUID.nameUUIDFromBytes("journal-add".toByteArray()))))
        assertTrue(authority.deferAnnotationEdit(shard, note.copy(text = "edited", revision = 4)))
        assertTrue(authority.deferAnnotationRemoval(shard, UUID.nameUUIDFromBytes("journal-remove".toByteArray())))
        assertTrue(authority.deferBlockCleanup(DeferredBlockCleanup(shard, BlockPos(-2, 63, 34), BlockCleanupKind.SUPPORT_POSITION)))
        assertTrue(authority.deferFootprintErosion(shard, foot.id, foot.blockPos, 0.8))
        assertTrue(authority.deferSupportPrune(shard, -1, 2))
        assertTrue(authority.deferNeighborhoodWeakening(shard, foot.blockPos, 0, 0.9))

        journal.persist(authority.exportJournalState())
        val restored = EvictedShardAuthority(16).apply { restoreJournalState(journal.load()) }

        assertEquals(authority.snapshot().map { it.first to it.second.footTracesSnapshot() },
            restored.snapshot().map { it.first to it.second.footTracesSnapshot() })
        assertEquals(authority.captureSnapshot(), restored.captureSnapshot())
        assertEquals(authority.seenSnapshot(), restored.seenSnapshot())
        assertEquals(authority.annotationSnapshot(), restored.annotationSnapshot())
        assertEquals(authority.annotationEditSnapshot(), restored.annotationEditSnapshot())
        assertEquals(authority.annotationRemovalSnapshot(), restored.annotationRemovalSnapshot())
        assertEquals(authority.blockCleanupSnapshot(), restored.blockCleanupSnapshot())
        assertEquals(authority.footprintErosionSnapshot(), restored.footprintErosionSnapshot())
        assertEquals(authority.supportPruneSnapshot(), restored.supportPruneSnapshot())
        assertEquals(authority.neighborhoodWeakeningSnapshot(), restored.neighborhoodWeakeningSnapshot())
        assertEquals(41L, requireNotNull(restored.pendingSnapshot(shard)).first.archiveRevision(),
            "pending archive revision survives journal recovery")
    }

    @Test
    fun durableReplayCheckpointPreventsRepeatingNonIdempotentErosion(@TempDir dir: Path) {
        val journal = DeferredOperationJournal(dir.resolve("deferred-operations.v1"))
        val shard = TraceShardId("minecraft:overworld", 0, 0)
        val foot = trace("journal-erosion", 1.5, 64.0, 1.5, TraceKind.FOOTPRINT,
            TraceSupport(BlockPos(1, 63, 1), ResourceLocation("minecraft", "stone")))
        val authority = EvictedShardAuthority(4)
        val state = TraceShardState().apply { addFootTrace(foot) }
        assertTrue(authority.deferFootprintErosion(shard, foot.id, foot.blockPos, 0.5))
        val operation = authority.footprintErosionSnapshot().single()
        val replayedState = state.snapshot().first
        assertTrue(replayedState.updateTraceWeakness(operation.traceId, operation.factor))

        // This single journal replacement stands for replay commit: remove the operation
        // while publishing its result snapshot as the new recovery authority.
        authority.completeFootprintErosion(operation)
        assertTrue(authority.offer(shard, replayedState.snapshot().first))
        journal.persist(authority.exportJournalState())

        val recovered = EvictedShardAuthority(4).apply { restoreJournalState(journal.load()) }
        assertTrue(recovered.footprintErosionSnapshot().isEmpty())
        val snapshot = requireNotNull(recovered.pendingSnapshot(shard)).first
        assertEquals(0.5f, snapshot.footTracesSnapshot().single().strength)
    }

    @Test
    fun deferredJournalRejectsCorruptPayloadInsteadOfDroppingAcceptedWork() {
        val journal = DeferredOperationJournal(Path.of("deferred-operations.v1"))
        val bytes = journal.encode(DeferredJournalState())
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x01).toByte()
        assertThrows(IllegalArgumentException::class.java) { journal.decode(bytes) }
    }

    @Test
    fun deferredCleanupTransitionsAdvanceClientTileRevisionMonotonically() {
        var sent: Long? = 8L
        var storage: Long? = 8L
        var cleanupCount: Int? = 0

        fun step(nextStorage: Long, nextCleanupCount: Int): Long {
            val next = nextTraceTileWireRevision(sent, storage, nextStorage, cleanupCount, nextCleanupCount)
            sent = next
            storage = nextStorage
            cleanupCount = nextCleanupCount
            return next
        }

        assertEquals(9L, step(8L, 1))
        assertEquals(9L, step(8L, 1))
        assertEquals(10L, step(8L, 0))
        assertEquals(11L, step(9L, 0))
    }

    @Test
    fun pendingEvictionRemainsAuthoritativeAcrossReloadAndDelayedWriter(@TempDir dir: Path) {
        val id = TraceShardId("minecraft:overworld", 0, 0)
        val authority = EvictedShardAuthority()
        val first = TraceShardState().apply {
            addFootTrace(trace("evicted-a", 1.5, 64.0, 1.5, TraceKind.FOOTPRINT,
                TraceSupport(BlockPos(1, 63, 1), ResourceLocation("minecraft", "stone"))))
        }
        val oldSnapshot = first.snapshot().first
        authority.offer(id, oldSnapshot)

        // A cache miss must recover A from pending authority, not the stale disk file.
        val reloaded = requireNotNull(authority.reclaim(id))
        reloaded.addFootTrace(trace("evicted-b", 2.5, 64.0, 1.5, TraceKind.FOOTPRINT,
            TraceSupport(BlockPos(2, 63, 1), ResourceLocation("minecraft", "stone"))))
        val newerSnapshot = reloaded.snapshot().first
        authority.offer(id, newerSnapshot)

        // Model the delayed A writer completing after B was evicted again. It must not
        // erase B's pending authority; the following durable write must retain both.
        val path = dir.resolve("r.0.0.traces")
        TraceSerializer.write(path, oldSnapshot, Geometry.shardToBounds(0, 0))
        authority.complete(id, oldSnapshot)
        assertEquals(1, authority.snapshot().size)
        val pending = authority.snapshot().single().second
        TraceSerializer.write(path, pending, Geometry.shardToBounds(0, 0))
        authority.complete(id, pending)

        assertEquals(setOf(
            UUID.nameUUIDFromBytes("evicted-a".toByteArray()),
            UUID.nameUUIDFromBytes("evicted-b".toByteArray()),
        ), TraceSerializer.read(path).footTracesSnapshot().map { it.id }.toSet())
        assertTrue(authority.snapshot().isEmpty())
    }

    @Test
    fun boundedOutageBufferRejectsNewAuthorityWithoutReplacingAcceptedSnapshot() {
        val firstId = TraceShardId("minecraft:overworld", 0, 0)
        val secondId = TraceShardId("minecraft:overworld", 1, 0)
        val authority = EvictedShardAuthority(1)
        val accepted = TraceShardState().apply {
            addFootTrace(trace("accepted-before-outage", 1.5, 64.0, 1.5, TraceKind.FOOTPRINT,
                TraceSupport(BlockPos(1, 63, 1), ResourceLocation("minecraft", "stone"))))
        }.snapshot().first

        assertTrue(authority.offer(firstId, accepted))
        assertFalse(authority.offer(secondId, TraceShardState()))
        authority.recordRejected(secondId, StorageOutageFrontier.Kind.CAPTURE)
        authority.recordRejected(secondId, StorageOutageFrontier.Kind.ANNOTATION)

        assertEquals(listOf(firstId), authority.snapshot().map { it.first })
        assertEquals(setOf(UUID.nameUUIDFromBytes("accepted-before-outage".toByteArray())),
            authority.snapshot().single().second.footTracesSnapshot().map { it.id }.toSet())
        assertEquals(1L, authority.frontierSnapshot().rejectedCaptures)
        assertEquals(1L, authority.frontierSnapshot().rejectedAnnotations)
        assertEquals(setOf(secondId), authority.frontierSnapshot().affectedShards)
    }

    @Test
    fun boundedOutageBufferReclaimsAndRetriesAfterCapacityIsFreed() {
        val firstId = TraceShardId("minecraft:overworld", 0, 0)
        val secondId = TraceShardId("minecraft:overworld", 1, 0)
        val authority = EvictedShardAuthority(1)
        val first = TraceShardState()
        val second = TraceShardState()

        assertTrue(authority.offer(firstId, first))
        assertTrue(requireNotNull(authority.reclaim(firstId)).footTracesSnapshot().isEmpty())
        assertTrue(authority.offer(secondId, second))
        authority.complete(secondId, second)
        assertTrue(authority.snapshot().isEmpty(), "a successful retry releases bounded capacity")
        assertTrue(authority.offer(firstId, first), "released capacity admits a later retry")
    }

    @Test
    fun boundedCaptureQueueReplaysOnceAndSharesAuthorityCapacity() {
        val id = TraceShardId("minecraft:overworld", 0, 0)
        val trace = trace("deferred-capture", 1.5, 64.0, 1.5, TraceKind.FOOTPRINT,
            TraceSupport(BlockPos(1, 63, 1), ResourceLocation("minecraft", "stone")))
        val authority = EvictedShardAuthority(2)
        assertTrue(authority.offerCapture(trace))
        assertTrue(authority.offerCapture(trace), "retrying the same capture is idempotent")
        assertTrue(authority.offer(id, TraceShardState()), "capture and snapshot share capacity")
        assertFalse(authority.offerCapture(trace("over-capacity", 2.5, 64.0, 1.5, TraceKind.FOOTPRINT,
            TraceSupport(BlockPos(2, 63, 1), ResourceLocation("minecraft", "stone")))))
        val replay = authority.captureSnapshot().single()
        assertEquals(trace.id, replay.first)
        assertEquals(trace, replay.second)
        val shard = TraceShardState()
        assertTrue(shard.addFootTraceIfAbsent(replay.second))
        assertFalse(shard.addFootTraceIfAbsent(replay.second), "replaying the same capture does not duplicate it")
        assertEquals(1, shard.footTracesSnapshot().size)
        authority.completeCapture(replay.first)
        assertTrue(authority.offerCapture(replay.second), "capacity is released after replay")
    }

    @Test
    fun captureAdmissionConvertsReservationToBackpressureQueue() {
        val id = UUID.nameUUIDFromBytes("reserved-capture".toByteArray())
        val trace = trace("reserved-capture", 1.5, 64.0, 1.5, TraceKind.FOOTPRINT,
            TraceSupport(BlockPos(1, 63, 1), ResourceLocation("minecraft", "stone")))
        val authority = EvictedShardAuthority(1)
        assertEquals(CaptureAdmission.RESERVED, authority.reserveCapture(trace))
        assertFalse(authority.canOffer(TraceShardId("minecraft:overworld", 0, 0)))
        assertFalse(authority.canOffer(TraceShardId("minecraft:overworld", 0, 0)))
        assertTrue(authority.offerCapture(trace))
        assertEquals(listOf(id), authority.captureSnapshot().map { it.first })
        assertTrue(authority.captureSnapshot().single().second == trace)
    }

    @Test
    fun duplicateQueuedCaptureCannotOverbookOrChangePayload() {
        val trace = trace("duplicate-queued", 1.5, 64.0, 1.5, TraceKind.FOOTPRINT,
            TraceSupport(BlockPos(1, 63, 1), ResourceLocation("minecraft", "stone")))
        val authority = EvictedShardAuthority(1)
        assertEquals(CaptureAdmission.RESERVED, authority.reserveCapture(trace))
        assertTrue(authority.offerCapture(trace))
        assertEquals(CaptureAdmission.ALREADY_QUEUED, authority.reserveCapture(trace))
        val altered = trace.copy(x = 9.5)
        assertEquals(CaptureAdmission.REJECTED, authority.reserveCapture(altered))
        assertFalse(authority.canOffer(TraceShardId("minecraft:overworld", 0, 0)))
        assertEquals(listOf(trace.id), authority.captureSnapshot().map { it.first })
    }

    @Test
    fun deferredSeenStateCoalescesMonotonicallyAndSharesBoundedCapacity() {
        val shardId = TraceShardId("minecraft:overworld", 2, -1)
        val player = UUID.nameUUIDFromBytes("seen-player".toByteArray())
        val annotation = UUID.nameUUIDFromBytes("seen-annotation".toByteArray())
        val authority = EvictedShardAuthority(1)
        val first = SeenStateRecord(annotation, player, 3)

        assertTrue(authority.deferSeen(shardId, first))
        assertTrue(authority.deferSeen(shardId, SeenStateRecord(annotation, player, 7)))
        assertTrue(authority.deferSeen(shardId, SeenStateRecord(annotation, player, 5)))
        val queued = authority.seenSnapshot().single()
        assertEquals(7, queued.record.highestRevision, "older retries cannot lower seen revision")
        assertFalse(authority.canOffer(TraceShardId("minecraft:overworld", 3, -1)))
        val capture = trace("seen-capacity", 1.5, 64.0, 1.5, TraceKind.FOOTPRINT,
            TraceSupport(BlockPos(1, 63, 1), ResourceLocation("minecraft", "stone")))
        assertEquals(CaptureAdmission.REJECTED, authority.reserveCapture(capture))

        assertTrue(authority.deferSeen(shardId, SeenStateRecord(annotation, player, 8)))
        authority.completeSeen(queued)
        assertEquals(8, authority.seenSnapshot().single().record.highestRevision,
            "a stale completion cannot remove a newer queued revision")
        authority.completeSeen(authority.seenSnapshot().single())
        assertEquals(CaptureAdmission.RESERVED, authority.reserveCapture(capture),
            "replay completion releases the shared capacity")
    }

    @Test
    fun deferredAnnotationAddsAreIdempotentAndShareBoundedCapacity() {
        val authority = EvictedShardAuthority(1)
        val shard = TraceShardId("minecraft:overworld", 0, 0)
        val id = UUID.nameUUIDFromBytes("deferred-annotation".toByteArray())
        val annotation = TraceAnnotation(
            id, "Buffered", "pin", 0x123456, BlockPos(1, 64, 1), BlockPos(1, 64, 1),
            GLOBAL_TEAM, 0, UUID.nameUUIDFromBytes("creator".toByteArray()),
        )

        assertTrue(authority.deferAnnotation(shard, annotation))
        assertTrue(authority.deferAnnotation(shard, annotation), "duplicate retry keeps the accepted intent")
        assertEquals(listOf(DeferredAnnotation(shard, annotation)), authority.annotationSnapshot())
        assertEquals(CaptureAdmission.REJECTED,
            authority.reserveCapture(trace("capacity-loser", 0.5, 64.0, 0.5, TraceKind.FOOTPRINT, null)))

        val queued = authority.annotationSnapshot().single()
        val updated = annotation.copy(text = "Updated", revision = annotation.revision + 1)
        assertTrue(authority.replaceAnnotation(id, updated))
        authority.completeAnnotation(queued)
        assertEquals(listOf(DeferredAnnotation(shard, updated)), authority.annotationSnapshot(),
            "stale completion cannot remove an updated queued annotation")
        assertEquals(updated, authority.annotation(id))
        assertEquals(DeferredAnnotation(shard, updated), authority.cancelAnnotation(id))
        assertTrue(authority.annotationSnapshot().isEmpty())

        val state = TraceShardState()
        assertTrue(state.putAnnotationIfAbsent(annotation))
        assertTrue(state.putAnnotationIfAbsent(annotation), "replayed identical add is idempotent")
        assertFalse(state.putAnnotationIfAbsent(annotation.copy(text = "conflicting ID")),
            "a conflicting annotation ID must not overwrite the accepted annotation")
        assertEquals(listOf(annotation), state.annotationsSnapshot())
    }

    @Test
    fun deferredAnnotationEditsCoalesceOverlayAndYieldTheirSlotAtomicallyToRemoval() {
        val authority = EvictedShardAuthority(1)
        val shard = TraceShardId("minecraft:overworld", 0, 0)
        val id = UUID.nameUUIDFromBytes("deferred-annotation-edit".toByteArray())
        val owner = UUID.nameUUIDFromBytes("annotation-owner".toByteArray())
        val original = TraceAnnotation(
            id, "Original", "pin", 0x123456, BlockPos(1, 64, 1), BlockPos(1, 64, 1),
            GLOBAL_TEAM, 3, owner,
        )
        val firstEdit = original.copy(text = "First", revision = 4)
        val latestEdit = original.copy(text = "Latest", icon = "star", revision = 5)
        val base = listOf(original)

        assertTrue(authority.deferAnnotationEdit(shard, firstEdit))
        val stale = authority.annotationEditSnapshot().single()
        assertTrue(authority.deferAnnotationEdit(shard, latestEdit), "repeated edits coalesce without consuming another slot")
        assertEquals(latestEdit, authority.annotationEdit(id))
        authority.completeAnnotationEdit(stale)
        assertEquals(listOf(DeferredAnnotationEdit(shard, latestEdit)), authority.annotationEditSnapshot(),
            "an older replay completion must not discard a newer edit")
        assertEquals(listOf(latestEdit), mergeDeferredAnnotations(base, emptyList(), authority.annotationEditSnapshot(), emptySet()))
        assertEquals(emptyList<TraceAnnotation>(), mergeDeferredAnnotations(
            base, emptyList(), authority.annotationEditSnapshot(), setOf(id),
        ), "a queued removal wins over the edit overlay")

        assertTrue(authority.deferAnnotationRemoval(shard, id), "removal atomically replaces the queued edit at capacity one")
        assertTrue(authority.annotationEditSnapshot().isEmpty())
        assertEquals(id, authority.annotationRemovalSnapshot().single().annotationId)
        authority.completeAnnotationRemoval(authority.annotationRemoval(id)!!)
        assertEquals(CaptureAdmission.RESERVED,
            authority.reserveCapture(trace("edit-slot-released", 0.5, 64.0, 0.5, TraceKind.FOOTPRINT, null)))

        val state = TraceShardState()
        assertTrue(state.putAnnotationIfAbsent(original))
        assertTrue(state.replaceAnnotation(latestEdit))
        assertEquals(listOf(latestEdit), state.annotationsSnapshot())
        assertFalse(state.replaceAnnotation(firstEdit), "stale edits cannot overwrite a newer revision")
        assertThrows(IllegalArgumentException::class.java) {
            state.replaceAnnotation(latestEdit.copy(position = BlockPos(2, 64, 1)))
        }
    }

    @Test
    fun deferredAnnotationRemovalsAreIdempotentAndShareBoundedCapacity() {
        val authority = EvictedShardAuthority(1)
        val shard = TraceShardId("minecraft:overworld", 0, 0)
        val annotationId = UUID.nameUUIDFromBytes("deferred-annotation-removal".toByteArray())
        val removal = com.bettercontent.playertraces.storage.DeferredAnnotationRemoval(shard, annotationId)

        assertTrue(authority.deferAnnotationRemoval(shard, annotationId))
        assertTrue(authority.deferAnnotationRemoval(shard, annotationId), "retry preserves accepted deletion")
        assertEquals(listOf(removal), authority.annotationRemovalSnapshot())
        assertEquals(removal, authority.annotationRemoval(annotationId))
        assertFalse(authority.deferAnnotationRemoval(TraceShardId("minecraft:overworld", 1, 0), annotationId),
            "one annotation cannot be assigned conflicting shard identities")
        assertEquals(CaptureAdmission.REJECTED,
            authority.reserveCapture(trace("removal-capacity-loser", 0.5, 64.0, 0.5, TraceKind.FOOTPRINT, null)))

        authority.completeAnnotationRemoval(removal)
        assertTrue(authority.annotationRemovalSnapshot().isEmpty())
        assertEquals(CaptureAdmission.RESERVED, authority.reserveCapture(
            trace("removal-capacity-released", 0.5, 64.0, 0.5, TraceKind.FOOTPRINT, null)))
    }

    @Test
    fun deferredBlockCleanupCoalescesRetriesAndSharesBoundedCapacity() {
        val authority = EvictedShardAuthority(1)
        val shard = TraceShardId("minecraft:overworld", -1, 0)
        val cleanup = DeferredBlockCleanup(shard, BlockPos(-1, 63, 4), BlockCleanupKind.SUPPORT_POSITION)

        assertTrue(authority.deferBlockCleanup(cleanup))
        assertTrue(authority.deferBlockCleanup(cleanup), "repeat block event coalesces to one idempotent cleanup")
        assertEquals(listOf(cleanup), authority.blockCleanupSnapshot())
        assertFalse(authority.deferBlockCleanup(cleanup.copy(kind = BlockCleanupKind.TRACE_POSITION)),
            "distinct cleanup intent still consumes bounded queue space")
        assertEquals(CaptureAdmission.REJECTED,
            authority.reserveCapture(trace("cleanup-capacity-loser", 0.5, 64.0, 0.5, TraceKind.FOOTPRINT, null)))

        authority.completeBlockCleanup(cleanup)
        assertTrue(authority.blockCleanupSnapshot().isEmpty())
        assertTrue(authority.deferBlockCleanup(cleanup.copy(kind = BlockCleanupKind.TRACE_POSITION)),
            "completion releases queue capacity")
    }

    @Test
    fun deferredRainErosionPreservesRepeatedExposureAndOrderWithinCapacity() {
        val authority = EvictedShardAuthority(2)
        val shard = TraceShardId("minecraft:overworld", 0, 0)
        val traceId = UUID.nameUUIDFromBytes("rain-erosion".toByteArray())
        val position = BlockPos(8, 64, 8)

        assertTrue(authority.deferFootprintErosion(shard, traceId, position, 0.8))
        assertTrue(authority.deferFootprintErosion(shard, traceId, position, 0.8),
            "separate rain intervals must each weaken once rather than coalescing")
        val queued = authority.footprintErosionSnapshot()
        assertEquals(2, queued.size)
        assertTrue(queued[0].serial < queued[1].serial)
        assertEquals(CaptureAdmission.REJECTED,
            authority.reserveCapture(trace("erosion-capacity-loser", 0.5, 64.0, 0.5, TraceKind.FOOTPRINT, null)))

        authority.completeFootprintErosion(queued.first())
        assertEquals(listOf(queued.last()), authority.footprintErosionSnapshot())
        assertThrows(IllegalArgumentException::class.java) {
            authority.deferFootprintErosion(shard, traceId, position, Double.NaN)
        }
    }

    @Test
    fun deferredSupportPruneCoalescesByTileAndSharesBoundedCapacity() {
        val authority = EvictedShardAuthority(1)
        val shard = TraceShardId("minecraft:overworld", -1, 0)
        val queued = DeferredSupportPrune(shard, -1, 3)

        assertTrue(authority.deferSupportPrune(shard, -1, 3))
        assertTrue(authority.deferSupportPrune(shard, -1, 3), "repeated tile polls coalesce")
        assertEquals(listOf(queued), authority.supportPruneSnapshot())
        assertFalse(authority.deferSupportPrune(shard, -1, 4), "a distinct tile consumes another bounded slot")
        assertEquals(CaptureAdmission.REJECTED,
            authority.reserveCapture(trace("support-prune-capacity-loser", 0.5, 64.0, 0.5, TraceKind.FOOTPRINT, null)))

        authority.completeSupportPrune(queued)
        assertTrue(authority.supportPruneSnapshot().isEmpty())
        assertTrue(authority.deferSupportPrune(shard, -1, 4), "completion releases queue capacity")
    }

    @Test
    fun invalidSupportPruningNeverInspectsAnUnloadedChunk() {
        val support = TraceSupport(BlockPos(16, 63, 16), ResourceLocation("minecraft", "stone"))
        var validityChecks = 0

        assertFalse(shouldPruneInvalidSupport(support, { false }) { validityChecks++; false })
        assertEquals(0, validityChecks, "unloaded support chunks are not queried")
        assertTrue(shouldPruneInvalidSupport(support, { true }) { validityChecks++; false })
        assertFalse(shouldPruneInvalidSupport(support, { true }) { validityChecks++; true })
        assertEquals(2, validityChecks)
    }

    @Test
    fun deferredMutationReplayRotatesQueuePriorityAndProtectsAnnotationDependency() {
        val authority = EvictedShardAuthority(1)
        val expected = DeferredReplayQueue.values().toList()

        repeat(expected.size) { start ->
            val order = authority.nextReplayOrder()
            assertEquals(expected.size, order.distinct().size)
            assertEquals(expected[start], order.first())
        }
        assertEquals(expected.first(), authority.nextReplayOrder().first(), "priority rotation wraps")

        assertFalse(mayReplaySeenState(hasQueuedAnnotationAdds = true),
            "seen-state cannot replay before its queued annotation add")
        assertTrue(mayReplaySeenState(hasQueuedAnnotationAdds = false))
    }

    @Test
    fun deferredNeighborhoodWeakeningIsSequencedBoundedAndBudgetedByShardCount() {
        val authority = EvictedShardAuthority(2)
        val center = TraceShardId("minecraft:overworld", -1, 0)
        val position = BlockPos(-2, 63, 4)

        assertEquals(9L, neighborhoodShardCount(0), "radius zero preserves the existing adjacent-shard search")
        assertEquals(25L, neighborhoodShardCount(512))
        assertTrue(neighborhoodFitsReplayBudget(0, 9))
        assertFalse(neighborhoodFitsReplayBudget(512, 9), "an operation must fit one tick's shard budget")
        assertFalse(neighborhoodFitsReplayBudget(0, 0))

        assertTrue(authority.deferNeighborhoodWeakening(center, position, 0, 0.8))
        assertTrue(authority.deferNeighborhoodWeakening(center, position, 0, 0.8),
            "separate neighborhood exposures retain their order")
        val queued = authority.neighborhoodWeakeningSnapshot()
        assertEquals(2, queued.size)
        assertTrue(queued[0].serial < queued[1].serial)
        assertEquals(position, queued.first().position)
        assertEquals(CaptureAdmission.REJECTED,
            authority.reserveCapture(trace("weakening-capacity-loser", 0.5, 64.0, 0.5, TraceKind.FOOTPRINT, null)))
        assertThrows(IllegalArgumentException::class.java) {
            authority.deferNeighborhoodWeakening(center, position, 0, Double.NaN)
        }

        authority.completeNeighborhoodWeakening(queued.first())
        assertEquals(listOf(queued.last()), authority.neighborhoodWeakeningSnapshot())
    }

    @Test
    fun deferredRangeCleanupReservationIsAtomicAndReadOverlayUsesInclusiveBounds() {
        val authority = EvictedShardAuthority(2)
        val shard = TraceShardId("minecraft:overworld", 0, 0)
        val exact = DeferredBlockCleanup(shard, BlockPos.ZERO, BlockCleanupKind.TRACE_BOUNDS,
            BlockPos(0, 60, 0), BlockPos(15, 70, 15))
        val first = DeferredBlockCleanup(shard, BlockPos(1, 63, 1), BlockCleanupKind.TRACE_POSITION)
        val second = DeferredBlockCleanup(shard, BlockPos(2, 63, 2), BlockCleanupKind.TRACE_POSITION)

        assertTrue(authority.deferBlockCleanup(first))
        assertFalse(authority.deferBlockCleanups(listOf(exact, second)),
            "a multi-shard range must not be partially reserved")
        assertEquals(listOf(first), authority.blockCleanupSnapshot())

        authority.completeBlockCleanup(first)
        assertTrue(authority.deferBlockCleanups(listOf(exact, second)))
        assertTrue(authority.deferBlockCleanups(listOf(exact, second)), "retry coalesces the full accepted batch")
        assertEquals(listOf(exact, second), authority.blockCleanupSnapshot())
        assertTrue(isInsideTraceCleanupBounds(BlockPos(15, 70, 15), exact.boundsMin!!, exact.boundsMax!!))
        assertFalse(isInsideTraceCleanupBounds(BlockPos(16, 70, 15), exact.boundsMin!!, exact.boundsMax!!))
        assertTrue(blockCleanupAffectsTile(exact, 0, 0))
        assertFalse(blockCleanupAffectsTile(exact, 1, 0))
        assertEquals(9L, overlayCleanupRevision(7L, authority.blockCleanupCountForTile(shard, 0, 0)))
        assertEquals(Long.MAX_VALUE, overlayCleanupRevision(Long.MAX_VALUE, 1))
    }

    @Test
    fun captureReservationCannotEvictDirtySnapshotWithoutAnotherSlot() {
        val trace = trace("reserved-with-dirty", 1.5, 64.0, 1.5, TraceKind.FOOTPRINT,
            TraceSupport(BlockPos(1, 63, 1), ResourceLocation("minecraft", "stone")))
        val shardId = TraceShardId("minecraft:overworld", 0, 0)
        val authority = EvictedShardAuthority(1)
        assertEquals(CaptureAdmission.RESERVED, authority.reserveCapture(trace))
        assertFalse(authority.canOffer(shardId), "dirty eviction needs a second slot")
        assertFalse(authority.offer(shardId, TraceShardState()), "eviction is rejected before mutation")
        assertTrue(authority.offerCapture(trace), "the capture reservation converts safely")
        assertEquals(listOf(trace.id), authority.captureSnapshot().map { it.first })
    }

    @Test
    fun reclaimReturnsCopySoQueuedSnapshotCannotBeMutated() {
        val id = TraceShardId("minecraft:overworld", 0, 0)
        val authority = EvictedShardAuthority(1)
        val queuedWriterSnapshot = TraceShardState()
        assertTrue(authority.offer(id, queuedWriterSnapshot))

        val reclaimed = requireNotNull(authority.reclaim(id))
        reclaimed.addFootTrace(trace("reclaimed-only", 1.5, 64.0, 1.5, TraceKind.FOOTPRINT,
            TraceSupport(BlockPos(1, 63, 1), ResourceLocation("minecraft", "stone"))))

        assertTrue(queuedWriterSnapshot.footTracesSnapshot().isEmpty(), "queued writer keeps its immutable snapshot")
        assertEquals(1, reclaimed.footTracesSnapshot().size)
    }

    @Test
    fun outageStatusNamesCountsRegionsAndMemoryOnlyLimit() {
        val frontier = StorageOutageFrontier(
            rejectedCaptures = 3,
            rejectedAnnotations = 2,
            affectedShards = setOf(
                TraceShardId("minecraft:overworld", 0, 0),
                TraceShardId("minecraft:the_nether", 0, 0),
            ),
        )
        assertEquals(
            "storage outage frontier: rejected captures=3 annotations=2 affected regions=2 deferred= accepted deferred mutations durably journaled; diagnostic counters are memory-only",
            StorageOutageStatus.format(frontier),
        )
    }

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

    @Test
    fun segmentedSnapshotReplaysExistingV3PayloadExactly(@TempDir dir: Path) {
        val path = dir.resolve("r.0.0.traces")
        val state = TraceShardState()
        val support = TraceSupport(BlockPos(3, 63, 4), ResourceLocation("minecraft", "stone"))
        state.addFootTrace(trace("archive-foot", 3.25, 64.0, 4.75, TraceKind.FOOTPRINT, support))
        val annotationId = UUID.nameUUIDFromBytes("archive-note".toByteArray())
        val creator = UUID.nameUUIDFromBytes("archive-creator".toByteArray())
        state.putAnnotation(TraceAnnotation(
            annotationId, "archived note", "pin", 0x35E7FF, BlockPos(4, 64, 5), BlockPos(5, 64, 5),
            GLOBAL_TEAM, 3, creator,
        ))
        state.seenStates += SeenStateRecord(annotationId, creator, 3)
        val payload = TraceSerializer.encodeV3(state, Geometry.shardToBounds(0, 0))

        assertTrue(TraceArchive.publish(path, payload, state.archiveRevision()))
        val loaded = TraceSerializer.read(path)
        assertEquals(state.footTracesSnapshot(), loaded.footTracesSnapshot())
        assertEquals(state.annotationsSnapshot(), loaded.annotationsSnapshot())
        assertEquals(state.seenStatesSnapshot(), loaded.seenStatesSnapshot())
        assertEquals(state.tileRevisionsSnapshot(), loaded.tileRevisionsSnapshot())
        assertEquals(state.archiveRevision(), loaded.archiveRevision())
        assertTrue(Files.notExists(path), "the archive slice must not synthesize a second v3 authority")
    }

    @Test
    fun tornLatestFrameIsRejectedAndPriorManifestIsRestored(@TempDir dir: Path) {
        val path = dir.resolve("r.0.0.traces")
        val first = TraceShardState().apply {
            addFootTrace(trace("frame-a", 1.5, 64.0, 1.5, TraceKind.FOOTPRINT,
                TraceSupport(BlockPos(1, 63, 1), ResourceLocation("minecraft", "stone"))))
        }
        val second = first.snapshot().first.apply {
            addFootTrace(trace("frame-b", 2.5, 64.0, 1.5, TraceKind.FOOTPRINT,
                TraceSupport(BlockPos(2, 63, 1), ResourceLocation("minecraft", "stone"))))
        }
        TraceArchive.publish(path, TraceSerializer.encodeV3(first, Geometry.shardToBounds(0, 0)), first.archiveRevision())
        TraceArchive.publish(path, TraceSerializer.encodeV3(second, Geometry.shardToBounds(0, 0)), second.archiveRevision())
        val latestSegment = Files.list(dir).use { paths ->
            paths.iterator().asSequence()
                .filter { it.fileName.toString().contains(".seg.") }
                .maxBy { it.fileName.toString() }
        }
        val torn = Files.readAllBytes(latestSegment).also { it[it.lastIndex] = (it.last().toInt() xor 0x40).toByte() }
        Files.write(latestSegment, torn)

        val recovered = TraceSerializer.read(path)
        assertEquals(first.archiveRevision(), recovered.archiveRevision())
        assertEquals(setOf(UUID.nameUUIDFromBytes("frame-a".toByteArray())),
            recovered.footTracesSnapshot().map { it.id }.toSet())
        assertTrue(Files.isRegularFile(dir.resolve("r.0.0.traces.gap")))
        assertEquals(first.archiveRevision(), TraceArchive.readLatest(path).revision)
    }

    @Test
    fun invalidManifestRollsBackToPriorPublishedSnapshot(@TempDir dir: Path) {
        val path = dir.resolve("r.0.0.traces")
        val first = TraceShardState().apply {
            addFootTrace(trace("manifest-a", 1.5, 64.0, 1.5, TraceKind.FOOTPRINT,
                TraceSupport(BlockPos(1, 63, 1), ResourceLocation("minecraft", "stone"))))
        }
        val second = first.snapshot().first.apply {
            addFootTrace(trace("manifest-b", 2.5, 64.0, 1.5, TraceKind.FOOTPRINT,
                TraceSupport(BlockPos(2, 63, 1), ResourceLocation("minecraft", "stone"))))
        }
        TraceArchive.publish(path, TraceSerializer.encodeV3(first, Geometry.shardToBounds(0, 0)), first.archiveRevision())
        TraceArchive.publish(path, TraceSerializer.encodeV3(second, Geometry.shardToBounds(0, 0)), second.archiveRevision())
        val manifest = dir.resolve("r.0.0.traces.manifest")
        Files.write(manifest, byteArrayOf(0, 1, 2))

        val recovered = TraceSerializer.read(path)
        assertEquals(first.archiveRevision(), recovered.archiveRevision())
        assertEquals(1, recovered.footTracesSnapshot().size)
        assertTrue(Files.isRegularFile(dir.resolve("r.0.0.traces.gap")), "rollback leaves durable gap/frontier metadata")
        assertFalse(TraceArchive.publish(path, byteArrayOf(1), first.archiveRevision()))
    }

    @Test
    fun delayedOlderEvictionCannotReplaceNewerPublishedRevision(@TempDir dir: Path) {
        val path = dir.resolve("r.0.0.traces")
        val old = TraceShardState().apply {
            addFootTrace(trace("ordered-a", 1.5, 64.0, 1.5, TraceKind.FOOTPRINT,
                TraceSupport(BlockPos(1, 63, 1), ResourceLocation("minecraft", "stone"))))
        }
        val newer = old.snapshot().first.apply {
            addFootTrace(trace("ordered-b", 2.5, 64.0, 1.5, TraceKind.FOOTPRINT,
                TraceSupport(BlockPos(2, 63, 1), ResourceLocation("minecraft", "stone"))))
        }
        TraceArchive.publish(path, TraceSerializer.encodeV3(newer, Geometry.shardToBounds(0, 0)), newer.archiveRevision())

        assertFalse(TraceArchive.publish(path, TraceSerializer.encodeV3(old, Geometry.shardToBounds(0, 0)), old.archiveRevision()))
        assertEquals(2, TraceSerializer.read(path).footTracesSnapshot().size)
    }

    @Test
    fun publishRecoversBackupBeforeAllocatingSegmentSequence(@TempDir dir: Path) {
        val path = dir.resolve("r.0.0.traces")
        val first = TraceShardState().apply {
            addFootTrace(trace("backup-a", 1.5, 64.0, 1.5, TraceKind.FOOTPRINT,
                TraceSupport(BlockPos(1, 63, 1), ResourceLocation("minecraft", "stone"))))
        }
        val second = first.snapshot().first.apply {
            addFootTrace(trace("backup-b", 2.5, 64.0, 1.5, TraceKind.FOOTPRINT,
                TraceSupport(BlockPos(2, 63, 1), ResourceLocation("minecraft", "stone"))))
        }
        val third = first.snapshot().first.apply {
            addFootTrace(trace("backup-c", 3.5, 64.0, 1.5, TraceKind.FOOTPRINT,
                TraceSupport(BlockPos(3, 63, 1), ResourceLocation("minecraft", "stone"))))
        }
        TraceArchive.publish(path, TraceSerializer.encodeV3(first, Geometry.shardToBounds(0, 0)), first.archiveRevision())
        TraceArchive.publish(path, TraceSerializer.encodeV3(second, Geometry.shardToBounds(0, 0)), second.archiveRevision())
        val firstSegment = dir.resolve("r.0.0.traces.seg.000000000001")
        val firstBytes = Files.readAllBytes(firstSegment)
        Files.delete(dir.resolve("r.0.0.traces.manifest"))

        assertTrue(TraceArchive.publish(path, TraceSerializer.encodeV3(third, Geometry.shardToBounds(0, 0)), third.archiveRevision()))
        assertTrue(Files.isRegularFile(firstSegment))
        assertEquals(firstBytes.toList(), Files.readAllBytes(firstSegment).toList())
        assertTrue(Files.isRegularFile(dir.resolve("r.0.0.traces.seg.000000000002")))
        assertTrue(Files.isRegularFile(dir.resolve("r.0.0.traces.gap")))
        val loaded = TraceSerializer.read(path).footTracesSnapshot().map { it.id }.toSet()
        assertEquals(setOf(UUID.nameUUIDFromBytes("backup-a".toByteArray()), UUID.nameUUIDFromBytes("backup-c".toByteArray())), loaded)
    }

    @Test
    fun successfulPublishesBoundRetainedSnapshotsAndKeepRollback(@TempDir dir: Path) {
        val path = dir.resolve("r.0.0.traces")
        repeat(32) { revision ->
            assertTrue(TraceArchive.publish(path, byteArrayOf(revision.toByte()), revision.toLong()))
            assertEquals(revision.toByte(), TraceArchive.readLatest(path).payload.single())
            val retainedSegments = Files.list(dir).use { paths ->
                paths.filter { it.fileName.toString().startsWith("r.0.0.traces.seg.") }.count()
            }
            assertTrue(retainedSegments <= 2, "current and one rollback segment are the only retained snapshots")
            if (revision > 0) {
                assertTrue(Files.isRegularFile(dir.resolve("r.0.0.traces.manifest.bak")))
                assertTrue(Files.isRegularFile(dir.resolve("r.0.0.traces.manifest")))
            }
        }

        val newest = dir.resolve("r.0.0.traces.seg.000000000032")
        Files.write(newest, Files.readAllBytes(newest).also { it[it.lastIndex] = (it.last().toInt() xor 0x01).toByte() })
        assertEquals(30L, TraceArchive.readLatest(path).revision)
        assertEquals(30.toByte(), TraceArchive.readLatest(path).payload.single())
    }

    @Test
    fun spatialQueriesUseTileIndexAcrossLoadUpdateAndRemoval() {
        val state = TraceShardState()
        val near = trace("indexed-near", 2.5, 64.0, 2.5, TraceKind.FOOTPRINT,
            TraceSupport(BlockPos(2, 63, 2), ResourceLocation("minecraft", "stone")))
        val far = trace("indexed-far", 20.5, 64.0, 2.5, TraceKind.FOOTPRINT,
            TraceSupport(BlockPos(20, 63, 2), ResourceLocation("minecraft", "dirt")))
        state.addLoadedFootTrace(near)
        state.addFootTrace(far)
        assertEquals(listOf(near.id), state.queryTraceTile(0, 0).map { it.id })
        assertEquals(listOf(far.id), state.nearbyFootTraces(BlockPos(16, 0, 0), BlockPos(31, 255, 15)).map { it.id })

        assertTrue(state.updateTraceWeakness(near.id, 0.01))
        assertTrue(state.queryTraceTile(0, 0).isEmpty())
        assertEquals(1, state.removeFootTraces(BlockPos(16, 0, 0), BlockPos(31, 255, 15)))
        assertTrue(state.nearbyFootTraces(BlockPos(0, 0, 0), BlockPos(255, 255, 255)).isEmpty())

        val positionRemoval = trace("indexed-position-removal", 5.5, 64.0, 5.5, TraceKind.FOOTPRINT,
            TraceSupport(BlockPos(5, 63, 5), ResourceLocation("minecraft", "stone")))
        state.addFootTrace(positionRemoval)
        assertThrows(IllegalArgumentException::class.java) {
            state.updateTraceWeakness(positionRemoval.id, Double.NaN)
        }
        assertEquals(listOf(positionRemoval.id), state.queryTraceTile(0, 0).map { it.id },
            "invalid erosion must not corrupt the stored strength")
        assertEquals(1, state.removeAtPosition(positionRemoval.blockPos))
        assertTrue(state.queryTraceTile(0, 0).isEmpty(), "position cleanup also removes the indexed trace")

        val noteId = UUID.nameUUIDFromBytes("indexed-note".toByteArray())
        val creator = UUID.nameUUIDFromBytes("indexed-creator".toByteArray())
        state.putAnnotation(TraceAnnotation(noteId, "indexed", "pin", 0x35E7FF,
            BlockPos(20, 64, 2), BlockPos(20, 64, 2), GLOBAL_TEAM, 1, creator))
        assertEquals(listOf(noteId), state.nearbyAnnotations(BlockPos(16, 0, 0), BlockPos(31, 255, 15)).map { it.id })
        assertTrue(state.updateAnnotation(noteId, "updated", null, null) != null)
        val bounds = Geometry.shardToBounds(0, 0)
        val reloaded = TraceSerializer.decodeV3(TraceSerializer.encodeV3(state, bounds), Path.of("r.0.0.traces"))
        assertEquals(listOf(noteId), reloaded.nearbyAnnotations(BlockPos(16, 0, 0), BlockPos(31, 255, 15)).map { it.id })
        assertTrue(state.removeAnnotation(noteId))
        assertTrue(state.nearbyAnnotations(BlockPos(16, 0, 0), BlockPos(31, 255, 15)).isEmpty())
    }

    @Test
    fun temporalSpatialIndexMatchesReferenceAfterMutationAndReload() {
        val state = TraceShardState()
        val traces = listOf(
            trace("time-old", 2.5, 64.0, 2.5, TraceKind.FOOTPRINT,
                TraceSupport(BlockPos(2, 63, 2), ResourceLocation("minecraft", "stone"))).copy(createdAt = 10),
            trace("time-at-cutoff", 2.75, 64.0, 2.75, TraceKind.FOOTPRINT,
                TraceSupport(BlockPos(2, 63, 2), ResourceLocation("minecraft", "stone"))).copy(createdAt = 20),
            trace("time-new", 3.5, 64.0, 3.5, TraceKind.FOOTPRINT,
                TraceSupport(BlockPos(3, 63, 3), ResourceLocation("minecraft", "stone"))).copy(createdAt = 30),
            trace("time-outside", 80.5, 64.0, 80.5, TraceKind.FOOTPRINT,
                TraceSupport(BlockPos(80, 63, 80), ResourceLocation("minecraft", "stone"))).copy(createdAt = 40),
        )
        traces.forEach(state::addLoadedFootTrace)
        val min = BlockPos(0, 0, 0)
        val max = BlockPos(16, 255, 16)
        fun reference(s: TraceShardState) = s.footTracesSnapshot()
            .filter { it.createdAt > 20 && it.surviving && it.blockPos.x in min.x..max.x && it.blockPos.y in min.y..max.y && it.blockPos.z in min.z..max.z }
            .map { it.id }
        assertEquals(reference(state), state.nearbyFootTracesAfter(min, max, 20).map { it.id })

        assertTrue(state.updateTraceWeakness(traces[1].id, 0.01))
        assertEquals(reference(state), state.nearbyFootTracesAfter(min, max, 20).map { it.id })
        state.removeFootTraces(BlockPos(0, 0, 0), BlockPos(16, 255, 16))
        assertEquals(reference(state), state.nearbyFootTracesAfter(min, max, 20).map { it.id })

        val reloaded = TraceSerializer.decodeV3(
            TraceSerializer.encodeV3(state, Geometry.shardToBounds(0, 0)), Path.of("r.0.0.traces")
        )
        assertEquals(reference(reloaded), reloaded.nearbyFootTracesAfter(min, max, 20).map { it.id })
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
