package com.bettercontent.traces.prototype

import com.bettercontent.traces.client.death.DeathEchoRenderer
import com.bettercontent.traces.client.death.GeometrySnapshot
import com.bettercontent.traces.echo.*

import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.world.phys.Vec3
import net.minecraftforge.client.event.RenderGuiEvent
import net.minecraftforge.client.event.RenderLevelStageEvent
import net.minecraftforge.event.TickEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import org.slf4j.LoggerFactory
import java.nio.file.Files
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin

internal object EchoPrototypeController {
    private val log = LoggerFactory.getLogger("TracesEchoPrototype")
    private var phase = Phase.IDLE
    private var durationSeconds = 3
    private var countdownTicks = 0
    private var recordingStartedNanos = 0L
    private var replayStartedNanos = 0L
    private var origin: Vec3? = null
    private var originYaw = 0f
    private var cameraAtStart = "unknown"
    private val boneFrames = ArrayList<EchoFrame>()
    private val geometryFrames = ArrayList<EchoFrame>()
    private var geometryTopology: IntArray? = null
    private var nextFrame = 0
    private var droppedSamples = 0
    private var rejectedCaptures = 0
    private var topologyMismatches = 0
    private var boneClip: EchoClip? = null
    private var geometryClip: EchoClip? = null
    private var status = "F8: record 3s echo | F7: duration | F6: PlayerAnimator bend"
    private var automaticRunStarted = false

    @SubscribeEvent
    fun onClientTick(event: TickEvent.ClientTickEvent) {
        if (event.phase != TickEvent.Phase.END) return
        val mc = Minecraft.getInstance()
        if (mc.player == null || mc.level == null) return
        if (!automaticRunStarted && java.lang.Boolean.getBoolean("traces.echoPrototype")) {
            automaticRunStarted = true
            beginCountdown()
        }
        while (EchoPrototypeKeys.quarkWave.consumeClick()) triggerQuarkWave()
        while (EchoPrototypeKeys.syntheticBend.consumeClick()) {
            val enabled = SyntheticBendAnimation.toggle()
            status = "Synthetic PlayerAnimator bend ${if (enabled) "enabled" else "disabled"}"
            tell(status)
        }
        while (EchoPrototypeKeys.duration.consumeClick()) {
            if (phase == Phase.RECORDING || phase == Phase.COUNTDOWN) continue
            durationSeconds = durationSeconds % EchoClip.MAX_DURATION_SECONDS + 1
            status = "Echo recording duration: ${durationSeconds}s"
            tell(status)
        }
        while (EchoPrototypeKeys.record.consumeClick()) {
            if (phase == Phase.COUNTDOWN || phase == Phase.RECORDING) cancelRecording() else beginCountdown()
        }
        if (phase == Phase.COUNTDOWN) {
            countdownTicks--
            if (countdownTicks <= 0) {
                phase = Phase.RECORDING
                triggerQuarkWave()
                status = "RECORDING ${durationSeconds}s — move or perform an emote"
                tell(status)
            }
        }
    }

