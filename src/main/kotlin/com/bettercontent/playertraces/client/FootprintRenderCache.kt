package com.bettercontent.playertraces.client

import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.platform.NativeImage.Format
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

data class CachedFootprint(val mark: TraceVisualMark, val quad: SurfaceQuad, val bounds: AABB)
data class CachedFootprintDecal(val quad: SurfaceQuad, val bounds: AABB, val renderType: RenderType, val sourceCount: Int)
data class FootprintRenderData(
    val sparse: List<CachedFootprint> = emptyList(),
    val dense: List<CachedFootprintDecal> = emptyList(),
    val sourceCount: Int = 0,
    val mergedSourceCount: Int = 0,
    val atlasPages: Int = 0,
)

/** Caches surface raycasts and folds dense, coplanar footprints into alpha-mask decals. */
object FootprintRenderCache {
    internal const val MERGE_THRESHOLD = 16
    internal const val PIXELS_PER_BLOCK = 64
    internal const val CELL_SIZE = 4
    internal const val ATLAS_SIZE = 2048
    internal const val MAX_ATLAS_PAGES = 4
    private const val TERRAIN_VALIDATION_INTERVAL = 20L
    private const val PLANE_STEPS_PER_BLOCK = 256.0
    private const val GUTTER = 1
    private val footprintTexture = ResourceLocation.fromNamespaceAndPath("player_traces", "textures/effect/leg_contact.png")

    private var levelIdentity: Level? = null
    private var payloadRevision = Long.MIN_VALUE
    private var data = FootprintRenderData()
    private var supportStates = emptyMap<BlockPos, BlockState>()
    private var nextTerrainValidation = 0L
    private val atlasLocations = mutableListOf<ResourceLocation>()

    fun renderData(level: Level, traces: List<com.bettercontent.playertraces.dto.VisibleTraceDto>, revision: Long): FootprintRenderData {
        val terrainChanged = level.gameTime >= nextTerrainValidation && supportStates.any { (pos, state) -> level.getBlockState(pos) != state }
        if (levelIdentity !== level || payloadRevision != revision || terrainChanged) rebuild(level, traces, revision)
        if (level.gameTime >= nextTerrainValidation) nextTerrainValidation = level.gameTime + TERRAIN_VALIDATION_INTERVAL
        return data
    }

    fun clear() {
        val textures = Minecraft.getInstance().textureManager
        atlasLocations.forEach(textures::release)
        atlasLocations.clear()
        data = FootprintRenderData()
        supportStates = emptyMap()
        levelIdentity = null
        payloadRevision = Long.MIN_VALUE
    }

    private fun rebuild(level: Level, traces: List<com.bettercontent.playertraces.dto.VisibleTraceDto>, revision: Long) {
        val started = System.nanoTime()
        clear()
        levelIdentity = level
        payloadRevision = revision
        nextTerrainValidation = level.gameTime + TERRAIN_VALIDATION_INTERVAL
        val marks = TraceVisualModel.marks(traces, 1f, 0f, TracesClientRenderer.MAX_RENDERED_FOOTPRINTS)
        val resolved = marks.mapNotNull { mark ->
            val quad = SurfaceAnchorResolver.footprintQuad(level, mark.trace, mark.angle, mark.lateralOffset, mark.longitudinalOffset)
                ?: return@mapNotNull null
            Resolved(mark, quad, bounds(quad))
        }
        supportStates = resolved.flatMap { it.quad.vertices }.map { BlockPos.containing(it.position.x, it.position.y - 0.01, it.position.z) }
            .distinct().associateWith(level::getBlockState)

        val sparse = mutableListOf<CachedFootprint>()
        val denseGroups = mutableListOf<List<Resolved>>()
        resolved.groupBy { resolvedKey(it) }.values.forEach { bucket ->
            connectedComponents(bucket).forEach { group ->
                if (group.size >= MERGE_THRESHOLD) denseGroups += group
                else group.forEach { sparse += CachedFootprint(it.mark, it.quad, it.bounds) }
            }
        }

        val sprite = loadSprite()
        if (sprite == null) {
            denseGroups.flatten().forEach { sparse += CachedFootprint(it.mark, it.quad, it.bounds) }
            data = FootprintRenderData(sparse, sourceCount = resolved.size)
            return
        }
        val pages = mutableListOf<AtlasPage>()
        val dense = mutableListOf<CachedFootprintDecal>()
        denseGroups.forEach { group ->
            val mask = rasterize(group, sprite)
            val placement = place(pages, mask.width + GUTTER * 2, mask.height + GUTTER * 2)
            if (placement == null) {
                group.forEach { sparse += CachedFootprint(it.mark, it.quad, it.bounds) }
                mask.image.close()
            } else {
                val px = placement.x + GUTTER
                val py = placement.y + GUTTER
                copy(mask.image, placement.page.image, px, py)
                val u0 = px.toFloat() / ATLAS_SIZE
                val v0 = py.toFloat() / ATLAS_SIZE
                val u1 = (px + mask.width).toFloat() / ATLAS_SIZE
                val v1 = (py + mask.height).toFloat() / ATLAS_SIZE
                dense += CachedFootprintDecal(
                    decalQuad(mask.minX, mask.maxX, mask.y, mask.minZ, mask.maxZ, u0, v0, u1, v1),
                    AABB(mask.minX, mask.y - 0.01, mask.minZ, mask.maxX, mask.y + 0.02, mask.maxZ),
                    placement.page.renderType,
                    group.size,
                )
                mask.image.close()
            }
        }
        sprite.close()
        pages.forEach { it.upload() }
        data = FootprintRenderData(sparse, dense, resolved.size, dense.sumOf { it.sourceCount }, pages.size)
        if (com.bettercontent.playertraces.config.TracesConfig.client.visualDiagnostics.get()) {
            TracesClientLog.LOGGER.info(
                "TRACES_FOOTPRINT_CACHE source={} sparse={} mergedGroups={} mergedSources={} atlasPages={} rebuildMicros={}",
                data.sourceCount, data.sparse.size, data.dense.size, data.mergedSourceCount, data.atlasPages,
                (System.nanoTime() - started) / 1_000,
            )
        }
    }

