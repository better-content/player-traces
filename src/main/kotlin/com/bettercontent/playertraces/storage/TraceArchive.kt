package com.bettercontent.playertraces.storage

import org.slf4j.LoggerFactory
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
import java.security.MessageDigest
import java.util.zip.CRC32

/** First-slice append-only snapshot archive. A manifest is the sole publication point. */
internal object TraceArchive {
    private const val MANIFEST_MAGIC = 0x54524d46 // TRMF
    private const val SEGMENT_MAGIC = 0x54525347 // TRSG
    private const val GAP_MAGIC = 0x54524750 // TRGP
    private const val VERSION = 1
    private const val MAX_PAYLOAD_BYTES = 64 * 1024 * 1024
    private const val MAX_SEGMENTS = 100_000
    private const val MAX_MANIFEST_BYTES = 64 * 1024 * 1024
    private val log = LoggerFactory.getLogger(TraceArchive::class.java)

    internal data class Snapshot(val payload: ByteArray, val revision: Long)
    private data class Entry(val sequence: Long, val file: String, val digest: ByteArray, val revision: Long)
    private data class Manifest(val entries: List<Entry>, val revision: Long)

    fun hasManifest(shard: Path): Boolean = Files.exists(manifestPath(shard)) || Files.exists(backupPath(shard))

    /** Returns false when a delayed older/equal snapshot is superseded by published state. */
    @Synchronized
    fun publish(shard: Path, payload: ByteArray, revision: Long): Boolean {
        require(revision >= 0) { "negative archive revision" }
        require(payload.size <= MAX_PAYLOAD_BYTES) { "snapshot exceeds $MAX_PAYLOAD_BYTES bytes" }
        Files.createDirectories(shard.parent)
        val manifestFile = manifestPath(shard)
        val current = if (Files.exists(manifestFile) || Files.exists(backupPath(shard))) {
            // Resolve/recover before choosing a sequence. In particular, never reuse
            // sequence 1 when the primary manifest is gone but its backup still owns it.
            readLatest(shard)
            validateManifest(Files.readAllBytes(manifestFile), shard.parent)
        } else null
        if (current != null && revision < current.revision) return false
        if (current != null && revision == current.revision) {
            val latest = current.entries.last()
            val existing = Files.readAllBytes(shard.resolveSibling(latest.file))
            val existingPayload = decodeFrame(existing, latest.sequence, latest.revision)
            if (!existingPayload.contentEquals(payload)) return false
            // Idempotent retry after an ambiguous post-rename failure must establish
            // the directory durability barrier before the manager treats it as saved.
            forceDirectory(shard.parent)
            return true
        }
        val sequence = (current?.entries?.lastOrNull()?.sequence ?: 0L) + 1L
        val segmentName = "${shard.fileName}.seg.${sequence.toString().padStart(12, '0')}"
        val segment = shard.resolveSibling(segmentName)
        val segmentTemp = shard.resolveSibling(".$segmentName.tmp")
        val bytes = encodeFrame(sequence, revision, payload)
        durableWrite(segmentTemp, bytes)
        decodeFrame(Files.readAllBytes(segmentTemp), sequence, revision)
        atomicMove(segmentTemp, segment)
        forceDirectory(shard.parent)

        // Every segment is a complete serialized TraceShardState snapshot. Keep the
        // current snapshot and one prior snapshot for rollback; retaining the whole
        // snapshot history would make both writes and recovery grow with shard age.
        val entries = listOf(Entry(sequence, segmentName, sha256(bytes), revision))
        val next = Manifest(entries, revision)
        val manifestBytes = encodeManifest(next)
        val manifestTemp = tempPath(manifestFile)
        durableWrite(manifestTemp, manifestBytes)
        validateManifest(Files.readAllBytes(manifestTemp), shard.parent)

        if (Files.exists(manifestFile)) {
            val backupTemp = tempPath(backupPath(shard))
            durableWrite(backupTemp, Files.readAllBytes(manifestFile))
            parseManifest(Files.readAllBytes(backupTemp))
            atomicMove(backupTemp, backupPath(shard))
            forceDirectory(shard.parent)
        }
        atomicMove(manifestTemp, manifestFile)
        forceDirectory(shard.parent)
        cleanupUnreferencedSegments(shard)
        return true
    }

