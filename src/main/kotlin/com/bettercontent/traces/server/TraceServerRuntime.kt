package com.bettercontent.traces.server

import com.bettercontent.traces.config.TracesConfig
import com.bettercontent.traces.logic.AnnotationService
import com.bettercontent.traces.logic.CaptureService
import com.bettercontent.traces.logic.ErosionService
import com.bettercontent.traces.storage.TraceStorageManager
import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.UUID
import com.bettercontent.traces.domain.FootTrace
import com.bettercontent.traces.domain.MovementClass
import com.bettercontent.traces.domain.TraceAnnotation
import com.bettercontent.traces.domain.GLOBAL_TEAM
import com.mojang.logging.LogUtils
import net.minecraft.world.level.block.Blocks

class TraceServerRuntime(val server: MinecraftServer) {
    private val log = LogUtils.getLogger()
    private val storages = ConcurrentHashMap<String, TraceStorageManager>()
    private val captureServices = ConcurrentHashMap<String, CaptureService>()
    private val annotationServices = ConcurrentHashMap<String, AnnotationService>()
    private val erosionServices = ConcurrentHashMap<String, ErosionService>()
    private val tickCounter = AtomicInteger()

    private val config = TracesConfig.common

    private fun levelKey(level: ServerLevel) = level.dimension().location().toString()

    fun storage(level: ServerLevel): TraceStorageManager =
        storages.getOrPut(levelKey(level)) { TraceStorageManager(level, config) }

    fun capture(level: ServerLevel): CaptureService =
        captureServices.getOrPut(levelKey(level)) { CaptureService(level, storage(level), config) }

    fun annotations(level: ServerLevel): AnnotationService =
        annotationServices.getOrPut(levelKey(level)) { AnnotationService(storage(level)) }

    fun erosion(level: ServerLevel): ErosionService =
        erosionServices.getOrPut(levelKey(level)) { ErosionService(storage(level), config) }

    fun onServerTick() {
        val tick = tickCounter.incrementAndGet()
        if (tick % 20 == 0) {
            for (level in server.allLevels) {
                storage(level).tickFlush()
                erosion(level).tick(level, tick)
            }
        }
    }

    fun storageCount(): Int = storages.size

    fun onPlayerTick(player: ServerPlayer) {
        capture(player.serverLevel()).onPlayerTick(player)
    }

    fun onFluidPlaced(level: ServerLevel, blockPos: BlockPos) {
        erosion(level).onFluidTick(blockPos)
    }

    fun onPlayerChangedDimension(player: ServerPlayer) {
        capture(player.serverLevel()).onDimensionTeleport(player)
    }

    fun seedVisualFixture(level: ServerLevel, player: ServerPlayer) {
        val store = storage(level)
        val fixtureAnnotationId = UUID.nameUUIDFromBytes("traces-visual-disconnected-v10".toByteArray())
        buildVisualScene(level)
        player.teleportTo(level, 0.5, 101.0, -7.5, 0.0f, 25.0f)
        if (store.annotationById(fixtureAnnotationId) != null) {
            val fixtureAnnotations = store.queryAnnotations(BlockPos(-20, 0, -20), BlockPos(20, 255, 20)).size
            log.info(
                "TRACES_VISUAL_FIXTURE traces=persisted annotation={} nearbyAnnotations={} camera=0.5,101,-7.5 yaw=0 pitch=25",
                fixtureAnnotationId,
                fixtureAnnotations,
            )
            return
        }

        val sequence = UUID.nameUUIDFromBytes("traces-visual-route-v10".toByteArray())
        val points = buildList {
            for (z in -3..6) add(BlockPos(0, 101, z))
            for (x in 1..3) add(BlockPos(x, 101, 6))
            for (z in 7..9) add(BlockPos(3, 101, z))
        }
        points.forEachIndexed { index, pos ->
            store.addFootTrace(
                FootTrace(
                    id = UUID.nameUUIDFromBytes("traces-visual-v10-$index".toByteArray()),
                    levelKey = level.dimension().location().toString(),
                    blockPos = pos,
                    movementClass = if (index >= 10) MovementClass.SPRINT else MovementClass.WALK,
                    strength = if (index >= 10) 1.0f else 0.82f,
                    sequenceId = sequence,
                    sequenceIndex = index,
                    createdAt = level.gameTime,
                    sequenceEpoch = level.gameTime,
                    surviving = true,
                    sourcePlayerInternal = player.uuid,
                ),
            )
        }
        val annotationPos = BlockPos(-6, 101, 8)
        store.addAnnotation(
            TraceAnnotation(
                id = fixtureAnnotationId,
                text = "Unlinked note",
                icon = "pin",
                color = 0xF2B84B,
                position = annotationPos,
                targetBlock = annotationPos.below(),
                team = GLOBAL_TEAM,
                revision = 1,
                createdByInternal = player.uuid,
            ),
        )
        val fixtureAnnotations = store.queryAnnotations(BlockPos(-20, 0, -20), BlockPos(20, 255, 20)).size
        log.info("TRACES_VISUAL_FIXTURE traces={} annotation={} nearbyAnnotations={} camera=0.5,101,-7.5 yaw=0 pitch=25", points.size, fixtureAnnotationId, fixtureAnnotations)
    }

