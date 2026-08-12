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
    private const val PROTOCOL = "traces_v1"
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
            val storage = TracesMod.getRuntime(level.server).storage(level)
            val visibleAnnotations = result.annotations
                .filter { annotation -> annotation.team == GLOBAL_TEAM }
                .map {
                    val seen = storage.getSeen(sender.uuid, it.id) >= it.revision
                    com.bettercontent.traces.dto.VisibleAnnotationDto(
                        id = it.id.toString(), text = it.text, icon = it.icon, color = it.color,
                        x = it.position.x, y = it.position.y, z = it.position.z, team = it.team.id,
                        revision = it.revision, seen = seen,
                    )
                }
            val response = TraceQueryResponsePacket(
                traces = result.traces
                    .sortedWith(compareBy<com.bettercontent.traces.domain.FootTrace> {
                        val dx = it.blockPos.x - pos.x
                        val dz = it.blockPos.z - pos.z
                        dx * dx + dz * dz
                    }.thenBy { it.id })
                    .take(TracesConfig.common.maxPayloadTraces.get())
                    .map {
                        com.bettercontent.traces.dto.VisibleTraceDto(
                            id = it.id.toString(), sequenceId = it.sequenceId.toString(), movementClass = it.movementClass,
                            x = it.blockPos.x, y = it.blockPos.y, z = it.blockPos.z, strength = it.strength,
                            sequenceIndex = it.sequenceIndex,
                        )
                    },
                annotations = visibleAnnotations,
            )
            if (java.lang.Boolean.getBoolean("traces.visualValidation")) {
                logger.info("TRACES_VISUAL_RESPONSE traces={} annotations={}", response.traces.size, response.annotations.size)
            }
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
            val storage = TracesMod.getRuntime(sender.server).storage(sender.serverLevel())
            msg.annotations.forEach { storage.setSeen(sender.uuid, UUID.fromString(it.id), it.revision) }
        }
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
}
