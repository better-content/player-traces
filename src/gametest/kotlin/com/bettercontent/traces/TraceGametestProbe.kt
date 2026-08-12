package com.bettercontent.traces

import com.bettercontent.traces.config.TracesConfig
import com.bettercontent.traces.domain.FootTrace
import com.bettercontent.traces.domain.MovementClass
import com.bettercontent.traces.logic.AnnotationService
import com.bettercontent.traces.logic.ErosionService
import com.bettercontent.traces.storage.TraceStorageManager
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.level.block.Blocks
import net.minecraftforge.gametest.GameTestHolder
import java.util.UUID

@GameTestHolder("traces")
object TraceGametestProbe {

    @GameTest(batch = "traces", template = "empty", timeoutTicks = 200)
    @JvmStatic
    fun persistenceSurvivesRestart(helper: GameTestHelper) {
        val level = helper.level
        val config = TracesConfig.common
        val traceId = UUID.randomUUID()
        val source = UUID.randomUUID()
        val trace = FootTrace(
            id = traceId,
            levelKey = level.dimension().toString(),
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

    @GameTest(batch = "traces", template = "empty", timeoutTicks = 200)
    @JvmStatic
    fun waterErasesTraces(helper: GameTestHelper) {
        val level = helper.level
        val storage = TraceStorageManager(level, TracesConfig.common)
        val trace = FootTrace(
            id = UUID.randomUUID(),
            levelKey = level.dimension().toString(),
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

    @GameTest(batch = "traces", template = "empty", timeoutTicks = 260)
    @JvmStatic
    fun rainWeakensExposedTrace(helper: GameTestHelper) {
        val level = helper.level
        val storage = TraceStorageManager(level, TracesConfig.common)
        val erosion = ErosionService(storage, TracesConfig.common)
        val trace = FootTrace(
            id = UUID.randomUUID(),
            levelKey = level.dimension().toString(),
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

    @GameTest(batch = "traces", template = "empty", timeoutTicks = 260)
    @JvmStatic
    fun rainShelteredTraceRemains(helper: GameTestHelper) {
        val level = helper.level
        val storage = TraceStorageManager(level, TracesConfig.common)
        val erosion = ErosionService(storage, TracesConfig.common)
        val trace = FootTrace(
            id = UUID.randomUUID(),
            levelKey = level.dimension().toString(),
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

    @GameTest(batch = "traces", template = "empty", timeoutTicks = 220)
    @JvmStatic
    fun annotationPersistsAfterTargetBlockReplaced(helper: GameTestHelper) {
        val level = helper.level
        val storage = TraceStorageManager(level, TracesConfig.common)
        val service = AnnotationService(storage)
        val player = helper.makeMockSurvivalPlayer()
        val target = BlockPos(1500, 64, 1500)

        try {
            val annotation = service.create(level, player, "probe", "pin", 0x44FF44, target)
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

    @GameTest(batch = "traces", template = "empty", timeoutTicks = 220)
    @JvmStatic
    fun globalTeamVisibleToSecondPlayer(helper: GameTestHelper) {
        val level = helper.level
        val storage = TraceStorageManager(level, TracesConfig.common)
        val service = AnnotationService(storage)
        val author = helper.makeMockSurvivalPlayer()
        val viewer = helper.makeMockSurvivalPlayer()
        val position = BlockPos(1800, 64, 1800)

        try {
            val created = service.create(level, author, "team check", "pin", 0x3366CC, position)

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
}