    private fun loadSprite(): NativeImage? = runCatching {
        Minecraft.getInstance().resourceManager.getResource(footprintTexture).orElseThrow().open().use(NativeImage::read)
    }.onFailure { TracesClientLog.LOGGER.warn("Unable to build dense footprint masks", it) }.getOrNull()

    private fun resolvedKey(item: Resolved): Triple<Int, Int, Int> {
        val center = item.bounds.center
        val cellX = floor(center.x / CELL_SIZE).toInt()
        val cellZ = floor(center.z / CELL_SIZE).toInt()
        val plane = (item.quad.vertices.first().position.y * PLANE_STEPS_PER_BLOCK).roundToInt()
        return Triple(cellX, cellZ, plane)
    }

    internal fun connectedIndexGroups(bounds: List<AABB>): List<List<Int>> {
        val unseen = bounds.indices.toMutableSet()
        val groups = mutableListOf<List<Int>>()
        while (unseen.isNotEmpty()) {
            val queue = ArrayDeque<Int>()
            val group = mutableListOf<Int>()
            queue += unseen.first().also(unseen::remove)
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                group += current
                val touching = unseen.filter { intersects2d(bounds[current], bounds[it]) }
                touching.forEach { unseen.remove(it); queue += it }
            }
            groups += group
        }
        return groups
    }

    private fun connectedComponents(items: List<Resolved>): List<List<Resolved>> =
        connectedIndexGroups(items.map { it.bounds }).map { group -> group.map(items::get) }

    private fun intersects2d(a: AABB, b: AABB): Boolean =
        a.maxX >= b.minX && b.maxX >= a.minX && a.maxZ >= b.minZ && b.maxZ >= a.minZ

    private fun rasterize(group: List<Resolved>, sprite: NativeImage): Mask {
        val minX = group.minOf { it.bounds.minX }
        val maxX = group.maxOf { it.bounds.maxX }
        val minZ = group.minOf { it.bounds.minZ }
        val maxZ = group.maxOf { it.bounds.maxZ }
        val width = max(1, ceil((maxX - minX) * PIXELS_PER_BLOCK).toInt())
        val height = max(1, ceil((maxZ - minZ) * PIXELS_PER_BLOCK).toInt())
        val image = NativeImage(Format.RGBA, width, height, true)
        group.forEach { resolved ->
            val trace = resolved.mark.trace
            val angle = resolved.mark.angle.toDouble()
            val forwardX = cos(angle); val forwardZ = sin(angle)
            val rightX = -sin(angle); val rightZ = cos(angle)
            val centerX = trace.x + rightX * resolved.mark.lateralOffset + forwardX * resolved.mark.longitudinalOffset
            val centerZ = trace.z + rightZ * resolved.mark.lateralOffset + forwardZ * resolved.mark.longitudinalOffset
            val x0 = max(0, floor((resolved.bounds.minX - minX) * PIXELS_PER_BLOCK).toInt())
            val x1 = min(width - 1, ceil((resolved.bounds.maxX - minX) * PIXELS_PER_BLOCK).toInt())
            val z0 = max(0, floor((resolved.bounds.minZ - minZ) * PIXELS_PER_BLOCK).toInt())
            val z1 = min(height - 1, ceil((resolved.bounds.maxZ - minZ) * PIXELS_PER_BLOCK).toInt())
            for (z in z0..z1) for (x in x0..x1) {
                val worldX = minX + (x + 0.5) / PIXELS_PER_BLOCK
                val worldZ = minZ + (z + 0.5) / PIXELS_PER_BLOCK
                val dx = worldX - centerX; val dz = worldZ - centerZ
                val localRight = dx * rightX + dz * rightZ
                val localForward = dx * forwardX + dz * forwardZ
                val u = localRight / SurfaceAnchorResolver.FOOTPRINT_WIDTH + 0.5
                val v = 0.5 - localForward / SurfaceAnchorResolver.FOOTPRINT_LENGTH
                if (u !in 0.0..1.0 || v !in 0.0..1.0) continue
                val sx = min(sprite.width - 1, floor(u * sprite.width).toInt())
                val sy = min(sprite.height - 1, floor(v * sprite.height).toInt())
                val alpha = sprite.getPixelRGBA(sx, sy) ushr 24 and 0xff
                val composed = composeAlpha(image.getPixelRGBA(x, z) ushr 24 and 0xff, alpha)
                image.setPixelRGBA(x, z, (composed shl 24) or 0x00ffffff)
            }
        }
        return Mask(image, width, height, minX, maxX, group.first().quad.vertices.first().position.y, minZ, maxZ)
    }

    private fun place(pages: MutableList<AtlasPage>, width: Int, height: Int): Placement? {
        if (width > ATLAS_SIZE || height > ATLAS_SIZE) return null
        pages.forEach { page -> page.place(width, height)?.let { return Placement(page, it.first, it.second) } }
        if (pages.size >= MAX_ATLAS_PAGES) return null
        val page = AtlasPage(pages.size)
        pages += page
        val pos = page.place(width, height) ?: return null
        return Placement(page, pos.first, pos.second)
    }

    private fun copy(from: NativeImage, to: NativeImage, x: Int, y: Int) {
        for (py in 0 until from.height) for (px in 0 until from.width) to.setPixelRGBA(x + px, y + py, from.getPixelRGBA(px, py))
    }

    internal fun composeAlpha(existing: Int, incoming: Int): Int = max(existing.coerceIn(0, 255), incoming.coerceIn(0, 255))

    private fun decalQuad(minX: Double, maxX: Double, y: Double, minZ: Double, maxZ: Double, u0: Float, v0: Float, u1: Float, v1: Float): SurfaceQuad =
        SurfaceQuad(listOf(
            SurfaceVertex(Vec3(minX, y, minZ), u0, v1, 0),
            SurfaceVertex(Vec3(maxX, y, minZ), u1, v1, 0),
            SurfaceVertex(Vec3(maxX, y, maxZ), u1, v0, 0),
            SurfaceVertex(Vec3(minX, y, maxZ), u0, v0, 0),
        ))

    private fun bounds(quad: SurfaceQuad): AABB {
        val xs = quad.vertices.map { it.position.x }; val ys = quad.vertices.map { it.position.y }; val zs = quad.vertices.map { it.position.z }
        return AABB(xs.min(), ys.min() - 0.01, zs.min(), xs.max(), ys.max() + 0.01, zs.max())
    }

    private data class Resolved(val mark: TraceVisualMark, val quad: SurfaceQuad, val bounds: AABB)
    private data class Mask(val image: NativeImage, val width: Int, val height: Int, val minX: Double, val maxX: Double, val y: Double, val minZ: Double, val maxZ: Double)
    private data class Placement(val page: AtlasPage, val x: Int, val y: Int)

    private class AtlasPage(index: Int) {
        val image = NativeImage(Format.RGBA, ATLAS_SIZE, ATLAS_SIZE, true)
        private var x = 0
        private var y = 0
        private var rowHeight = 0
        private val texture = DynamicTexture(image)
        val location: ResourceLocation = Minecraft.getInstance().textureManager.register("player_traces/footprint_atlas_$index", texture)
        val renderType: RenderType = RenderType.entityTranslucent(location)
        init { atlasLocations += location }

        fun place(width: Int, height: Int): Pair<Int, Int>? {
            if (x + width > ATLAS_SIZE) { x = 0; y += rowHeight; rowHeight = 0 }
            if (y + height > ATLAS_SIZE) return null
            val result = x to y
            x += width
            rowHeight = max(rowHeight, height)
            return result
        }

        fun upload() = texture.upload()
    }
}
