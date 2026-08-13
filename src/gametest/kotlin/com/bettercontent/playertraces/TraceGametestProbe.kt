package com.bettercontent.playertraces

import com.mojang.authlib.GameProfile
import com.bettercontent.playertraces.config.TracesConfig
import com.bettercontent.playertraces.domain.FootTrace
import com.bettercontent.playertraces.domain.MovementClass
import com.bettercontent.playertraces.domain.AnnotationComponents
import com.bettercontent.playertraces.domain.AnnotationEchoRecord
import com.bettercontent.playertraces.echo.EchoClip
import com.bettercontent.playertraces.echo.EchoClipCodec
import com.bettercontent.playertraces.echo.EchoEncoding
import com.bettercontent.playertraces.echo.EchoFrame
import com.bettercontent.playertraces.echo.EchoRoot
import com.bettercontent.playertraces.logic.AnnotationService
import com.bettercontent.playertraces.logic.ErosionService
import com.bettercontent.playertraces.storage.AnnotationEchoSavedData
import com.bettercontent.playertraces.storage.TraceStorageManager
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.block.Blocks
import net.minecraftforge.gametest.GameTestHolder
import java.util.UUID

@GameTestHolder("player_traces")
object TraceGametestProbe {

    private fun validEchoClip(): ByteArray = EchoClipCodec.encodeQuantized(
        EchoClip(
            EchoEncoding.BONE,
            EchoClip.SAMPLE_RATE,
            intArrayOf(),
            listOf(
                EchoFrame(EchoRoot(0f, 0f, 0f, 0f, 0f), FloatArray(EchoClip.BONE_CHANNEL_COUNT)),
                EchoFrame(EchoRoot(0.25f, 0f, 0f, 0.1f, 0.1f), FloatArray(EchoClip.BONE_CHANNEL_COUNT) { 0.05f }),
            ),
        ),
    )

    @GameTest(batch = "player_traces", template = "empty", timeoutTicks = 200)
    @JvmStatic
    fun persistenceSurvivesRestart(helper: GameTestHelper) {
        val level = helper.level
        val config = TracesConfig.common
        val traceId = UUID.randomUUID()
        val source = UUID.randomUUID()
        val trace = FootTrace(
            id = traceId,
            levelKey = level.dimension().location().toString(),
            blockPos = BlockPos(300, 64, 300),
            movementClass = MovementClass.WALK,
            strength = 1.0f,
            sequenceId = UUID.randomUUID(),
            sequenceIndex = 0,
            createdAt = 1,
            sequenceEpoch = 1,
            surviving = true,
            sourcePlayerInternal = source,
        )

        val writer = TraceStorageManager(level, config)
        val boundsMin = trace.blockPos.offset(-1, 0, -1)
        val boundsMax = trace.blockPos.offset(1, 0, 1)

        try {
            writer.addFootTrace(trace)
            writer.tickFlush()
            writer.close()

            val reader = TraceStorageManager(level, config)
            try {
                val loaded = reader.queryTraces(boundsMin, boundsMax)
                helper.assertTrue(loaded.isNotEmpty(), "loaded trace batch size=${loaded.size}, ids=${loaded.map { it.id }}")
                helper.assertTrue(loaded.any { it.id == traceId }, "trace should persist across storage manager reload")
                helper.succeed()
            } finally {
                reader.close()
            }
        } finally {
            // no-op
        }
    }

    @GameTest(batch = "player_traces", template = "empty", timeoutTicks = 200)
    @JvmStatic
    fun waterErasesTraces(helper: GameTestHelper) {
        val level = helper.level
        val storage = TraceStorageManager(level, TracesConfig.common)
        val trace = FootTrace(
            id = UUID.randomUUID(),
            levelKey = level.dimension().location().toString(),
            blockPos = BlockPos(600, 64, 600),
            movementClass = MovementClass.WALK,
            strength = 1.0f,
            sequenceId = UUID.randomUUID(),
            sequenceIndex = 1,
            createdAt = 1,
            sequenceEpoch = 1,
            surviving = true,
            sourcePlayerInternal = UUID.randomUUID(),
        )

        try {
            storage.addFootTrace(trace)
            storage.tickFlush()
            helper.assertTrue(storage.queryTraces(trace.blockPos, trace.blockPos).isNotEmpty(), "precondition: trace written")

            ErosionService(storage, TracesConfig.common).onFluidTick(trace.blockPos)
            helper.assertTrue(storage.queryTraces(trace.blockPos, trace.blockPos).isEmpty(), "water should remove nearby traces")
            helper.succeed()
        } finally {
            storage.close()
        }
    }

