package com.bettercontent.playertraces.network

import com.bettercontent.playertraces.dto.VisibleAnnotationDto
import com.bettercontent.playertraces.dto.VisibleTraceDto
import com.bettercontent.playertraces.dto.GuidanceRouteDto
import com.bettercontent.playertraces.dto.GuidancePointDto
import net.minecraft.network.FriendlyByteBuf
import com.bettercontent.playertraces.domain.DeathEchoRecord
import com.bettercontent.playertraces.dto.VisibleBloodPoolDto
import com.bettercontent.playertraces.dto.VisibleDeathEchoDto
import java.util.UUID
import com.bettercontent.playertraces.domain.AnnotationEchoRecord
import com.bettercontent.playertraces.domain.AnnotationComponents
import com.bettercontent.playertraces.domain.EchoMutation

private const val MAX_RESPONSE_RECORDS = 4096
private const val MAX_ANNOTATION_TEXT = 256
private const val MAX_GUIDANCE_ROUTES = 256
private const val MAX_GUIDANCE_POINTS = 4096
private const val MAX_BLOOD_POOLS = 256
private const val MAX_DEATH_ECHOES = 32
private const val MAX_TRACE_TILE_PAGE_RECORDS = 1024
private const val MAX_TRACE_TILE_EVICTIONS = 512

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
            val count = buf.readVarInt()
            require(count in 0..256) { "annotation acknowledgement count is invalid" }
            return TraceAnnotationsSeenPacket(List(count) {
                val id = buf.readUtf(36)
                val revision = buf.readVarInt()
                require(revision >= 0) { "annotation acknowledgement revision is invalid" }
                SeenAnnotation(id, revision)
            })
        }
    }
}

