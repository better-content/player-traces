package com.bettercontent.traces.client

import com.bettercontent.traces.config.TracesConfig
import com.bettercontent.traces.dto.VisibleAnnotationDto
import com.bettercontent.traces.dto.VisibleTraceDto
import com.bettercontent.traces.domain.GuidanceSignal
import com.bettercontent.traces.logic.GuidanceEngine
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos

object TracesClientState {
    private val traces = mutableListOf<VisibleTraceDto>()
    private val annotations = mutableListOf<VisibleAnnotationDto>()
    private val guidance = mutableListOf<GuidanceSignal>()

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

    fun acceptNetworkPayload(payload: com.bettercontent.traces.network.TraceQueryResponsePacket) {
        traces.clear()
        traces.addAll(payload.traces)
        annotations.clear()
        annotations.addAll(payload.annotations)
        lastUnseenAnnotationCount = annotations.count { !it.seen }
        lastPayloadTraceCount = traces.size
        lastPayloadAnnotationCount = annotations.size
        lastPayloadAtMillis = System.currentTimeMillis()
        guidance.clear()
        guidance.addAll(buildCurrentGuidance())
    }

    fun visibleTraces(): List<VisibleTraceDto> = traces.toList()
    fun visibleAnnotations(): List<VisibleAnnotationDto> = annotations.toList()
    fun visibleGuidance(): List<GuidanceSignal> = guidance.toList()

    fun exposureFor(trace: VisibleTraceDto): Float {
        if (traces.isEmpty()) return 1f
        var density = 0
        for (other in traces) {
            if (trace.id == other.id) continue
            val dx = trace.x - other.x
            val dz = trace.z - other.z
            if (dx * dx + dz * dz <= 16) {
                density++
            }
        }
        val denom = TracesConfig.client.referenceDensity.get().toFloat().coerceAtLeast(0.1f)
        val falloff = 1f / (1f + density.toFloat() / denom)
        return (falloff * trace.strength).coerceAtLeast(TracesConfig.client.minVisibleAlpha.get().toFloat())
    }

    fun sampledVisibleTraces(limit: Int = 180): List<VisibleTraceDto> {
        if (traces.isEmpty()) return emptyList()
        val step = (traces.size / limit).coerceAtLeast(1)
        return traces
            .asSequence()
            .filter { trace ->
                val alpha = exposureFor(trace)
                alpha > TracesConfig.client.minVisibleAlpha.get().toFloat()
            }
            .toList()
            .let { it.filterIndexed { index, _ -> index % step == 0 } }
    }

    fun toggleOverlay() {
        overlayEnabled = !overlayEnabled
    }

    fun hasSeen(annotationId: String, revision: Int): Boolean {
        return annotations.any { it.id == annotationId && it.revision == revision && it.seen }
    }

    fun guidance(playerPos: BlockPos): List<GuidanceSignal> {
        val active = GuidanceEngine.buildSignals(visibleTraces(), visibleAnnotations(), playerPos)
            .filter { it.intensity >= TracesConfig.client.guidanceStrengthFloor.get().toFloat() }
        guidance.clear()
        guidance.addAll(active)
        return active
    }

    fun buildCurrentGuidance(): List<GuidanceSignal> {
        val player = Minecraft.getInstance().player ?: return emptyList()
        return GuidanceEngine.buildSignals(visibleTraces(), visibleAnnotations(), player.blockPosition())
            .filter { it.intensity >= TracesConfig.client.guidanceStrengthFloor.get().toFloat() }
    }
}