    fun readLatest(shard: Path): Snapshot {
        val primary = manifestPath(shard)
        var primaryFailure: Exception? = null
        if (Files.exists(primary)) {
            try {
                return readPublished(primary, shard.parent)
            } catch (error: Exception) {
                primaryFailure = error
                log.warn("Trace archive manifest {} is invalid; attempting prior manifest", primary, error)
            }
        }
        val backup = backupPath(shard)
        if (Files.exists(backup)) {
            try {
                val recovered = readPublished(backup, shard.parent)
                val attemptedRevision = runCatching { readManifest(primary).revision }.getOrNull() ?: -1L
                writeGapMarker(shard, recovered.revision, attemptedRevision)
                val restore = tempPath(primary)
                durableWrite(restore, Files.readAllBytes(backup))
                atomicMove(restore, primary)
                forceDirectory(shard.parent)
                return recovered
            } catch (error: Exception) {
                log.warn("Prior trace archive manifest {} is invalid", backup, error)
                primaryFailure?.addSuppressed(error)
            }
        }
        throw IllegalStateException("No valid trace archive manifest for $shard", primaryFailure)
    }

    private fun readPublished(manifestFile: Path, directory: Path): Snapshot {
        val manifest = readManifest(manifestFile)
        var latest: Snapshot? = null
        for (entry in manifest.entries) {
            val segment = directory.resolve(entry.file).normalize()
            require(segment.parent == directory.normalize()) { "segment path escapes archive directory" }
            val bytes = Files.readAllBytes(segment)
            require(MessageDigest.isEqual(sha256(bytes), entry.digest)) { "segment checksum mismatch: ${entry.file}" }
            val decoded = decodeFrame(bytes, entry.sequence, entry.revision)
            latest = Snapshot(decoded, entry.revision)
        }
        return requireNotNull(latest) { "manifest has no committed snapshots" }.also {
            require(it.revision == manifest.revision) { "manifest revision differs from final segment" }
        }
    }

    private fun encodeFrame(sequence: Long, revision: Long, payload: ByteArray): ByteArray {
        val bodyBytes = ByteArrayOutputStream()
        DataOutputStream(bodyBytes).use { out ->
            out.writeInt(SEGMENT_MAGIC)
            out.writeInt(VERSION)
            out.writeLong(sequence)
            out.writeLong(revision)
            out.writeInt(payload.size)
            out.write(payload)
        }
        val body = bodyBytes.toByteArray()
        val crc = CRC32().also { it.update(body) }.value
        return body + ByteBuffer.allocate(java.lang.Long.BYTES).putLong(crc).array()
    }

    private fun decodeFrame(bytes: ByteArray, expectedSequence: Long, expectedRevision: Long): ByteArray {
        require(bytes.size >= 4 + 4 + 8 + 8 + 4 + 8) { "truncated segment frame" }
        val bodyLength = bytes.size - java.lang.Long.BYTES
        val expectedCrc = ByteBuffer.wrap(bytes, bodyLength, java.lang.Long.BYTES).long
        val actualCrc = CRC32().also { it.update(bytes, 0, bodyLength) }.value
        require(expectedCrc == actualCrc) { "segment frame CRC mismatch" }
        DataInputStream(ByteArrayInputStream(bytes, 0, bodyLength)).use { input ->
            require(input.readInt() == SEGMENT_MAGIC) { "unexpected segment magic" }
            require(input.readInt() == VERSION) { "unsupported segment version" }
            require(input.readLong() == expectedSequence) { "segment sequence mismatch" }
            require(input.readLong() == expectedRevision) { "segment revision mismatch" }
            val size = input.readInt()
            require(size in 0..MAX_PAYLOAD_BYTES && size == input.available()) { "invalid segment payload length" }
            return ByteArray(size).also(input::readFully)
        }
    }

    private fun encodeManifest(manifest: Manifest): ByteArray {
        val bodyBytes = ByteArrayOutputStream()
        DataOutputStream(bodyBytes).use { out ->
            out.writeInt(MANIFEST_MAGIC)
            out.writeInt(VERSION)
            out.writeLong(manifest.revision)
            out.writeInt(manifest.entries.size)
            manifest.entries.forEach { entry ->
                out.writeLong(entry.sequence)
                out.writeUTF(entry.file)
                out.writeInt(entry.digest.size)
                out.write(entry.digest)
                out.writeLong(entry.revision)
            }
        }
        val body = bodyBytes.toByteArray()
        val crc = CRC32().also { it.update(body) }.value
        return body + ByteBuffer.allocate(java.lang.Long.BYTES).putLong(crc).array()
    }

