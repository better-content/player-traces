package com.bettercontent.traces.network

import com.bettercontent.traces.TracesMod
import com.bettercontent.traces.config.TracesConfig
import com.bettercontent.traces.domain.GLOBAL_TEAM
import com.bettercontent.traces.logic.TraceQueryService
import net.minecraft.resources.ResourceLocation
import net.minecraftforge.network.NetworkEvent
import net.minecraftforge.network.NetworkRegistry
import net.minecraftforge.network.PacketDistributor
import net.minecraftforge.network.simple.SimpleChannel
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.fml.DistExecutor
import java.util.function.Supplier
import java.util.UUID
import com.mojang.logging.LogUtils

object TracesNetwork {
    private val logger = LogUtils.getLogger()
    private const val PROTOCOL = "traces_v6"
    private val channel: SimpleChannel = NetworkRegistry.newSimpleChannel(
        ResourceLocation.fromNamespaceAndPath("traces", "main"),
        { PROTOCOL },
        { it == PROTOCOL },
        { it == PROTOCOL }
    )

    fun register() {
        channel.registerMessage(0, TraceQueryRequestPacket::class.java, TraceQueryRequestPacket::encode, TraceQueryRequestPacket.Companion::decode) { msg, context ->
            onRequest(msg, context)
        }
        channel.registerMessage(1, TraceQueryResponsePacket::class.java, TraceQueryResponsePacket::encode, TraceQueryResponsePacket.Companion::decode) { msg, context ->
            onResponse(msg, context)
        }
        channel.registerMessage(2, TraceAnnotationsSeenPacket::class.java, TraceAnnotationsSeenPacket::encode, TraceAnnotationsSeenPacket.Companion::decode) { msg, context ->
            onAnnotationsSeen(msg, context)
        }
        channel.registerMessage(3, AnnotationCreatePacket::class.java, AnnotationCreatePacket::encode, AnnotationCreatePacket.Companion::decode) { msg, context -> onCreate(msg, context) }
        channel.registerMessage(4, AnnotationUpdatePacket::class.java, AnnotationUpdatePacket::encode, AnnotationUpdatePacket.Companion::decode) { msg, context -> onUpdate(msg, context) }
        channel.registerMessage(5, AnnotationDeletePacket::class.java, AnnotationDeletePacket::encode, AnnotationDeletePacket.Companion::decode) { msg, context -> onDelete(msg, context) }
        channel.registerMessage(6, DeathCaptureRequestPacket::class.java, DeathCaptureRequestPacket::encode, DeathCaptureRequestPacket.Companion::decode) { msg, context -> onDeathCaptureRequest(msg, context) }
        channel.registerMessage(7, DeathEchoSubmitPacket::class.java, DeathEchoSubmitPacket::encode, DeathEchoSubmitPacket.Companion::decode) { msg, context -> onDeathEchoSubmit(msg, context) }
        channel.registerMessage(8, AnnotationMutationResultPacket::class.java, AnnotationMutationResultPacket::encode, AnnotationMutationResultPacket.Companion::decode) { msg, context -> onMutationResult(msg, context) }
        channel.registerMessage(9, AnnotationEchoRequestPacket::class.java, AnnotationEchoRequestPacket::encode, AnnotationEchoRequestPacket.Companion::decode) { msg, context -> onAnnotationEchoRequest(msg, context) }
        channel.registerMessage(10, AnnotationEchoResponsePacket::class.java, AnnotationEchoResponsePacket::encode, AnnotationEchoResponsePacket.Companion::decode) { msg, context -> onAnnotationEchoResponse(msg, context) }
    }

