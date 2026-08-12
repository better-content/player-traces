package com.bettercontent.traces.client

import com.bettercontent.traces.network.TraceQueryResponsePacket
import com.bettercontent.traces.network.TracesNetwork

object TracesClientNetworkHandler {
    fun accept(payload: TraceQueryResponsePacket) {
        TracesClientState.acceptNetworkPayload(payload)
        if (com.bettercontent.traces.config.TracesConfig.client.visualDiagnostics.get()) {
            TracesClientLog.LOGGER.info(
                "TRACES_VISUAL_READY traces={} annotations={} overlay={}",
                payload.traces.size,
                payload.annotations.size,
                TracesClientState.overlayEnabled,
            )
        }
        TracesNetwork.acknowledgeAnnotations(payload.annotations.filter { !it.seen })
    }
}