    private fun parseManifest(bytes: ByteArray): Manifest {
        require(bytes.size in 28..MAX_MANIFEST_BYTES) { "invalid manifest size ${bytes.size}" }
        val bodyLength = bytes.size - java.lang.Long.BYTES
        val expectedCrc = ByteBuffer.wrap(bytes, bodyLength, java.lang.Long.BYTES).long
        require(expectedCrc == CRC32().also { it.update(bytes, 0, bodyLength) }.value) { "manifest CRC mismatch" }
        DataInputStream(ByteArrayInputStream(bytes, 0, bodyLength)).use { input ->
            require(input.readInt() == MANIFEST_MAGIC) { "unexpected manifest magic" }
            require(input.readInt() == VERSION) { "unsupported manifest version" }
            val revision = input.readLong()
            require(revision >= 0) { "negative manifest revision" }
            val count = input.readInt()
            require(count in 1..MAX_SEGMENTS) { "invalid manifest segment count $count" }
            val entries = ArrayList<Entry>(count)
            var priorSequence: Long? = null
            var priorRevision = -1L
            repeat(count) {
                val sequence = input.readLong()
                val file = input.readUTF()
                val digestSize = input.readInt()
                require(digestSize == 32) { "invalid segment SHA-256 length" }
                val digest = ByteArray(digestSize).also(input::readFully)
                val entryRevision = input.readLong()
                require(sequence > 0 && (priorSequence == null || sequence == priorSequence + 1L)) {
                    "non-contiguous segment sequence"
                }
                require(entryRevision > priorRevision && entryRevision <= revision) { "invalid segment revision ordering" }
                require(file.matches(Regex("r\\.-?\\d+\\.-?\\d+\\.traces\\.seg\\.\\d{12}"))) { "invalid segment filename" }
                entries += Entry(sequence, file, digest, entryRevision)
                priorSequence = sequence
                priorRevision = entryRevision
            }
            require(input.available() == 0) { "trailing manifest bytes" }
            require(entries.last().revision == revision) { "manifest revision has no corresponding segment" }
            return Manifest(entries, revision)
        }
    }

    /** Cleanup is best-effort and runs only after both publication points are durable. */
    private fun cleanupUnreferencedSegments(shard: Path) {
        val referenced = linkedSetOf<String>()
        listOf(manifestPath(shard), backupPath(shard)).forEach { path ->
            if (Files.exists(path)) {
                runCatching { parseManifest(Files.readAllBytes(path)).entries.mapTo(referenced) { it.file } }
                    .onFailure { log.warn("Cannot read trace archive manifest during segment cleanup: {}", path, it) }
            }
        }
        val prefix = "${shard.fileName}.seg."
        Files.list(shard.parent).use { paths ->
            paths.filter { it.fileName.toString().startsWith(prefix) }
                .filter { it.fileName.toString() !in referenced }
                .forEach { path ->
                    runCatching { Files.deleteIfExists(path) }
                        .onFailure { log.warn("Could not remove unreferenced trace segment {}", path, it) }
                }
        }
    }

    private fun readManifest(path: Path): Manifest = parseManifest(Files.readAllBytes(path))

    private fun validateManifest(bytes: ByteArray, directory: Path): Manifest {
        val manifest = parseManifest(bytes)
        manifest.entries.forEach { entry ->
            val file = directory.resolve(entry.file)
            val segmentBytes = Files.readAllBytes(file)
            require(MessageDigest.isEqual(sha256(segmentBytes), entry.digest)) { "segment checksum mismatch: ${entry.file}" }
            decodeFrame(segmentBytes, entry.sequence, entry.revision)
        }
        return manifest
    }

    private fun durableWrite(path: Path, bytes: ByteArray) {
        FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
    }

    private fun atomicMove(source: Path, target: Path) {
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun forceDirectory(path: Path) {
        FileChannel.open(path, StandardOpenOption.READ).use { it.force(true) }
    }

    private fun writeGapMarker(shard: Path, durableRevision: Long, attemptedRevision: Long) {
        val body = ByteBuffer.allocate(4 + 4 + 8 + 8)
            .putInt(GAP_MAGIC).putInt(VERSION).putLong(durableRevision).putLong(attemptedRevision).array()
        val bytes = body + ByteBuffer.allocate(java.lang.Long.BYTES)
            .putLong(CRC32().also { it.update(body) }.value).array()
        val marker = gapPath(shard)
        val temp = tempPath(marker)
        durableWrite(temp, bytes)
        atomicMove(temp, marker)
        forceDirectory(shard.parent)
    }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun manifestPath(shard: Path) = shard.resolveSibling("${shard.fileName}.manifest")
    private fun backupPath(shard: Path) = shard.resolveSibling("${shard.fileName}.manifest.bak")
    private fun gapPath(shard: Path) = shard.resolveSibling("${shard.fileName}.gap")
    private fun tempPath(path: Path) = path.resolveSibling(".${path.fileName}.tmp")
}