    @GameTest(batch = "player_traces", template = "empty", timeoutTicks = 260)
    @JvmStatic
    fun rainWeakensExposedTrace(helper: GameTestHelper) {
        val level = helper.level
        val storage = TraceStorageManager(level, TracesConfig.common)
        val erosion = ErosionService(storage, TracesConfig.common)
        val trace = FootTrace(
            id = UUID.randomUUID(),
            levelKey = level.dimension().location().toString(),
            blockPos = BlockPos(900, 64, 900),
            movementClass = MovementClass.WALK,
            strength = 1.0f,
            sequenceId = UUID.randomUUID(),
            sequenceIndex = 0,
            createdAt = 1,
            sequenceEpoch = 1,
            surviving = true,
            sourcePlayerInternal = UUID.randomUUID(),
        )

        try {
            level.setWeatherParameters(0, 200, true, false)
            level.rainLevel = 1f
            level.oRainLevel = 1f
            storage.addFootTrace(trace)
            storage.tickFlush()

            val before = storage.queryTraces(trace.blockPos, trace.blockPos).first()
            erosion.tick(level, 80)
            val after = storage.queryTraces(trace.blockPos, trace.blockPos).first()

            helper.assertTrue(after.strength < before.strength || !after.surviving, "exposed rain should reduce trace strength")
            helper.succeed()
        } finally {
            level.setWeatherParameters(0, 0, false, false)
            storage.close()
        }
    }

    @GameTest(batch = "player_traces", template = "empty", timeoutTicks = 260)
    @JvmStatic
    fun rainShelteredTraceRemains(helper: GameTestHelper) {
        val level = helper.level
        val storage = TraceStorageManager(level, TracesConfig.common)
        val erosion = ErosionService(storage, TracesConfig.common)
        val trace = FootTrace(
            id = UUID.randomUUID(),
            levelKey = level.dimension().location().toString(),
            blockPos = BlockPos(1200, 64, 1200),
            movementClass = MovementClass.WALK,
            strength = 1.0f,
            sequenceId = UUID.randomUUID(),
            sequenceIndex = 0,
            createdAt = 1,
            sequenceEpoch = 1,
            surviving = true,
            sourcePlayerInternal = UUID.randomUUID(),
        )

        val coverPos = BlockPos(trace.blockPos.x, trace.blockPos.y + 30, trace.blockPos.z)

        try {
            level.setBlockAndUpdate(coverPos, Blocks.STONE.defaultBlockState())
            level.setWeatherParameters(0, 200, true, false)

            storage.addFootTrace(trace)
            storage.tickFlush()

            val before = storage.queryTraces(trace.blockPos, trace.blockPos).first()
            erosion.tick(level, 80)
            val after = storage.queryTraces(trace.blockPos, trace.blockPos).first()
            helper.assertTrue(after.strength >= before.strength * 0.99f, "sheltered trace should remain stable")
            helper.succeed()
        } finally {
            level.setBlockAndUpdate(coverPos, Blocks.AIR.defaultBlockState())
            level.setWeatherParameters(0, 0, false, false)
            storage.close()
        }
    }

    @GameTest(batch = "player_traces", template = "empty", timeoutTicks = 220)
    @JvmStatic
    fun annotationPersistsAfterTargetBlockReplaced(helper: GameTestHelper) {
        val level = helper.level
        val storage = TraceStorageManager(level, TracesConfig.common)
        val service = AnnotationService(storage)
        val player = helper.makeMockSurvivalPlayer()
        val target = player.blockPosition().offset(2, 0, 0)

        try {
            val annotation = service.create(level, player, "probe", "pin", 0x55D66B, target)
            level.setBlockAndUpdate(target, Blocks.STONE.defaultBlockState())

            val boundsMin = target.offset(-1, 0, -1)
            val boundsMax = target.offset(1, 0, 1)
            val visible = service.annotationsWithin(level, boundsMin, boundsMax, player)

            helper.assertTrue(visible.annotations.any { it.id == annotation.id }, "annotation should persist when block is replaced")
            helper.succeed()
        } finally {
            storage.close()
        }
    }

