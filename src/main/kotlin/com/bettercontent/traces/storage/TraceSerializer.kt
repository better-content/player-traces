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
import java.nio.file.StandardOpenOption
import java.nio.channels.FileChannel
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

    class UnsupportedShardVersionException(message: String) : IllegalArgumentException(message)

    fun read(path: Path): TraceShardState {
        if (!Files.exists(path)) return TraceShardState()
        return try {
            parse(Files.readAllBytes(path), path)
        } catch (unsupported: UnsupportedShardVersionException) {
            log.warn("Refusing unsupported shard {}: {}", path, unsupported.message)
            throw unsupported
        } catch (error: Exception) {
            log.warn("Failed parsing shard {}: {}", path, error.toString())
            quarantineCorrupt(path)
            recoverBackup(path) ?: TraceShardState()
        }
    }

    private fun parse(all: ByteArray, path: Path): TraceShardState {
        val state = TraceShardState()
        if (all.size < MAGIC_BYTES + FOOTER_BYTES) {
            throw IllegalArgumentException("truncated shard")
        }

        val bodyLen = all.size - FOOTER_BYTES
        val body = all.copyOfRange(0, bodyLen)

        val footer = ByteBuffer.wrap(all)
        val footerOffset = all.size - FOOTER_BYTES
        val footerCount = footer.getInt(footerOffset)
        val expectedCrc = footer.getLong(footerOffset + 4)
        try {
            val crc = CRC32().also { it.update(body) }
            if (footerCount < 0 || expectedCrc != crc.value) {
                throw IllegalArgumentException("CRC mismatch: footerCount=$footerCount expectedCrc=$expectedCrc actualCrc=${crc.value}")
            }

            DataInputStream(ByteArrayInputStream(body)).use { input ->
                if (input.readInt() != MAGIC) throw IllegalArgumentException("unexpected magic")
                val major = input.readUnsignedShort()
                val minor = input.readUnsignedShort()
                if (major != MAJOR) {
                    throw UnsupportedShardVersionException("unsupported major version $major; expected $MAJOR")
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
            return state
        } catch (unsupported: UnsupportedShardVersionException) {
            throw unsupported
        } catch (error: Exception) {
            throw IllegalArgumentException("invalid shard $path: ${error.message}", error)
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
        val bytes = body + footerBuffer.toByteArray()
        Files.createDirectories(path.parent)
        val temp = tempPath(path)
        writeDurable(temp, bytes)
        parse(Files.readAllBytes(temp), temp)
        if (Files.exists(path)) {
            parse(Files.readAllBytes(path), path)
            val backupTemp = backupTempPath(path)
            writeDurable(backupTemp, Files.readAllBytes(path))
            parse(Files.readAllBytes(backupTemp), backupTemp)
            moveReplacing(backupTemp, backupPath(path))
            forceDirectory(path.parent)
        }
        moveReplacing(temp, path)
        forceDirectory(path.parent)
    }

    private fun recoverBackup(path: Path): TraceShardState? {
        val backup = backupPath(path)
        if (!Files.exists(backup)) return null
        return try {
            val bytes = Files.readAllBytes(backup)
            val recovered = parse(bytes, backup)
            val temp = tempPath(path)
            writeDurable(temp, bytes)
            parse(Files.readAllBytes(temp), temp)
            moveReplacing(temp, path)
            forceDirectory(path.parent)
            recovered
        } catch (unsupported: UnsupportedShardVersionException) {
            throw unsupported
        } catch (error: Exception) {
            log.warn("Backup recovery failed for {} from {}: {}", path, backup, error.toString())
            quarantineCorrupt(backup)
            null
        }
    }

    private fun writeDurable(path: Path, bytes: ByteArray) {
        FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE).use { channel ->
            var buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
    }

    private fun moveReplacing(source: Path, target: Path) {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun forceDirectory(directory: Path) {
        try {
            FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
        } catch (_: Exception) {
            // Not every filesystem permits opening directories. File contents were already forced.
        }
    }

    private fun tempPath(path: Path) = path.resolveSibling(".${path.fileName}.tmp")
    private fun backupPath(path: Path) = path.resolveSibling("${path.fileName}.bak")
    private fun backupTempPath(path: Path) = path.resolveSibling(".${path.fileName}.bak.tmp")

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
