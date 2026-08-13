package com.bettercontent.traces.logic

import com.bettercontent.traces.dto.GuidanceBuildResult
import com.bettercontent.traces.storage.TraceStorageManager
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerPlayer
import com.mojang.logging.LogUtils

class GuidanceService(
    private val storage: TraceStorageManager,
    private val progress: GuidanceProgressTracker = GuidanceProgressTracker(),
) {
    private val log = LogUtils.getLogger()

    fun query(player: ServerPlayer, radiusBlocks: Int = RADIUS_BLOCKS): GuidanceBuildResult {
        val level = player.serverLevel()
        val radius = radiusBlocks.coerceIn(1, RADIUS_BLOCKS)
        val center = player.blockPosition()
        val min = BlockPos(center.x - radius, level.minBuildHeight, center.z - radius)
        val max = BlockPos(center.x + radius, level.maxBuildHeight - 1, center.z + radius)
        val result = GuidanceEngine.buildRoutes(
            traces = storage.queryTraces(min, max),
            annotations = storage.queryAnnotations(min, max),
            viewer = player.uuid,
            player = player.position(),
            seenRevision = { storage.getSeen(player.uuid, it) },
        )
        val reward = progress.observe(player.uuid, player.position(), result.routes.firstOrNull())
        if (reward.experience > 0) {
            player.giveExperiencePoints(reward.experience)
            val route = result.routes.first()
            log.info(
                "TRACES_GUIDANCE_XP player={} annotation={} revision={} awarded={} advancedDistance={}",
                player.gameProfile.name, route.targetAnnotationId, route.targetRevision,
                reward.experience, reward.advancedDistance,
            )
        }
        return result
    }

    fun clearProgress(player: ServerPlayer) = progress.clear(player.uuid)

    companion object {
        const val RADIUS_BLOCKS = 72
    }
}