    fun disconnectVisualFixture(level: ServerLevel, player: ServerPlayer) {
        val store = storage(level)
        val connected = UUID.nameUUIDFromBytes("traces-visual-connected-v10".toByteArray())
        val disconnected = UUID.nameUUIDFromBytes("traces-visual-disconnected-v10".toByteArray())
        store.removeAnnotation(connected)
        if (store.annotationById(disconnected) == null) {
            val pos = BlockPos(-6, 101, 8)
            store.addAnnotation(
                TraceAnnotation(
                    id = disconnected,
                    text = "Unlinked note",
                    icon = "pin",
                    color = 0xF2B84B,
                    position = pos,
                    targetBlock = pos.below(),
                    team = GLOBAL_TEAM,
                    revision = 1,
                    createdByInternal = player.uuid,
                ),
            )
        }
        log.info("TRACES_VISUAL_DISCONNECTED annotation={}", disconnected)
    }

    fun occludeVisualFixture(level: ServerLevel) {
        for (x in -1..1) {
            for (y in 101..103) {
                level.setBlockAndUpdate(BlockPos(x, y, 1), Blocks.STONE.defaultBlockState())
            }
        }
        log.info("TRACES_VISUAL_OCCLUDER x=-1..1 y=101..103 z=1")
    }

    fun connectVisualFixture(level: ServerLevel, player: ServerPlayer) {
        val store = storage(level)
        val connected = UUID.nameUUIDFromBytes("traces-visual-connected-v10".toByteArray())
        val disconnected = UUID.nameUUIDFromBytes("traces-visual-disconnected-v10".toByteArray())
        store.removeAnnotation(disconnected)
        if (store.annotationById(connected) == null) {
            val pos = BlockPos(3, 101, 9)
            store.addAnnotation(
                TraceAnnotation(
                    id = connected,
                    text = "Trail workshop",
                    icon = "pin",
                    color = 0xF2B84B,
                    position = pos,
                    targetBlock = pos.below(),
                    team = GLOBAL_TEAM,
                    revision = 1,
                    createdByInternal = player.uuid,
                ),
            )
        }
        log.info("TRACES_VISUAL_CONNECTED annotation={}", connected)
    }

    private fun buildVisualScene(level: ServerLevel) {
        level.setWeatherParameters(12000, 0, false, false)
        level.setDayTime(6000L)
        for (x in -9..9) {
            for (z in -10..16) {
                for (y in 101..128) level.setBlockAndUpdate(BlockPos(x, y, z), Blocks.AIR.defaultBlockState())
                val surface = when {
                    x >= 2 -> Blocks.SMOOTH_STONE
                    z >= 9 -> Blocks.MOSS_BLOCK
                    else -> Blocks.GRASS_BLOCK
                }
                level.setBlockAndUpdate(BlockPos(x, 100, z), surface.defaultBlockState())
            }
        }
        for (z in 2..11) {
            for (y in 101..103) {
                level.setBlockAndUpdate(BlockPos(-9, y, z), Blocks.OAK_LEAVES.defaultBlockState())
                level.setBlockAndUpdate(BlockPos(9, y, z), Blocks.OAK_LEAVES.defaultBlockState())
            }
        }
        listOf(BlockPos(-8, 100, -4), BlockPos(8, 100, -4), BlockPos(-8, 100, 14), BlockPos(8, 100, 14)).forEach {
            level.setBlockAndUpdate(it, Blocks.SEA_LANTERN.defaultBlockState())
        }
    }

    fun close() {
        storages.values.forEach { it.close() }
    }
}