    private fun onRequest(msg: TraceQueryRequestPacket, ctx: Supplier<NetworkEvent.Context>) {
        val context = ctx.get()
        val sender = context.sender ?: return
        context.enqueueWork {
            val radius = msg.radius.coerceIn(2, 16)
            val level = sender.serverLevel()
            val pos = sender.blockPosition()
            val blockRadius = radius * 16
            val min = net.minecraft.core.BlockPos(pos.x - blockRadius, level.minBuildHeight, pos.z - blockRadius)
            val max = net.minecraft.core.BlockPos(pos.x + blockRadius, level.maxBuildHeight - 1, pos.z + blockRadius)
            val result = TraceQueryService().tracesWithin(level, min, max)
            val runtime = TracesMod.getRuntime(level.server)
            val storage = runtime.storage(level)
            val guidance = runtime.guidance(level).query(sender)
            val guidanceTargets = guidance.routes.mapTo(HashSet()) { it.targetAnnotationId }
            val deathData = runtime.deathTraces(level)
            val minX = min.x.toDouble(); val maxX = max.x.toDouble()
            val minZ = min.z.toDouble(); val maxZ = max.z.toDouble()
            val bloodPools = deathData.poolsWithin(minX, maxX, minZ, maxZ)
                .sortedBy {
                    val dx = it.x - sender.x; val dz = it.z - sender.z
                    dx * dx + dz * dz
                }
                .take(TracesConfig.common.maxPayloadBloodPools.get())
                .map { com.bettercontent.traces.dto.VisibleBloodPoolDto(it.id.toString(), it.ownerName, it.x, it.y, it.z, it.createdAt) }
            val deathEchoes = deathData.echoesWithin(minX, maxX, minZ, maxZ)
                .sortedBy {
                    val dx = it.x - sender.x; val dz = it.z - sender.z
                    dx * dx + dz * dz
                }
                .take(TracesConfig.common.maxPayloadDeathEchoes.get())
                .map { com.bettercontent.traces.dto.VisibleDeathEchoDto(it.id.toString(), it.ownerName, it.x, it.y, it.z, it.createdAt, it.encodedClip) }
            val visibleAnnotations = result.annotations
                .filter { annotation -> annotation.team == GLOBAL_TEAM }
                .sortedWith(
                    compareByDescending<com.bettercontent.traces.domain.TraceAnnotation> { it.id.toString() in guidanceTargets }
                        .thenBy {
                            val dx = it.position.x - pos.x
                            val dz = it.position.z - pos.z
                            dx * dx + dz * dz
                        }
                        .thenBy { it.id }
                )
                .take(TracesConfig.common.maxPayloadAnnotations.get())
                .map {
                    val seen = storage.getSeen(sender.uuid, it.id) >= it.revision
                    val echo = runtime.annotationEchoes(level).get(it.id)
                    com.bettercontent.traces.dto.VisibleAnnotationDto(
                        id = it.id.toString(), text = it.text, icon = it.icon, color = it.color,
                        x = it.position.x, y = it.position.y, z = it.position.z, team = it.team.id,
                        revision = it.revision, seen = seen,
                        canEdit = it.createdByInternal == sender.uuid || sender.hasPermissions(2),
                        hasEcho = echo?.annotationRevision == it.revision,
                        echoRevision = echo?.takeIf { echo -> echo.annotationRevision == it.revision }?.annotationRevision ?: 0,
                    )
                }
            val response = TraceQueryResponsePacket(
                traces = result.traces
                    .sortedWith(compareByDescending<com.bettercontent.traces.domain.FootTrace> { it.sourcePlayerInternal == sender.uuid }.thenBy {
                        val dx = it.blockPos.x - pos.x
                        val dz = it.blockPos.z - pos.z
                        dx * dx + dz * dz
                    }.thenBy { it.id })
                    .take(TracesConfig.common.maxPayloadTraces.get())
                    .map {
                        com.bettercontent.traces.dto.VisibleTraceDto(
                            id = it.id.toString(), sequenceId = it.sequenceId.toString(), movementClass = it.movementClass,
                            x = it.x, y = it.y, z = it.z, facingYaw = it.facingYaw, strength = it.strength,
                            sequenceIndex = it.sequenceIndex,
                            own = it.sourcePlayerInternal == sender.uuid,
                        )
                    },
                annotations = visibleAnnotations,
                guidanceRoutes = guidance.routes,
                guidanceTotal = guidance.totalReachable,
                guidanceTruncated = guidance.truncated,
                bloodPools = bloodPools,
                deathEchoes = deathEchoes,
            )
            logger.info(
                "TRACES_MVP_RESPONSE captured={} returned={} footprints={} notes={}",
                TracesMod.getRuntime(level.server).capturedCount(), response.traces.size + response.annotations.size,
                response.traces.size, response.annotations.size,
            )
            logger.info("TRACES_ANNOTATION_ECHO_ADVERTISED count={}", visibleAnnotations.count { it.hasEcho })
            logger.info("TRACES_DEATH_RESPONSE pools={} echoes={}", response.bloodPools.size, response.deathEchoes.size)
            logger.debug("Trace query response: traces={}, annotations={}, radius={}", response.traces.size, response.annotations.size, radius)
            channel.send(PacketDistributor.PLAYER.with { sender }, response)
        }
        context.packetHandled = true
    }