data class TraceQueryResponsePacket(
    val traces: List<VisibleTraceDto>,
    val annotations: List<VisibleAnnotationDto>,
    val guidanceRoutes: List<GuidanceRouteDto> = emptyList(),
    val guidanceTotal: Int = guidanceRoutes.size,
    val guidanceTruncated: Boolean = false,
    val bloodPools: List<VisibleBloodPoolDto> = emptyList(),
    val deathEchoes: List<VisibleDeathEchoDto> = emptyList(),
    val subscriptionGeneration: Long = 0L,
    val dimension: String = "",
    val loginGameTime: Long = 0L,
) {
    fun encode(buf: FriendlyByteBuf) {
        require(traces.size <= MAX_RESPONSE_RECORDS && annotations.size <= MAX_RESPONSE_RECORDS) { "trace response exceeds protocol limits" }
        require(guidanceRoutes.size <= MAX_GUIDANCE_ROUTES && guidanceRoutes.sumOf { it.path.size } <= MAX_GUIDANCE_POINTS) {
            "guidance response exceeds protocol limits"
        }
        require(guidanceTotal >= guidanceRoutes.size) { "guidance total is invalid" }
        buf.writeInt(traces.size)
        traces.forEach {
            buf.writeUtf(it.id, 36)
            buf.writeUtf(it.sequenceId, 36)
            buf.writeByte(it.movementClass.ordinal)
            require(validPoint(it.x, it.y, it.z) && it.facingYaw.isFinite()) { "trace response geometry is invalid" }
            buf.writeDouble(it.x)
            buf.writeDouble(it.y)
            buf.writeDouble(it.z)
            buf.writeFloat(it.facingYaw)
            buf.writeFloat(it.strength)
            buf.writeInt(it.sequenceIndex)
            buf.writeBoolean(it.own)
            writeTraceMetadata(buf, it)
        }

        buf.writeInt(annotations.size)
        annotations.forEach {
            buf.writeUtf(it.id, 36)
            buf.writeUtf(it.text, 256)
            buf.writeUtf(it.icon, 64)
            buf.writeInt(it.color)
            buf.writeInt(it.x)
            buf.writeInt(it.y)
            buf.writeInt(it.z)
            buf.writeUtf(it.team, 64)
            buf.writeInt(it.revision)
            buf.writeBoolean(it.seen)
            buf.writeBoolean(it.canEdit)
            buf.writeBoolean(it.hasEcho)
            buf.writeVarInt(it.echoRevision)
        }

        buf.writeVarInt(guidanceTotal)
        buf.writeBoolean(guidanceTruncated)
        buf.writeVarInt(guidanceRoutes.size)
        guidanceRoutes.forEach { route ->
            require(route.targetRevision >= 0) { "guidance target revision is invalid" }
            require(route.path.size >= 2) { "guidance path is too short" }
            buf.writeUtf(route.targetAnnotationId, 36)
            buf.writeVarInt(route.targetRevision)
            buf.writeVarInt(route.path.size)
            route.path.forEach {
                require(validPoint(it.x, it.y, it.z)) { "guidance point is invalid" }
                buf.writeDouble(it.x); buf.writeDouble(it.y); buf.writeDouble(it.z)
            }
        }

        require(bloodPools.size <= MAX_BLOOD_POOLS && deathEchoes.size <= MAX_DEATH_ECHOES) { "death trace response exceeds protocol limits" }
        buf.writeVarInt(bloodPools.size)
        bloodPools.forEach {
            require(validPoint(it.x, it.y, it.z) && it.createdAt >= 0) { "blood pool response is invalid" }
            buf.writeUtf(it.id, 36); buf.writeUtf(it.ownerName, 16)
            buf.writeDouble(it.x); buf.writeDouble(it.y); buf.writeDouble(it.z); buf.writeVarLong(it.createdAt)
        }
        buf.writeVarInt(deathEchoes.size)
        deathEchoes.forEach {
            require(validPoint(it.x, it.y, it.z) && it.createdAt >= 0) { "death echo response is invalid" }
            require(it.encodedClip.size in 1..DeathEchoRecord.MAX_ENCODED_ECHO_BYTES) { "death echo response payload is invalid" }
            buf.writeUtf(it.id, 36); buf.writeUtf(it.ownerName, 16)
            buf.writeDouble(it.x); buf.writeDouble(it.y); buf.writeDouble(it.z); buf.writeVarLong(it.createdAt)
            buf.writeByteArray(it.encodedClip)
        }
        require(subscriptionGeneration >= 0L && dimension.length <= 256 && loginGameTime >= 0L) { "trace subscription context is invalid" }
        buf.writeVarLong(subscriptionGeneration)
        buf.writeUtf(dimension, 256)
        buf.writeVarLong(loginGameTime)
    }

    companion object {
        fun decode(buf: FriendlyByteBuf): TraceQueryResponsePacket {
            val traceCount = buf.readInt()
            require(traceCount in 0..MAX_RESPONSE_RECORDS) { "trace response count is invalid" }
            val traces = mutableListOf<VisibleTraceDto>()
            repeat(traceCount) {
                val id = buf.readUtf(36)
                val sequenceId = buf.readUtf(36)
                val movementOrdinal = buf.readUnsignedByte().toInt()
                require(movementOrdinal in com.bettercontent.playertraces.domain.MovementClass.values().indices) {
                    "trace response movement class is invalid"
                }
                val x = buf.readDouble()
                val y = buf.readDouble()
                val z = buf.readDouble()
                val facingYaw = buf.readFloat()
                require(validPoint(x, y, z) && facingYaw.isFinite()) { "trace response geometry is invalid" }
                val s = buf.readFloat()
                require(s.isFinite() && s >= 0f) { "trace response strength is invalid" }
                val seq = buf.readInt()
                require(seq >= 0) { "trace response sequence index is invalid" }
                val own = buf.readBoolean()
                val metadata = readTraceMetadata(buf)
                traces += VisibleTraceDto(
                    id,
                    sequenceId,
                    com.bettercontent.playertraces.domain.MovementClass.values()[movementOrdinal],
                    x,
                    y,
                    z,
                    facingYaw,
                    s,
                    seq,
                    own,
                    metadata.kind,
                    metadata.createdAt,
                    metadata.support,
                )
            }

            val annCount = buf.readInt()
            require(annCount in 0..MAX_RESPONSE_RECORDS) { "annotation response count is invalid" }
            val annotations = mutableListOf<VisibleAnnotationDto>()
            repeat(annCount) {
                val id = buf.readUtf(36)
                val text = buf.readUtf(256)
                val icon = buf.readUtf(64)
                val color = buf.readInt()
                val x = buf.readInt()
                val y = buf.readInt()
                val z = buf.readInt()
                val team = buf.readUtf(64)
                val revision = buf.readInt()
                require(revision >= 0) { "annotation response revision is invalid" }
                annotations += VisibleAnnotationDto(
                    id = id,
                    text = text,
                    icon = icon,
                    color = color,
                    x = x,
                    y = y,
                    z = z,
                    team = team,
                    revision = revision,
                    seen = buf.readBoolean(),
                    canEdit = buf.readBoolean(),
                    hasEcho = buf.readBoolean(),
                    echoRevision = buf.readVarInt().also { require(it >= 0) { "annotation echo revision is invalid" } },
                )
            }
            val guidanceTotal = buf.readVarInt()
            require(guidanceTotal >= 0) { "guidance total is invalid" }
            val guidanceTruncated = buf.readBoolean()
            val routeCount = buf.readVarInt()
            require(routeCount in 0..MAX_GUIDANCE_ROUTES && routeCount <= guidanceTotal) { "guidance route count is invalid" }
            var pointCount = 0
            val routes = List(routeCount) {
                val targetId = buf.readUtf(36)
                val revision = buf.readVarInt()
                require(revision >= 0) { "guidance target revision is invalid" }
                val pathSize = buf.readVarInt()
                require(pathSize in 2..MAX_GUIDANCE_POINTS) { "guidance path size is invalid" }
                pointCount += pathSize
                require(pointCount <= MAX_GUIDANCE_POINTS) { "guidance response has too many points" }
                GuidanceRouteDto(targetId, revision, List(pathSize) {
                    val x = buf.readDouble(); val y = buf.readDouble(); val z = buf.readDouble()
                    require(validPoint(x, y, z)) { "guidance point is invalid" }
                    GuidancePointDto(x, y, z)
                })
            }
            val poolCount = buf.readVarInt()
            require(poolCount in 0..MAX_BLOOD_POOLS) { "blood pool response count is invalid" }
            val pools = List(poolCount) {
                val id = buf.readUtf(36); val owner = buf.readUtf(16)
                val x = buf.readDouble(); val y = buf.readDouble(); val z = buf.readDouble(); val created = buf.readVarLong()
                require(validPoint(x, y, z) && created >= 0) { "blood pool response is invalid" }
                VisibleBloodPoolDto(id, owner, x, y, z, created)
            }
            val echoCount = buf.readVarInt()
            require(echoCount in 0..MAX_DEATH_ECHOES) { "death echo response count is invalid" }
            val echoes = List(echoCount) {
                val id = buf.readUtf(36); val owner = buf.readUtf(16)
                val x = buf.readDouble(); val y = buf.readDouble(); val z = buf.readDouble(); val created = buf.readVarLong()
                require(validPoint(x, y, z) && created >= 0) { "death echo response is invalid" }
                val clip = buf.readByteArray(DeathEchoRecord.MAX_ENCODED_ECHO_BYTES)
                require(clip.isNotEmpty()) { "death echo response payload is empty" }
                VisibleDeathEchoDto(id, owner, x, y, z, created, clip)
            }
            val generation = buf.readVarLong().also { require(it >= 0L) { "trace subscription generation is invalid" } }
            val dimension = buf.readUtf(256)
            val loginGameTime = buf.readVarLong().also { require(it >= 0L) { "trace login time is invalid" } }
            return TraceQueryResponsePacket(
                traces, annotations, routes, guidanceTotal, guidanceTruncated, pools, echoes,
                generation, dimension, loginGameTime,
            )
        }
    }
}

