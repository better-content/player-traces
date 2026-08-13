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
import net.minecraft.world.phys.Vec3

class CaptureService(
    private val level: ServerLevel,
    private val storage: TraceStorageManager,
    private val config: TracesConfig.Common
) {
    private data class PlayerState(
        var lastPos: net.minecraft.world.phys.Vec3,
        var lastTick: Long,
        var sequenceId: UUID,
        var sequenceIndex: Int,
        var onGround: Boolean,
        var hasTrace: Boolean,
        var distanceSinceEmission: Double,
    )

    private val states = HashMap<UUID, PlayerState>()

    fun onPlayerTick(player: ServerPlayer): Int {
        val uid = player.uuid
        val state = states.getOrPut(uid) {
            PlayerState(player.position(), level.gameTime, UUID.randomUUID(), 0, true, false, 0.0)
        }

        val onGround = player.onGround()
        // Ground contact is the authoritative guard for footprints.  Do not also
        // trust the flight ability flag: it can remain set for a tick after a
        // Creative -> Survival change and would incorrectly suppress grounded steps.
        val suppress = player.isSwimming || player.isFallFlying || player.isSpectator
        if (suppress || !onGround) {
            state.onGround = onGround
            state.hasTrace = false
            state.distanceSinceEmission = 0.0
            state.lastPos = player.position()
            return 0
        }

        val now = level.gameTime
        if (now <= state.lastTick) {
            return 0
        }
        state.lastTick = now

        val changedDimension = player.lastPosDelta() > 8.0
        if (changedDimension) {
            state.sequenceId = UUID.randomUUID()
            state.sequenceIndex = 0
            state.hasTrace = false
            state.distanceSinceEmission = 0.0
            state.lastPos = player.position()
            return 0
        }

        val movement = when {
            player.isSprinting -> MovementClass.SPRINT
            player.isCrouching -> MovementClass.SNEAK
            (!state.onGround && onGround) -> MovementClass.JUMP_LANDING
            else -> MovementClass.WALK
        }

        val nextPos = player.position()
        val movedDistance = horizontalDistance(nextPos.x, nextPos.z, state.lastPos.x, state.lastPos.z)
        if (!movedDistance.isFinite() || movedDistance < 1.0e-6) {
            state.lastPos = nextPos
            state.onGround = onGround
            return 0
        }

        val strength = when (movement) {
            MovementClass.SPRINT -> 1.25f
            MovementClass.SNEAK -> 0.6f
            MovementClass.JUMP_LANDING -> 1.05f
            else -> 1.0f
        }

        val points = mutableListOf<Vec3>()
        if (!state.hasTrace) {
            if (state.sequenceIndex > 0) {
                state.sequenceId = UUID.randomUUID()
                state.sequenceIndex = 0
            }
            points += state.lastPos
            state.hasTrace = true
            state.distanceSinceEmission = 0.0
        }
        val sampled = sampleSegment(state.lastPos, nextPos, state.distanceSinceEmission, captureSpacing(movement))
        points += sampled.points
        state.distanceSinceEmission = sampled.distanceSinceEmission
        val yaw = player.yRot.takeIf { it.isFinite() } ?: 0f
        points.forEach { point -> storage.addFootTrace(
            FootTrace(
                id = UUID.randomUUID(),
                levelKey = level.dimension().location().toString(),
                x = point.x, y = point.y, z = point.z,
                facingYaw = yaw,
                movementClass = movement,
                strength = strength,
                sequenceId = state.sequenceId,
                sequenceIndex = state.sequenceIndex++,
                createdAt = now,
                sequenceEpoch = now,
                surviving = true,
                sourcePlayerInternal = player.uuid
            )
        ) }

        state.lastPos = nextPos
        state.onGround = onGround
        return points.size
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

    internal fun captureSpacing(@Suppress("UNUSED_PARAMETER") movementClass: MovementClass): Double = 0.75

    companion object {
        data class SamplingResult(val points: List<Vec3>, val distanceSinceEmission: Double)

        internal fun sampleSegment(start: Vec3, end: Vec3, carried: Double, spacing: Double = 0.75): SamplingResult {
            require(spacing.isFinite() && spacing > 0.0)
            require(carried.isFinite() && carried >= 0.0 && carried < spacing)
            val dx = end.x - start.x
            val dz = end.z - start.z
            val length = sqrt(dx * dx + dz * dz)
            if (!length.isFinite() || length < 1.0e-9) return SamplingResult(emptyList(), carried)
            val points = mutableListOf<Vec3>()
            var distance = spacing - carried
            while (distance <= length + 1.0e-9) {
                val t = (distance / length).coerceIn(0.0, 1.0)
                points += Vec3(start.x + (end.x - start.x) * t, start.y + (end.y - start.y) * t, start.z + (end.z - start.z) * t)
                distance += spacing
            }
            val remainder = (carried + length) % spacing
            return SamplingResult(points, if (remainder < 1.0e-9 || spacing - remainder < 1.0e-9) 0.0 else remainder)
        }

        internal fun horizontalDistance(x: Double, z: Double, priorX: Double, priorZ: Double): Double {
            val dx = x - priorX
            val dz = z - priorZ
            return sqrt(dx * dx + dz * dz)
        }
    }

    fun onDimensionTeleport(player: Player) {
        states.remove(player.uuid)
    }
}
