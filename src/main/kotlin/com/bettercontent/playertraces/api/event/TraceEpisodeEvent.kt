package com.bettercontent.playertraces.api.event

import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.Level
import net.minecraftforge.eventbus.api.Event

/** A provider-owned trace journey boundary. The episode ID is stable across commit and return. */
class TraceEpisodeEvent(
    val player: ServerPlayer,
    val kind: Kind,
    val episodeId: String,
    val originDimension: ResourceKey<Level>,
    val origin: BlockPos,
) : Event() {
    enum class Kind { COMMITTED, RETURNED }
}
