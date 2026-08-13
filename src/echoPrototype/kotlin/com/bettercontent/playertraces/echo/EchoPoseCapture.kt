package com.bettercontent.playertraces.prototype

import com.bettercontent.playertraces.client.death.CaptureBufferSource
import com.bettercontent.playertraces.client.death.GeometrySnapshot
import com.bettercontent.playertraces.echo.EchoClip

import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.Minecraft
import net.minecraft.client.model.PlayerModel
import net.minecraft.client.model.geom.ModelPart
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.client.renderer.entity.player.PlayerRenderer
import net.minecraftforge.client.event.RenderPlayerEvent
import net.minecraftforge.eventbus.api.EventPriority
import net.minecraftforge.eventbus.api.SubscribeEvent
import kotlin.math.PI

internal data class CapturedPose(val boneChannels: FloatArray, val geometry: GeometrySnapshot)

internal object EchoPoseCapture {
    private var activePlayer: AbstractClientPlayer? = null
    private var capturedBones: FloatArray? = null

    fun capture(player: AbstractClientPlayer, partialTick: Float): CapturedPose? {
        val mc = Minecraft.getInstance()
        val renderer = mc.entityRenderDispatcher.getRenderer(player) as? PlayerRenderer ?: return null
        val source = CaptureBufferSource()
        activePlayer = player
        capturedBones = null
        return try {
            val bodyYaw = degreesLerp(partialTick, player.yBodyRotO, player.yBodyRot)
            renderer.render(player, bodyYaw, partialTick, PoseStack(), source, FULL_BRIGHT)
            val bones = capturedBones ?: return null
            CapturedPose(bones, source.snapshot())
        } finally {
            activePlayer = null
            capturedBones = null
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    fun captureFinalModel(event: RenderPlayerEvent.Post) {
        if (event.entity !== activePlayer) return
        capturedBones = encodeParts(event.renderer.model)
    }

    private fun encodeParts(model: PlayerModel<AbstractClientPlayer>): FloatArray {
        val parts = listOf(
            model.head, model.hat, model.body, model.jacket,
            model.leftArm, model.leftSleeve, model.rightArm, model.rightSleeve,
            model.leftLeg, model.leftPants, model.rightLeg, model.rightPants,
        )
        return FloatArray(EchoClip.BONE_CHANNEL_COUNT).also { channels ->
            parts.forEachIndexed { index, part -> encodePart(part, channels, index * EchoClip.CHANNELS_PER_BONE) }
        }
    }

    private fun encodePart(part: ModelPart, output: FloatArray, offset: Int) {
        output[offset] = part.x
        output[offset + 1] = part.y
        output[offset + 2] = part.z
        output[offset + 3] = part.xRot
        output[offset + 4] = part.yRot
        output[offset + 5] = part.zRot
        output[offset + 6] = part.xScale
        output[offset + 7] = part.yScale
        output[offset + 8] = part.zScale
    }

    private fun degreesLerp(amount: Float, from: Float, to: Float): Float {
        var delta = (to - from) % 360f
        if (delta < -180f) delta += 360f
        if (delta >= 180f) delta -= 360f
        return from + amount * delta
    }

    fun radians(degrees: Float): Float = degrees * (PI.toFloat() / 180f)

    private const val FULL_BRIGHT = 0x00F000F0
}