data class TraceTileSnapshotPacket(
    val generation: Long,
    val dimension: String,
    val chunkX: Int,
    val chunkZ: Int,
    val revision: Long,
    val pageIndex: Int,
    val pageCount: Int,
    val traces: List<VisibleTraceDto>,
) {
    fun encode(buf: FriendlyByteBuf) {
        require(generation >= 0L && dimension.length <= 256 && revision >= 0L) { "trace tile identity is invalid" }
        require(pageCount in 1..4096 && pageIndex in 0 until pageCount) { "trace tile page is invalid" }
        require(traces.size <= MAX_TRACE_TILE_PAGE_RECORDS) { "trace tile page exceeds protocol limit" }
        buf.writeVarLong(generation); buf.writeUtf(dimension, 256)
        buf.writeInt(chunkX); buf.writeInt(chunkZ); buf.writeVarLong(revision)
        buf.writeVarInt(pageIndex); buf.writeVarInt(pageCount); buf.writeVarInt(traces.size)
        traces.forEach { writeVisibleTrace(buf, it) }
    }

    companion object {
        fun decode(buf: FriendlyByteBuf): TraceTileSnapshotPacket {
            val generation = buf.readVarLong().also { require(it >= 0L) { "trace tile generation is invalid" } }
            val dimension = buf.readUtf(256)
            val chunkX = buf.readInt(); val chunkZ = buf.readInt()
            val revision = buf.readVarLong().also { require(it >= 0L) { "trace tile revision is invalid" } }
            val pageIndex = buf.readVarInt(); val pageCount = buf.readVarInt()
            require(pageCount in 1..4096 && pageIndex in 0 until pageCount) { "trace tile page is invalid" }
            val count = buf.readVarInt()
            require(count in 0..MAX_TRACE_TILE_PAGE_RECORDS) { "trace tile record count is invalid" }
            return TraceTileSnapshotPacket(
                generation, dimension, chunkX, chunkZ, revision, pageIndex, pageCount,
                List(count) { readVisibleTrace(buf) },
            )
        }
    }
}

