package com.bettercontent.traces.client

import com.bettercontent.traces.config.TracesConfig
import com.bettercontent.traces.network.TracesNetwork
import net.minecraft.client.Minecraft
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.client.event.InputEvent
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent
import net.minecraftforge.client.event.RegisterKeyMappingsEvent
import net.minecraftforge.client.event.RenderLevelStageEvent
import net.minecraftforge.event.TickEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod

@Mod.EventBusSubscriber(value = [Dist.CLIENT], bus = Mod.EventBusSubscriber.Bus.MOD)
object TracesClientKeyBindings {
    @SubscribeEvent
    @JvmStatic
    fun onRegisterBindings(event: RegisterKeyMappingsEvent) {
        event.register(TracesClientConfig.revealToggle)
    }

    @SubscribeEvent
    @JvmStatic
    fun onRegisterReloadListeners(event: RegisterClientReloadListenersEvent) {
        event.registerReloadListener(WorldDesaturationPass)
    }
}

@Mod.EventBusSubscriber(value = [Dist.CLIENT], bus = Mod.EventBusSubscriber.Bus.FORGE)
object TracesClientHandlers {
    private var cooldown = 0

    @SubscribeEvent
    @JvmStatic
    fun onRenderLevelStage(event: RenderLevelStageEvent) {
        TracesClientRenderer.onRenderLevelStage(event)
    }

    @SubscribeEvent
    @JvmStatic
    @Suppress("UNUSED_PARAMETER")
    fun onKeyInput(event: InputEvent.Key) {
        if (TracesClientConfig.revealToggle.consumeClick()) {
            TracesClientState.toggleOverlay()
        }
    }

    @SubscribeEvent
    @JvmStatic
    fun onClientTick(_event: TickEvent.ClientTickEvent) {
        if (_event.phase != TickEvent.Phase.END) return
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        if (!TracesClientState.overlayEnabled) return
        if (cooldown > 0) {
            cooldown--
            return
        }
        TracesClientState.guidance(player.blockPosition())
        if (TracesConfig.client.visualDiagnostics.get()) {
            TracesClientLog.LOGGER.debug(
                "Requesting nearby traces: overlay={}, player={}, payloadTraces={}",
                TracesClientState.overlayEnabled,
                player.blockPosition(),
                TracesClientState.lastPayloadTraceCount,
            )
        }
        TracesNetwork.requestNearby(TracesConfig.client.maxRenderDistance.get())
        cooldown = 20
    }
}
