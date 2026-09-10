package com.bettercontent.playertraces.compat

import com.bettercontent.downedplayerrevival.api.RevivalApi
import com.bettercontent.downedplayerrevival.api.event.PlayerDownedEvent
import com.bettercontent.downedplayerrevival.api.event.PlayerRevivedEvent
import com.bettercontent.playertraces.TracesMod
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Player
import net.minecraftforge.common.MinecraftForge

/** Loaded only after Forge confirms the exact Revival API provider is present. */
internal object DownedPlayerRevivalIntegration {
    fun isDowned(player: Player): Boolean = RevivalApi.isDowned(player)

    fun register() {
        MinecraftForge.EVENT_BUS.addListener(::onPlayerDowned)
        MinecraftForge.EVENT_BUS.addListener(::onPlayerRevived)
    }

    private fun onPlayerDowned(event: PlayerDownedEvent) {
        val player = event.entity as ServerPlayer
        TracesMod.getRuntime(player.server).onPlayerDowned(player)
    }

    private fun onPlayerRevived(event: PlayerRevivedEvent) {
        val player = event.entity as ServerPlayer
        TracesMod.getRuntime(player.server).onPlayerRevived(player)
    }
}
