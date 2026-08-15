package com.bettercontent.playertraces.client

import com.bettercontent.playertraces.config.TracesConfig
import com.bettercontent.playertraces.network.TracesNetwork
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.client.event.InputEvent
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent
import net.minecraftforge.client.event.RenderGuiOverlayEvent
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
        event.register(TracesClientConfig.placeAnnotation)
    }

    @SubscribeEvent
    @JvmStatic
    fun onRegisterOverlays(event: RegisterGuiOverlaysEvent) {
        event.registerBelowAll("trace_sight") { _, graphics, _, width, height ->
            TraceSightOverlayRenderer.render(graphics, width, height)
        }
    }

}

@Mod.EventBusSubscriber(value = [Dist.CLIENT], bus = Mod.EventBusSubscriber.Bus.FORGE)
object TracesClientHandlers {
    internal const val REVEAL_QUERY_INTERVAL_TICKS = 5
    internal const val HIDDEN_QUERY_INTERVAL_TICKS = 100
    private var cooldown = 0

    @SubscribeEvent
    @JvmStatic
    fun onRenderLevelStage(event: RenderLevelStageEvent) {
        TracesClientRenderer.onRenderLevelStage(event)
    }

    @SubscribeEvent
    @JvmStatic
    fun onRenderGuiOverlay(event: RenderGuiOverlayEvent.Pre) {
        if (TraceSightOverlayModel.shouldHideHudOverlay(
                event.overlay.id(),
                TracesClientState.overlayEnabled,
                TraceSightOverlayRenderer.visibility(),
            )
        ) {
            event.isCanceled = true
        }
    }

    @SubscribeEvent
    @JvmStatic
    @Suppress("UNUSED_PARAMETER")
    fun onKeyInput(event: InputEvent.Key) {
        if (TracesClientConfig.revealToggle.consumeClick()) {
            TracesClientState.toggleOverlay()
            cooldown = 0
            Minecraft.getInstance().player?.displayClientMessage(
                Component.translatable(
                    if (TracesClientState.overlayEnabled) "message.traces.enabled" else "message.traces.disabled"
                ),
                true,
            )
        }
        if (TracesClientConfig.placeAnnotation.consumeClick()) {
            val mc = Minecraft.getInstance()
            if (mc.player != null && mc.screen == null) {
                val hit = mc.hitResult as? net.minecraft.world.phys.BlockHitResult
                if (hit != null && hit.type == net.minecraft.world.phys.HitResult.Type.BLOCK) {
                    val recent = runCatching { com.bettercontent.playertraces.client.death.DeathEchoRecorder.freezeRecentAnnotationClip() }
                    val existing = TracesClientState.visibleAnnotations().firstOrNull {
                        it.x == hit.blockPos.x && it.y == hit.blockPos.y && it.z == hit.blockPos.z
                    }
                    if (existing == null || existing.canEdit) {
                        mc.setScreen(AnnotationEditScreen(
                            hit.blockPos, existing, recent.getOrNull(), recent.exceptionOrNull()?.message.orEmpty(),
                            openingKeyCode = event.key,
                        ))
                    } else {
                        mc.player?.displayClientMessage(Component.literal("This note belongs to another player"), true)
                    }
                }
            }
        }
    }

    @SubscribeEvent
    @JvmStatic
    fun onClientTick(_event: TickEvent.ClientTickEvent) {
        if (_event.phase != TickEvent.Phase.END) return
        val mc = Minecraft.getInstance()
        TraceSightOverlayRenderer.updateTarget(
            TracesClientState.overlayEnabled && mc.level != null && mc.player != null && mc.screen == null,
        )
        val player = mc.player ?: return
        if (cooldown > 0) {
            cooldown--
            return
        }
        if (TracesConfig.client.visualDiagnostics.get()) {
            TracesClientLog.LOGGER.debug(
                "Requesting nearby traces: overlay={}, player={}, payloadTraces={}",
                TracesClientState.overlayEnabled,
                player.blockPosition(),
                TracesClientState.lastPayloadTraceCount,
            )
        }
        TracesNetwork.requestNearby(TracesConfig.client.maxRenderDistance.get())
        cooldown = queryIntervalTicks(TracesClientState.overlayEnabled)
    }

    internal fun queryIntervalTicks(overlayEnabled: Boolean): Int =
        if (overlayEnabled) REVEAL_QUERY_INTERVAL_TICKS else HIDDEN_QUERY_INTERVAL_TICKS
}
