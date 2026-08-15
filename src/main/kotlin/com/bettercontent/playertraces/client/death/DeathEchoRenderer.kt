package com.bettercontent.playertraces.client.death

import com.bettercontent.playertraces.echo.EchoClip
import com.bettercontent.playertraces.echo.EchoEncoding
import com.bettercontent.playertraces.echo.EchoPlayback
import com.bettercontent.playertraces.echo.EchoRoot

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.Minecraft
import net.minecraft.client.model.PlayerModel
import net.minecraft.client.model.geom.ModelLayers
import net.minecraft.client.model.geom.ModelPart
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

internal const val ECHO_BODY_SCALE = 1.25

internal fun echoWorldVertex(local: Vec3, root: EchoRoot, anchor: Vec3): Vec3 {
    val scaledX = local.x * ECHO_BODY_SCALE
    val scaledY = local.y * ECHO_BODY_SCALE
    val scaledZ = local.z * ECHO_BODY_SCALE
    val yaw = root.bodyYaw.toDouble()
    val rotatedX = scaledX * cos(yaw) - scaledZ * sin(yaw)
    val rotatedZ = scaledX * sin(yaw) + scaledZ * cos(yaw)
    return anchor.add(root.x + rotatedX, root.y + scaledY, root.z + rotatedZ)
}

object DeathEchoRenderer {
    private val whiteTexture = ResourceLocation("forge", "textures/white.png")
    private val renderType = RenderType.entityTranslucentEmissive(whiteTexture)
    private var model: PlayerModel<AbstractClientPlayer>? = null

    fun render(
        clip: EchoClip,
        anchor: Vec3,
        playbackSeconds: Float,
        animationSeconds: Double,
        pose: PoseStack,
        buffers: MultiBufferSource.BufferSource,
        fade: Float,
    ): Int {
        val frame = EchoPlayback.sample(clip, playbackSeconds)
        val geometry = when (clip.encoding) {
            EchoEncoding.BONE -> boneGeometry(frame.channels)
            EchoEncoding.GEOMETRY -> GeometrySnapshot(frame.channels, clip.topology)
        }
        val consumer = buffers.getBuffer(renderType)
        val unique = HashSet<EdgeKey>()
        var submitted = 0
        geometry.topology.asList().chunked(2).forEachIndexed { edgeIndex, edge ->
            if (edge.size != 2) return@forEachIndexed
            val first = transformedVertex(geometry.vertices, edge[0], frame.root, anchor)
            val second = transformedVertex(geometry.vertices, edge[1], frame.root, anchor)
            if (first.distanceToSqr(second) < 1.0e-8) return@forEachIndexed
            val key = EdgeKey.of(first, second)
            if (!unique.add(key)) return@forEachIndexed
            val pulse = threadPulse(edgeIndex, animationSeconds)
            emitGradientPrism(
                consumer, pose.last().pose(), first, second, HALO_RADIUS, HALO_RGB,
                edgeIndex, animationSeconds, fade * (0.12f + pulse * 0.18f),
            )
            emitGradientPrism(
                consumer, pose.last().pose(), first, second, CORE_RADIUS, CORE_RGB,
                edgeIndex, animationSeconds, fade,
            )
            submitted++
        }
        return submitted
    }

    fun endBatch(buffers: MultiBufferSource.BufferSource) = buffers.endBatch(renderType)

    private fun boneGeometry(channels: FloatArray): GeometrySnapshot {
        val mc = Minecraft.getInstance()
        val playerModel = model ?: PlayerModel<AbstractClientPlayer>(
            mc.entityModels.bakeLayer(ModelLayers.PLAYER),
            false,
        ).also { model = it }
        val parts = listOf(
            playerModel.head, playerModel.hat, playerModel.body, playerModel.jacket,
            playerModel.leftArm, playerModel.leftSleeve, playerModel.rightArm, playerModel.rightSleeve,
            playerModel.leftLeg, playerModel.leftPants, playerModel.rightLeg, playerModel.rightPants,
        )
        parts.forEachIndexed { index, part -> applyPart(part, channels, index * EchoClip.CHANNELS_PER_BONE) }
        playerModel.setAllVisible(true)
        val capture = CaptureBufferSource()
        val modelPose = PoseStack()
        modelPose.scale(-1f, -1f, 1f)
        modelPose.translate(0.0, -1.501, 0.0)
        playerModel.renderToBuffer(
            modelPose,
            capture.getBuffer(RenderType.entitySolid(whiteTexture)),
            FULL_BRIGHT,
            OverlayTexture.NO_OVERLAY,
            1f, 1f, 1f, 1f,
        )
        return capture.snapshot()
    }

    private fun applyPart(part: ModelPart, channels: FloatArray, offset: Int) {
        part.x = channels[offset]
        part.y = channels[offset + 1]
        part.z = channels[offset + 2]
        part.xRot = channels[offset + 3]
        part.yRot = channels[offset + 4]
        part.zRot = channels[offset + 5]
        part.xScale = channels[offset + 6]
        part.yScale = channels[offset + 7]
        part.zScale = channels[offset + 8]
        part.visible = true
        part.skipDraw = false
    }