    @SubscribeEvent
    fun onRenderLevel(event: RenderLevelStageEvent) {
        if (event.stage != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return
        if (phase == Phase.RECORDING) captureFrame(event.partialTick)
        if (phase == Phase.REPLAY) renderReplay(event)
    }

    @SubscribeEvent
    fun onRenderGui(event: RenderGuiEvent.Post) {
        val mc = Minecraft.getInstance()
        if (mc.options.hideGui || mc.player == null) return
        val message = when (phase) {
            Phase.COUNTDOWN -> "Echo recording in ${max(1, (countdownTicks + 19) / 20)}…"
            Phase.RECORDING -> "● RECORDING ${boneFrames.size}/${durationSeconds * EchoClip.SAMPLE_RATE}"
            Phase.REPLAY -> "F8 re-record | F7 ${durationSeconds}s | F6 synthetic bend"
            Phase.IDLE -> status
        }
        event.guiGraphics.drawCenteredString(mc.font, Component.literal(message), event.window.guiScaledWidth / 2, 12, 0xA8F8FF)
    }

    private fun beginCountdown() {
        phase = Phase.COUNTDOWN
        countdownTicks = COUNTDOWN_SECONDS * 20
        boneFrames.clear()
        geometryFrames.clear()
        geometryTopology = null
        origin = null
        nextFrame = 0
        droppedSamples = 0
        rejectedCaptures = 0
        topologyMismatches = 0
        boneClip = null
        geometryClip = null
        status = "Echo recording armed"
        tell("Echo recording starts in $COUNTDOWN_SECONDS seconds. Use Quark or move now.")
    }

    private fun cancelRecording() {
        phase = Phase.IDLE
        status = "Echo recording cancelled"
        tell(status)
    }

    private fun captureFrame(partialTick: Float) {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        val now = System.nanoTime()
        if (origin == null) {
            origin = player.getPosition(partialTick)
            originYaw = EchoPoseCapture.radians(lerpDegrees(partialTick, player.yBodyRotO, player.yBodyRot))
            cameraAtStart = mc.options.cameraType.name.lowercase()
            recordingStartedNanos = now
        }
        val desiredFrames = durationSeconds * EchoClip.SAMPLE_RATE
        val elapsedSeconds = (now - recordingStartedNanos) / 1_000_000_000.0
        val targetFrame = floor(elapsedSeconds * EchoClip.SAMPLE_RATE).toInt().coerceIn(0, desiredFrames - 1)
        if (targetFrame < nextFrame) return

        while (nextFrame < targetFrame && boneFrames.isNotEmpty()) {
            boneFrames += cloneFrame(boneFrames.last())
            if (geometryFrames.isNotEmpty()) geometryFrames += cloneFrame(geometryFrames.last())
            droppedSamples++
            nextFrame++
        }

        val captured = try {
            EchoPoseCapture.capture(player, partialTick)
        } catch (error: RuntimeException) {
            log.warn("Echo pose capture rejected", error)
            null
        }
        if (captured == null) {
            rejectedCaptures++
            if (elapsedSeconds > durationSeconds + 1.0) abortFailedCapture()
            return
        }

        val start = origin ?: return
        val position = player.getPosition(partialTick).subtract(start)
        val bodyYaw = EchoPoseCapture.radians(lerpDegrees(partialTick, player.yBodyRotO, player.yBodyRot))
        val headYaw = EchoPoseCapture.radians(lerpDegrees(partialTick, player.yHeadRotO, player.yHeadRot))
        boneFrames += EchoFrame(
            EchoRoot(position.x.toFloat(), position.y.toFloat(), position.z.toFloat(), bodyYaw, headYaw),
            captured.boneChannels,
        )
        acceptGeometryFrame(position, captured.geometry)
        nextFrame++

        if (nextFrame >= desiredFrames) finishRecording()
    }

    private fun acceptGeometryFrame(position: Vec3, snapshot: GeometrySnapshot) {
        val topology = geometryTopology
        if (topology == null) {
            if (snapshot.vertices.isEmpty() || snapshot.topology.isEmpty()) {
                rejectedCaptures++
                return
            }
            geometryTopology = snapshot.topology
            geometryFrames += EchoFrame(
                EchoRoot(position.x.toFloat(), position.y.toFloat(), position.z.toFloat(), 0f, 0f),
                snapshot.vertices,
            )
            return
        }
        val valid = snapshot.vertices.size == geometryFrames.first().channels.size && topology.contentEquals(snapshot.topology)
        if (valid) {
            geometryFrames += EchoFrame(
                EchoRoot(position.x.toFloat(), position.y.toFloat(), position.z.toFloat(), 0f, 0f),
                snapshot.vertices,
            )
        } else {
            topologyMismatches++
            if (geometryFrames.isNotEmpty()) geometryFrames += cloneFrame(geometryFrames.last())
        }
    }

    private fun finishRecording() {
        val topology = geometryTopology
        boneClip = EchoClip(EchoEncoding.BONE, EchoClip.SAMPLE_RATE, intArrayOf(), boneFrames.toList())
        geometryClip = if (topology != null && geometryFrames.size == boneFrames.size) {
            EchoClip(EchoEncoding.GEOMETRY, EchoClip.SAMPLE_RATE, topology, geometryFrames.toList())
        } else null
        replayStartedNanos = System.nanoTime()
        phase = Phase.REPLAY
        val boneMeasurement = EchoClipCodec.measure(boneClip!!)
        val geometryMeasurement = geometryClip?.let(EchoClipCodec::measure)
        status = if (geometryMeasurement == null) "Bone replay ready; geometry topology was unstable" else "Bone and exact-geometry replays ready"
        log.info(
            "ECHO_PROTOTYPE_CAPTURE camera={} requested={} captured={} dropped={} rejected={} topologyMismatches={} boneQuantized={} boneDeflated={} geometryQuantized={} geometryDeflated={}",
            cameraAtStart, durationSeconds * EchoClip.SAMPLE_RATE, boneFrames.size, droppedSamples, rejectedCaptures, topologyMismatches,
            boneMeasurement.quantizedBytes, boneMeasurement.deflatedQuantizedBytes,
            geometryMeasurement?.quantizedBytes ?: -1, geometryMeasurement?.deflatedQuantizedBytes ?: -1,
        )
        writeReport(boneMeasurement, geometryMeasurement)
        tell(status)
    }

    private fun renderReplay(event: RenderLevelStageEvent) {
        val mc = Minecraft.getInstance()
        val camera = event.camera
        val anchor = origin ?: return
        val bone = boneClip ?: return
        val elapsed = (System.nanoTime() - replayStartedNanos) / 1_000_000_000.0
        val cycle = durationSeconds + REPLAY_PAUSE_SECONDS
        val phaseSeconds = (elapsed % cycle).toFloat()
        val playback = phaseSeconds.coerceAtMost(bone.durationSeconds)
        val fade = replayFade(phaseSeconds, bone.durationSeconds)
        if (fade <= 0f) return
        val right = Vec3(cos(originYaw.toDouble()), 0.0, sin(originYaw.toDouble()))
        val pose = event.poseStack
        pose.pushPose()
        pose.translate(-camera.position.x, -camera.position.y, -camera.position.z)
        val buffers = mc.renderBuffers().bufferSource()
        try {
            val boneEdges = DeathEchoRenderer.render(
                bone, anchor.add(right.scale(-SIDE_OFFSET)), playback, elapsed, pose, buffers, fade,
            )
            val geometryEdges = geometryClip?.let {
                DeathEchoRenderer.render(it, anchor.add(right.scale(SIDE_OFFSET)), playback, elapsed, pose, buffers, fade)
            } ?: 0
            DeathEchoRenderer.endBatch(buffers)
            if ((mc.level?.gameTime ?: 0L) % 100L == 0L) {
                log.info("ECHO_PROTOTYPE_RENDER boneEdges={} geometryEdges={} fade={}", boneEdges, geometryEdges, fade)
            }
        } finally {
            pose.popPose()
        }
    }

    private fun replayFade(seconds: Float, clipDuration: Float): Float {
        if (seconds > clipDuration) return 0f
        val fadeIn = (seconds / 0.35f).coerceIn(0f, 1f)
        val fadeOut = ((clipDuration - seconds) / 0.5f).coerceIn(0f, 1f)
        return minOf(fadeIn, fadeOut)
    }

    private fun abortFailedCapture() {
        phase = Phase.IDLE
        status = "Echo capture failed; inspect the prototype log"
        log.error("ECHO_PROTOTYPE_CAPTURE_FAILED rejected={} frames={}", rejectedCaptures, boneFrames.size)
        tell(status)
    }

    private fun writeReport(bone: EchoSizeMeasurement, geometry: EchoSizeMeasurement?) {
        val mc = Minecraft.getInstance()
        val reportDir = mc.gameDirectory.toPath().resolve("echo-prototype")
        try {
            Files.createDirectories(reportDir)
            val geometryJson = geometry?.let {
                "{\"raw\":${it.rawBytes},\"quantized\":${it.quantizedBytes},\"deflatedRaw\":${it.deflatedRawBytes},\"deflatedQuantized\":${it.deflatedQuantizedBytes}}"
            } ?: "null"
            val report = """{
  "durationSeconds": $durationSeconds,
  "sampleRate": ${EchoClip.SAMPLE_RATE},
  "cameraAtStart": "$cameraAtStart",
  "requestedFrames": ${durationSeconds * EchoClip.SAMPLE_RATE},
  "capturedFrames": ${boneFrames.size},
  "droppedSamples": $droppedSamples,
  "rejectedCaptures": $rejectedCaptures,
  "topologyMismatches": $topologyMismatches,
  "geometryVertices": ${(geometryClip?.channelCount ?: 0) / 3},
  "geometryEdges": ${(geometryClip?.topology?.size ?: 0) / 2},
  "boneBytes": {"raw":${bone.rawBytes},"quantized":${bone.quantizedBytes},"deflatedRaw":${bone.deflatedRawBytes},"deflatedQuantized":${bone.deflatedQuantizedBytes}},
  "geometryBytes": $geometryJson
}
"""
            Files.writeString(reportDir.resolve("latest-report.json"), report)
        } catch (error: Exception) {
            log.warn("Could not write echo prototype report", error)
        }
    }

    private fun tell(message: String) {
        Minecraft.getInstance().player?.displayClientMessage(
            Component.literal("[Echo Prototype] ").withStyle(ChatFormatting.AQUA)
                .append(Component.literal(message).withStyle(ChatFormatting.WHITE)),
            false,
        )
    }

    private fun triggerQuarkWave() {
        try {
            com.bettercontent.traces.client.QuarkEmoteBridge.request("wave")
            log.info("ECHO_PROTOTYPE_QUARK emote=wave triggered=true path=request_to_server")
            tell("Triggered Quark wave")
        } catch (error: RuntimeException) {
            log.error("ECHO_PROTOTYPE_QUARK emote=wave triggered=false", error)
            tell("Could not trigger Quark wave; inspect log")
        }
    }

    private fun cloneFrame(frame: EchoFrame): EchoFrame = EchoFrame(frame.root.copy(), frame.channels.copyOf())

    private fun lerpDegrees(amount: Float, from: Float, to: Float): Float {
        var delta = (to - from) % 360f
        if (delta < -180f) delta += 360f
        if (delta >= 180f) delta -= 360f
        return from + amount * delta
    }

    private enum class Phase { IDLE, COUNTDOWN, RECORDING, REPLAY }

    private const val COUNTDOWN_SECONDS = 3
    private const val REPLAY_PAUSE_SECONDS = 0.8
    private const val SIDE_OFFSET = 1.35
}
