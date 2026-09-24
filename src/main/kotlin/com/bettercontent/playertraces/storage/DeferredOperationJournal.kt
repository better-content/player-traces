package com.bettercontent.playertraces.storage

import com.bettercontent.playertraces.domain.FootTrace
import com.bettercontent.playertraces.domain.TraceAnnotation
import com.bettercontent.playertraces.util.Geometry
import com.bettercontent.playertraces.util.TraceShardId
import net.minecraft.core.BlockPos
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.zip.CRC32

internal data class DeferredShardSnapshot(val id: TraceShardId, val serial: Long, val state: TraceShardState)

internal data class DeferredJournalState(
    val pendingShards: List<DeferredShardSnapshot> = emptyList(),
    val captures: List<FootTrace> = emptyList(),
    val seen: List<DeferredSeenState> = emptyList(),
    val annotations: List<DeferredAnnotation> = emptyList(),
    val annotationEdits: List<DeferredAnnotationEdit> = emptyList(),
    val annotationRemovals: List<DeferredAnnotationRemoval> = emptyList(),
    val blockCleanups: List<DeferredBlockCleanup> = emptyList(),
    val footprintErosions: List<DeferredFootprintErosion> = emptyList(),
    val supportPrunes: List<DeferredSupportPrune> = emptyList(),
    val neighborhoodWeakenings: List<DeferredNeighborhoodWeakening> = emptyList(),
    val nextSerial: Long = 0,
    val nextErosionSerial: Long = 0,
    val nextNeighborhoodSerial: Long = 0,
)

/** Atomic, checksummed snapshot of every accepted outage mutation and pending shard snapshot. */
internal class DeferredOperationJournal(private val path: Path) {
    fun load(): DeferredJournalState {
        if (!Files.exists(path)) return DeferredJournalState()
        require(Files.size(path) <= MAX_JOURNAL_BYTES) { "deferred-operation journal exceeds $MAX_JOURNAL_BYTES bytes" }
        return decode(Files.readAllBytes(path))
    }

