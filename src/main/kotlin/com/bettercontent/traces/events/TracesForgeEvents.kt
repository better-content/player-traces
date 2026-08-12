package com.bettercontent.traces.events

import com.bettercontent.traces.TracesMod
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.entity.player.PlayerEvent
import net.minecraftforge.event.level.BlockEvent
import net.minecraftforge.event.server.ServerStartedEvent
import net.minecraftforge.event.server.ServerStoppingEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
object TracesForgeEvents {
    @SubscribeEvent
    fun onServerStarted(event: ServerStartedEvent) {
        TracesMod.getRuntime(event.server)
    }

    @SubscribeEvent
    fun onServerStopping(event: ServerStoppingEvent) {
        TracesMod.removeRuntime(event.server)
    }

    @SubscribeEvent
    fun onServerTick(event: TickEvent.ServerTickEvent) {
        if (event.phase != TickEvent.Phase.END) return
        TracesMod.getRuntime(event.server).onServerTick()
    }

    @SubscribeEvent
    fun onPlayerTick(event: TickEvent.PlayerTickEvent) {
        if (event.phase != TickEvent.Phase.END) return
        val player = event.player as? ServerPlayer ?: return
        if (java.lang.Boolean.getBoolean("traces.visualValidation")) return
        TracesMod.getRuntime(player.server).onPlayerTick(player)
    }

    @SubscribeEvent
    fun onPlayerChangedDimension(event: PlayerEvent.PlayerChangedDimensionEvent) {
        val player = event.entity as? ServerPlayer ?: return
        TracesMod.getRuntime(player.server).onPlayerChangedDimension(player)
    }

    @SubscribeEvent
    fun onLogin(event: net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent) {
        val player = event.entity as? ServerPlayer ?: return
        val level = player.serverLevel()
        val runtime = TracesMod.getRuntime(player.server)
        if (com.bettercontent.traces.config.TracesConfig.common.devVisualFixture.get() || java.lang.Boolean.getBoolean("traces.visualValidation")) {
            runtime.seedVisualFixture(level, player)
        }
        val min = player.blockPosition().offset(-72, 0, -72)
        val max = player.blockPosition().offset(72, 255, 72)
        val unseen = runtime.annotations(level).annotationsWithin(level, min, max, player).unseenCount
        player.sendSystemMessage(Component.literal("Reveal Traces loaded. Press 'G' to toggle trace overlay."))
        if (unseen > 0) {
            player.sendSystemMessage(Component.literal("You have $unseen unseen nearby annotations."))
        }
    }

    @SubscribeEvent
    fun onFluidPlace(event: BlockEvent.EntityPlaceEvent) {
        val level = event.level as? net.minecraft.world.level.Level ?: return
        val serverLevel = level as? ServerLevel ?: return
        val state = event.state
        if (!state.fluidState.isEmpty) {
            val runtime = TracesMod.getRuntime(serverLevel.server)
            runtime.onFluidPlaced(serverLevel, event.pos)
        }
    }

    @SubscribeEvent
    fun onBlockFluidPlace(event: BlockEvent.FluidPlaceBlockEvent) {
        val serverLevel = event.level as? ServerLevel ?: return
        val runtime = TracesMod.getRuntime(serverLevel.server)
        runtime.onFluidPlaced(serverLevel, event.pos)
    }
}
