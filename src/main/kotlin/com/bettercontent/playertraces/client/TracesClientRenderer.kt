package com.bettercontent.playertraces.client

import com.bettercontent.playertraces.config.TracesConfig
import net.minecraft.ChatFormatting
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.minecraftforge.client.event.RenderLevelStageEvent
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.cos
import kotlin.math.sin
import com.bettercontent.playertraces.client.death.DeathEchoRenderer
import net.minecraft.client.renderer.LevelRenderer

object TracesClientRenderer {
    internal const val MAX_RENDERED_FOOTPRINTS = 1_000_000_000
    private var visualStageObserved = false
    private const val NOTE_RGB = 0xFFFFFF
    private const val LABEL_RGB = 0xFFFFFF
    private const val LABEL_OUTLINE_RGB = 0x000000
    internal const val LABEL_SCALE = 0.021f
    internal const val LABEL_HEIGHT = 0.42
    private const val MAX_LABELS = 128
    internal const val GUIDANCE_COOL_RGB = 0x35E7FF
    internal const val GUIDANCE_WARM_RGB = 0xFF9F45
    internal const val GUIDANCE_BASE_ALPHA = 0.50f
    internal const val GUIDANCE_PULSE_ALPHA = 0.90f
    internal const val GUIDANCE_RADIUS = 0.07
    internal const val GUIDANCE_CYLINDER_SIDES = 8
    internal const val GUIDANCE_ELEVATION = 0.085
    private const val GUIDANCE_PULSE_SPACING = 0.34f
    private const val GUIDANCE_PULSE_HALF_WIDTH = 0.07f
    private const val GUIDANCE_PULSE_SPEED = 0.22f

    private data class PointKey(val x: Long, val y: Long, val z: Long) : Comparable<PointKey> {
        override fun compareTo(other: PointKey): Int = compareValuesBy(this, other, PointKey::x, PointKey::y, PointKey::z)
    }
    private data class SegmentKey(val first: PointKey, val second: PointKey)
    private data class GuidanceSegment(
        val from: SurfaceSample,
        val to: SurfaceSample,
        val startProgress: Float,
        val endProgress: Float,
    )
    private data class GuidanceVertex(val position: Vec3, val rgb: Int, val alpha: Float)

    internal fun renderStage(): RenderLevelStageEvent.Stage = RenderLevelStageEvent.Stage.AFTER_PARTICLES

