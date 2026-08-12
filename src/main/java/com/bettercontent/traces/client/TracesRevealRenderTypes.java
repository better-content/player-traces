package com.bettercontent.traces.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

public final class TracesRevealRenderTypes {
    private TracesRevealRenderTypes() {
    }

    public static RenderType reveal(ResourceLocation texture, String name, com.mojang.blaze3d.vertex.VertexFormat.Mode mode) {
        return Access.reveal(texture, name, mode);
    }

    private static final class Access extends RenderType {
        private Access() {
            super("traces_reveal_access", DefaultVertexFormat.NEW_ENTITY, com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS, 256, true, true, () -> {}, () -> {});
            throw new IllegalStateException("access-only");
        }

        private static RenderType reveal(ResourceLocation texture, String name, com.mojang.blaze3d.vertex.VertexFormat.Mode mode) {
            CompositeState state = CompositeState.builder()
                    .setShaderState(RENDERTYPE_ENTITY_TRANSLUCENT_SHADER)
                    .setTextureState(new RenderStateShard.TextureStateShard(texture, false, false))
                    .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                    .setCullState(NO_CULL)
                    .setLightmapState(LIGHTMAP)
                    .setOverlayState(OVERLAY)
                    .setDepthTestState(LEQUAL_DEPTH_TEST)
                    .setWriteMaskState(COLOR_WRITE)
                    .createCompositeState(true);
            return RenderType.create("traces_reveal_" + name, DefaultVertexFormat.NEW_ENTITY, mode, 256, true, true, state);
        }
    }
}
