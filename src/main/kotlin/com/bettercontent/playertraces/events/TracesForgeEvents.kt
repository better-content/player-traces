package com.bettercontent.playertraces.events

import com.bettercontent.playertraces.TracesMod
import net.minecraft.server.level.ServerPlayer
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.entity.player.PlayerEvent
import net.minecraftforge.event.server.ServerStartedEvent
import net.minecraftforge.event.server.ServerStoppingEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.event.entity.living.LivingDeathEvent
import com.bettercontent.playertraces.compat.DownedPlayerRevivalBridge
import com.bettercontent.playertraces.network.TracesNetwork
import net.minecraft.server.level.ServerLevel
import net.minecraftforge.event.level.BlockEvent
import net.minecraftforge.event.level.ExplosionEvent
import net.minecraftforge.event.level.PistonEvent
import net.minecraftforge.eventbus.api.EventPriority
import com.bettercontent.playertraces.api.ReturnSummaryApi
object TracesForgeEvents {
    @SubscribeEvent
    fun onServerStarted(event: ServerStartedEvent) {
        DownedPlayerRevivalBridge.registerIfPresent()
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
        TracesMod.getRuntime(player.server).onPlayerTick(player)
    }

    @SubscribeEvent
    fun onPlayerChangedDimension(event: PlayerEvent.PlayerChangedDimensionEvent) {
        val player = event.entity as? ServerPlayer ?: return
        TracesMod.getRuntime(player.server).onPlayerChangedDimension(player, event.from)
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    fun onPlayerDeath(event: LivingDeathEvent) {
        if (event.isCanceled) return
        val player = event.entity as? ServerPlayer ?: return
        TracesMod.getRuntime(player.server).onPlayerDeath(player, event.source.msgId)
    }

    @SubscribeEvent
    fun onLogin(event: net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent) {
        val player = event.entity as? ServerPlayer ?: return
        val level = player.serverLevel()
        val runtime = TracesMod.getRuntime(player.server)
        runtime.onPlayerLogin(player)
        TracesNetwork.onPlayerLogin(player)
        if (com.bettercontent.playertraces.config.TracesConfig.common.devVisualFixture.get() || java.lang.Boolean.getBoolean("traces.visualValidation")) {
            runtime.seedVisualFixture(level, player)
        }
    }

    @SubscribeEvent
    fun onLogout(event: PlayerEvent.PlayerLoggedOutEvent) {
        val player = event.entity as? ServerPlayer ?: return
        val runtime = TracesMod.getRuntime(player.server)
        runtime.onPlayerLogout(player)
        ReturnSummaryApi.recordLogout(player)
        TracesNetwork.onPlayerLogout(player)
    }

    @SubscribeEvent
    fun onRespawn(event: PlayerEvent.PlayerRespawnEvent) {
        val player = event.entity as? ServerPlayer ?: return
        TracesMod.getRuntime(player.server).onPlayerRespawn(player)
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    fun onBlockBroken(event: BlockEvent.BreakEvent) {
        if (event.isCanceled) return
        val level = event.level as? ServerLevel ?: return
        TracesMod.getRuntime(level.server).onSupportRemoved(level, event.pos)
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    fun onFluidPlaced(event: BlockEvent.FluidPlaceBlockEvent) {
        if (event.isCanceled) return
        val level = event.level as? ServerLevel ?: return
        TracesMod.getRuntime(level.server).onFluidPlaced(level, event.pos)
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    fun onExplosion(event: ExplosionEvent.Detonate) {
        val level = event.level as? ServerLevel ?: return
        val runtime = TracesMod.getRuntime(level.server)
        event.affectedBlocks.forEach { runtime.onSupportRemoved(level, it) }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    fun onPiston(event: PistonEvent.Pre) {
        if (event.isCanceled) return
        val level = event.level as? ServerLevel ?: return
        val resolver = event.structureHelper ?: return
        if (!resolver.resolve()) return
        val runtime = TracesMod.getRuntime(level.server)
        resolver.toPush.forEach { runtime.onSupportRemoved(level, it) }
        resolver.toDestroy.forEach { runtime.onSupportRemoved(level, it) }
    }

}
