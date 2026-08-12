package com.bettercontent.traces.client

import com.mojang.blaze3d.platform.GlStateManager
import com.mojang.blaze3d.pipeline.TextureTarget
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferBuilder
import com.mojang.blaze3d.vertex.BufferUploader
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.Tesselator
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.EffectInstance
import com.bettercontent.traces.config.TracesConfig
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.server.packs.resources.ResourceManagerReloadListener
import net.minecraft.util.profiling.ProfilerFiller
import org.joml.Matrix4f
import org.lwjgl.opengl.GL30
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

object WorldDesaturationPass : ResourceManagerReloadListener {
    private var colorCopy: TextureTarget? = null
    private var geometryCapture: TextureTarget? = null
    private var effect: EffectInstance? = null
    private var compositeEffect: EffectInstance? = null
    private var disabledAfterFailure = false
    private var appliedLogged = false
    private var captureLogged = false
    private var geometryReady = false

    fun beginGeometryCapture(mc: Minecraft): Boolean {
        if (disabledAfterFailure) return false
        val main = mc.mainRenderTarget
        if (main.width <= 0 || main.height <= 0) return false
        try {
            geometryReady = false
            val capture = ensureGeometryTarget(main.width, main.height)
            capture.setClearColor(0f, 0f, 0f, 0f)
            capture.clear(Minecraft.ON_OSX)
            blitDepth(main, capture)
            capture.bindWrite(false)
            RenderSystem.viewport(0, 0, capture.width, capture.height)
            return true
        } catch (error: Throwable) {
            disableAfterFailure(error)
            return false
        }
    }

    fun endGeometryCapture(mc: Minecraft) {
        val main = mc.mainRenderTarget
        main.bindWrite(false)
        RenderSystem.viewport(0, 0, main.width, main.height)
        geometryReady = true
        if (TracesConfig.client.visualDiagnostics.get() && !captureLogged) {
            captureLogged = true
            TracesClientLog.LOGGER.info("Traces Oculus world geometry capture active at {}x{}", main.width, main.height)
        }
    }

    fun compositeCapturedGeometry(mc: Minecraft) {
        if (disabledAfterFailure || !geometryReady) return
        val main = mc.mainRenderTarget
        val capture = geometryCapture ?: return
        if (capture.width != main.width || capture.height != main.height) return
        try {
            val shader = ensureCompositeEffect(mc.resourceManager)
            RenderSystem.viewport(0, 0, main.width, main.height)
            RenderSystem.disableDepthTest()
            RenderSystem.depthMask(false)
            shader.setSampler("DiffuseSampler") { capture.colorTextureId }
            shader.safeGetUniform("ProjMat").set(Matrix4f().setOrtho(0f, main.width.toFloat(), main.height.toFloat(), 0f, -1f, 1f))
            shader.safeGetUniform("OutSize").set(main.width.toFloat(), main.height.toFloat())
            shader.apply()
            main.bindWrite(false)
            val builder = Tesselator.getInstance().builder
            builder.begin(com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION)
            quad(builder, 0.0, 0.0, main.width.toDouble(), main.height.toDouble())
            BufferUploader.draw(builder.end())
            shader.clear()
            RenderSystem.depthMask(true)
            RenderSystem.enableDepthTest()
            main.bindWrite(false)
            geometryReady = false
        } catch (error: Throwable) {
            disableAfterFailure(error)
        }
    }

    fun apply(mc: Minecraft) {
        if (disabledAfterFailure) return
        val main = mc.mainRenderTarget
        if (main.width <= 0 || main.height <= 0) return
        try {
            val copy = ensureTarget(main.width, main.height)
            val shader = ensureEffect(mc.resourceManager)
            copyColor(main, copy)

            RenderSystem.viewport(0, 0, main.width, main.height)
            RenderSystem.disableDepthTest()
            RenderSystem.depthMask(false)
            shader.setSampler("DiffuseSampler") { copy.colorTextureId }
            shader.safeGetUniform("ProjMat").set(Matrix4f().setOrtho(0f, main.width.toFloat(), main.height.toFloat(), 0f, -1f, 1f))
            shader.safeGetUniform("OutSize").set(main.width.toFloat(), main.height.toFloat())
            shader.safeGetUniform("Desaturation").set(TracesConfig.client.worldDesaturation.get().toFloat())
            shader.apply()
            main.bindWrite(false)
            val builder = Tesselator.getInstance().builder
            builder.begin(com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION)
            quad(builder, 0.0, 0.0, main.width.toDouble(), main.height.toDouble())
            BufferUploader.draw(builder.end())
            shader.clear()
            RenderSystem.depthMask(true)
            RenderSystem.enableDepthTest()
            main.bindWrite(false)
            if (TracesConfig.client.visualDiagnostics.get() && !appliedLogged) {
                appliedLogged = true
                TracesClientLog.LOGGER.debug("World desaturation pass applied at {}x{} with amount={}", main.width, main.height, TracesConfig.client.worldDesaturation.get())
            }
        } catch (error: Throwable) {
            disableAfterFailure(error)
        }
    }

