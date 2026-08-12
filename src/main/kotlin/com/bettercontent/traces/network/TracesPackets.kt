package com.bettercontent.traces.network

import com.bettercontent.traces.dto.VisibleAnnotationDto
import com.bettercontent.traces.dto.VisibleTraceDto
import net.minecraft.network.FriendlyByteBuf

data class TraceQueryRequestPacket(val radius: Int) {
    fun encode(buf: FriendlyByteBuf) {
        buf.writeInt(radius)
    }

    companion object {
        fun decode(buf: FriendlyByteBuf): TraceQueryRequestPacket {
            return TraceQueryRequestPacket(buf.readInt())
        }
    }
}

data class SeenAnnotation(val id: String, val revision: Int)

data class TraceAnnotationsSeenPacket(val annotations: List<SeenAnnotation>) {
    fun encode(buf: FriendlyByteBuf) {
        val visible = annotations.take(256)
        buf.writeVarInt(visible.size)
        visible.forEach {
            buf.writeUtf(it.id)
            buf.writeVarInt(it.revision)
        }
    }

    companion object {
        fun decode(buf: FriendlyByteBuf): TraceAnnotationsSeenPacket {
            val count = buf.readVarInt().coerceIn(0, 256)
            return TraceAnnotationsSeenPacket(List(count) { SeenAnnotation(buf.readUtf(), buf.readVarInt()) })
        }
    }
}

data class TraceQueryResponsePacket(
    val traces: List<VisibleTraceDto>,
    val annotations: List<VisibleAnnotationDto>,
) {
    fun encode(buf: FriendlyByteBuf) {
        buf.writeInt(traces.size)
        traces.forEach {
            buf.writeUtf(it.id)
            buf.writeUtf(it.sequenceId)
            buf.writeByte(it.movementClass.ordinal)
            buf.writeInt(it.x)
            buf.writeInt(it.y)
            buf.writeInt(it.z)
            buf.writeFloat(it.strength)
            buf.writeInt(it.sequenceIndex)
        }

        buf.writeInt(annotations.size)
        annotations.forEach {
            buf.writeUtf(it.id)
            buf.writeUtf(it.text)
            buf.writeUtf(it.icon)
            buf.writeInt(it.color)
            buf.writeInt(it.x)
            buf.writeInt(it.y)
            buf.writeInt(it.z)
            buf.writeUtf(it.team)
        buf.writeInt(it.revision)
        buf.writeBoolean(it.seen)
        }
    }

    companion object {
        fun decode(buf: FriendlyByteBuf): TraceQueryResponsePacket {
            val traceCount = buf.readInt()
            val traces = mutableListOf<VisibleTraceDto>()
            repeat(traceCount) {
                val id = buf.readUtf()
                val sequenceId = buf.readUtf()
                val mc = buf.readByte().toInt().coerceIn(0, 4)
                val x = buf.readInt()
                val y = buf.readInt()
                val z = buf.readInt()
                val s = buf.readFloat()
                val seq = buf.readInt()
                traces += VisibleTraceDto(id, sequenceId, com.bettercontent.traces.domain.MovementClass.values()[mc], x, y, z, s, seq)
            }

            val annCount = buf.readInt()
            val annotations = mutableListOf<VisibleAnnotationDto>()
            repeat(annCount) {
            annotations += VisibleAnnotationDto(
                id = buf.readUtf(),
                text = buf.readUtf(),
                icon = buf.readUtf(),
                color = buf.readInt(),
                x = buf.readInt(),
                y = buf.readInt(),
                z = buf.readInt(),
                team = buf.readUtf(),
                revision = buf.readInt(),
                seen = buf.readBoolean(),
            )
            }
            return TraceQueryResponsePacket(traces, annotations)
        }
    }
}