    fun onRenderLevelStage(event: RenderLevelStageEvent) {
        if (event.stage != renderStage()) return
        if (!visualStageObserved && java.lang.Boolean.getBoolean("traces.visualValidation")) {
            visualStageObserved = true
            TracesClientLog.LOGGER.info("Traces render-level stage observed")
        }
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val player = mc.player ?: return
        val camera = event.camera
        val distance = TracesConfig.client.maxRenderDistance.get().toDouble() * 16.0
        val nowMillis = net.minecraft.Util.getMillis()
        val traceVisibility = TraceSightOverlayRenderer.visibility(nowMillis)
        if (traceVisibility <= 0.001f) {
            TracesClientState.playingAnnotationEchoes(player.position(), nowMillis, traceSightVisible = false)
            return
        }
        val footprintData = FootprintRenderCache.renderData(
            level, TracesClientState.visibleTraces(), TracesClientState.footprintPayloadRevision,
        )
        val annotations = TracesClientState.visibleAnnotations()
            .sortedBy { player.distanceToSqr(it.x + 0.5, it.y + 0.5, it.z + 0.5) }
            .take(MAX_LABELS)
        val guidance = TracesClientState.visibleGuidance()
        val bloodPools = TracesClientState.visibleBloodPools()
        val deathEchoes = TracesClientState.visibleDeathEchoes()
        val noteEchoes = TracesClientState.playingAnnotationEchoes(player.position(), nowMillis, traceSightVisible = true)
        if (footprintData.cells.isEmpty() && annotations.isEmpty() && guidance.isEmpty() && bloodPools.isEmpty() && deathEchoes.isEmpty() && noteEchoes.isEmpty()) return

        val pose = event.poseStack
        pose.pushPose()
        try {
            pose.translate(-camera.position.x, -camera.position.y, -camera.position.z)
            val buffer = mc.renderBuffers().bufferSource()
            var drawable = 0
            var submitted = 0
            bloodPools.forEach { pool ->
                val center = Vec3(pool.x, pool.y, pool.z)
                if (camera.position.distanceToSqr(center) > distance * distance ||
                    !event.frustum.isVisible(AABB(center, center).inflate(1.5, 0.25, 1.5))
                ) return@forEach
                emitBloodPool(
                    buffer.getBuffer(TracesRenderTypes.bloodPools), pose.last().pose(), level, pool, traceVisibility,
                )
                drawable++
                submitted++
            }
            buffer.endBatch(TracesRenderTypes.bloodPools)
            val guidanceSegments = buildGuidanceSegments(level, guidance, player.position())
            val guidanceConsumer = buffer.getBuffer(TracesRenderTypes.guidance)
            val animationSeconds = (level.gameTime + event.partialTick).toDouble() / 20.0
            guidanceSegments.values.forEach { segment ->
                emitGuidanceSegment(guidanceConsumer, pose.last().pose(), segment, animationSeconds, traceVisibility)
            }
            footprintData.cells.forEach cellLoop@{ cell ->
                val cellDistance = distance + FootprintRenderCache.CELL_SIZE_BLOCKS
                if (camera.position.distanceToSqr(cell.bounds.center) > cellDistance * cellDistance ||
                    !event.frustum.isVisible(cell.bounds)
                ) return@cellLoop
                cell.footprints.forEach footprintLoop@{ cached ->
                    if (camera.position.distanceToSqr(cached.bounds.center) > distance * distance) return@footprintLoop
                    emitQuad(
                        buffer.getBuffer(TracesRenderTypes.trace(cached.mark.trace.kind)), pose.last().pose(), cached.quad,
                        TraceSightOverlayModel.scaledAlpha(cached.mark.alpha, traceVisibility), cached.mark.color, FULL_BRIGHT,
                    )
                    drawable++
                    submitted++
                }
            }
            TracesRenderTypes.traceTypes.forEach(buffer::endBatch)
            annotations.forEach { annotation ->
                val pos = BlockPos(annotation.x, annotation.y, annotation.z)
                if (!visible(pos, camera, event, distance)) return@forEach
                val quad = SurfaceAnchorResolver.annotationQuad(level, annotation) ?: return@forEach
                drawable++
                if (annotation.icon.isNotEmpty()) emitQuad(
                    buffer.getBuffer(TracesRenderTypes.note(annotation.icon)), pose.last().pose(), quad,
                    traceVisibility, annotation.color,
                )
                val center = quad.vertices.fold(Vec3.ZERO) { sum, vertex -> sum.add(vertex.position) }
                    .scale(1.0 / quad.vertices.size)
                    .add(0.0, LABEL_HEIGHT, 0.0)
                emitLabel(mc, buffer, pose, camera, center, annotation.text, traceVisibility)
                if (!annotation.seen && player.distanceToSqr(
                        annotation.x + 0.5,
                        annotation.y + 0.5,
                        annotation.z + 0.5,
                    ) <= 64.0
                ) {
                    TracesClientState.acknowledgeViewed(annotation)
                }
                submitted++
            }
            buffer.endBatch(TracesRenderTypes.guidance)
            com.bettercontent.playertraces.domain.AnnotationComponents.icons.forEach { buffer.endBatch(TracesRenderTypes.note(it)) }
            deathEchoes.forEach { echo ->
                val center = Vec3(echo.dto.x, echo.dto.y, echo.dto.z)
                if (camera.position.distanceToSqr(center) > distance * distance ||
                    !event.frustum.isVisible(AABB(center, center).inflate(4.0))
                ) return@forEach
                val cycle = echo.clip.durationSeconds + DEATH_ECHO_PAUSE_SECONDS
                val elapsedTicks = (level.gameTime - echo.dto.createdAt).coerceAtLeast(0L) + event.partialTick
                val phaseSeconds = ((elapsedTicks / 20.0) % cycle).toFloat()
                val playback = phaseSeconds.coerceAtMost(echo.clip.durationSeconds)
                val fade = deathEchoFade(phaseSeconds, echo.clip.durationSeconds)
                if (fade > 0f) {
                    DeathEchoRenderer.render(
                        echo.clip, center, playback, elapsedTicks / 20.0,
                        pose, buffer, fade * traceVisibility,
                    )
                    drawable++
                    submitted++
                }
            }
            noteEchoes.forEach { echo ->
                val surface = SurfaceAnchorResolver.sample(level, echo.dto.x + 0.5, echo.dto.y + 1.0, echo.dto.z + 0.5) ?: return@forEach
                val elapsed = ((nowMillis - echo.startedAt).coerceAtLeast(0L) / 1000.0).toFloat()
                if (elapsed <= echo.clip.durationSeconds) {
                    DeathEchoRenderer.render(
                        echo.clip, surface.position, elapsed, level.gameTime / 20.0,
                        pose, buffer, traceVisibility,
                    )
                    drawable++; submitted++
                }
            }
            DeathEchoRenderer.endBatch(buffer)
            buffer.endBatch()
            if (TracesConfig.client.visualDiagnostics.get()) {
                TracesClientLog.LOGGER.info(
                    "TRACES_MVP_RENDER accepted={} drawable={} submitted={} footprints={} notes={} noteEchoes={} guidanceSegments={}",
                    TracesClientState.lastPayloadTraceCount + TracesClientState.lastPayloadAnnotationCount,
                    drawable, submitted, footprintData.sourceCount, annotations.size, noteEchoes.size, guidanceSegments.size,
                )
            }
        } finally {
            pose.popPose()
        }
    }

