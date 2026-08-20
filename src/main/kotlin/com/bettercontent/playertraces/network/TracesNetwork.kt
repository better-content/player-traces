package com.bettercontent.playertraces.network

import com.bettercontent.playertraces.TracesMod
import com.bettercontent.playertraces.config.TracesConfig
import com.bettercontent.playertraces.domain.GLOBAL_TEAM
import net.minecraft.resources.ResourceLocation
import net.minecraftforge.network.NetworkEvent
import net.minecraftforge.network.NetworkRegistry
import net.minecraftforge.network.PacketDistributor
import net.minecraftforge.network.simple.SimpleChannel
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.fml.DistExecutor
import java.util.function.Supplier
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import com.mojang.logging.LogUtils

object TracesNetwork {
    private val logger = LogUtils.getLogger()
    private const val PROTOCOL = "player_traces_v7"
    private const val MAX_TILE_SNAPSHOTS_PER_POLL = 64
    private const val TILE_PAGE_SIZE = 1024
    private val nextSubscriptionGeneration = AtomicLong(1L)
    private val subscriptions = ConcurrentHashMap<UUID, ServerTraceSubscription>()
    private val loginGameTimes = ConcurrentHashMap<UUID, Long>()
    private val channel: SimpleChannel = NetworkRegistry.newSimpleChannel(
        ResourceLocation.fromNamespaceAndPath("player_traces", "main"),
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
        channel.registerMessage(11, TraceTileSnapshotPacket::class.java, TraceTileSnapshotPacket::encode, TraceTileSnapshotPacket.Companion::decode) { msg, context -> onTraceTileSnapshot(msg, context) }
        channel.registerMessage(12, TraceTileEvictPacket::class.java, TraceTileEvictPacket::encode, TraceTileEvictPacket.Companion::decode) { msg, context -> onTraceTileEvict(msg, context) }
        channel.registerMessage(13, DownedCaptureFreezePacket::class.java, DownedCaptureFreezePacket::encode, DownedCaptureFreezePacket.Companion::decode) { msg, context -> onDownedCaptureFreeze(msg, context) }
        channel.registerMessage(14, DownedCaptureDiscardPacket::class.java, DownedCaptureDiscardPacket::encode, DownedCaptureDiscardPacket.Companion::decode) { msg, context -> onDownedCaptureDiscard(msg, context) }
    }

    private fun onRequest(msg: TraceQueryRequestPacket, ctx: Supplier<NetworkEvent.Context>) {
        val context = ctx.get()
        val sender = context.sender ?: return
        context.enqueueWork {
            val radius = msg.radius.coerceIn(2, TracesConfig.common.maxRenderDistance.get())
            val level = sender.serverLevel()
            val pos = sender.blockPosition()
            val blockRadius = radius * 16
            val min = net.minecraft.core.BlockPos(pos.x - blockRadius, level.minBuildHeight, pos.z - blockRadius)
            val max = net.minecraft.core.BlockPos(pos.x + blockRadius, level.maxBuildHeight - 1, pos.z + blockRadius)
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
                .map { com.bettercontent.playertraces.dto.VisibleBloodPoolDto(it.id.toString(), it.ownerName, it.x, it.y, it.z, it.createdAt) }
            val deathEchoes = deathData.echoesWithin(minX, maxX, minZ, maxZ)
                .sortedBy {
                    val dx = it.x - sender.x; val dz = it.z - sender.z
                    dx * dx + dz * dz
                }
                .take(TracesConfig.common.maxPayloadDeathEchoes.get())
                .map { com.bettercontent.playertraces.dto.VisibleDeathEchoDto(it.id.toString(), it.ownerName, it.x, it.y, it.z, it.createdAt, it.encodedClip) }
            val visibleAnnotations = storage.queryAnnotations(min, max)
                .filter { annotation -> annotation.team == GLOBAL_TEAM }
                .sortedWith(
                    compareByDescending<com.bettercontent.playertraces.domain.TraceAnnotation> { it.id.toString() in guidanceTargets }
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
                    com.bettercontent.playertraces.dto.VisibleAnnotationDto(
                        id = it.id.toString(), text = it.text, icon = it.icon, color = it.color,
                        x = it.position.x, y = it.position.y, z = it.position.z, team = it.team.id,
                        revision = it.revision, seen = seen,
                        canEdit = it.createdByInternal == sender.uuid || sender.hasPermissions(2),
                        hasEcho = echo?.annotationRevision == it.revision,
                        echoRevision = echo?.takeIf { echo -> echo.annotationRevision == it.revision }?.annotationRevision ?: 0,
                    )
                }
            val dimension = level.dimension().location().toString()
            val subscription = subscription(sender, dimension, radius, level.gameTime)
            val response = TraceQueryResponsePacket(
                traces = emptyList(),
                annotations = visibleAnnotations,
                guidanceRoutes = guidance.routes,
                guidanceTotal = guidance.totalReachable,
                guidanceTruncated = guidance.truncated,
                bloodPools = bloodPools,
                deathEchoes = deathEchoes,
                subscriptionGeneration = subscription.generation,
                dimension = dimension,
                loginGameTime = subscription.loginGameTime,
            )
            if (java.lang.Boolean.getBoolean("traces.visualValidation")) {
                logger.debug(
                    "Trace query response: traces={}, annotations={}, annotationEchoes={}, bloodPools={}, deathEchoes={}, radius={}",
                    response.traces.size, response.annotations.size, visibleAnnotations.count { it.hasEcho },
                    response.bloodPools.size, response.deathEchoes.size, radius,
                )
            }
            channel.send(PacketDistributor.PLAYER.with { sender }, response)
            streamTraceTiles(sender, level, storage, subscription)
        }
        context.packetHandled = true
    }

