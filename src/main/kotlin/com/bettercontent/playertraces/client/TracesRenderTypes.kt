package com.bettercontent.playertraces.client

import net.minecraft.client.renderer.RenderType
import net.minecraft.resources.ResourceLocation

object TracesRenderTypes {
    private val footprintTexture = ResourceLocation.fromNamespaceAndPath("player_traces", "textures/effect/leg_contact.png")
    private val whiteTexture = ResourceLocation.fromNamespaceAndPath("minecraft", "textures/misc/white.png")
    private val bloodPoolTexture = ResourceLocation.fromNamespaceAndPath("player_traces", "textures/effect/blood_pool.png")

    val footprints: RenderType = RenderType.entityTranslucent(footprintTexture)
    private val noteTypes = com.bettercontent.playertraces.domain.AnnotationComponents.icons.associateWith { icon ->
        RenderType.entityCutoutNoCullZOffset(ResourceLocation.fromNamespaceAndPath("player_traces", "textures/effect/icon_$icon.png"))
    }
    fun note(icon: String): RenderType = noteTypes[icon] ?: noteTypes.getValue("pin")
    val guidance: RenderType = RenderType.entityTranslucent(whiteTexture)
    val bloodPools: RenderType = RenderType.entityTranslucent(bloodPoolTexture)
}