    private fun transformedVertex(vertices: FloatArray, index: Int, root: EchoRoot, anchor: Vec3): Vec3 {
        val offset = index * 3
        return echoWorldVertex(
            Vec3(vertices[offset].toDouble(), vertices[offset + 1].toDouble(), vertices[offset + 2].toDouble()),
            root,
            anchor,
        )
    }

    private fun emitGradientPrism(
        consumer: VertexConsumer,
        matrix: Matrix4f,
        from: Vec3,
        to: Vec3,
        radius: Double,
        rgb: Int,
        edgeIndex: Int,
        animationSeconds: Double,
        alphaScale: Float,
    ) {
        repeat(OPACITY_SPANS) { span ->
            val fromProgress = span.toDouble() / OPACITY_SPANS
            val toProgress = (span + 1).toDouble() / OPACITY_SPANS
            emitPrismSpan(
                consumer, matrix,
                from.lerp(to, fromProgress), from.lerp(to, toProgress),
                radius, rgb,
                alphaScale * deathEchoThreadOpacity(edgeIndex, fromProgress, animationSeconds),
                alphaScale * deathEchoThreadOpacity(edgeIndex, toProgress, animationSeconds),
            )
        }
    }

    private fun emitPrismSpan(
        consumer: VertexConsumer,
        matrix: Matrix4f,
        from: Vec3,
        to: Vec3,
        radius: Double,
        rgb: Int,
        fromAlpha: Float,
        toAlpha: Float,
    ) {
        if (fromAlpha <= 0.001f && toAlpha <= 0.001f) return
        val forward = to.subtract(from).normalize()
        val seed = if (kotlin.math.abs(forward.y) < 0.92) Vec3(0.0, 1.0, 0.0) else Vec3(1.0, 0.0, 0.0)
        val right = forward.cross(seed).normalize()
        val up = right.cross(forward).normalize()
        val ring = (0..THREAD_SIDES).map { side ->
            val angle = PI * 2.0 * side / THREAD_SIDES
            right.scale(cos(angle) * radius).add(up.scale(sin(angle) * radius))
        }
        repeat(THREAD_SIDES) { side ->
            val normal = ring[side].add(ring[side + 1]).normalize()
            emitVertex(consumer, matrix, from.add(ring[side]), rgb, fromAlpha, normal, 0f, 1f)
            emitVertex(consumer, matrix, from.add(ring[side + 1]), rgb, fromAlpha, normal, 1f, 1f)
            emitVertex(consumer, matrix, to.add(ring[side + 1]), rgb, toAlpha, normal, 1f, 0f)
            emitVertex(consumer, matrix, to.add(ring[side]), rgb, toAlpha, normal, 0f, 0f)
        }
    }

    private fun emitVertex(
        consumer: VertexConsumer,
        matrix: Matrix4f,
        position: Vec3,
        rgb: Int,
        alpha: Float,
        normal: Vec3,
        u: Float,
        v: Float,
    ) {
        consumer.vertex(matrix, position.x.toFloat(), position.y.toFloat(), position.z.toFloat())
            .color((rgb shr 16) and 255, (rgb shr 8) and 255, rgb and 255, (alpha.coerceIn(0f, 1f) * 255).roundToInt())
            .uv(u, v)
            .overlayCoords(OverlayTexture.NO_OVERLAY)
            .uv2(FULL_BRIGHT)
            .normal(normal.x.toFloat(), normal.y.toFloat(), normal.z.toFloat())
            .endVertex()
    }

    private fun threadPulse(edgeIndex: Int, animationSeconds: Double): Float {
        val raw = ((animationSeconds * 0.85 - edgeIndex * 0.019) % 1.0 + 1.0) % 1.0
        val distance = kotlin.math.min(raw, 1.0 - raw)
        val normalized = (1.0 - distance / 0.10).coerceIn(0.0, 1.0)
        return (normalized * normalized * normalized).toFloat()
    }

    private data class PointKey(val x: Int, val y: Int, val z: Int) : Comparable<PointKey> {
        override fun compareTo(other: PointKey): Int = compareValuesBy(this, other, PointKey::x, PointKey::y, PointKey::z)

        companion object {
            fun of(point: Vec3): PointKey = PointKey(
                (point.x * 4096.0).roundToInt(),
                (point.y * 4096.0).roundToInt(),
                (point.z * 4096.0).roundToInt(),
            )
        }
    }

    private data class EdgeKey(val first: PointKey, val second: PointKey) {
        companion object {
            fun of(first: Vec3, second: Vec3): EdgeKey {
                val a = PointKey.of(first)
                val b = PointKey.of(second)
                return if (a <= b) EdgeKey(a, b) else EdgeKey(b, a)
            }
        }
    }

    private const val THREAD_SIDES = 4
    private const val OPACITY_SPANS = 6
    private const val CORE_RADIUS = 0.0075 * ECHO_BODY_SCALE
    private const val HALO_RADIUS = 0.018 * ECHO_BODY_SCALE
    private const val CORE_RGB = 0xA8F8FF
    private const val HALO_RGB = 0x21DFF7
    private const val FULL_BRIGHT = 0x00F000F0
}