    private fun emitBloodPool(
        consumer: com.mojang.blaze3d.vertex.VertexConsumer,
        matrix: org.joml.Matrix4f,
        level: net.minecraft.world.level.Level,
        pool: com.bettercontent.playertraces.dto.VisibleBloodPoolDto,
        alphaScale: Float,
    ) {
        val hash = pool.id.hashCode()
        val halfSize = 0.82 + ((hash ushr 8) and 15) / 100.0
        val angle = (hash and 0xFFFF) / 65535.0 * Math.PI * 2.0
        val right = Vec3(cos(angle) * halfSize, 0.0, sin(angle) * halfSize)
        val forward = Vec3(-sin(angle) * halfSize, 0.0, cos(angle) * halfSize)
        val center = Vec3(pool.x, pool.y, pool.z)
        val positions = listOf(
            center.subtract(right).add(forward),
            center.add(right).add(forward),
            center.add(right).subtract(forward),
            center.subtract(right).subtract(forward),
        )
        val uv = listOf(0f to 0f, 1f to 0f, 1f to 1f, 0f to 1f)
        val light = LevelRenderer.getLightColor(level, BlockPos.containing(center))
        positions.zip(uv).forEach { (position, texture) ->
            consumer.vertex(matrix, position.x.toFloat(), position.y.toFloat(), position.z.toFloat())
                .color(255, 255, 255, TraceSightOverlayModel.alphaByte(235f / 255f, alphaScale))
                .uv(texture.first, texture.second)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(light)
                .normal(0f, 1f, 0f)
                .endVertex()
        }
    }

    internal fun deathEchoFade(seconds: Float, duration: Float): Float {
        if (seconds > duration) return 0f
        return minOf((seconds / 0.28f).coerceIn(0f, 1f), ((duration - seconds) / 0.42f).coerceIn(0f, 1f))
    }

    private fun visible(pos: BlockPos, camera: Camera, event: RenderLevelStageEvent, distance: Double): Boolean =
        camera.position.distanceToSqr(Vec3.atCenterOf(pos)) <= distance * distance && event.frustum.isVisible(AABB(pos).inflate(0.8))

    private fun emitQuad(
        consumer: com.mojang.blaze3d.vertex.VertexConsumer,
        matrix: org.joml.Matrix4f,
        quad: SurfaceQuad,
        alpha: Float,
        rgb: Int,
        packedLightOverride: Int? = null,
    ) {
        quad.vertices.forEach { vertex ->
            consumer.vertex(matrix, vertex.position.x.toFloat(), vertex.position.y.toFloat(), vertex.position.z.toFloat())
                .color((rgb shr 16) and 255, (rgb shr 8) and 255, rgb and 255, (alpha.coerceIn(0f, 1f) * 255).toInt())
                .uv(vertex.u, vertex.v)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(packedLightOverride ?: vertex.packedLight)
                .normal(0f, 1f, 0f)
                .endVertex()
        }
    }