data class TraceTileCoordinate(val chunkX: Int, val chunkZ: Int)

data class TraceTileEvictPacket(
    val generation: Long,
    val dimension: String,
    val tiles: List<TraceTileCoordinate>,
) {
    fun encode(buf: FriendlyByteBuf) {
        require(generation >= 0L && dimension.length <= 256 && tiles.size <= MAX_TRACE_TILE_EVICTIONS) { "trace tile eviction is invalid" }
        buf.writeVarLong(generation); buf.writeUtf(dimension, 256); buf.writeVarInt(tiles.size)
        tiles.forEach { buf.writeInt(it.chunkX); buf.writeInt(it.chunkZ) }
    }

    companion object {
        fun decode(buf: FriendlyByteBuf): TraceTileEvictPacket {
            val generation = buf.readVarLong().also { require(it >= 0L) }
            val dimension = buf.readUtf(256)
            val count = buf.readVarInt().also { require(it in 0..MAX_TRACE_TILE_EVICTIONS) }
            return TraceTileEvictPacket(generation, dimension, List(count) { TraceTileCoordinate(buf.readInt(), buf.readInt()) })
        }
    }
}

private data class TraceMetadata(
    val kind: com.bettercontent.playertraces.domain.TraceKind,
    val createdAt: Long,
    val support: com.bettercontent.playertraces.domain.TraceSupport?,
)

private fun writeTraceMetadata(buf: FriendlyByteBuf, trace: VisibleTraceDto) {
    require(trace.createdAt >= 0L) { "trace creation time is invalid" }
    buf.writeByte(trace.kind.serializedId)
    buf.writeVarLong(trace.createdAt)
    buf.writeBoolean(trace.support != null)
    trace.support?.let {
        buf.writeLong(it.position.asLong())
        buf.writeResourceLocation(it.blockId)
    }
}

private fun readTraceMetadata(buf: FriendlyByteBuf): TraceMetadata {
    val kind = com.bettercontent.playertraces.domain.TraceKind.fromSerializedId(buf.readUnsignedByte().toInt())
    val createdAt = buf.readVarLong().also { require(it >= 0L) { "trace creation time is invalid" } }
    val support = if (buf.readBoolean()) {
        com.bettercontent.playertraces.domain.TraceSupport(
            net.minecraft.core.BlockPos.of(buf.readLong()),
            buf.readResourceLocation(),
        )
    } else null
    return TraceMetadata(kind, createdAt, support)
}

