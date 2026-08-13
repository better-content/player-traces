package com.bettercontent.playertraces.echo

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import kotlin.math.roundToInt

data class EchoSizeMeasurement(
    val rawBytes: Int,
    val quantizedBytes: Int,
    val deflatedRawBytes: Int,
    val deflatedQuantizedBytes: Int,
)

object EchoClipCodec {
    private const val MAGIC = 0x4543484F
    private const val VERSION = 1
    private const val SCALE = 4096f
    private const val MAX_ENCODED_BYTES = 16 * 1024 * 1024

    fun encodeRaw(clip: EchoClip): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            writeHeader(output, clip, false)
            clip.frames.forEach { frame ->
                output.writeFloat(frame.root.x)
                output.writeFloat(frame.root.y)
                output.writeFloat(frame.root.z)
                output.writeFloat(frame.root.bodyYaw)
                output.writeFloat(frame.root.headYaw)
                frame.channels.forEach(output::writeFloat)
            }
        }
        bytes.toByteArray()
    }

    fun encodeQuantized(clip: EchoClip): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            writeHeader(output, clip, true)
            val previous = IntArray(5 + clip.channelCount)
            clip.frames.forEachIndexed { frameIndex, frame ->
                val values = FloatArray(previous.size)
                values[0] = frame.root.x
                values[1] = frame.root.y
                values[2] = frame.root.z
                values[3] = frame.root.bodyYaw
                values[4] = frame.root.headYaw
                frame.channels.copyInto(values, destinationOffset = 5)
                values.forEachIndexed { index, value ->
                    val quantized = quantize(value)
                    val delta = if (frameIndex == 0) quantized else quantized - previous[index]
                    writeSignedVarInt(output, delta)
                    previous[index] = quantized
                }
            }
        }
        bytes.toByteArray()
    }

    fun decodeQuantized(encoded: ByteArray): EchoClip {
        require(encoded.size <= MAX_ENCODED_BYTES) { "encoded echo is too large" }
        DataInputStream(ByteArrayInputStream(encoded)).use { input ->
            val header = readHeader(input)
            require(header.quantized) { "echo is not quantized" }
            val previous = IntArray(5 + header.channelCount)
            val frames = ArrayList<EchoFrame>(header.frameCount)
            repeat(header.frameCount) { frameIndex ->
                val values = FloatArray(previous.size)
                for (index in values.indices) {
                    val delta = readSignedVarInt(input)
                    val quantized = if (frameIndex == 0) delta else previous[index] + delta
                    previous[index] = quantized
                    values[index] = quantized / SCALE
                }
                frames += EchoFrame(
                    EchoRoot(values[0], values[1], values[2], values[3], values[4]),
                    values.copyOfRange(5, values.size),
                )
            }
            require(input.read() == -1) { "encoded echo has trailing data" }
            return EchoClip(header.encoding, header.sampleRate, header.topology, frames)
        }
    }

    fun measure(clip: EchoClip): EchoSizeMeasurement {
        val raw = encodeRaw(clip)
        val quantized = encodeQuantized(clip)
        return EchoSizeMeasurement(raw.size, quantized.size, deflate(raw).size, deflate(quantized).size)
    }

    fun deflate(bytes: ByteArray): ByteArray = ByteArrayOutputStream().use { result ->
        DeflaterOutputStream(result, Deflater(Deflater.BEST_COMPRESSION)).use { it.write(bytes) }
        result.toByteArray()
    }

    private fun writeHeader(output: DataOutputStream, clip: EchoClip, quantized: Boolean) {
        output.writeInt(MAGIC)
        output.writeByte(VERSION)
        output.writeByte(clip.encoding.ordinal)
        output.writeBoolean(quantized)
        writeUnsignedVarInt(output, clip.sampleRate)
        writeUnsignedVarInt(output, clip.frames.size)
        writeUnsignedVarInt(output, clip.channelCount)
        writeUnsignedVarInt(output, clip.topology.size)
        clip.topology.forEach { writeUnsignedVarInt(output, it) }
    }

    private fun readHeader(input: DataInputStream): Header {
        require(input.readInt() == MAGIC) { "echo magic is invalid" }
        require(input.readUnsignedByte() == VERSION) { "echo version is unsupported" }
        val encodingOrdinal = input.readUnsignedByte()
        require(encodingOrdinal in EchoEncoding.entries.indices) { "echo encoding is invalid" }
        val quantized = input.readBoolean()
        val sampleRate = readUnsignedVarInt(input)
        val frameCount = readUnsignedVarInt(input)
        val channelCount = readUnsignedVarInt(input)
        val topologyCount = readUnsignedVarInt(input)
        require(sampleRate in 1..EchoClip.MAX_SAMPLE_RATE) { "echo sample rate is invalid" }
        require(frameCount in 1..sampleRate * EchoClip.MAX_DURATION_SECONDS) { "echo frame count is invalid" }
        require(channelCount in 1..EchoClip.MAX_GEOMETRY_CHANNELS) { "echo channel count is invalid" }
        require(topologyCount in 0..EchoClip.MAX_TOPOLOGY_INDICES) { "echo topology count is invalid" }
        val topology = IntArray(topologyCount) { readUnsignedVarInt(input) }
        return Header(EchoEncoding.entries[encodingOrdinal], quantized, sampleRate, frameCount, channelCount, topology)
    }

    private fun quantize(value: Float): Int {
        require(value.isFinite() && value in -2048f..2048f) { "echo channel is outside codec range" }
        return (value * SCALE).roundToInt()
    }

    private fun writeUnsignedVarInt(output: DataOutputStream, value: Int) {
        require(value >= 0)
        var remaining = value
        while ((remaining and 0x7F.inv()) != 0) {
            output.writeByte((remaining and 0x7F) or 0x80)
            remaining = remaining ushr 7
        }
        output.writeByte(remaining)
    }

    private fun readUnsignedVarInt(input: DataInputStream): Int {
        var result = 0
        var shift = 0
        while (shift < 35) {
            val byte = input.readUnsignedByte()
            result = result or ((byte and 0x7F) shl shift)
            if ((byte and 0x80) == 0) return result
            shift += 7
        }
        throw IllegalArgumentException("echo varint is too long")
    }

    private fun writeSignedVarInt(output: DataOutputStream, value: Int) =
        writeUnsignedVarInt(output, (value shl 1) xor (value shr 31))

    private fun readSignedVarInt(input: DataInputStream): Int {
        val value = readUnsignedVarInt(input)
        return (value ushr 1) xor -(value and 1)
    }

    private data class Header(
        val encoding: EchoEncoding,
        val quantized: Boolean,
        val sampleRate: Int,
        val frameCount: Int,
        val channelCount: Int,
        val topology: IntArray,
    )
}
