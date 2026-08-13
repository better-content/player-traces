package com.bettercontent.traces.client.death

import com.bettercontent.traces.echo.EchoClip
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import kotlin.math.roundToInt

data class GeometrySnapshot(val vertices: FloatArray, val topology: IntArray)

class CaptureBufferSource : MultiBufferSource {
    private val batches = linkedMapOf<RenderType, CaptureVertexConsumer>()

    override fun getBuffer(renderType: RenderType): VertexConsumer =
        batches.getOrPut(renderType) { CaptureVertexConsumer() }

    fun snapshot(): GeometrySnapshot {
        val vertices = ArrayList<Float>()
        val edges = ArrayList<Int>()
        batches.forEach { (renderType, consumer) ->
            if (!accepted(renderType)) return@forEach
            val batch = consumer.positions
            val primitiveSize = when (renderType.mode()) {
                VertexFormat.Mode.QUADS -> 4
                VertexFormat.Mode.TRIANGLES -> 3
                else -> return@forEach
            }
            val completeVertices = (batch.size / 3 / primitiveSize) * primitiveSize
            var localVertex = 0
            while (localVertex < completeVertices) {
                val first = vertices.size / 3
                repeat(primitiveSize) { vertexIndex ->
                    val source = (localVertex + vertexIndex) * 3
                    vertices += batch[source]
                    vertices += batch[source + 1]
                    vertices += batch[source + 2]
                }
                repeat(primitiveSize) { edge ->
                    edges += first + edge
                    edges += first + (edge + 1) % primitiveSize
                }
                localVertex += primitiveSize
            }
        }
        require(vertices.size / 3 <= EchoClip.MAX_GEOMETRY_VERTICES) { "captured player geometry exceeds the prototype vertex cap" }
        require(edges.size <= EchoClip.MAX_TOPOLOGY_INDICES) { "captured player geometry exceeds the prototype topology cap" }
        return GeometrySnapshot(vertices.toFloatArray(), edges.toIntArray())
    }

    private fun accepted(renderType: RenderType): Boolean {
        val name = renderType.toString().lowercase()
        return renderType.mode() in setOf(VertexFormat.Mode.QUADS, VertexFormat.Mode.TRIANGLES) &&
            listOf("rendertype[text", "rendertype[glint", "rendertype[entity_glint", "rendertype[shadow", "rendertype[leash")
                .none(name::startsWith)
    }
}

private class CaptureVertexConsumer : VertexConsumer {
    val positions = ArrayList<Float>()
    private var x = 0f
    private var y = 0f
    private var z = 0f
    private var hasPosition = false

    override fun vertex(x: Double, y: Double, z: Double): VertexConsumer = apply {
        this.x = x.toFloat()
        this.y = y.toFloat()
        this.z = z.toFloat()
        hasPosition = true
    }

    override fun color(red: Int, green: Int, blue: Int, alpha: Int): VertexConsumer = this
    override fun uv(u: Float, v: Float): VertexConsumer = this
    override fun overlayCoords(u: Int, v: Int): VertexConsumer = this
    override fun uv2(u: Int, v: Int): VertexConsumer = this
    override fun normal(x: Float, y: Float, z: Float): VertexConsumer = this

    override fun endVertex() {
        if (hasPosition && x.isFinite() && y.isFinite() && z.isFinite() &&
            x in -16f..16f && y in -16f..16f && z in -16f..16f
        ) {
            positions += x
            positions += y
            positions += z
        }
        hasPosition = false
    }

    override fun defaultColor(red: Int, green: Int, blue: Int, alpha: Int) = Unit
    override fun unsetDefaultColor() = Unit
}

internal fun GeometrySnapshot.hasSameTopology(other: GeometrySnapshot): Boolean =
    vertices.size == other.vertices.size && topology.contentEquals(other.topology)

internal fun GeometrySnapshot.uniqueEdgeCount(): Int {
    val unique = HashSet<List<Int>>()
    topology.asList().chunked(2).forEach { pair ->
        val first = pointKey(pair[0])
        val second = pointKey(pair[1])
        unique += if (compareKeys(first, second) <= 0) first + second else second + first
    }
    return unique.size
}

private fun GeometrySnapshot.pointKey(index: Int): List<Int> {
    val offset = index * 3
    return listOf(
        (vertices[offset] * 4096f).roundToInt(),
        (vertices[offset + 1] * 4096f).roundToInt(),
        (vertices[offset + 2] * 4096f).roundToInt(),
    )
}

private fun compareKeys(first: List<Int>, second: List<Int>): Int {
    repeat(3) { index ->
        val compared = first[index].compareTo(second[index])
        if (compared != 0) return compared
    }
    return 0
}