private fun writeVisibleTrace(buf: FriendlyByteBuf, trace: VisibleTraceDto) {
    require(validPoint(trace.x, trace.y, trace.z) && trace.facingYaw.isFinite()) { "trace geometry is invalid" }
    require(trace.strength.isFinite() && trace.strength >= 0f && trace.sequenceIndex >= 0) { "trace appearance is invalid" }
    buf.writeByte(trace.movementClass.ordinal)
    buf.writeDouble(trace.x); buf.writeDouble(trace.y); buf.writeDouble(trace.z)
    buf.writeFloat(trace.facingYaw); buf.writeFloat(trace.strength); buf.writeVarInt(trace.sequenceIndex)
    writeTraceMetadata(buf, trace)
}

private fun readVisibleTrace(buf: FriendlyByteBuf): VisibleTraceDto {
    val movementOrdinal = buf.readUnsignedByte().toInt()
    require(movementOrdinal in com.bettercontent.playertraces.domain.MovementClass.entries.indices) { "trace movement class is invalid" }
    val x = buf.readDouble(); val y = buf.readDouble(); val z = buf.readDouble(); val yaw = buf.readFloat()
    require(validPoint(x, y, z) && yaw.isFinite()) { "trace geometry is invalid" }
    val strength = buf.readFloat().also { require(it.isFinite() && it >= 0f) { "trace strength is invalid" } }
    val sequenceIndex = buf.readVarInt().also { require(it >= 0) { "trace sequence index is invalid" } }
    val metadata = readTraceMetadata(buf)
    return VisibleTraceDto(
        id = "", sequenceId = "", movementClass = com.bettercontent.playertraces.domain.MovementClass.entries[movementOrdinal],
        x = x, y = y, z = z, facingYaw = yaw, strength = strength, sequenceIndex = sequenceIndex,
        kind = metadata.kind, createdAt = metadata.createdAt, support = metadata.support,
    )
}

private fun validPoint(x: Double, y: Double, z: Double): Boolean =
    x.isFinite() && y.isFinite() && z.isFinite() &&
        kotlin.math.abs(x) <= 30_000_001.0 && kotlin.math.abs(z) <= 30_000_001.0 && y in -2048.0..2048.0

data class AnnotationCreatePacket(
    val requestId: UUID,
    val target: Long,
    val text: String,
    val icon: String,
    val color: Int,
    val echoMutation: EchoMutation,
    val encodedClip: ByteArray?,
) {
    constructor(target: Long, text: String) : this(UUID.randomUUID(), target, text, "pin", AnnotationComponents.colors.getValue("orange"), EchoMutation.KEEP, null)

    fun encode(buf: FriendlyByteBuf) {
        require(text.length <= MAX_ANNOTATION_TEXT) { "annotation text exceeds protocol limit" }
        require(icon.length <= 64) { "annotation icon exceeds protocol limit" }
        validateEchoMutation(echoMutation, encodedClip, creating = true)
        buf.writeUUID(requestId)
        buf.writeLong(target)
        buf.writeUtf(text, MAX_ANNOTATION_TEXT)
        buf.writeUtf(icon, 64)
        buf.writeInt(color)
        buf.writeByte(echoMutation.ordinal)
        buf.writeBoolean(encodedClip != null)
        encodedClip?.let(buf::writeByteArray)
    }

    companion object {
        fun decode(buf: FriendlyByteBuf): AnnotationCreatePacket {
            val requestId = buf.readUUID()
            val target = buf.readLong()
            val text = buf.readUtf(MAX_ANNOTATION_TEXT)
            val icon = buf.readUtf(64)
            val color = buf.readInt()
            val mutation = readEchoMutation(buf)
            val clip = if (buf.readBoolean()) buf.readByteArray(AnnotationEchoRecord.MAX_ENCODED_BYTES) else null
            validateEchoMutation(mutation, clip, creating = true)
            return AnnotationCreatePacket(requestId, target, text, icon, color, mutation, clip)
        }
    }
}

