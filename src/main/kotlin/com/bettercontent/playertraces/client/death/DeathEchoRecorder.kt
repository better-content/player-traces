package com.bettercontent.playertraces.client.death

import com.bettercontent.playertraces.TracesMod
import com.bettercontent.playertraces.echo.EchoClip
import com.bettercontent.playertraces.echo.EchoClipCodec
import com.bettercontent.playertraces.echo.EchoEncoding
import com.bettercontent.playertraces.echo.EchoFrame
import com.bettercontent.playertraces.echo.EchoRoot
import com.bettercontent.playertraces.network.DeathCaptureRequestPacket
import com.bettercontent.playertraces.network.DeathEchoSubmitPacket
import com.bettercontent.playertraces.network.TracesNetwork
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.Minecraft
import net.minecraft.client.model.PlayerModel
import net.minecraft.client.model.geom.ModelPart
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.client.renderer.entity.player.PlayerRenderer
import net.minecraft.world.phys.Vec3
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.client.event.RenderLevelStageEvent
import net.minecraftforge.client.event.RenderPlayerEvent
import net.minecraftforge.eventbus.api.EventPriority
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod
import org.slf4j.LoggerFactory
import java.util.UUID
import kotlin.math.PI
import com.bettercontent.playertraces.logic.AnnotationClipTools
import com.bettercontent.playertraces.logic.AnnotationEchoValidation
import com.bettercontent.playertraces.logic.ClipAnchor

@Mod.EventBusSubscriber(modid = TracesMod.MOD_ID, value = [Dist.CLIENT], bus = Mod.EventBusSubscriber.Bus.FORGE)
object DeathEchoRecorder {
    private val log = LoggerFactory.getLogger(DeathEchoRecorder::class.java)
    private val rollingFrames = com.bettercontent.playertraces.echo.RollingPoseBuffer<AbsoluteFrame>(EchoClip.SAMPLE_RATE * RECORDING_SECONDS)
    private var activePlayer: AbstractClientPlayer? = null
    private var capturedBones: FloatArray? = null
    private var lastSampleGameTime = Long.MIN_VALUE
    private var currentPlayer: UUID? = null
    private var currentDimension: String? = null
    private var selectedCapture: SelectedCapture? = null
    private var frozenDeathCapture: FrozenDeathCapture? = null