    private fun emitLabel(
        mc: Minecraft,
        buffer: MultiBufferSource.BufferSource,
        pose: com.mojang.blaze3d.vertex.PoseStack,
        camera: Camera,
        position: Vec3,
        text: String,
        alphaScale: Float,
    ): Boolean {
        val label = text.trim().take(32)
        if (label.isEmpty()) return false
        pose.pushPose()
        pose.translate(position.x, position.y, position.z)
        pose.mulPose(camera.rotation())
        pose.scale(-LABEL_SCALE, -LABEL_SCALE, LABEL_SCALE)
        val component = Component.literal(label).withStyle(ChatFormatting.BOLD)
        mc.font.drawInBatch8xOutline(
            component.visualOrderText, -mc.font.width(component) / 2f, 0f,
            TraceSightOverlayModel.argb(LABEL_RGB, 1f, alphaScale),
            TraceSightOverlayModel.argb(LABEL_OUTLINE_RGB, 1f, alphaScale),
            pose.last().pose(), buffer, 0x00F000F0,
        )
        pose.popPose()
        return true
    }

    private fun buildGuidanceSegments(
        level: net.minecraft.world.level.Level,
        routes: List<com.bettercontent.playertraces.dto.GuidanceRouteDto>,
        playerPosition: Vec3,
    ): LinkedHashMap<SegmentKey, GuidanceSegment> {
        val segments = LinkedHashMap<SegmentKey, GuidanceSegment>()
        routes.forEach { route ->
            val remainingPath = GuidancePathModel.remainingPath(route.path, playerPosition)
            val lengths = remainingPath.zipWithNext().map { (from, to) ->
                val dx = to.x - from.x
                val dy = to.y - from.y
                val dz = to.z - from.z
                sqrt(dx * dx + dy * dy + dz * dz)
            }
            val totalLength = lengths.sum().coerceAtLeast(1.0e-6)
            var traveled = 0.0
            remainingPath.zipWithNext().forEachIndexed { index, (fromPos, toPos) ->
                val startProgress = (traveled / totalLength).toFloat()
                traveled += lengths[index]
                val endProgress = (traveled / totalLength).toFloat()
                if (fromPos == toPos) return@forEachIndexed
                val first = PointKey(fromPos.x.toBits(), fromPos.y.toBits(), fromPos.z.toBits())
                val second = PointKey(toPos.x.toBits(), toPos.y.toBits(), toPos.z.toBits())
                val key = if (first <= second) SegmentKey(first, second) else SegmentKey(second, first)
                if (key in segments) return@forEachIndexed
                val from = SurfaceAnchorResolver.sample(level, fromPos.x, fromPos.y, fromPos.z)
                    ?: return@forEachIndexed
                val to = SurfaceAnchorResolver.sample(level, toPos.x, toPos.y, toPos.z)
                    ?: return@forEachIndexed
                segments[key] = GuidanceSegment(
                    from = from.copy(position = from.position.add(0.0, GUIDANCE_ELEVATION, 0.0)),
                    to = to.copy(position = to.position.add(0.0, GUIDANCE_ELEVATION, 0.0)),
                    startProgress = startProgress,
                    endProgress = endProgress,
                )
            }
        }
        return segments
    }