data class AnnotationUpdatePacket(
    val requestId: UUID,
    val id: String,
    val expectedRevision: Int,
    val text: String,
    val icon: String,
    val color: Int,
    val echoMutation: EchoMutation,
    val encodedClip: ByteArray?,
) {
    constructor(id: String, expectedRevision: Int, text: String) : this(
        UUID.randomUUID(), id, expectedRevision, text, "pin", AnnotationComponents.colors.getValue("orange"), EchoMutation.KEEP, null,
    )

    fun encode(buf: FriendlyByteBuf) {
        require(expectedRevision >= 0) { "annotation revision is invalid" }
        require(text.length <= MAX_ANNOTATION_TEXT) { "annotation text exceeds protocol limit" }
        require(icon.length <= 64) { "annotation icon exceeds protocol limit" }
        validateEchoMutation(echoMutation, encodedClip, creating = false)
        buf.writeUUID(requestId)
        buf.writeUtf(id, 36)
        buf.writeVarInt(expectedRevision)
        buf.writeUtf(text, MAX_ANNOTATION_TEXT)
        buf.writeUtf(icon, 64)
        buf.writeInt(color)
        buf.writeByte(echoMutation.ordinal)
        buf.writeBoolean(encodedClip != null)
        encodedClip?.let(buf::writeByteArray)
    }

    companion object {
        fun decode(buf: FriendlyByteBuf): AnnotationUpdatePacket {
            try {
                val requestId = buf.readUUID()
                val id = buf.readUtf(36)
                val revision = buf.readVarInt()
                require(revision >= 0) { "annotation revision is invalid" }
                val text = buf.readUtf(MAX_ANNOTATION_TEXT)
                val icon = buf.readUtf(64)
                val color = buf.readInt()
                val mutation = readEchoMutation(buf)
                val clip = if (buf.readBoolean()) buf.readByteArray(AnnotationEchoRecord.MAX_ENCODED_BYTES) else null
                validateEchoMutation(mutation, clip, creating = false)
                return AnnotationUpdatePacket(requestId, id, revision, text, icon, color, mutation, clip)
            } catch (error: IndexOutOfBoundsException) {
                throw IllegalArgumentException("annotation update packet is truncated", error)
            }
        }
    }
}

data class AnnotationMutationResultPacket(
    val requestId: UUID,
    val success: Boolean,
    val annotationId: String,
    val revision: Int,
    val error: String,
) {
    fun encode(buf: FriendlyByteBuf) {
        require(revision >= 0 && error.length <= 256) { "annotation result is invalid" }
        buf.writeUUID(requestId); buf.writeBoolean(success); buf.writeUtf(annotationId, 36); buf.writeVarInt(revision); buf.writeUtf(error, 256)
    }

    companion object {
        fun decode(buf: FriendlyByteBuf): AnnotationMutationResultPacket = AnnotationMutationResultPacket(
            buf.readUUID(), buf.readBoolean(), buf.readUtf(36), buf.readVarInt().also { require(it >= 0) }, buf.readUtf(256),
        )
    }
}

data class AnnotationEchoRequestPacket(val annotationId: String, val echoRevision: Int) {
    fun encode(buf: FriendlyByteBuf) { require(echoRevision >= 1); buf.writeUtf(annotationId, 36); buf.writeVarInt(echoRevision) }
    companion object { fun decode(buf: FriendlyByteBuf) = AnnotationEchoRequestPacket(buf.readUtf(36), buf.readVarInt().also { require(it >= 1) }) }
}

data class AnnotationEchoResponsePacket(val annotationId: String, val echoRevision: Int, val encodedClip: ByteArray?) {
    fun encode(buf: FriendlyByteBuf) {
        require(echoRevision >= 1); require(encodedClip == null || encodedClip.size in 1..AnnotationEchoRecord.MAX_ENCODED_BYTES)
        buf.writeUtf(annotationId, 36); buf.writeVarInt(echoRevision); buf.writeBoolean(encodedClip != null); encodedClip?.let(buf::writeByteArray)
    }
    companion object {
        fun decode(buf: FriendlyByteBuf): AnnotationEchoResponsePacket {
            val id = buf.readUtf(36); val revision = buf.readVarInt().also { require(it >= 1) }
            val clip = if (buf.readBoolean()) buf.readByteArray(AnnotationEchoRecord.MAX_ENCODED_BYTES) else null
            return AnnotationEchoResponsePacket(id, revision, clip)
        }
    }
}

