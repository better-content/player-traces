package com.bettercontent.playertraces.client

import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.Util
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics

object TraceSightOverlayRenderer {
    private var animation = TraceSightOverlayTransition()
    private var lastLoggedTarget: Boolean? = null

    fun updateTarget(active: Boolean, nowMillis: Long = Util.getMillis()) {
        animation = animation.retarget(active, nowMillis)
        if (lastLoggedTarget != active && java.lang.Boolean.getBoolean("traces.visualValidation")) {
            lastLoggedTarget = active
            val mc = Minecraft.getInstance()
            TracesClientLog.LOGGER.info(
                "TRACES_SIGHT_OVERLAY_TARGET active={} screenOpen={}", active, mc.screen != null,
            )
        }
    }

    fun visibility(nowMillis: Long = Util.getMillis()): Float {
        val mc = Minecraft.getInstance()
        val active = TracesClientState.overlayEnabled && mc.level != null && mc.player != null && mc.screen == null
        updateTarget(active, nowMillis)
        return animation.valueAt(nowMillis)
    }

    fun render(graphics: GuiGraphics, screenWidth: Int, screenHeight: Int, nowMillis: Long = Util.getMillis()) {
        val mc = Minecraft.getInstance()
        if (mc.screen != null || screenWidth <= 0 || screenHeight <= 0) return
        val visibility = visibility(nowMillis)
        if (visibility <= 0.001f) return

        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
        try {
            graphics.fill(
                0, 0, screenWidth, screenHeight,
                TraceSightOverlayModel.argb(0x000000, TraceSightOverlayModel.CENTER_DIM_ALPHA, visibility),
            )
            val span = TraceSightOverlayModel.vignetteSpan(screenWidth, screenHeight)
            repeat(TraceSightOverlayModel.VIGNETTE_BANDS) { band ->
                val start = band * span / TraceSightOverlayModel.VIGNETTE_BANDS
                val end = (band + 1) * span / TraceSightOverlayModel.VIGNETTE_BANDS
                val color = TraceSightOverlayModel.argb(
                    TraceSightOverlayModel.VIGNETTE_RGB,
                    TraceSightOverlayModel.vignetteAlpha(band),
                    visibility,
                )
                graphics.fill(0, start, screenWidth, end, color)
                graphics.fill(0, screenHeight - end, screenWidth, screenHeight - start, color)
                graphics.fill(start, 0, end, screenHeight, color)
                graphics.fill(screenWidth - end, 0, screenWidth - start, screenHeight, color)
            }
        } finally {
            RenderSystem.disableBlend()
        }
    }
}
