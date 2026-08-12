package com.bettercontent.traces.storage

import com.bettercontent.traces.domain.FootTrace
import com.bettercontent.traces.domain.MovementClass
import com.bettercontent.traces.domain.TraceAnnotation
import com.bettercontent.traces.domain.TraceTeam
import net.minecraft.core.BlockPos
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import org.slf4j.LoggerFactory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.CRC32

private const val MAGIC = 0x54524143
private const val MAJOR = 1
private const val MINOR = 0

private const val FOOT_BLOCK = 1
private const val ANNOTATION_BLOCK = 2
private const val SEEN_BLOCK = 3
private const val VERSION_BLOCK = 4

private const val FOOTER_BYTES = 12
private const val HEADER_EXTRA_INTS = 8
private const val HEADER_HEADER_BYTES = 2 + 2 + (HEADER_EXTRA_INTS * 4)
private const val MAGIC_BYTES = 4

object TraceSerializer {
    private val log = LoggerFactory.getLogger(TraceSerializer::class.java)

    fun read(path: Path): TraceShardState {
        val state = TraceShardState()
        if (!Files.exists(path)) return state
        val all = Files.readAllBytes(path)
        if (all.size < MAGIC_BYTES + FOOTER_BYTES) {
            log.warn("Rejecting truncated shard {}", path)
            quarantineCorrupt(path)
            return state
        }

        val bodyLen = all.size - FOOTER_BYTES
        val body = all.copyOfRange(0, bodyLen)

        val footer = ByteBuffer.wrap(all)
        val footerOffset = all.size - FOOTER_BYTES
        val footerCount = footer.getInt(footerOffset)
        val expectedCrc = footer.getLong(footerOffset + 4)
        return try {
            val crc = CRC32().also { it.update(body) }
            if (footerCount < 0 || expectedCrc != crc.value) {
                log.warn(
                    "Rejecting shard {} due CRC mismatch: path={} footerCount={} expectedCrc={} actualCrc={} fileSize={}",
                    path,
                    path,
                    footerCount,
                    expectedCrc,
                    crc.value,
                    all.size,
                )
                quarantineCorrupt(path)
                return state
            }

            DataInputStream(ByteArrayInputStream(body)).use { input ->
                if (input.readInt() != MAGIC) throw IllegalArgumentException("unexpected magic")
                val major = input.readUnsignedShort()
                val minor = input.readUnsignedShort()
                if (major != MAJOR) {
                    log.warn("Rejecting shard {} with unsupported major version {}", path, major)
                    throw IllegalArgumentException("unsupported major version $major")
                }
                repeat(HEADER_EXTRA_INTS) { input.readInt() }
                log.debug(
                    "Parsing shard {}: footerCount={} major={} minor={} bytes={}",
                    path,
                    footerCount,
                    major,
                    minor,
                    body.size,
                )

                if (minor > MINOR) {
                    // future minor versions: try best-effort parse of known blocks
                }

                while (input.available() > 0) {
                    val type = input.readUnsignedByte()
                    log.debug("Shard {} next block type {}", path, type)
                    when (type) {
                        FOOT_BLOCK -> {
                            val count = input.readInt()
                            require(count >= 0) { "negative foot trace count" }
                            repeat(count) { state.footTraces.add(readFootTrace(input)) }
                        }
                        ANNOTATION_BLOCK -> {
                            val count = input.readInt()
                            require(count >= 0) { "negative annotation count" }
                            repeat(count) { state.annotations.add(readAnnotation(input)) }
                        }
                        SEEN_BLOCK -> {
                            val count = input.readInt()
                            require(count >= 0) { "negative seen-state count" }
                            repeat(count) { state.seenStates.add(readSeenState(input)) }
                        }
                        VERSION_BLOCK -> {
                            input.readInt()
                        }
                        else -> {
                            throw IllegalArgumentException("unknown block type $type")
                        }
                    }
                }
            }
            val observed = state.footTraces.size + state.annotations.size + state.seenStates.size
            require(observed == footerCount) { "footer record count $footerCount does not match $observed" }
            state
        } catch (ex: Exception) {
            log.warn("Failed parsing shard {}: {}", path, ex.toString())
            quarantineCorrupt(path)
            TraceShardState()
        }
    }