private fun readEchoMutation(buf: FriendlyByteBuf): EchoMutation {
    val ordinal = buf.readUnsignedByte().toInt()
    require(ordinal in EchoMutation.entries.indices) { "annotation echo mutation is invalid" }
    return EchoMutation.entries[ordinal]
}

private fun validateEchoMutation(mutation: EchoMutation, clip: ByteArray?, creating: Boolean) {
    require((mutation == EchoMutation.REPLACE) == (clip != null)) { "replacement gesture payload is missing or unexpected" }
    require(!creating || mutation != EchoMutation.REMOVE) { "a new annotation cannot remove a gesture" }
}

data class AnnotationDeletePacket(val id: String, val expectedRevision: Int) {
    fun encode(buf: FriendlyByteBuf) {
        require(expectedRevision >= 0) { "annotation revision is invalid" }
        buf.writeUtf(id, 36)
        buf.writeVarInt(expectedRevision)
    }

    companion object {
        fun decode(buf: FriendlyByteBuf): AnnotationDeletePacket {
            val id = buf.readUtf(36)
            val revision = buf.readVarInt()
            require(revision >= 0) { "annotation revision is invalid" }
            return AnnotationDeletePacket(id, revision)
        }
    }
}

data class DeathCaptureRequestPacket(
    val nonce: UUID,
    val x: Double,
    val y: Double,
    val z: Double,
    val captureToken: UUID? = null,
) {
    fun encode(buf: FriendlyByteBuf) {
        require(validPoint(x, y, z)) { "death capture position is invalid" }
        buf.writeUUID(nonce); buf.writeDouble(x); buf.writeDouble(y); buf.writeDouble(z)
        buf.writeBoolean(captureToken != null); captureToken?.let(buf::writeUUID)
    }

    companion object {
        fun decode(buf: FriendlyByteBuf): DeathCaptureRequestPacket {
            val nonce = buf.readUUID(); val x = buf.readDouble(); val y = buf.readDouble(); val z = buf.readDouble()
            val packet = DeathCaptureRequestPacket(nonce, x, y, z, if (buf.readBoolean()) buf.readUUID() else null)
            require(validPoint(packet.x, packet.y, packet.z)) { "death capture position is invalid" }
            return packet
        }
    }
}

data class DownedCaptureFreezePacket(
    val token: UUID,
    val dimension: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val downGameTime: Long,
) {
    fun encode(buf: FriendlyByteBuf) {
        require(dimension.length <= 256 && validPoint(x, y, z) && downGameTime >= 0L) { "downed capture freeze is invalid" }
        buf.writeUUID(token); buf.writeUtf(dimension, 256); buf.writeDouble(x); buf.writeDouble(y); buf.writeDouble(z); buf.writeVarLong(downGameTime)
    }

    companion object {
        fun decode(buf: FriendlyByteBuf): DownedCaptureFreezePacket = DownedCaptureFreezePacket(
            buf.readUUID(), buf.readUtf(256), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readVarLong(),
        ).also { require(validPoint(it.x, it.y, it.z) && it.downGameTime >= 0L) { "downed capture freeze is invalid" } }
    }
}

data class DownedCaptureDiscardPacket(val token: UUID) {
    fun encode(buf: FriendlyByteBuf) { buf.writeUUID(token) }
    companion object { fun decode(buf: FriendlyByteBuf) = DownedCaptureDiscardPacket(buf.readUUID()) }
}

data class DeathEchoSubmitPacket(val nonce: UUID, val encodedClip: ByteArray) {
    fun encode(buf: FriendlyByteBuf) {
        require(encodedClip.size in 1..DeathEchoRecord.MAX_ENCODED_ECHO_BYTES) { "death echo submission is invalid" }
        buf.writeUUID(nonce); buf.writeByteArray(encodedClip)
    }

    companion object {
        fun decode(buf: FriendlyByteBuf): DeathEchoSubmitPacket {
            val nonce = buf.readUUID()
            val clip = buf.readByteArray(DeathEchoRecord.MAX_ENCODED_ECHO_BYTES)
            require(clip.isNotEmpty()) { "death echo submission is empty" }
            return DeathEchoSubmitPacket(nonce, clip)
        }
    }
}
