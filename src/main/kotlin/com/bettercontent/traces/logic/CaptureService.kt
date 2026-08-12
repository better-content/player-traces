package com.bettercontent.traces.logic

import com.bettercontent.traces.config.TracesConfig
import com.bettercontent.traces.domain.FootTrace
import com.bettercontent.traces.domain.MovementClass
import com.bettercontent.traces.storage.TraceStorageManager
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Player
import java.util.UUID
import kotlin.math.sqrt

class CaptureService(
    private val level: ServerLevel,
    private val storage: TraceStorageManager,
    private val config: TracesConfig.Common
) {
    private data class PlayerState(
        var lastPos: net.minecraft.core.BlockPos,
        var lastTick: Long,
        var sequenceId: UUID,
        var sequenceIndex: Int,
        var onGround: Boolean,
        var hasTrace: Boolean,
        var emitPos: net.minecraft.core.BlockPos,
    )

    private val states = HashMap<UUID, PlayerState>()

    fun onPlayerTick(player: ServerPlayer) {
        val uid = player.uuid
        val state = states.getOrPut(uid) {
            PlayerState(player.blockPosition(), level.gameTime, UUID.randomUUID(), 0, true, false, player.blockPosition())
        }

        val onGround = player.onGround()
        val suppress = player.isSwimming || player.isFallFlying || player.isSpectator || player.isCreative
            || player.abilities.flying
        if (suppress || !onGround) {
            state.onGround = onGround
            state.hasTrace = false
            return
        }

        val now = level.gameTime
        if (now <= state.lastTick) {
            return
        }
        state.lastTick = now

        val changedDimension = player.lastPosDelta() > 8.0
        if (changedDimension) {
            state.sequenceId = UUID.randomUUID()
            state.sequenceIndex = 0
        }

        val movement = when {
            player.isSprinting -> MovementClass.SPRINT
            player.isCrouching -> MovementClass.SNEAK
            (!state.onGround && onGround) -> MovementClass.JUMP_LANDING
            else -> MovementClass.WALK
        }

        val nextPos = player.blockPosition()
        val dedupe = dedupeRadius(movement)
        val movedDistance = sqrt(nextPos.distSqr(state.emitPos).toDouble())
        if (movedDistance < dedupe && state.hasTrace) {
            return
        }

        val strength = when (movement) {
            MovementClass.SPRINT -> 1.25f
            MovementClass.SNEAK -> 0.6f
            MovementClass.JUMP_LANDING -> 1.05f
            else -> 1.0f
        }

        storage.addFootTrace(
            FootTrace(
                id = UUID.randomUUID(),
                levelKey = level.dimension().location().toString(),
                blockPos = player.blockPosition(),
                movementClass = movement,
                strength = strength,
                sequenceId = state.sequenceId,
                sequenceIndex = state.sequenceIndex++,
                createdAt = now,
                sequenceEpoch = now,
                surviving = true,
                sourcePlayerInternal = player.uuid
            )
        )

        state.lastPos = nextPos
        state.emitPos = nextPos
        state.onGround = onGround
        state.hasTrace = true
    }

    private fun ServerPlayer.lastPosDelta(): Double {
        return try {
            val x = xOld - this.x
            val z = zOld - this.z
            kotlin.math.sqrt(x * x + z * z)
        } catch (_: Exception) {
            0.0
        }
    }

    private fun dedupeRadius(movementClass: MovementClass): Double = when (movementClass) {
        MovementClass.SPRINT -> 0.4
        MovementClass.SNEAK -> 1.1
        MovementClass.JUMP_LANDING -> 0.5
        MovementClass.NONE -> 0.8
        MovementClass.WALK -> 0.65
    }

    fun onDimensionTeleport(player: Player) {
        states.remove(player.uuid)
    }
}
