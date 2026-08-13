package com.bettercontent.traces.events

import com.bettercontent.traces.TracesMod
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.entity.player.PlayerEvent
import net.minecraftforge.event.server.ServerStartedEvent
import net.minecraftforge.event.server.ServerStoppingEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.event.entity.living.LivingDeathEvent
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
        TracesMod.getRuntime(player.server).onPlayerTick(player)
    }

    @SubscribeEvent
    fun onPlayerChangedDimension(event: PlayerEvent.PlayerChangedDimensionEvent) {
        val player = event.entity as? ServerPlayer ?: return
        TracesMod.getRuntime(player.server).onPlayerChangedDimension(player)
    }

    @SubscribeEvent
    fun onPlayerDeath(event: LivingDeathEvent) {
        val player = event.entity as? ServerPlayer ?: return
        TracesMod.getRuntime(player.server).onPlayerDeath(player, event.source.msgId)
    }

    @SubscribeEvent
    fun onLogin(event: net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent) {
        val player = event.entity as? ServerPlayer ?: return
        val level = player.serverLevel()
        val runtime = TracesMod.getRuntime(player.server)
        if (com.bettercontent.traces.config.TracesConfig.common.devVisualFixture.get() || java.lang.Boolean.getBoolean("traces.visualValidation")) {
            runtime.seedVisualFixture(level, player)
        }
        val guidance = runtime.guidance(level).query(player)
        player.sendSystemMessage(Component.literal("Reveal Traces loaded. Press 'G' to toggle trace sight; aim at a block and press 'N' to place an annotation."))
        if (guidance.totalReachable > 0) {
            player.sendSystemMessage(Component.literal(
                "${guidance.totalReachable} changed notes nearby; press G to reveal their trails."
            ))
        }
        val radius = 96.0
        val deathData = runtime.deathTraces(level)
        val pools = deathData.poolsWithin(player.x - radius, player.x + radius, player.z - radius, player.z + radius).size
        val echoes = deathData.echoesWithin(player.x - radius, player.x + radius, player.z - radius, player.z + radius).size
        if (pools > 0 || echoes > 0) {
            player.sendSystemMessage(Component.literal("$pools bloodstains and $echoes death echoes linger nearby."))
        }
    }

}