    fun write(path: Path, state: TraceShardState, bounds: Pair<BlockPos, BlockPos>) {
        val payload = ByteArrayOutputStream()
        DataOutputStream(payload).use { out ->
            out.writeInt(MAGIC)
            out.writeShort(MAJOR)
            out.writeShort(MINOR)
            out.writeInt(bounds.first.x)
            out.writeInt(bounds.first.y)
            out.writeInt(bounds.first.z)
            out.writeInt(bounds.second.x)
            out.writeInt(bounds.second.y)
            out.writeInt(bounds.second.z)
            out.writeInt(0)
            out.writeInt(0)

            out.writeByte(FOOT_BLOCK)
            out.writeInt(state.footTraces.size)
            state.footTraces.forEach { writeFootTrace(out, it) }

            out.writeByte(ANNOTATION_BLOCK)
            out.writeInt(state.annotations.size)
            state.annotations.forEach { writeAnnotation(out, it) }

            out.writeByte(SEEN_BLOCK)
            out.writeInt(state.seenStates.size)
            state.seenStates.forEach { writeSeenState(out, it) }
            out.writeByte(VERSION_BLOCK)
            out.writeInt(1)
        }

        val body = payload.toByteArray()
        val crc = CRC32().also { it.update(body) }
        val footerBuffer = ByteArrayOutputStream()
        DataOutputStream(footerBuffer).use { footerOut ->
            footerOut.writeInt(state.footTraces.size + state.annotations.size + state.seenStates.size)
            footerOut.writeLong(crc.value)
        }
        Files.createDirectories(path.parent)
        val temp = path.resolveSibling("." + path.fileName.toString() + ".tmp")
        Files.write(temp, body + footerBuffer.toByteArray())
        try {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun quarantineCorrupt(path: Path) {
        try {
            if (!Files.exists(path)) return
            val ts = SimpleDateFormat("yyyyMMddHHmmss", Locale.ROOT).format(Date())
            Files.move(
                path,
                path.resolveSibling("${path.fileName}.corrupt.$ts"),
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: Exception) {
        }
    }

    private fun writeFootTrace(output: DataOutputStream, trace: FootTrace) {
        output.writeUTF(trace.id.toString())
        output.writeUTF(trace.levelKey)
        output.writeInt(trace.blockPos.x)
        output.writeInt(trace.blockPos.y)
        output.writeInt(trace.blockPos.z)
        output.writeByte(trace.movementClass.ordinal)
        output.writeFloat(trace.strength)
        output.writeLong(trace.sequenceId.mostSignificantBits)
        output.writeLong(trace.sequenceId.leastSignificantBits)
        output.writeInt(trace.sequenceIndex)
        output.writeLong(trace.createdAt)
        output.writeLong(trace.sequenceEpoch)
        output.writeBoolean(trace.surviving)
        output.writeLong(trace.sourcePlayerInternal.mostSignificantBits)
        output.writeLong(trace.sourcePlayerInternal.leastSignificantBits)
    }

    private fun writeAnnotation(output: DataOutputStream, annotation: TraceAnnotation) {
        output.writeUTF(annotation.id.toString())
        output.writeUTF(annotation.text)
        output.writeUTF(annotation.icon)
        output.writeInt(annotation.color)
        output.writeInt(annotation.position.x)
        output.writeInt(annotation.position.y)
        output.writeInt(annotation.position.z)
        output.writeInt(annotation.targetBlock.x)
        output.writeInt(annotation.targetBlock.y)
        output.writeInt(annotation.targetBlock.z)
        output.writeUTF(annotation.team.id)
        output.writeInt(annotation.revision)
        output.writeLong(annotation.createdByInternal.mostSignificantBits)
        output.writeLong(annotation.createdByInternal.leastSignificantBits)
    }

    private fun writeSeenState(output: DataOutputStream, state: SeenStateRecord) {
        output.writeLong(state.annotationId.mostSignificantBits)
        output.writeLong(state.annotationId.leastSignificantBits)
        output.writeLong(state.playerId.mostSignificantBits)
        output.writeLong(state.playerId.leastSignificantBits)
        output.writeInt(state.highestRevision)
    }

    private fun readFootTrace(input: DataInputStream): FootTrace {
        val id = UUID.fromString(input.readUTF())
        val levelKey = input.readUTF()
        val x = input.readInt()
        val y = input.readInt()
        val z = input.readInt()
        val movement = MovementClass.values()[input.readUnsignedByte()]
        val strength = input.readFloat()
        val sequence = UUID(input.readLong(), input.readLong())
        val sequenceIndex = input.readInt()
        val createdAt = input.readLong()
        val epoch = input.readLong()
        val surviving = input.readBoolean()
        val player = UUID(input.readLong(), input.readLong())
        return FootTrace(id, levelKey, BlockPos(x, y, z), movement, strength, sequence, sequenceIndex, createdAt, epoch, surviving, player)
    }

    private fun readAnnotation(input: DataInputStream): TraceAnnotation {
        val id = UUID.fromString(input.readUTF())
        val text = input.readUTF()
        val icon = input.readUTF()
        val color = input.readInt()
        val x = input.readInt()
        val y = input.readInt()
        val z = input.readInt()
        val tx = input.readInt()
        val ty = input.readInt()
        val tz = input.readInt()
        val team = TraceTeam(input.readUTF())
        val revision = input.readInt()
        val creator = UUID(input.readLong(), input.readLong())
        return TraceAnnotation(id, text, icon, color, BlockPos(x, y, z), BlockPos(tx, ty, tz), team, revision, creator)
    }

    private fun readSeenState(input: DataInputStream): SeenStateRecord {
        val annotation = UUID(input.readLong(), input.readLong())
        val player = UUID(input.readLong(), input.readLong())
        val revision = input.readInt()
        return SeenStateRecord(annotation, player, revision)
    }
}