    private fun onResponse(msg: TraceQueryResponsePacket, ctx: Supplier<NetworkEvent.Context>) {
        val context = ctx.get()
        context.enqueueWork {
            logger.debug("Trace query payload received on client: traces={}, annotations={}", msg.traces.size, msg.annotations.size)
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT) {
                Runnable { com.bettercontent.traces.client.TracesClientNetworkHandler.accept(msg) }
            }
        }
        context.packetHandled = true
    }

    private fun onAnnotationsSeen(msg: TraceAnnotationsSeenPacket, ctx: Supplier<NetworkEvent.Context>) {
        val context = ctx.get()
        val sender = context.sender ?: return
        context.enqueueWork {
            val annotations = TracesMod.getRuntime(sender.server).annotations(sender.serverLevel())
            msg.annotations.forEach {
                val id = runCatching { UUID.fromString(it.id) }.getOrNull() ?: return@forEach
                annotations.acknowledgeViewed(sender, id, it.revision)
            }
        }
        context.packetHandled = true
    }

    private fun onDeathCaptureRequest(msg: DeathCaptureRequestPacket, ctx: Supplier<NetworkEvent.Context>) {
        val context = ctx.get()
        context.enqueueWork {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT) {
                Runnable { com.bettercontent.traces.client.death.DeathEchoRecorder.onDeathConfirmed(msg) }
            }
        }
        context.packetHandled = true
    }

    private fun onDeathEchoSubmit(msg: DeathEchoSubmitPacket, ctx: Supplier<NetworkEvent.Context>) {
        val context = ctx.get()
        val sender = context.sender ?: return
        context.enqueueWork {
            runCatching { TracesMod.getRuntime(sender.server).acceptDeathEcho(sender, msg) }
                .onFailure { logger.warn("Rejected death echo from {}: {}", sender.scoreboardName, it.message) }
        }
        context.packetHandled = true
    }

    private fun onCreate(msg: AnnotationCreatePacket, ctx: Supplier<NetworkEvent.Context>) = mutateAnnotation(ctx, msg.requestId) { sender ->
        val level = sender.serverLevel()
        val echoes = TracesMod.getRuntime(sender.server).annotationEchoes(level)
        val decoded = msg.encodedClip?.also(com.bettercontent.traces.logic.AnnotationEchoValidation::decode)
        val hasEcho = msg.echoMutation == com.bettercontent.traces.domain.EchoMutation.REPLACE
        com.bettercontent.traces.domain.AnnotationComponents.validate(msg.text, msg.icon, msg.color, hasEcho)
        if (decoded != null) echoes.requireCapacity(UUID.randomUUID(), sender.uuid)
        val annotation = TracesMod.getRuntime(sender.server).annotations(level).createComponents(
            level, sender, msg.text, msg.icon, msg.color, net.minecraft.core.BlockPos.of(msg.target), hasEcho,
        )
        try {
            if (decoded != null) {
                echoes.requireCapacity(annotation.id, sender.uuid)
                echoes.replace(com.bettercontent.traces.domain.AnnotationEchoRecord(annotation.id, annotation.revision, sender.uuid, msg.encodedClip!!))
            }
        } catch (error: Throwable) {
            TracesMod.getRuntime(sender.server).storage(level).removeAnnotation(annotation.id)
            throw error
        }
        MutationOutcome(annotation.id.toString(), annotation.revision)
    }

    private fun onUpdate(msg: AnnotationUpdatePacket, ctx: Supplier<NetworkEvent.Context>) = mutateAnnotation(ctx, msg.requestId) { sender ->
        val id = UUID.fromString(msg.id)
        val level = sender.serverLevel()
        val runtime = TracesMod.getRuntime(sender.server)
        val echoes = runtime.annotationEchoes(level)
        val current = runtime.storage(level).annotationById(id) ?: throw IllegalArgumentException("annotation not found")
        val currentEcho = echoes.get(id)?.takeIf { it.annotationRevision == current.revision }
        val hasEchoAfter = when (msg.echoMutation) {
            com.bettercontent.traces.domain.EchoMutation.KEEP -> currentEcho != null
            com.bettercontent.traces.domain.EchoMutation.REPLACE -> true
            com.bettercontent.traces.domain.EchoMutation.REMOVE -> false
        }
        msg.encodedClip?.also(com.bettercontent.traces.logic.AnnotationEchoValidation::decode)
        if (msg.echoMutation == com.bettercontent.traces.domain.EchoMutation.REPLACE) echoes.requireCapacity(id, current.createdByInternal)
        val annotation = runtime.annotations(level).updateComponents(
            level, sender, id, msg.expectedRevision, msg.text, msg.icon, msg.color, hasEchoAfter,
        )
        when (msg.echoMutation) {
            com.bettercontent.traces.domain.EchoMutation.KEEP -> currentEcho?.let {
                echoes.replace(com.bettercontent.traces.domain.AnnotationEchoRecord(id, annotation.revision, it.ownerId, it.encodedClip))
            }
            com.bettercontent.traces.domain.EchoMutation.REPLACE -> echoes.replace(
                com.bettercontent.traces.domain.AnnotationEchoRecord(id, annotation.revision, current.createdByInternal, msg.encodedClip!!),
            )
            com.bettercontent.traces.domain.EchoMutation.REMOVE -> echoes.remove(id)
        }
        MutationOutcome(annotation.id.toString(), annotation.revision)
    }

    private fun onDelete(msg: AnnotationDeletePacket, ctx: Supplier<NetworkEvent.Context>) = mutateAnnotation(ctx) { sender ->
        val id = UUID.fromString(msg.id)
        check(TracesMod.getRuntime(sender.server).annotations(sender.serverLevel()).delete(
            sender.serverLevel(), sender, id, msg.expectedRevision,
        )) { "annotation delete denied" }
        TracesMod.getRuntime(sender.server).annotationEchoes(sender.serverLevel()).remove(id)
        sender.sendSystemMessage(net.minecraft.network.chat.Component.literal("Annotation deleted"))
    }

    private fun mutateAnnotation(ctx: Supplier<NetworkEvent.Context>, requestId: UUID, mutation: (net.minecraft.server.level.ServerPlayer) -> MutationOutcome) {
        val context = ctx.get()
        val sender = context.sender ?: return
        context.enqueueWork {
            val response = runCatching { mutation(sender) }.fold(
                { AnnotationMutationResultPacket(requestId, true, it.id, it.revision, "") },
                { AnnotationMutationResultPacket(requestId, false, "", 0, it.message ?: "Annotation request rejected") },
            )
            channel.send(PacketDistributor.PLAYER.with { sender }, response)
        }
        context.packetHandled = true
    }

    private fun mutateAnnotation(ctx: Supplier<NetworkEvent.Context>, mutation: (net.minecraft.server.level.ServerPlayer) -> Unit) {
        val context = ctx.get(); val sender = context.sender ?: return
        context.enqueueWork { runCatching { mutation(sender) }.onFailure {
            sender.sendSystemMessage(net.minecraft.network.chat.Component.literal(it.message ?: "Annotation request rejected"))
        } }
        context.packetHandled = true
    }

    private data class MutationOutcome(val id: String, val revision: Int)

    private fun onMutationResult(msg: AnnotationMutationResultPacket, ctx: Supplier<NetworkEvent.Context>) {
        val context = ctx.get()
        context.enqueueWork { DistExecutor.unsafeRunWhenOn(Dist.CLIENT) { Runnable {
            com.bettercontent.traces.client.AnnotationDrafts.accept(msg)
        } } }
        context.packetHandled = true
    }

    private fun onAnnotationEchoRequest(msg: AnnotationEchoRequestPacket, ctx: Supplier<NetworkEvent.Context>) {
        val context = ctx.get(); val sender = context.sender ?: return
        context.enqueueWork {
            val id = runCatching { UUID.fromString(msg.annotationId) }.getOrNull()
            val annotation = id?.let { TracesMod.getRuntime(sender.server).storage(sender.serverLevel()).annotationById(it) }
            val echo = id?.let { TracesMod.getRuntime(sender.server).annotationEchoes(sender.serverLevel()).get(it) }
                ?.takeIf { annotation != null && it.annotationRevision == annotation.revision && it.annotationRevision == msg.echoRevision }
            channel.send(PacketDistributor.PLAYER.with { sender }, AnnotationEchoResponsePacket(msg.annotationId, msg.echoRevision, echo?.encodedClip))
            logger.info("TRACES_ANNOTATION_ECHO_RESPONSE annotation={} revision={} bytes={}", msg.annotationId, msg.echoRevision, echo?.encodedClip?.size ?: 0)
        }
        context.packetHandled = true
    }

    private fun onAnnotationEchoResponse(msg: AnnotationEchoResponsePacket, ctx: Supplier<NetworkEvent.Context>) {
        val context = ctx.get()
        context.enqueueWork { DistExecutor.unsafeRunWhenOn(Dist.CLIENT) { Runnable {
            com.bettercontent.traces.client.TracesClientState.acceptAnnotationEcho(msg)
        } } }
        context.packetHandled = true
    }

    fun requestNearby(radius: Int) {
        channel.sendToServer(TraceQueryRequestPacket(radius.coerceIn(2, 16)))
    }

    fun acknowledgeAnnotations(annotations: List<com.bettercontent.traces.dto.VisibleAnnotationDto>) {
        if (annotations.isNotEmpty()) {
            channel.sendToServer(TraceAnnotationsSeenPacket(annotations.map { SeenAnnotation(it.id, it.revision) }))
        }
    }

    fun createAnnotation(packet: AnnotationCreatePacket) = channel.sendToServer(packet)
    fun updateAnnotation(packet: AnnotationUpdatePacket) = channel.sendToServer(packet)
    fun createAnnotation(target: net.minecraft.core.BlockPos, text: String) = channel.sendToServer(AnnotationCreatePacket(target.asLong(), text))
    fun updateAnnotation(id: String, expectedRevision: Int, text: String) = channel.sendToServer(AnnotationUpdatePacket(id, expectedRevision, text))
    fun deleteAnnotation(id: String, expectedRevision: Int) = channel.sendToServer(AnnotationDeletePacket(id, expectedRevision))
    fun requestDeathEcho(player: net.minecraft.server.level.ServerPlayer, packet: DeathCaptureRequestPacket) =
        channel.send(PacketDistributor.PLAYER.with { player }, packet)
    fun submitDeathEcho(packet: DeathEchoSubmitPacket) = channel.sendToServer(packet)
    fun requestAnnotationEcho(id: String, revision: Int) = channel.sendToServer(AnnotationEchoRequestPacket(id, revision))
}
