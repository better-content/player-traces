package com.bettercontent.playertraces.api

import com.bettercontent.playertraces.TracesMod
import com.bettercontent.playertraces.storage.AnnotationUpdateIndex
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerPlayer

data class ReturnSummary(
    val distinctNewPathSequences: Int,
    val changedNotes: Int,
    val bloodPools: Int,
    val deathEchoes: Int,
) {
    fun hasChanges(): Boolean = distinctNewPathSequences + changedNotes + bloodPools + deathEchoes > 0
}

/** Truthful login-area changes since this player's last logout in this dimension. */
object ReturnSummaryApi {
    private const val ROOT = "PlayerTracesReturnHistory"
    private const val RADIUS = 96

    @JvmStatic fun summarize(player: ServerPlayer): ReturnSummary {
        val level = player.serverLevel()
        val key = level.dimension().location().toString()
        val history = player.persistentData.getCompound(PlayerTag.PERSISTED).getCompound(ROOT)
        val cutoff = history.getLong(key)
        val now = level.gameTime
        if (!history.contains(key) || cutoff < 0 || now < cutoff) {
            if (now < cutoff) recordLogout(player)
            return ReturnSummary(0, 0, 0, 0)
        }
        val center = player.blockPosition()
        val min = BlockPos(center.x - RADIUS, center.y - RADIUS, center.z - RADIUS)
        val max = BlockPos(center.x + RADIUS, center.y + RADIUS, center.z + RADIUS)
        val storage = TracesMod.getRuntime(player.server).storage(level)
        val paths = storage.queryTraces(min, max).asSequence()
            .filter { it.createdAt > cutoff && it.sourcePlayerInternal != player.uuid }
            .map { it.sequenceId }.distinct().count()
        val updates = AnnotationUpdateIndex.get(level)
        val notes = storage.queryAnnotations(min, max).count { annotation ->
            annotation.createdByInternal != player.uuid && (updates.updatedAt(annotation.id)?.let { it > cutoff } == true)
        }
        val deaths = TracesMod.getRuntime(player.server).deathTraces(level)
        val pools = deaths.poolsWithin(player.x - RADIUS, player.x + RADIUS, player.z - RADIUS, player.z + RADIUS)
            .count { it.createdAt > cutoff && it.ownerId != player.uuid }
        val echoes = deaths.echoesWithin(player.x - RADIUS, player.x + RADIUS, player.z - RADIUS, player.z + RADIUS)
            .count { it.createdAt > cutoff && it.ownerId != player.uuid }
        return ReturnSummary(paths, notes, pools, echoes)
    }

    @JvmStatic fun recordLogout(player: ServerPlayer) {
        val persisted = player.persistentData.getCompound(PlayerTag.PERSISTED)
        val history = persisted.getCompound(ROOT)
        history.putLong(player.serverLevel().dimension().location().toString(), player.serverLevel().gameTime)
        persisted.put(ROOT, history)
        player.persistentData.put(PlayerTag.PERSISTED, persisted)
    }

    private object PlayerTag { const val PERSISTED = "PlayerPersisted" }
}