    private fun emitGuidanceSegment(
        consumer: com.mojang.blaze3d.vertex.VertexConsumer,
        matrix: org.joml.Matrix4f,
        segment: GuidanceSegment,
        animationSeconds: Double,
        alphaScale: Float,
    ) {
        val direction = segment.to.position.subtract(segment.from.position)
        if (!direction.lengthSqr().isFinite() || direction.lengthSqr() < 1.0e-8) return
        val forward = direction.normalize()
        val rightSeed = forward.cross(Vec3(0.0, 1.0, 0.0))
        val right = if (rightSeed.lengthSqr() > 1.0e-8) rightSeed.normalize() else Vec3(1.0, 0.0, 0.0)
        val up = right.cross(forward).normalize()
        val fromColor = guidanceColor(segment.startProgress, animationSeconds)
        val toColor = guidanceColor(segment.endProgress, animationSeconds)
        val fromAlpha = TraceSightOverlayModel.scaledAlpha(guidanceAlpha(segment.startProgress, animationSeconds), alphaScale)
        val toAlpha = TraceSightOverlayModel.scaledAlpha(guidanceAlpha(segment.endProgress, animationSeconds), alphaScale)
        val ring = (0..GUIDANCE_CYLINDER_SIDES).map { side ->
            val angle = Math.PI * 2.0 * side / GUIDANCE_CYLINDER_SIDES
            right.scale(kotlin.math.cos(angle) * GUIDANCE_RADIUS)
                .add(up.scale(kotlin.math.sin(angle) * GUIDANCE_RADIUS))
        }
        for (side in 0 until GUIDANCE_CYLINDER_SIDES) {
            val normalA = ring[side].normalize()
            val normalB = ring[side + 1].normalize()
            emitGuidanceFace(
                consumer,
                matrix,
                listOf(
                    GuidanceVertex(segment.from.position.add(ring[side]), fromColor, fromAlpha),
                    GuidanceVertex(segment.from.position.add(ring[side + 1]), fromColor, fromAlpha),
                    GuidanceVertex(segment.to.position.add(ring[side + 1]), toColor, toAlpha),
                    GuidanceVertex(segment.to.position.add(ring[side]), toColor, toAlpha),
                ),
                normalA.add(normalB).normalize(),
            )
        }
    }

    private fun emitGuidanceFace(
        consumer: com.mojang.blaze3d.vertex.VertexConsumer,
        matrix: org.joml.Matrix4f,
        vertices: List<GuidanceVertex>,
        normal: Vec3,
    ) {
        val uv = listOf(0f to 1f, 1f to 1f, 1f to 0f, 0f to 0f)
        vertices.zip(uv).forEach { (vertex, texture) ->
            val (position, rgb, alpha) = vertex
            consumer.vertex(matrix, position.x.toFloat(), position.y.toFloat(), position.z.toFloat())
                .color((rgb shr 16) and 255, (rgb shr 8) and 255, rgb and 255, (alpha * 255).toInt())
                .uv(texture.first, texture.second)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(FULL_BRIGHT)
                .normal(normal.x.toFloat(), normal.y.toFloat(), normal.z.toFloat())
                .endVertex()
        }
    }

    internal fun guidanceColor(progress: Float, animationSeconds: Double): Int {
        val amount = progress.coerceIn(0f, 1f)
        val glow = guidancePulse(amount, animationSeconds) * 0.32f
        fun channel(shift: Int): Int {
            val cool = (GUIDANCE_COOL_RGB shr shift) and 255
            val warm = (GUIDANCE_WARM_RGB shr shift) and 255
            val base = cool + (warm - cool) * amount
            return (base + (255f - base) * glow).toInt().coerceIn(0, 255)
        }
        return (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    internal fun guidanceAlpha(progress: Float, animationSeconds: Double): Float =
        GUIDANCE_BASE_ALPHA + (GUIDANCE_PULSE_ALPHA - GUIDANCE_BASE_ALPHA) * guidancePulse(progress, animationSeconds)

    internal fun guidancePulse(progress: Float, animationSeconds: Double): Float {
        val movingPhase = animationSeconds.toFloat() * GUIDANCE_PULSE_SPEED
        val rawPhase = (progress.coerceIn(0f, 1f) - movingPhase) % GUIDANCE_PULSE_SPACING
        val phase = if (rawPhase < 0f) rawPhase + GUIDANCE_PULSE_SPACING else rawPhase
        val distance = minOf(phase, GUIDANCE_PULSE_SPACING - phase)
        return (1f - (distance / GUIDANCE_PULSE_HALF_WIDTH).coerceIn(0f, 1f)).toDouble().pow(3.0).toFloat()
    }

    private const val FULL_BRIGHT = 0x00F000F0
    private const val DEATH_ECHO_PAUSE_SECONDS = 1.2f
}