    private fun ensureTarget(width: Int, height: Int): TextureTarget {
        val current = colorCopy
        if (current != null && current.width == width && current.height == height) return current
        current?.destroyBuffers()
        return TextureTarget(width, height, false, Minecraft.ON_OSX).also { colorCopy = it }
    }

    private fun ensureGeometryTarget(width: Int, height: Int): TextureTarget {
        val current = geometryCapture
        if (current != null && current.width == width && current.height == height) return current
        current?.destroyBuffers()
        return TextureTarget(width, height, true, Minecraft.ON_OSX).also { geometryCapture = it }
    }

    private fun ensureEffect(resourceManager: ResourceManager): EffectInstance {
        return effect ?: EffectInstance(resourceManager, "traces:world_desaturate").also { effect = it }
    }

    private fun ensureCompositeEffect(resourceManager: ResourceManager): EffectInstance {
        return compositeEffect ?: EffectInstance(resourceManager, "traces:geometry_composite").also { compositeEffect = it }
    }

    private fun copyColor(main: com.mojang.blaze3d.pipeline.RenderTarget, copy: TextureTarget) {
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.frameBufferId)
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, copy.frameBufferId)
        GL30.glBlitFramebuffer(
            0,
            0,
            main.width,
            main.height,
            0,
            0,
            copy.width,
            copy.height,
            GL30.GL_COLOR_BUFFER_BIT,
            GL30.GL_NEAREST,
        )
        main.bindWrite(false)
    }

    private fun blitDepth(
        source: com.mojang.blaze3d.pipeline.RenderTarget,
        destination: com.mojang.blaze3d.pipeline.RenderTarget,
    ) {
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, source.frameBufferId)
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, destination.frameBufferId)
        GL30.glBlitFramebuffer(
            0,
            0,
            source.width,
            source.height,
            0,
            0,
            destination.width,
            destination.height,
            GL30.GL_DEPTH_BUFFER_BIT,
            GL30.GL_NEAREST,
        )
        Minecraft.getInstance().mainRenderTarget.bindWrite(false)
    }

    private fun disableAfterFailure(error: Throwable) {
        disabledAfterFailure = true
        closeEffect()
        TracesClientLog.LOGGER.error("Disabling Traces world desaturation after render-target failure", error)
    }

    private fun quad(builder: BufferBuilder, left: Double, top: Double, right: Double, bottom: Double) {
        builder.vertex(left, bottom, 0.0).endVertex()
        builder.vertex(right, bottom, 0.0).endVertex()
        builder.vertex(right, top, 0.0).endVertex()
        builder.vertex(left, top, 0.0).endVertex()
    }

    private fun closeEffect() {
        effect?.close()
        effect = null
        compositeEffect?.close()
        compositeEffect = null
    }

    override fun onResourceManagerReload(resourceManager: ResourceManager) {
        closeEffect()
        disabledAfterFailure = false
        appliedLogged = false
        captureLogged = false
        geometryReady = false
    }

    fun close() {
        closeEffect()
        colorCopy?.destroyBuffers()
        colorCopy = null
        geometryCapture?.destroyBuffers()
        geometryCapture = null
        appliedLogged = false
        captureLogged = false
        geometryReady = false
    }

    override fun reload(
        preparationBarrier: net.minecraft.server.packs.resources.PreparableReloadListener.PreparationBarrier,
        resourceManager: ResourceManager,
        preparationsProfiler: ProfilerFiller,
        reloadProfiler: ProfilerFiller,
        backgroundExecutor: Executor,
        gameExecutor: Executor,
    ): CompletableFuture<Void> = super<ResourceManagerReloadListener>.reload(
        preparationBarrier,
        resourceManager,
        preparationsProfiler,
        reloadProfiler,
        backgroundExecutor,
        gameExecutor,
    )
}
