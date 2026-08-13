package com.bettercontent.playertraces.client

import com.bettercontent.playertraces.network.TraceQueryResponsePacket

object TracesClientNetworkHandler {
    fun accept(payload: TraceQueryResponsePacket) {
        TracesClientState.acceptNetworkPayload(payload)
        if (com.bettercontent.playertraces.config.TracesConfig.client.visualDiagnostics.get()) {
            TracesClientLog.LOGGER.info(
                "TRACES_VISUAL_READY traces={} annotations={} overlay={}",
                payload.traces.size,
                payload.annotations.size,
                TracesClientState.overlayEnabled,
            )
        }
    }
}
