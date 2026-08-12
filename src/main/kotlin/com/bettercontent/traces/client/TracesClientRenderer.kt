package com.bettercontent.traces.client

import com.bettercontent.traces.config.TracesConfig
import com.bettercontent.traces.domain.GuidanceSignal
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.culling.Frustum
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.minecraftforge.fml.ModList
import net.minecraftforge.client.event.RenderLevelStageEvent

object TracesClientRenderer {
    private const val TRACE_RGB = 0x58D8E7
    private const val GUIDANCE_RGB = 0xD9F7FF
    private const val ANNOTATION_RGB = 0xF6B94A
    private const val LABEL_RGB = 0xFFF1CF
    private const val MAX_LABELS = 8
    private val observedStages = mutableSetOf<RenderLevelStageEvent.Stage>()
    private var interactiveWorldLogged = false

    fun onRenderLevelStage(event: RenderLevelStageEvent) {
        if (TracesConfig.client.visualDiagnostics.get() && observedStages.add(event.stage)) {
            TracesClientLog.LOGGER.info("Traces render-level stage observed: stage={}, overlay={}", event.stage, TracesClientState.overlayEnabled)
        }
        if (TracesConfig.client.visualDiagnostics.get() &&
            !interactiveWorldLogged &&
            event.stage == RenderLevelStageEvent.Stage.AFTER_LEVEL
        ) {
            val client = Minecraft.getInstance()
            if (client.level !== null && client.player !== null && client.screen === null && client.overlay === null) {
                interactiveWorldLogged = true
                TracesClientLog.LOGGER.info("TRACES_VISUAL_INTERACTIVE")
            }
        }
        if (!TracesClientState.overlayEnabled) return
        val mc = Minecraft.getInstance()
        val oculusLoaded = ModList.get().isLoaded("oculus")
        if (oculusLoaded && event.stage == RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            WorldDesaturationPass.apply(mc)
            WorldDesaturationPass.compositeCapturedGeometry(mc)
            return
        }
        if (event.stage != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return
        val level = mc.level ?: return
        val player = mc.player ?: return
        val camera = event.camera

        // Oculus replaces the world color after this stage. Resolve Traces against the
        // valid world depth now, then composite those already-occluded pixels after its
        // final pass. Vanilla can desaturate and draw directly at this stage.
        val captureGeometry = oculusLoaded && WorldDesaturationPass.beginGeometryCapture(mc)
        if (oculusLoaded && !captureGeometry) return
        if (!oculusLoaded) WorldDesaturationPass.apply(mc)

        val marks = TraceVisualModel.marks(
            TracesClientState.visibleTraces(),
            TracesConfig.client.referenceDensity.get().toFloat(),
            TracesConfig.client.minVisibleAlpha.get().toFloat(),
            TracesConfig.client.maxRenderedMarks.get(),
        )
        val renderDistance = TracesConfig.client.maxRenderDistance.get().toDouble() * 16.0
        val playerPos = player.position()
        val labelDistance = TracesConfig.client.annotationLabelDistance.get().toDouble()
        val annotations = TracesClientState.visibleAnnotations()
            .filter { playerPos.distanceToSqr(Vec3(it.x + 0.5, it.y.toDouble(), it.z + 0.5)) <= labelDistance * labelDistance }
            .sortedBy { playerPos.distanceToSqr(Vec3(it.x + 0.5, it.y.toDouble(), it.z + 0.5)) }
            .take(MAX_LABELS)
        val guidance = TracesClientState.visibleGuidance()
        if (marks.isEmpty() && annotations.isEmpty() && guidance.isEmpty()) {
            if (captureGeometry) WorldDesaturationPass.endGeometryCapture(mc)
            return
        }

        val pose = event.poseStack
        pose.pushPose()
        try {
            pose.translate(-camera.position.x, -camera.position.y, -camera.position.z)
            val buffer = mc.renderBuffers().bufferSource()
            var footprintCount = 0
            var annotationAnchorMisses = 0
            var anchoredAnnotations = 0
            marks.forEach { mark ->
                if (!withinRange(mark.trace.x + 0.5, mark.trace.y + 0.5, mark.trace.z + 0.5, camera, renderDistance)) return@forEach
                if (!event.frustum.isVisible(AABB(BlockPos(mark.trace.x, mark.trace.y, mark.trace.z)).inflate(0.4))) return@forEach
                val mesh = SurfaceAnchorResolver.footprintMesh(level, mark.trace, mark.angle)
                mesh.forEach { triangle ->
                    emitTexturedTriangle(buffer.getBuffer(TracesRenderTypes.footprints), pose.last().pose(), triangle, mark.alpha, mark.color)
                    footprintCount++
                }
            }
            guidance.forEach { signal ->
                emitGuidance(level, signal, camera, renderDistance, event.frustum, pose, buffer)
            }
            annotations.forEach { annotation ->
                if (!withinRange(annotation.x + 0.5, annotation.y + 0.5, annotation.z + 0.5, camera, renderDistance)) return@forEach
                if (!event.frustum.isVisible(AABB(BlockPos(annotation.x, annotation.y, annotation.z)).inflate(1.0))) return@forEach
                val anchor = SurfaceAnchorResolver.annotationAnchor(level, annotation)
                if (anchor == null) {
                    annotationAnchorMisses++
                    return@forEach
                }
                anchoredAnnotations++
                emitPin(buffer.getBuffer(TracesRenderTypes.pin), pose.last().pose(), anchor.position, LightTexture.FULL_BRIGHT, ANNOTATION_RGB)
                emitLabel(mc, buffer, pose, camera, anchor.position.add(0.0, 0.54, 0.0), annotation.text)
            }
            buffer.endBatch(TracesRenderTypes.footprints)
            buffer.endBatch(TracesRenderTypes.guidance)
            buffer.endBatch(TracesRenderTypes.pin)
            buffer.endBatch()
            if (TracesConfig.client.visualDiagnostics.get()) {
                TracesClientLog.LOGGER.info("TRACES_VISUAL_SUBMISSION footprintTriangles={} annotations={} anchored={} misses={} guidance={}", footprintCount, annotations.size, anchoredAnnotations, annotationAnchorMisses, guidance.size)
            }
        } finally {
            pose.popPose()
            if (captureGeometry) WorldDesaturationPass.endGeometryCapture(mc)
        }
    }

    private fun emitGuidance(
        level: net.minecraft.client.multiplayer.ClientLevel,
        signal: GuidanceSignal,
        camera: Camera,
        renderDistance: Double,
        frustum: Frustum,
        pose: com.mojang.blaze3d.vertex.PoseStack,
        buffer: MultiBufferSource.BufferSource,
    ) {
        if (signal.path.size < 2) return
        val phase = (signal.phase + (System.nanoTime() / 1_000_000_000.0 * TracesConfig.client.guidancePulseSpeed.get())).toFloat() % 1f
        val intensity = (signal.intensity * (0.55f + phase * 0.45f)).coerceIn(0.18f, 0.72f)
        signal.path.zipWithNext().forEach { (from, to) ->
            val distance = from.distSqr(to)
            if (distance > 18 || !eventEdgeVisible(from, to, camera, renderDistance, frustum)) return@forEach
            val dx = (to.x - from.x).toDouble()
            val dz = (to.z - from.z).toDouble()
            val length = kotlin.math.sqrt(dx * dx + dz * dz).coerceAtLeast(0.01)
            val forwardX = dx / length
            val forwardZ = dz / length
            val rightX = -forwardZ
            val rightZ = forwardX
            val steps = (length / 0.72).toInt().coerceAtLeast(1)
            for (step in 0 until steps) {
                val t = (step + 0.5) / steps.toDouble()
                val x = from.x + 0.5 + dx * t
                val z = from.z + 0.5 + dz * t
                val surface = SurfaceAnchorResolver.sample(level, x, from.y.toDouble(), z) ?: continue
                val halfWidth = 0.11
                val halfLength = 0.19
                val guidanceBase = surface.position.add(0.0, 0.014, 0.0)
                val tip = guidanceBase.add(forwardX * halfLength, 0.0, forwardZ * halfLength)
                val left = guidanceBase.add(rightX * halfWidth - forwardX * halfLength, 0.0, rightZ * halfWidth - forwardZ * halfLength)
                val notch = guidanceBase.add(-forwardX * halfLength * 0.22, 0.0, -forwardZ * halfLength * 0.22)
                val right = guidanceBase.add(-rightX * halfWidth - forwardX * halfLength, 0.0, -rightZ * halfWidth - forwardZ * halfLength)
                val consumer = buffer.getBuffer(TracesRenderTypes.guidance)
                emitSimpleVertex(consumer, pose.last().pose(), tip, 0.5f, 0f, intensity, GUIDANCE_RGB, LightTexture.FULL_BRIGHT)
                emitSimpleVertex(consumer, pose.last().pose(), left, 0f, 1f, intensity, GUIDANCE_RGB, LightTexture.FULL_BRIGHT)
                emitSimpleVertex(consumer, pose.last().pose(), notch, 0.5f, 0.72f, intensity, GUIDANCE_RGB, LightTexture.FULL_BRIGHT)
                emitSimpleVertex(consumer, pose.last().pose(), tip, 0.5f, 0f, intensity, GUIDANCE_RGB, LightTexture.FULL_BRIGHT)
                emitSimpleVertex(consumer, pose.last().pose(), notch, 0.5f, 0.72f, intensity, GUIDANCE_RGB, LightTexture.FULL_BRIGHT)
                emitSimpleVertex(consumer, pose.last().pose(), right, 1f, 1f, intensity, GUIDANCE_RGB, LightTexture.FULL_BRIGHT)
            }
        }
    }

    private fun eventEdgeVisible(from: BlockPos, to: BlockPos, camera: Camera, renderDistance: Double, frustum: Frustum): Boolean {
        val center = Vec3((from.x + to.x) * 0.5 + 0.5, (from.y + to.y) * 0.5 + 0.5, (from.z + to.z) * 0.5 + 0.5)
        return camera.position.distanceToSqr(center) <= renderDistance * renderDistance && frustum.isVisible(AABB(from, to).inflate(0.25))
    }

    private fun withinRange(x: Double, y: Double, z: Double, camera: Camera, distance: Double): Boolean {
        return camera.position.distanceToSqr(Vec3(x, y, z)) <= distance * distance
    }

    private fun emitTexturedTriangle(
        consumer: com.mojang.blaze3d.vertex.VertexConsumer,
        matrix: org.joml.Matrix4f,
        triangle: SurfaceTriangle,
        alpha: Float,
        rgb: Int,
    ) {
        emitSurfaceVertex(consumer, matrix, triangle.a, alpha, rgb)
        emitSurfaceVertex(consumer, matrix, triangle.b, alpha, rgb)
        emitSurfaceVertex(consumer, matrix, triangle.c, alpha, rgb)
    }

    private fun emitSurfaceVertex(consumer: com.mojang.blaze3d.vertex.VertexConsumer, matrix: org.joml.Matrix4f, vertex: SurfaceVertex, alpha: Float, rgb: Int) {
        emitSimpleVertex(consumer, matrix, vertex.position, vertex.u, vertex.v, alpha, rgb, LightTexture.FULL_BRIGHT)
    }

    private fun emitSimpleVertex(
        consumer: com.mojang.blaze3d.vertex.VertexConsumer,
        matrix: org.joml.Matrix4f,
        position: Vec3,
        u: Float,
        v: Float,
        alpha: Float,
        rgb: Int,
        packedLight: Int,
    ) {
        consumer.vertex(matrix, position.x.toFloat(), position.y.toFloat(), position.z.toFloat())
            .color((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF, (alpha.coerceIn(0f, 1f) * 255f).toInt())
            .uv(u, v)
            .overlayCoords(OverlayTexture.NO_OVERLAY)
            .uv2(packedLight)
            .normal(0f, 1f, 0f)
            .endVertex()
    }

    private fun emitPin(
        consumer: com.mojang.blaze3d.vertex.VertexConsumer,
        matrix: org.joml.Matrix4f,
        base: Vec3,
        packedLight: Int,
        rgb: Int,
    ) {
        val x = base.x
        val y = base.y
        val z = base.z
        val shaft = 0.05
        val top = y + 0.72
        val points = arrayOf(
            Vec3(x - shaft, y, z - shaft), Vec3(x + shaft, y, z - shaft), Vec3(x + shaft, top, z - shaft), Vec3(x - shaft, top, z - shaft),
            Vec3(x - shaft, y, z + shaft), Vec3(x + shaft, y, z + shaft), Vec3(x + shaft, top, z + shaft), Vec3(x - shaft, top, z + shaft),
        )
        val faces = arrayOf(
            intArrayOf(0, 1, 2, 0, 2, 3), intArrayOf(5, 4, 7, 5, 7, 6),
            intArrayOf(4, 0, 3, 4, 3, 7), intArrayOf(1, 5, 6, 1, 6, 2),
            intArrayOf(3, 2, 6, 3, 6, 7), intArrayOf(4, 5, 1, 4, 1, 0),
        )
        faces.forEach { face ->
            face.forEach { index ->
                val point = points[index]
                consumer.vertex(matrix, point.x.toFloat(), point.y.toFloat(), point.z.toFloat())
                    .color((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF, 245)
                    .uv(0f, 0f)
                    .overlayCoords(OverlayTexture.NO_OVERLAY)
                    .uv2(packedLight)
                    .normal(0f, 1f, 0f)
                    .endVertex()
            }
        }
        val headBase = top - 0.03
        val headTop = top + 0.20
        val r = 0.15
        val head = arrayOf(Vec3(x, headTop, z), Vec3(x - r, headBase, z), Vec3(x, headBase, z - r), Vec3(x + r, headBase, z), Vec3(x, headBase, z + r))
        intArrayOf(1, 2, 3, 4).forEach { i ->
            val next = if (i == 4) 1 else i + 1
            arrayOf(head[0], head[i], head[next]).forEach { point ->
                consumer.vertex(matrix, point.x.toFloat(), point.y.toFloat(), point.z.toFloat())
                    .color((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF, 255)
                    .uv(0f, 0f)
                    .overlayCoords(OverlayTexture.NO_OVERLAY)
                    .uv2(packedLight)
                    .normal(0f, 1f, 0f)
                    .endVertex()
            }
        }
    }

    private fun emitLabel(
        mc: Minecraft,
        buffer: MultiBufferSource.BufferSource,
        pose: com.mojang.blaze3d.vertex.PoseStack,
        camera: Camera,
        position: Vec3,
        text: String,
    ) {
        val label = text.trim().take(32)
        if (label.isEmpty()) return
        pose.pushPose()
        try {
            pose.translate(position.x, position.y, position.z)
            pose.mulPose(camera.rotation())
            pose.scale(-0.0125f, -0.0125f, 0.0125f)
            val width = mc.font.width(label)
            pose.translate(-width / 2.0, -4.0, 0.0)
            mc.font.drawInBatch(
                label,
                0f,
                0f,
                LABEL_RGB,
                true,
                pose.last().pose(),
                buffer,
                Font.DisplayMode.NORMAL,
                0,
                LightTexture.FULL_BRIGHT,
            )
        } finally {
            pose.popPose()
        }
    }
}
