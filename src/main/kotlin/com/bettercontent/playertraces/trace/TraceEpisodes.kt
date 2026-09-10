package com.bettercontent.playertraces.trace

import com.bettercontent.playertraces.api.event.TraceEpisodeEvent
import com.bettercontent.playertraces.storage.TraceStorageManager
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraftforge.common.MinecraftForge
import java.util.UUID

/** Owns trace journey identity and publishes domain events without knowing any consumer. */
object TraceEpisodes {
    private const val ROOT = "PlayerTracesThreadEpisode"
    private data class ReturnState(
        val dimension: ResourceKey<Level>,
        val origin: BlockPos,
        val episodeId: String,
        var armed: Boolean = false,
        var committedPublished: Boolean = false,
    )
    private val returns = mutableMapOf<UUID, ReturnState>()

    fun traceCommitted(player: ServerPlayer) {
        val state = state(player) ?: ReturnState(
            player.serverLevel().dimension(), player.blockPosition(), token(player)
        ).also { returns[player.uuid] = it; save(player, it) }
        if (state.committedPublished) return
        post(player, TraceEpisodeEvent.Kind.COMMITTED, state)
        state.committedPublished = true
        save(player, state)
    }

    fun checkReturn(player: ServerPlayer, storage: TraceStorageManager) {
        if (player.tickCount % 20 != 0) return
        val state = state(player) ?: return
        if (player.serverLevel().dimension() != state.dimension) {
            if (!state.armed) { state.armed = true; save(player, state) }
            return
        }
        val center = player.blockPosition()
        if (center.distSqr(state.origin) > 16.0 * 16.0) {
            if (!state.armed) { state.armed = true; save(player, state) }
            return
        }
        if (!state.armed) return
        val oldOwnTrace = storage.queryTraces(
            BlockPos(center.x - 8, center.y - 8, center.z - 8),
            BlockPos(center.x + 8, center.y + 8, center.z + 8),
        ).any { it.sourcePlayerInternal == player.uuid && it.createdAt <= player.serverLevel().gameTime - 6000 }
        if (oldOwnTrace) {
            returns.remove(player.uuid)
            clear(player)
            post(player, TraceEpisodeEvent.Kind.RETURNED, state)
        }
    }

    fun forget(player: ServerPlayer) { returns.remove(player.uuid) }

    private fun post(player: ServerPlayer, kind: TraceEpisodeEvent.Kind, state: ReturnState) {
        MinecraftForge.EVENT_BUS.post(TraceEpisodeEvent(player, kind, state.episodeId, state.dimension, state.origin))
    }

    private fun state(player: ServerPlayer): ReturnState? = returns[player.uuid] ?: run {
        val persisted = player.persistentData.getCompound(Player.PERSISTED_NBT_TAG)
        if (!persisted.contains(ROOT, Tag.TAG_COMPOUND.toInt())) return@run null
        val root = persisted.getCompound(ROOT)
        val dimensionId = ResourceLocation.tryParse(root.getString("dimension")) ?: return@run null
        val episodeId = root.getString("token")
        if (!validEpisodeId(episodeId)) return@run null
        ReturnState(
            ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, dimensionId),
            BlockPos.of(root.getLong("origin")),
            episodeId,
            root.getBoolean("armed"),
            root.getBoolean("committedPublished"),
        ).also { returns[player.uuid] = it }
    }

    private fun save(player: ServerPlayer, state: ReturnState) {
        val persisted = player.persistentData.getCompound(Player.PERSISTED_NBT_TAG)
        val root = CompoundTag()
        root.putString("dimension", state.dimension.location().toString())
        root.putLong("origin", state.origin.asLong())
        root.putString("token", state.episodeId)
        root.putBoolean("armed", state.armed)
        root.putBoolean("committedPublished", state.committedPublished)
        persisted.put(ROOT, root)
        player.persistentData.put(Player.PERSISTED_NBT_TAG, persisted)
    }

    private fun clear(player: ServerPlayer) {
        val persisted = player.persistentData.getCompound(Player.PERSISTED_NBT_TAG)
        persisted.remove(ROOT)
        player.persistentData.put(Player.PERSISTED_NBT_TAG, persisted)
    }

    private fun token(player: ServerPlayer) = "${player.uuid}:trace:${player.server.tickCount}"
    private fun validEpisodeId(value: String) = value.isNotBlank() && value.length <= 128 &&
        value.all { it.code in 0x21..0x7e }
}