    @GameTest(batch = "player_traces", template = "empty", timeoutTicks = 220)
    @JvmStatic
    fun globalTeamVisibleToSecondPlayer(helper: GameTestHelper) {
        val level = helper.level
        val storage = TraceStorageManager(level, TracesConfig.common)
        val service = AnnotationService(storage)
        val author = helper.makeMockSurvivalPlayer()
        val viewer = helper.makeMockSurvivalPlayer()
        val position = author.blockPosition().offset(2, 0, 0)

        try {
            val created = service.create(level, author, "team check", "pin", 0x579DFF, position)

            val visible = service.annotationsWithin(
                level,
                position.offset(-4, 0, -4),
                position.offset(4, 64, 4),
                viewer,
            )

            helper.assertTrue(
                visible.annotations.any { it.id == created.id },
                "global-team annotations should be visible to all players"
            )
            helper.succeed()
        } finally {
            storage.close()
        }
    }


    @GameTest(batch = "player_traces", template = "empty", timeoutTicks = 220)
    @JvmStatic
    fun annotationOwnershipAndRevisionAreEnforced(helper: GameTestHelper) {
        val level = helper.level
        val storage = TraceStorageManager(level, TracesConfig.common)
        val service = AnnotationService(storage)
        val author = helper.makeMockSurvivalPlayer()
        val viewer = helper.makeMockSurvivalPlayer()
        val target = author.blockPosition().offset(1, 0, 0)
        try {
            val created = service.create(level, author, "first", "pin", 0x579DFF, target)
            helper.assertTrue(runCatching { service.create(level, author, "duplicate", "pin", 0x579DFF, target) }.isFailure, "one creator may only have one note per block")
            helper.assertTrue(runCatching { service.update(level, viewer, created.id, 1, "stolen", null, null) }.isFailure, "other players must not update notes")
            helper.assertTrue(!service.delete(level, viewer, created.id, 1), "other players must not delete notes")
            val updated = service.update(level, author, created.id, 1, "second", null, null)
            helper.assertTrue(updated.revision == 2, "owner update should increment revision")
            helper.assertTrue(runCatching { service.update(level, author, created.id, 1, "stale", null, null) }.isFailure, "stale revisions must be rejected")
            helper.assertTrue(!service.acknowledgeViewed(viewer, created.id, 1), "stale viewed revisions must be rejected")
            helper.assertTrue(service.acknowledgeViewed(viewer, created.id, 2), "nearby current revision should be acknowledged")
            helper.assertTrue(service.seenRevision(viewer.uuid, created.id) == 2, "viewed revision should persist")
            helper.assertTrue(service.delete(level, author, created.id, 2), "owner should delete current revision")
            helper.succeed()
        } finally {
            storage.close()
        }
    }

    @GameTest(batch = "player_traces", template = "empty", timeoutTicks = 220)
    @JvmStatic
    fun annotationEchoPersistsRevisesAndDeletesAtomically(helper: GameTestHelper) {
        val level = helper.level
        val storage = TraceStorageManager(level, TracesConfig.common)
        val service = AnnotationService(storage)
        val owner = helper.makeMockSurvivalPlayer()
        val target = owner.blockPosition().offset(2, 0, 0)
        val echoes = AnnotationEchoSavedData()

        try {
            val created = service.createComponents(level, owner, "", "", 0, target, hasEcho = true)
            echoes.replace(AnnotationEchoRecord(created.id, created.revision, owner.uuid, validEchoClip()))
            val restored = AnnotationEchoSavedData.load(echoes.save(CompoundTag()))
            helper.assertTrue(restored.get(created.id)?.annotationRevision == 1, "echo should survive saved-data reload")

            val updated = service.updateComponents(level, owner, created.id, 1, "gesture", "", 0, hasEchoAfterMutation = true)
            val prior = restored.get(created.id)!!
            restored.replace(AnnotationEchoRecord(created.id, updated.revision, prior.ownerId, prior.encodedClip))
            helper.assertTrue(updated.revision == 2 && restored.get(created.id)?.annotationRevision == 2, "note and kept echo should revise together")

            helper.assertTrue(service.delete(level, owner, created.id, 2), "owner should delete the revised note")
            helper.assertTrue(restored.remove(created.id), "deleting a note should delete its echo")
            helper.assertTrue(restored.get(created.id) == null, "deleted echo must not remain queryable")
            helper.succeed()
        } finally {
            storage.close()
        }
    }

