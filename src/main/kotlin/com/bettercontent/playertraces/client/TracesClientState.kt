package com.bettercontent.playertraces.client

import com.bettercontent.playertraces.config.TracesConfig
import com.bettercontent.playertraces.dto.VisibleAnnotationDto
import com.bettercontent.playertraces.dto.VisibleTraceDto
import com.bettercontent.playertraces.dto.GuidanceRouteDto
import com.bettercontent.playertraces.network.TracesNetwork
import com.bettercontent.playertraces.dto.VisibleBloodPoolDto
import com.bettercontent.playertraces.dto.VisibleDeathEchoDto
import com.bettercontent.playertraces.echo.EchoClip
import com.bettercontent.playertraces.echo.EchoClipCodec

object TracesClientState {
    private val traces = mutableListOf<VisibleTraceDto>()
    private val annotations = mutableListOf<VisibleAnnotationDto>()
    private val guidanceRoutes = mutableListOf<GuidanceRouteDto>()
    private val bloodPools = mutableListOf<VisibleBloodPoolDto>()
    private val deathEchoes = mutableListOf<ClientDeathEcho>()
    private val annotationEchoes = mutableMapOf<Pair<String, Int>, EchoClip>()
    private val requestedAnnotationEchoes = mutableSetOf<Pair<String, Int>>()
    private val activeAnnotationEchoes = mutableMapOf<String, ActiveAnnotationEcho>()
    private val annotationPlayback = AnnotationEchoPlayback()
    private var playbackSawOverlay = false
    private val locallyAcknowledged = mutableSetOf<Pair<String, Int>>()

    @Volatile
    var overlayEnabled: Boolean = TracesConfig.client.enableRevealDefault.get()

    @Volatile
    var lastUnseenAnnotationCount: Int = 0

    @Volatile
    var lastPayloadTraceCount: Int = 0

    @Volatile
    var lastPayloadAnnotationCount: Int = 0

    @Volatile
    var lastPayloadAtMillis: Long = 0

    @Volatile
    var hasResponse: Boolean = false

    fun acceptNetworkPayload(payload: com.bettercontent.playertraces.network.TraceQueryResponsePacket) {
        traces.clear()
        traces.addAll(payload.traces)
        annotations.clear()
        annotations.addAll(payload.annotations)
        val advertised = annotations.filter { it.hasEcho }.mapTo(HashSet()) { it.id to it.echoRevision }
        annotationEchoes.keys.removeIf { it !in advertised }
        requestedAnnotationEchoes.removeIf { it !in advertised }
        activeAnnotationEchoes.keys.removeIf { id -> annotations.none { it.id == id && it.hasEcho } }
        annotationPlayback.retain(annotations.filter { it.hasEcho }.mapTo(HashSet()) { it.id })
        advertised.filter { it !in annotationEchoes && requestedAnnotationEchoes.add(it) }
            .forEach { (id, revision) -> TracesNetwork.requestAnnotationEcho(id, revision) }
        locallyAcknowledged.removeIf { (id, revision) ->
            annotations.any { it.id == id && (it.revision != revision || it.seen) }
        }
        guidanceRoutes.clear()
        guidanceRoutes.addAll(payload.guidanceRoutes.filter {
            (it.targetAnnotationId to it.targetRevision) !in locallyAcknowledged
        })
        bloodPools.clear()
        bloodPools.addAll(payload.bloodPools)
        deathEchoes.clear()
        deathEchoes.addAll(payload.deathEchoes.mapNotNull { dto ->
            runCatching { ClientDeathEcho(dto, EchoClipCodec.decodeQuantized(dto.encodedClip)) }
                .onFailure { TracesClientLog.LOGGER.warn("Rejected malformed visible death echo {}", dto.id, it) }
                .getOrNull()
        })
        lastUnseenAnnotationCount = annotations.count { !it.seen }
        lastPayloadTraceCount = traces.size
        lastPayloadAnnotationCount = annotations.size
        lastPayloadAtMillis = System.currentTimeMillis()
        hasResponse = true
        TracesClientLog.LOGGER.info(
            "TRACES_MVP_ACCEPTED accepted={} footprints={} notes={}",
            traces.size + annotations.size, traces.size, annotations.size,
        )
        TracesClientLog.LOGGER.info("TRACES_ANNOTATION_ECHO_CACHE advertised={} cached={}", advertised.size, annotationEchoes.size)
    }

    fun visibleTraces(): List<VisibleTraceDto> = traces.toList()
    fun visibleAnnotations(): List<VisibleAnnotationDto> = annotations.toList()
    fun visibleGuidance(): List<GuidanceRouteDto> = guidanceRoutes.toList()
    fun visibleBloodPools(): List<VisibleBloodPoolDto> = bloodPools.toList()
    fun visibleDeathEchoes(): List<ClientDeathEcho> = deathEchoes.toList()

