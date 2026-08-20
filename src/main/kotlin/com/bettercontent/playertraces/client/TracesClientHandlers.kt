package com.bettercontent.playertraces.client

import com.bettercontent.playertraces.config.TracesConfig
import com.bettercontent.playertraces.network.TracesNetwork
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.client.event.InputEvent
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent
import net.minecraftforge.client.event.RegisterKeyMappingsEvent
import net.minecraftforge.client.event.RenderLevelStageEvent
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.level.ChunkEvent
import net.minecraftforge.client.event.ClientPlayerNetworkEvent
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
                val blockHit = (mc.hitResult as? net.minecraft.world.phys.BlockHitResult)
                    ?.takeIf { it.type == net.minecraft.world.phys.HitResult.Type.BLOCK }
                val targetedAnnotation = AnnotationTargeting.pick(
                    mc.level ?: return,
                    mc.player ?: return,
                    TracesClientState.visibleAnnotations(),
                    blockHit,
                )
                val target = targetedAnnotation?.let { net.minecraft.core.BlockPos(it.x, it.y, it.z) }
                    ?: blockHit?.blockPos
                if (target != null) {
                    val recent = runCatching { com.bettercontent.playertraces.client.death.DeathEchoRecorder.freezeRecentAnnotationClip() }
                    val existing = targetedAnnotation ?: TracesClientState.visibleAnnotations().firstOrNull {
                        it.x == target.x && it.y == target.y && it.z == target.z
                    }
                    if (existing == null || existing.canEdit) {
                        mc.setScreen(AnnotationEditScreen(
                            target, existing, recent.getOrNull(), recent.exceptionOrNull()?.message.orEmpty(),
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

    @SubscribeEvent
    @JvmStatic
    fun onLogout(_event: ClientPlayerNetworkEvent.LoggingOut) {
        TracesClientState.clearTraceTiles()
    }

    @SubscribeEvent
    @JvmStatic
    fun onChunkLoaded(event: ChunkEvent.Load) {
        if (!event.level.isClientSide) return
        FootprintRenderCache.invalidateCell(event.chunk.pos.x, event.chunk.pos.z)
    }

    internal fun queryIntervalTicks(overlayEnabled: Boolean): Int =
        if (overlayEnabled) REVEAL_QUERY_INTERVAL_TICKS else HIDDEN_QUERY_INTERVAL_TICKS
}