    private fun onResponse(msg: TraceQueryResponsePacket, ctx: Supplier<NetworkEvent.Context>) {
        val context = ctx.get()
        context.enqueueWork {
            logger.debug("Trace query payload received on client: traces={}, annotations={}", msg.traces.size, msg.annotations.size)
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT) {
                Runnable { com.bettercontent.playertraces.client.TracesClientNetworkHandler.accept(msg) }
            }
        }
        context.packetHandled = true
    }

    private fun onTraceTileSnapshot(msg: TraceTileSnapshotPacket, ctx: Supplier<NetworkEvent.Context>) {
        val context = ctx.get()
        context.enqueueWork {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT) { Runnable {
                com.bettercontent.playertraces.client.TracesClientState.acceptTraceTilePage(
                    msg.generation,
                    msg.dimension,
                    com.bettercontent.playertraces.client.TraceTileKey(msg.chunkX, msg.chunkZ),
                    msg.revision,
                    msg.pageIndex,
                    msg.pageCount,
                    msg.traces,
                )
            } }
        }
        context.packetHandled = true
    }

    private fun onTraceTileEvict(msg: TraceTileEvictPacket, ctx: Supplier<NetworkEvent.Context>) {
        val context = ctx.get()
        context.enqueueWork {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT) { Runnable {
                com.bettercontent.playertraces.client.TracesClientState.evictTraceTiles(
                    msg.generation,
                    msg.dimension,
                    msg.tiles.map { com.bettercontent.playertraces.client.TraceTileKey(it.chunkX, it.chunkZ) },
                )
            } }
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
                Runnable { com.bettercontent.playertraces.client.death.DeathEchoRecorder.onDeathConfirmed(msg, msg.captureToken) }
            }
        }
        context.packetHandled = true
    }

    private fun onDownedCaptureFreeze(msg: DownedCaptureFreezePacket, ctx: Supplier<NetworkEvent.Context>) {
        val context = ctx.get()
        context.enqueueWork { DistExecutor.unsafeRunWhenOn(Dist.CLIENT) { Runnable {
            com.bettercontent.playertraces.client.death.DeathEchoRecorder.freezeForDowned(
                msg.token, msg.dimension, msg.x, msg.y, msg.z, msg.downGameTime,
            )
        } } }
        context.packetHandled = true
    }

    private fun onDownedCaptureDiscard(msg: DownedCaptureDiscardPacket, ctx: Supplier<NetworkEvent.Context>) {
        val context = ctx.get()
        context.enqueueWork { DistExecutor.unsafeRunWhenOn(Dist.CLIENT) { Runnable {
            com.bettercontent.playertraces.client.death.DeathEchoRecorder.discardDownedCapture(msg.token)
        } } }
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
        val decoded = msg.encodedClip?.also(com.bettercontent.playertraces.logic.AnnotationEchoValidation::decode)
        val hasEcho = msg.echoMutation == com.bettercontent.playertraces.domain.EchoMutation.REPLACE
        com.bettercontent.playertraces.domain.AnnotationComponents.validate(msg.text, msg.icon, msg.color, hasEcho)
        if (decoded != null) echoes.requireCapacity(UUID.randomUUID(), sender.uuid)
        val annotation = TracesMod.getRuntime(sender.server).annotations(level).createComponents(
            level, sender, msg.text, msg.icon, msg.color, net.minecraft.core.BlockPos.of(msg.target), hasEcho,
        )
        try {
            if (decoded != null) {
                echoes.requireCapacity(annotation.id, sender.uuid)
                echoes.replace(com.bettercontent.playertraces.domain.AnnotationEchoRecord(annotation.id, annotation.revision, sender.uuid, msg.encodedClip!!))
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
            com.bettercontent.playertraces.domain.EchoMutation.KEEP -> currentEcho != null
            com.bettercontent.playertraces.domain.EchoMutation.REPLACE -> true
            com.bettercontent.playertraces.domain.EchoMutation.REMOVE -> false
        }
        msg.encodedClip?.also(com.bettercontent.playertraces.logic.AnnotationEchoValidation::decode)
        if (msg.echoMutation == com.bettercontent.playertraces.domain.EchoMutation.REPLACE) echoes.requireCapacity(id, current.createdByInternal)
        val annotation = runtime.annotations(level).updateComponents(
            level, sender, id, msg.expectedRevision, msg.text, msg.icon, msg.color, hasEchoAfter,
        )
        when (msg.echoMutation) {
            com.bettercontent.playertraces.domain.EchoMutation.KEEP -> currentEcho?.let {
                echoes.replace(com.bettercontent.playertraces.domain.AnnotationEchoRecord(id, annotation.revision, it.ownerId, it.encodedClip))
            }
            com.bettercontent.playertraces.domain.EchoMutation.REPLACE -> echoes.replace(
                com.bettercontent.playertraces.domain.AnnotationEchoRecord(id, annotation.revision, current.createdByInternal, msg.encodedClip!!),
            )
            com.bettercontent.playertraces.domain.EchoMutation.REMOVE -> echoes.remove(id)
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
            com.bettercontent.playertraces.client.AnnotationDrafts.accept(msg)
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
            if (java.lang.Boolean.getBoolean("traces.visualValidation")) {
                logger.debug("Annotation echo response: annotation={}, revision={}, bytes={}", msg.annotationId, msg.echoRevision, echo?.encodedClip?.size ?: 0)
            }
        }
        context.packetHandled = true
    }

    private fun onAnnotationEchoResponse(msg: AnnotationEchoResponsePacket, ctx: Supplier<NetworkEvent.Context>) {
        val context = ctx.get()
        context.enqueueWork { DistExecutor.unsafeRunWhenOn(Dist.CLIENT) { Runnable {
            com.bettercontent.playertraces.client.TracesClientState.acceptAnnotationEcho(msg)
        } } }
        context.packetHandled = true
    }

    fun requestNearby(radius: Int) {
        channel.sendToServer(TraceQueryRequestPacket(radius.coerceIn(2, 32)))
    }

    fun onPlayerLogin(player: net.minecraft.server.level.ServerPlayer) {
        loginGameTimes[player.uuid] = player.serverLevel().gameTime
        subscriptions.remove(player.uuid)
    }

    fun onPlayerLogout(player: net.minecraft.server.level.ServerPlayer) {
        loginGameTimes.remove(player.uuid)
        subscriptions.remove(player.uuid)
    }

    fun acknowledgeAnnotations(annotations: List<com.bettercontent.playertraces.dto.VisibleAnnotationDto>) {
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
    fun freezeDeathEcho(
        player: net.minecraft.server.level.ServerPlayer,
        token: UUID,
        dimension: String,
        position: net.minecraft.world.phys.Vec3,
        downGameTime: Long,
    ) = channel.send(
        PacketDistributor.PLAYER.with { player },
        DownedCaptureFreezePacket(token, dimension, position.x, position.y, position.z, downGameTime),
    )
    fun discardDeathEcho(player: net.minecraft.server.level.ServerPlayer, token: UUID) =
        channel.send(PacketDistributor.PLAYER.with { player }, DownedCaptureDiscardPacket(token))
    fun submitDeathEcho(packet: DeathEchoSubmitPacket) = channel.sendToServer(packet)
    fun requestAnnotationEcho(id: String, revision: Int) = channel.sendToServer(AnnotationEchoRequestPacket(id, revision))

    private fun subscription(
        player: net.minecraft.server.level.ServerPlayer,
        dimension: String,
        radius: Int,
        now: Long,
    ): ServerTraceSubscription {
        val current = subscriptions[player.uuid]
        if (current != null && current.dimension == dimension && current.radius == radius) return current
        return ServerTraceSubscription(
            dimension = dimension,
            radius = radius,
            generation = nextSubscriptionGeneration.getAndIncrement(),
            loginGameTime = loginGameTimes.getOrPut(player.uuid) { now },
        ).also { subscriptions[player.uuid] = it }
    }

    private fun streamTraceTiles(
        player: net.minecraft.server.level.ServerPlayer,
        level: net.minecraft.server.level.ServerLevel,
        storage: com.bettercontent.playertraces.storage.TraceStorageManager,
        subscription: ServerTraceSubscription,
    ) {
        val centerX = player.chunkPosition().x
        val centerZ = player.chunkPosition().z
        val wanted = HashSet<TileCoordinate>()
        for (chunkX in centerX - subscription.radius..centerX + subscription.radius) {
            for (chunkZ in centerZ - subscription.radius..centerZ + subscription.radius) {
                wanted += TileCoordinate(chunkX, chunkZ)
            }
        }

        val evicted = subscription.sentRevisions.keys.filter { it !in wanted }
        evicted.chunked(512).forEach { batch ->
            channel.send(
                PacketDistributor.PLAYER.with { player },
                TraceTileEvictPacket(
                    subscription.generation,
                    subscription.dimension,
                    batch.map { TraceTileCoordinate(it.chunkX, it.chunkZ) },
                ),
            )
        }
        evicted.forEach(subscription.sentRevisions::remove)

        var tileCount = 0
        var recordCount = 0
        val recordBudget = TracesConfig.common.maxPayloadTraces.get()
        wanted.asSequence()
            .sortedWith(compareBy<TileCoordinate> {
                val dx = it.chunkX - centerX; val dz = it.chunkZ - centerZ
                dx * dx + dz * dz
            }.thenBy { it.chunkX }.thenBy { it.chunkZ })
            .forEach { tile ->
                if (tileCount >= MAX_TILE_SNAPSHOTS_PER_POLL || recordCount >= recordBudget) return@forEach
                storage.pruneInvalidSupports(tile.chunkX, tile.chunkZ) { support ->
                    val state = level.getBlockState(support.position)
                    !state.isAir && net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.block) == support.blockId
                }
                val revision = storage.tileRevision(tile.chunkX, tile.chunkZ)
                if (subscription.sentRevisions[tile] == revision) return@forEach
                val traces = storage.queryTraceTile(tile.chunkX, tile.chunkZ).map(::visibleTrace)
                if (traces.isNotEmpty() && recordCount > 0 && recordCount + traces.size > recordBudget) return@forEach
                val pages = traces.chunked(TILE_PAGE_SIZE).ifEmpty { listOf(emptyList()) }
                pages.forEachIndexed { pageIndex, page ->
                    channel.send(
                        PacketDistributor.PLAYER.with { player },
                        TraceTileSnapshotPacket(
                            subscription.generation,
                            subscription.dimension,
                            tile.chunkX,
                            tile.chunkZ,
                            revision,
                            pageIndex,
                            pages.size,
                            page,
                        ),
                    )
                }
                subscription.sentRevisions[tile] = revision
                tileCount++
                recordCount += traces.size
            }
    }

    private fun visibleTrace(trace: com.bettercontent.playertraces.domain.FootTrace) =
        com.bettercontent.playertraces.dto.VisibleTraceDto(
            id = "",
            sequenceId = "",
            movementClass = trace.movementClass,
            x = trace.x,
            y = trace.y,
            z = trace.z,
            facingYaw = trace.facingYaw,
            strength = trace.strength,
            sequenceIndex = trace.sequenceIndex,
            kind = trace.kind,
            createdAt = trace.createdAt,
            support = trace.support,
        )

    private data class TileCoordinate(val chunkX: Int, val chunkZ: Int)

    private data class ServerTraceSubscription(
        val dimension: String,
        val radius: Int,
        val generation: Long,
        val loginGameTime: Long,
        val sentRevisions: MutableMap<TileCoordinate, Long> = HashMap(),
    )
}