    fun acceptAnnotationEcho(packet: com.bettercontent.playertraces.network.AnnotationEchoResponsePacket) {
        val key = packet.annotationId to packet.echoRevision
        requestedAnnotationEchoes.remove(key)
        val encoded = packet.encodedClip ?: return
        val clip = runCatching { com.bettercontent.playertraces.logic.AnnotationEchoValidation.decode(encoded) }
            .onFailure { TracesClientLog.LOGGER.warn("Rejected malformed annotation echo {} revision {}", packet.annotationId, packet.echoRevision, it) }
            .getOrNull() ?: return
        if (annotations.any { it.id == packet.annotationId && it.echoRevision == packet.echoRevision }) annotationEchoes[key] = clip
        TracesClientLog.LOGGER.info("TRACES_ANNOTATION_ECHO_CACHED annotation={} revision={} frames={}", packet.annotationId, packet.echoRevision, clip.frames.size)
    }

    fun playingAnnotationEchoes(
        playerPosition: net.minecraft.world.phys.Vec3,
        nowMillis: Long,
        traceSightVisible: Boolean = overlayEnabled,
    ): List<ClientAnnotationEcho> {
        if (!traceSightVisible) {
            playbackSawOverlay = false
            activeAnnotationEchoes.clear()
            return emptyList()
        }
        val eligible = annotations.filter { annotation ->
            annotation.hasEcho && annotationEchoes.containsKey(annotation.id to annotation.echoRevision) &&
                playerPosition.distanceToSqr(annotation.x + 0.5, annotation.y + 0.5, annotation.z + 0.5) <=
                AnnotationEchoPlayback.MAX_DISTANCE * AnnotationEchoPlayback.MAX_DISTANCE
        }
        activeAnnotationEchoes.entries.removeIf { (id, active) ->
            val annotation = eligible.firstOrNull { it.id == id }
            annotation == null || nowMillis - active.startedAt > (active.clip.durationSeconds * 1000).toLong()
        }
        if (!overlayEnabled) {
            playbackSawOverlay = false
            return activeAnnotationEchoes.mapNotNull { (id, active) ->
                eligible.firstOrNull { it.id == id }?.let { ClientAnnotationEcho(it, active.clip, active.startedAt) }
            }
        }
        playbackSawOverlay = true
        annotationPlayback.onSightOpened(eligible.map { it.id }, nowMillis)
        eligible.forEach { annotation ->
            val distance = playerPosition.distanceToSqr(annotation.x + 0.5, annotation.y + 0.5, annotation.z + 0.5)
            val inside = distance <= AnnotationEchoPlayback.APPROACH_RADIUS * AnnotationEchoPlayback.APPROACH_RADIUS
            if (activeAnnotationEchoes.size < AnnotationEchoPlayback.MAX_SIMULTANEOUS && annotationPlayback.approach(annotation.id, inside, nowMillis)) {
                startAnnotationEcho(annotation, nowMillis)
            } else if (!inside) annotationPlayback.approach(annotation.id, false, nowMillis)
        }
        eligible.forEach { annotation ->
            if (annotation.id !in activeAnnotationEchoes && annotationPlayback.due(annotation.id, nowMillis, activeAnnotationEchoes.size)) {
                startAnnotationEcho(annotation, nowMillis)
            }
        }
        return activeAnnotationEchoes.mapNotNull { (id, active) ->
            eligible.firstOrNull { it.id == id }?.let { ClientAnnotationEcho(it, active.clip, active.startedAt) }
        }
    }

    private fun startAnnotationEcho(annotation: VisibleAnnotationDto, nowMillis: Long) {
        val clip = annotationEchoes[annotation.id to annotation.echoRevision] ?: return
        activeAnnotationEchoes[annotation.id] = ActiveAnnotationEcho(clip, nowMillis)
    }

    fun sampledVisibleTraces(limit: Int = 180): List<VisibleTraceDto> {
        return traces.sortedByDescending { it.own }.take(limit.coerceAtLeast(1))
    }

    fun toggleOverlay() {
        overlayEnabled = !overlayEnabled
    }

    fun hasSeen(annotationId: String, revision: Int): Boolean {
        return annotations.any { it.id == annotationId && it.revision == revision && it.seen }
    }

    fun acknowledgeViewed(annotation: VisibleAnnotationDto) {
        val key = annotation.id to annotation.revision
        if (!locallyAcknowledged.add(key)) return
        guidanceRoutes.removeIf { it.targetAnnotationId == annotation.id && it.targetRevision == annotation.revision }
        TracesNetwork.acknowledgeAnnotations(listOf(annotation))
    }
}

data class ClientDeathEcho(val dto: VisibleDeathEchoDto, val clip: EchoClip)
data class ClientAnnotationEcho(val dto: VisibleAnnotationDto, val clip: EchoClip, val startedAt: Long)
private data class ActiveAnnotationEcho(val clip: EchoClip, val startedAt: Long)