    fun persist(state: DeferredJournalState) {
        val bytes = encode(state)
        require(bytes.size <= MAX_JOURNAL_BYTES) { "deferred-operation journal exceeds $MAX_JOURNAL_BYTES bytes" }
        Files.createDirectories(path.parent)
        val temporary = Files.createTempFile(path.parent, ".${path.fileName}.", ".pending")
        try {
            FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            FileChannel.open(path.parent, StandardOpenOption.READ).use { it.force(true) }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    internal fun encode(state: DeferredJournalState): ByteArray {
        val body = LimitedByteArrayOutputStream(MAX_JOURNAL_BYTES - FOOTER_BYTES)
        DataOutputStream(body).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeLong(state.nextSerial)
            out.writeLong(state.nextErosionSerial)
            out.writeLong(state.nextNeighborhoodSerial)

            out.writeInt(state.pendingShards.size)
            state.pendingShards.forEach { pending ->
                writeShardId(out, pending.id)
                out.writeLong(pending.serial)
                out.writeLong(pending.state.archiveRevision())
                val payload = TraceSerializer.encodeV3(pending.state, Geometry.shardToBounds(pending.id.regionX, pending.id.regionZ))
                require(payload.size <= MAX_SHARD_BYTES) { "deferred shard snapshot exceeds $MAX_SHARD_BYTES bytes" }
                writeBytes(out, payload)
            }
            out.writeInt(state.captures.size)
            state.captures.forEach { TraceSerializer.writeJournalFootTrace(out, it) }
            out.writeInt(state.seen.size)
            state.seen.forEach { writeShardId(out, it.shardId); TraceSerializer.writeJournalSeenState(out, it.record) }
            out.writeInt(state.annotations.size)
            state.annotations.forEach { writeShardId(out, it.shardId); TraceSerializer.writeJournalAnnotation(out, it.annotation) }
            out.writeInt(state.annotationEdits.size)
            state.annotationEdits.forEach { writeShardId(out, it.shardId); TraceSerializer.writeJournalAnnotation(out, it.annotation) }
            out.writeInt(state.annotationRemovals.size)
            state.annotationRemovals.forEach { writeShardId(out, it.shardId); writeUuid(out, it.annotationId) }
            out.writeInt(state.blockCleanups.size)
            state.blockCleanups.forEach {
                writeShardId(out, it.shardId)
                writeBlockPos(out, it.position)
                out.writeByte(it.kind.ordinal)
                out.writeBoolean(it.boundsMin != null)
                it.boundsMin?.let { pos -> writeBlockPos(out, pos) }
                out.writeBoolean(it.boundsMax != null)
                it.boundsMax?.let { pos -> writeBlockPos(out, pos) }
            }
            out.writeInt(state.footprintErosions.size)
            state.footprintErosions.forEach {
                out.writeLong(it.serial)
                writeShardId(out, it.shardId)
                writeUuid(out, it.traceId)
                writeBlockPos(out, it.position)
                out.writeDouble(it.factor)
            }
            out.writeInt(state.supportPrunes.size)
            state.supportPrunes.forEach {
                writeShardId(out, it.shardId)
                out.writeInt(it.chunkX)
                out.writeInt(it.chunkZ)
            }
            out.writeInt(state.neighborhoodWeakenings.size)
            state.neighborhoodWeakenings.forEach {
                out.writeLong(it.serial)
                writeShardId(out, it.centerShard)
                writeBlockPos(out, it.position)
                out.writeInt(it.radius)
                out.writeDouble(it.factor)
            }
        }
        val payload = body.toByteArray()
        val crc = CRC32().also { it.update(payload) }.value
        return payload + ByteBuffer.allocate(java.lang.Long.BYTES).putLong(crc).array()
    }

    internal fun decode(bytes: ByteArray): DeferredJournalState {
        require(bytes.size in (HEADER_BYTES + FOOTER_BYTES)..MAX_JOURNAL_BYTES) { "invalid deferred journal size ${bytes.size}" }
        val bodyLength = bytes.size - FOOTER_BYTES
        val body = bytes.copyOfRange(0, bodyLength)
        val expectedCrc = ByteBuffer.wrap(bytes, bodyLength, FOOTER_BYTES).long
        val actualCrc = CRC32().also { it.update(body) }.value
        require(expectedCrc == actualCrc) { "deferred journal checksum mismatch" }
        DataInputStream(ByteArrayInputStream(body)).use { input ->
            require(input.readInt() == MAGIC) { "invalid deferred journal magic" }
            require(input.readInt() == VERSION) { "unsupported deferred journal version" }
            val nextSerial = input.readLong().also { require(it >= 0) }
            val nextErosionSerial = input.readLong().also { require(it >= 0) }
            val nextNeighborhoodSerial = input.readLong().also { require(it >= 0) }

            val pending = readList(input) {
                val id = readShardId(input)
                val serial = input.readLong().also { require(it >= 0) }
                val archiveRevision = input.readLong().also { require(it >= 0) }
                val state = TraceSerializer.decodeV3(readBytes(input), path.resolveSibling("deferred-shard"))
                    .also { it.setArchiveRevision(archiveRevision) }
                DeferredShardSnapshot(id, serial, state)
            }
            val captures = readList(input) { TraceSerializer.readJournalFootTrace(input) }
            val seen = readList(input) { DeferredSeenState(readShardId(input), TraceSerializer.readJournalSeenState(input)) }
            val annotations = readList(input) { DeferredAnnotation(readShardId(input), TraceSerializer.readJournalAnnotation(input)) }
            val edits = readList(input) { DeferredAnnotationEdit(readShardId(input), TraceSerializer.readJournalAnnotation(input)) }
            val removals = readList(input) { DeferredAnnotationRemoval(readShardId(input), readUuid(input)) }
            val cleanups = readList(input) {
                val shard = readShardId(input)
                val position = readBlockPos(input)
                val kind = BlockCleanupKind.entries.getOrNull(input.readUnsignedByte())
                    ?: throw IllegalArgumentException("invalid block-cleanup kind")
                val min = if (input.readBoolean()) readBlockPos(input) else null
                val max = if (input.readBoolean()) readBlockPos(input) else null
                DeferredBlockCleanup(shard, position, kind, min, max)
            }
            val erosions = readList(input) {
                DeferredFootprintErosion(input.readLong().also { require(it >= 0) }, readShardId(input), readUuid(input), readBlockPos(input), input.readDouble())
            }
            val prunes = readList(input) { DeferredSupportPrune(readShardId(input), input.readInt(), input.readInt()) }
            val weakenings = readList(input) {
                DeferredNeighborhoodWeakening(input.readLong().also { require(it >= 0) }, readShardId(input), readBlockPos(input), input.readInt(), input.readDouble())
            }
            require(input.available() == 0) { "trailing data in deferred journal" }
            return DeferredJournalState(
                pending, captures, seen, annotations, edits, removals, cleanups, erosions, prunes, weakenings,
                nextSerial, nextErosionSerial, nextNeighborhoodSerial,
            )
        }
    }

    private fun writeShardId(out: DataOutputStream, id: TraceShardId) {
        out.writeUTF(id.dimension)
        out.writeInt(id.regionX)
        out.writeInt(id.regionZ)
    }

    private fun readShardId(input: DataInputStream) = TraceShardId(input.readUTF(), input.readInt(), input.readInt())
    private fun writeUuid(out: DataOutputStream, id: UUID) { out.writeLong(id.mostSignificantBits); out.writeLong(id.leastSignificantBits) }
    private fun readUuid(input: DataInputStream) = UUID(input.readLong(), input.readLong())
    private fun writeBlockPos(out: DataOutputStream, pos: BlockPos) { out.writeInt(pos.x); out.writeInt(pos.y); out.writeInt(pos.z) }
    private fun readBlockPos(input: DataInputStream) = BlockPos(input.readInt(), input.readInt(), input.readInt())
    private fun writeBytes(out: DataOutputStream, bytes: ByteArray) { out.writeInt(bytes.size); out.write(bytes) }

    private fun readBytes(input: DataInputStream): ByteArray {
        val size = input.readInt()
        require(size in 0..MAX_SHARD_BYTES.toInt() && size <= input.available()) { "invalid deferred snapshot size $size" }
        return ByteArray(size).also(input::readFully)
    }

    private inline fun <T> readList(input: DataInputStream, read: () -> T): List<T> {
        val count = input.readInt()
        require(count in 0..MAX_QUEUE_ENTRIES) { "invalid deferred queue count $count" }
        return List(count) { read() }
    }

    private class LimitedByteArrayOutputStream(private val limit: Int) : ByteArrayOutputStream() {
        override fun write(value: Int) {
            check(size() < limit) { "deferred-operation journal exceeds $limit bytes" }
            super.write(value)
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            check(length >= 0 && size().toLong() + length <= limit) { "deferred-operation journal exceeds $limit bytes" }
            super.write(bytes, offset, length)
        }
    }

    companion object {
        private const val MAGIC = 0x54524A4E
        private const val VERSION = 1
        private const val HEADER_BYTES = 4 + 4 + 8 + 8 + 8
        private const val FOOTER_BYTES = 8
        private const val MAX_QUEUE_ENTRIES = 100_000
        const val MAX_JOURNAL_BYTES = 128 * 1024 * 1024
        private const val MAX_SHARD_BYTES = 64L * 1024L * 1024L
    }
}