    @GameTest(batch = "player_traces", template = "empty", timeoutTicks = 220)
    @JvmStatic
    fun annotationEchoCapacityRejectsWithoutEviction(helper: GameTestHelper) {
        val echoes = AnnotationEchoSavedData()
        val owner = UUID.randomUUID()
        val clip = validEchoClip()
        repeat(AnnotationEchoRecord.MAX_PER_PLAYER) {
            echoes.replace(AnnotationEchoRecord(UUID.randomUUID(), 1, owner, clip))
        }
        val existingIds = echoes.all().map { it.annotationId }.toSet()
        val rejected = runCatching { echoes.requireCapacity(UUID.randomUUID(), owner) }.isFailure
        helper.assertTrue(rejected, "the 65th player-owned echo should be rejected")
        helper.assertTrue(echoes.count() == AnnotationEchoRecord.MAX_PER_PLAYER, "capacity rejection must not change the echo count")
        helper.assertTrue(echoes.all().map { it.annotationId }.toSet() == existingIds, "capacity rejection must not evict an existing gesture")
        helper.succeed()
    }

    @GameTest(batch = "player_traces", template = "empty", timeoutTicks = 220)
    @JvmStatic
    fun deniedEchoEditLeavesNoteAndClipUnchanged(helper: GameTestHelper) {
        val level = helper.level
        val storage = TraceStorageManager(level, TracesConfig.common)
        val service = AnnotationService(storage)
        val owner = helper.makeMockSurvivalPlayer()
        val stranger = helper.makeMockSurvivalPlayer()
        val target = owner.blockPosition().offset(2, 0, 0)
        val echoes = AnnotationEchoSavedData()

        try {
            val created = service.createComponents(level, owner, "", "pin", AnnotationComponents.colors.getValue("cyan"), target, hasEcho = true)
            val encoded = validEchoClip()
            echoes.replace(AnnotationEchoRecord(created.id, 1, owner.uuid, encoded))
            val denied = runCatching {
                service.updateComponents(level, stranger, created.id, 1, "stolen", "", 0, hasEchoAfterMutation = false)
            }.isFailure
            helper.assertTrue(denied, "another player must not replace or remove an echo")
            helper.assertTrue(storage.annotationById(created.id)?.revision == 1, "denied edit must not increment the note revision")
            helper.assertTrue(echoes.get(created.id)?.encodedClip?.contentEquals(encoded) == true, "denied edit must preserve the original clip")
            helper.succeed()
        } finally {
            storage.close()
        }
    }

    @GameTest(batch = "player_traces", template = "empty", timeoutTicks = 220)
    @JvmStatic
    fun operatorCanReplaceAnnotationEcho(helper: GameTestHelper) {
        val level = helper.level
        val storage = TraceStorageManager(level, TracesConfig.common)
        val service = AnnotationService(storage)
        val owner = helper.makeMockSurvivalPlayer()
        val target = owner.blockPosition().offset(2, 0, 0)
        val operator = object : ServerPlayer(level.server, level, GameProfile(UUID.randomUUID(), "TracesGameTestOperator")) {
            override fun hasPermissions(permissionLevel: Int): Boolean = true
        }.also {
            it.setPos(owner.x, owner.y, owner.z)
        }
        val echoes = AnnotationEchoSavedData()

        try {
            val created = service.createComponents(level, owner, "original", "", 0, target, hasEcho = true)
            echoes.replace(AnnotationEchoRecord(created.id, 1, owner.uuid, validEchoClip()))
            val updated = service.updateComponents(level, operator, created.id, 1, "moderated", "memorial", AnnotationComponents.colors.getValue("white"), hasEchoAfterMutation = true)
            echoes.replace(AnnotationEchoRecord(created.id, updated.revision, owner.uuid, validEchoClip()))
            helper.assertTrue(updated.revision == 2, "operator replacement should increment the note revision")
            helper.assertTrue(echoes.get(created.id)?.annotationRevision == 2, "operator replacement should invalidate the old echo revision")
            helper.succeed()
        } finally {
            storage.close()
        }
    }
}
