package com.bettercontent.traces.client

import net.minecraft.client.renderer.RenderType
import net.minecraft.resources.ResourceLocation

object TracesRenderTypes {
    val footprintTexture = ResourceLocation.fromNamespaceAndPath("traces", "textures/effect/boot_prints.png")
    val guidanceTexture = ResourceLocation.fromNamespaceAndPath("traces", "textures/effect/guidance.png")
    val pinTexture = ResourceLocation.fromNamespaceAndPath("traces", "textures/effect/pin.png")

    val footprints: RenderType = TracesRevealRenderTypes.reveal(footprintTexture, "footprints", com.mojang.blaze3d.vertex.VertexFormat.Mode.TRIANGLES)
    val guidance: RenderType = TracesRevealRenderTypes.reveal(pinTexture, "guidance", com.mojang.blaze3d.vertex.VertexFormat.Mode.TRIANGLES)
    val pin: RenderType = TracesRevealRenderTypes.reveal(pinTexture, "pin", com.mojang.blaze3d.vertex.VertexFormat.Mode.TRIANGLES)
}