    @SubscribeEvent
    @JvmStatic
    fun onRenderLevel(event: RenderLevelStageEvent) {
        if (event.stage != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return clearIfDisconnected()
        val level = mc.level ?: return clearIfDisconnected()
        val dimension = level.dimension().location().toString()
        if (currentPlayer != player.uuid || currentDimension != dimension) {
            rollingFrames.clear()
            frozenDeathCapture = null
            currentPlayer = player.uuid
            currentDimension = dimension
            lastSampleGameTime = Long.MIN_VALUE
        }
        if (!player.isAlive || level.gameTime == lastSampleGameTime) return
        lastSampleGameTime = level.gameTime
        val channels = captureBones(player, event.partialTick) ?: return
        rollingFrames.add(
            AbsoluteFrame(
                gameTime = level.gameTime,
                position = player.getPosition(event.partialTick),
                bodyYaw = radians(lerpDegrees(event.partialTick, player.yBodyRotO, player.yBodyRot)),
                headYaw = radians(lerpDegrees(event.partialTick, player.yHeadRotO, player.yHeadRot)),
                channels = channels,
            ),
        )
        selectedCapture?.let { capture ->
            val latest = rollingFrames.snapshot().last()
            capture.frames += latest.copy(channels = latest.channels.copyOf())
            if (capture.frames.size >= EchoClip.SAMPLE_RATE * RECORDING_SECONDS) {
                selectedCapture = null
                runCatching { encodeAnnotation(capture.frames, ClipAnchor.START, trim = true) }
                    .fold(capture.complete, capture.fail)
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    @JvmStatic
    fun captureFinalModel(event: RenderPlayerEvent.Post) {
        if (event.entity !== activePlayer) return
        capturedBones = encodeParts(event.renderer.model)
    }

    fun freezeForDowned(
        captureToken: UUID,
        dimension: String,
        x: Double,
        y: Double,
        z: Double,
        downGameTime: Long,
    ): Boolean {
        val snapshot = rollingFrames.snapshot()
            .filter { it.gameTime < downGameTime }
            .takeLast(EchoClip.SAMPLE_RATE * RECORDING_SECONDS)
        if (snapshot.isEmpty() || currentDimension != dimension) {
            frozenDeathCapture = null
            log.warn(
                "TRACES_DEATH_ECHO_FREEZE_SKIPPED reason={} token={} expectedDimension={} clientDimension={}",
                if (snapshot.isEmpty()) "no_rolling_frames" else "dimension_mismatch",
                captureToken,
                dimension,
                currentDimension,
            )
            return false
        }
        val anchor = Vec3(x, y, z)
        return runCatching {
            frozenDeathCapture = FrozenDeathCapture(
                token = captureToken,
                dimension = dimension,
                anchor = anchor,
                encodedClip = encodeDeath(snapshot, anchor),
            )
            log.info(
                "TRACES_DEATH_ECHO_FROZEN frames={} bytes={} token={} dimension={}",
                snapshot.size,
                frozenDeathCapture!!.encodedClip.size,
                captureToken,
                dimension,
            )
            true
        }.getOrElse {
            frozenDeathCapture = null
            log.warn("TRACES_DEATH_ECHO_FREEZE_SKIPPED reason=encode_failed token={}", captureToken, it)
            false
        }
    }

    fun discardDownedCapture(captureToken: UUID? = null) {
        val frozen = frozenDeathCapture ?: return
        if (captureToken == null || frozen.token == captureToken) {
            frozenDeathCapture = null
            log.debug("Discarded frozen downed death capture {}", frozen.token)
        }
    }

    fun onDeathConfirmed(request: DeathCaptureRequestPacket) = onDeathConfirmed(request, null)

    fun onDeathConfirmed(request: DeathCaptureRequestPacket, captureToken: UUID?) {
        val encoded = if (captureToken != null) {
            val frozen = frozenDeathCapture
            if (frozen == null || frozen.token != captureToken || frozen.dimension != currentDimension) {
                log.warn("TRACES_DEATH_ECHO_SKIPPED reason=frozen_capture_absent token={} nonce={}", captureToken, request.nonce)
                return
            }
            frozenDeathCapture = null
            val requestedAnchor = Vec3(request.x, request.y, request.z)
            if (frozen.anchor.distanceToSqr(requestedAnchor) > MAX_ANCHOR_DRIFT_SQUARED) {
                log.warn("TRACES_DEATH_ECHO_SKIPPED reason=frozen_anchor_mismatch token={} nonce={}", captureToken, request.nonce)
                return
            }
            frozen.encodedClip
        } else {
            frozenDeathCapture = null
            val snapshot = rollingFrames.snapshot().takeLast(EchoClip.SAMPLE_RATE * RECORDING_SECONDS)
            if (snapshot.isEmpty()) {
                log.warn("TRACES_DEATH_ECHO_SKIPPED reason=no_rolling_frames nonce={}", request.nonce)
                return
            }
            runCatching { encodeDeath(snapshot, Vec3(request.x, request.y, request.z)) }.getOrElse {
                log.warn("TRACES_DEATH_ECHO_SKIPPED reason=encode_failed nonce={}", request.nonce, it)
                return
            }
        }
        runCatching {
            TracesNetwork.submitDeathEcho(DeathEchoSubmitPacket(request.nonce, encoded))
            rollingFrames.clear()
            log.info(
                "TRACES_DEATH_ECHO_SUBMITTED camera={} bytes={} nonce={} frozen={}",
                Minecraft.getInstance().options.cameraType.name.lowercase(), encoded.size, request.nonce, captureToken != null,
            )
        }.onFailure {
            log.warn("TRACES_DEATH_ECHO_SKIPPED reason=submit_failed nonce={}", request.nonce, it)
        }
    }

    private fun encodeDeath(frames: List<AbsoluteFrame>, anchor: Vec3): ByteArray {
        require(frames.isNotEmpty()) { "no player pose frames were captured" }
        val relativeFrames = frames.map { frame ->
            val offset = frame.position.subtract(anchor)
            EchoFrame(
                EchoRoot(offset.x.toFloat(), offset.y.toFloat(), offset.z.toFloat(), frame.bodyYaw, frame.headYaw),
                frame.channels.copyOf(),
            )
        }
        return EchoClipCodec.encodeQuantized(EchoClip(EchoEncoding.BONE, EchoClip.SAMPLE_RATE, intArrayOf(), relativeFrames))
    }

    internal fun bufferedFrameCount(): Int = rollingFrames.size

    fun freezeRecentAnnotationClip(): ByteArray = encodeAnnotation(
        rollingFrames.snapshot().takeLast(EchoClip.SAMPLE_RATE * RECORDING_SECONDS), ClipAnchor.END, trim = false,
    )

    fun captureSelectedEmote(complete: (ByteArray) -> Unit, fail: (Throwable) -> Unit) {
        require(selectedCapture == null) { "another gesture capture is already running" }
        selectedCapture = SelectedCapture(mutableListOf(), complete, fail)
    }

    private fun encodeAnnotation(frames: List<AbsoluteFrame>, anchor: ClipAnchor, trim: Boolean): ByteArray {
        require(frames.isNotEmpty()) { "no player pose frames were captured" }
        val base = if (anchor == ClipAnchor.START) frames.first().position else frames.last().position
        val clip = EchoClip(EchoEncoding.BONE, EchoClip.SAMPLE_RATE, intArrayOf(), frames.map { frame ->
            val offset = frame.position.subtract(base)
            EchoFrame(EchoRoot(offset.x.toFloat(), offset.y.toFloat(), offset.z.toFloat(), frame.bodyYaw, frame.headYaw), frame.channels.copyOf())
        })
        val normalized = AnnotationClipTools.normalize(clip, anchor)
        val ready = if (trim) AnnotationClipTools.trimStaticPadding(normalized) else normalized
        val encoded = EchoClipCodec.encodeQuantized(ready)
        AnnotationEchoValidation.decode(encoded)
        return encoded
    }

    private fun captureBones(player: AbstractClientPlayer, partialTick: Float): FloatArray? {
        val renderer = Minecraft.getInstance().entityRenderDispatcher.getRenderer(player) as? PlayerRenderer ?: return null
        activePlayer = player
        capturedBones = null
        return try {
            val bodyYaw = lerpDegrees(partialTick, player.yBodyRotO, player.yBodyRot)
            renderer.render(player, bodyYaw, partialTick, PoseStack(), CaptureBufferSource(), FULL_BRIGHT)
            capturedBones
        } catch (error: RuntimeException) {
            log.debug("Could not sample rolling death echo pose", error)
            null
        } finally {
            activePlayer = null
            capturedBones = null
        }
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
        output[offset] = part.x; output[offset + 1] = part.y; output[offset + 2] = part.z
        output[offset + 3] = part.xRot; output[offset + 4] = part.yRot; output[offset + 5] = part.zRot
        output[offset + 6] = part.xScale; output[offset + 7] = part.yScale; output[offset + 8] = part.zScale
    }

    private fun clearIfDisconnected() {
        rollingFrames.clear()
        currentPlayer = null
        currentDimension = null
        lastSampleGameTime = Long.MIN_VALUE
        frozenDeathCapture = null
        selectedCapture?.fail?.invoke(IllegalStateException("gesture capture was interrupted"))
        selectedCapture = null
    }

    private fun lerpDegrees(amount: Float, from: Float, to: Float): Float {
        var delta = (to - from) % 360f
        if (delta < -180f) delta += 360f
        if (delta >= 180f) delta -= 360f
        return from + amount * delta
    }

    private fun radians(degrees: Float): Float = degrees * (PI.toFloat() / 180f)

    private data class AbsoluteFrame(
        val gameTime: Long,
        val position: Vec3,
        val bodyYaw: Float,
        val headYaw: Float,
        val channels: FloatArray,
    )

    private data class SelectedCapture(
        val frames: MutableList<AbsoluteFrame>,
        val complete: (ByteArray) -> Unit,
        val fail: (Throwable) -> Unit,
    )

    private data class FrozenDeathCapture(
        val token: UUID,
        val dimension: String,
        val anchor: Vec3,
        val encodedClip: ByteArray,
    )

    private const val RECORDING_SECONDS = 3
    private const val FULL_BRIGHT = 0x00F000F0
    private const val MAX_ANCHOR_DRIFT_SQUARED = 0.01
}
