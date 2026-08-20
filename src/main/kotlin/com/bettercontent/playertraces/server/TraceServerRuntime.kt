package com.bettercontent.playertraces.server

import com.bettercontent.playertraces.config.TracesConfig
import com.bettercontent.playertraces.logic.AnnotationService
import com.bettercontent.playertraces.logic.CaptureService
import com.bettercontent.playertraces.logic.ErosionService
import com.bettercontent.playertraces.logic.GuidanceService
import com.bettercontent.playertraces.storage.TraceStorageManager
import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.UUID
import com.bettercontent.playertraces.domain.FootTrace
import com.bettercontent.playertraces.domain.MovementClass
import com.bettercontent.playertraces.domain.TraceAnnotation
import com.bettercontent.playertraces.domain.TraceKind
import com.bettercontent.playertraces.domain.GLOBAL_TEAM
import com.mojang.logging.LogUtils
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import com.bettercontent.playertraces.domain.BloodPoolRecord
import com.bettercontent.playertraces.domain.DeathEchoRecord
import com.bettercontent.playertraces.logic.DeathEchoValidation
import com.bettercontent.playertraces.network.DeathCaptureRequestPacket
import com.bettercontent.playertraces.network.DeathEchoSubmitPacket
import com.bettercontent.playertraces.network.TracesNetwork
import com.bettercontent.playertraces.storage.DeathTraceSavedData
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level
import com.bettercontent.playertraces.logic.TraceSupportResolver
import com.bettercontent.playertraces.compat.DownedPlayerRevivalBridge

class TraceServerRuntime(val server: MinecraftServer) {
    private val log = LogUtils.getLogger()
    private val storages = ConcurrentHashMap<String, TraceStorageManager>()
    private val captureServices = ConcurrentHashMap<String, CaptureService>()
    private val annotationServices = ConcurrentHashMap<String, AnnotationService>()
    private val erosionServices = ConcurrentHashMap<String, ErosionService>()
    private val guidanceServices = ConcurrentHashMap<String, GuidanceService>()
    private val tickCounter = AtomicInteger()
    private val capturedCounter = java.util.concurrent.atomic.AtomicLong()
    private val pendingDeathCaptures = ConcurrentHashMap<UUID, PendingDeathCapture>()
    private val lastPlayerLocations = ConcurrentHashMap<UUID, PlayerLocation>()
    private val downedDeathCaptures = ConcurrentHashMap<UUID, DownedDeathCapture>()
    private val unavailableDownedCaptures = ConcurrentHashMap.newKeySet<UUID>()
    private val annotationEchoesInitialized = ConcurrentHashMap.newKeySet<String>()

    private val config = TracesConfig.common

    private fun levelKey(level: ServerLevel) = level.dimension().location().toString()

    fun storage(level: ServerLevel): TraceStorageManager =
        storages.getOrPut(levelKey(level)) { TraceStorageManager(level, config) }

    fun capture(level: ServerLevel): CaptureService =
        captureServices.getOrPut(levelKey(level)) { CaptureService(level, storage(level), config) }

    fun annotations(level: ServerLevel): AnnotationService =
        annotationServices.getOrPut(levelKey(level)) { AnnotationService(storage(level)) }

    fun annotationEchoes(level: ServerLevel): com.bettercontent.playertraces.storage.AnnotationEchoSavedData {
        val data = com.bettercontent.playertraces.storage.AnnotationEchoSavedData.get(level)
        if (annotationEchoesInitialized.add(levelKey(level))) {
            val valid = storage(level).allAnnotations().associate { it.id to it.revision }
            val removed = data.prune(valid)
            if (removed > 0) log.warn("Pruned {} orphaned or stale annotation echoes in {}", removed, levelKey(level))
        }
        return data
    }

    fun erosion(level: ServerLevel): ErosionService =
        erosionServices.getOrPut(levelKey(level)) { ErosionService(storage(level), config) }

    fun guidance(level: ServerLevel): GuidanceService =
        guidanceServices.getOrPut(levelKey(level)) { GuidanceService(storage(level)) }

    fun onServerTick() {
        val tick = tickCounter.incrementAndGet()
        for (level in server.allLevels) {
            erosion(level).tick(level, tick)
        }
        if (tick % 20 == 0) {
            pendingDeathCaptures.entries.removeIf { it.value.expiresAtServerTick < server.tickCount }
        }
    }

    fun storageCount(): Int = storages.size

    fun onPlayerTick(player: ServerPlayer) {
        capturedCounter.addAndGet(capture(player.serverLevel()).onPlayerTick(player).toLong())
        remember(player)
    }

    fun capturedCount(): Long = capturedCounter.get()

    fun preparePlayerCaptureFixture(level: ServerLevel, player: ServerPlayer) {
        buildVisualScene(level)
        val min = BlockPos(-20, level.minBuildHeight, -24)
        val max = BlockPos(20, level.maxBuildHeight - 1, 24)
        val store = storage(level)
        val removed = store.removeFootTraces(min, max)
        store.removeAnnotation(UUID.nameUUIDFromBytes("traces-visual-connected-v10".toByteArray()))
        store.removeAnnotation(UUID.nameUUIDFromBytes("traces-visual-disconnected-v10".toByteArray()))
        capture(level).onDimensionTeleport(player)
        capturedCounter.set(0)
        player.teleportTo(level, 0.5, 101.0, 4.5, 0.0f, 25.0f)
        log.info("TRACES_PLAYER_CAPTURE_PREPARED removed={} start=0.5,101,4.5 yaw=0 pitch=25", removed)
    }

    fun setPlayerCaptureYaw(level: ServerLevel, player: ServerPlayer, yaw: Float) {
        check(yaw.isFinite() && yaw in -180f..180f) { "capture yaw is invalid" }
        player.teleportTo(level, player.x, player.y, player.z, yaw, 25f)
        log.info("TRACES_PLAYER_CAPTURE_YAW yaw={} position={},{},{}", yaw, player.x, player.y, player.z)
    }

    fun verifyPlayerCaptureFixture(level: ServerLevel, player: ServerPlayer) {
        val traces = storage(level).queryTraces(
            BlockPos(-20, level.minBuildHeight, -24), BlockPos(20, level.maxBuildHeight - 1, 24),
        ).filter { it.sourcePlayerInternal == player.uuid }
        val sequence = traces.groupBy { it.sequenceId }.values.maxByOrNull { it.size }
            ?.sortedBy { it.sequenceIndex }.orEmpty()
        check(sequence.size >= 6) { "player capture produced only ${sequence.size} footprints" }
        val pairs = sequence.zipWithNext()
        val spacings = pairs.map { (a, b) -> kotlin.math.hypot(b.x - a.x, b.z - a.z) }
        val backwardPairs = pairs.count { (a, b) ->
            val yaw = Math.toRadians(b.facingYaw.toDouble())
            val facingX = -kotlin.math.sin(yaw)
            val facingZ = kotlin.math.cos(yaw)
            (b.x - a.x) * facingX + (b.z - a.z) * facingZ < -0.05
        }
        val diagonalPairs = pairs.count { (a, b) ->
            kotlin.math.abs(b.x - a.x) > 0.05 && kotlin.math.abs(b.z - a.z) > 0.05
        }
        val distinctYaws = sequence.map { kotlin.math.round(it.facingYaw * 10f) / 10f }.distinct()
        val nonOrthogonalYaws = distinctYaws.filter { yaw ->
            val remainder = kotlin.math.abs(yaw % 90f)
            remainder > 1f && kotlin.math.abs(remainder - 90f) > 1f
        }
        log.info(
            "TRACES_PLAYER_CAPTURE_MEASURED captured={} sequence={} backwardPairs={} diagonalPairs={} yaws={} nonOrthogonalYaws={} minSpacing={} maxSpacing={}",
            capturedCounter.get(), sequence.size, backwardPairs, diagonalPairs, distinctYaws, nonOrthogonalYaws, spacings.min(), spacings.max(),
        )
        check(backwardPairs > 0) { "player capture has no backward movement" }
        check(diagonalPairs > 0) { "player capture has no diagonal movement" }
        check(distinctYaws.size >= 4 && nonOrthogonalYaws.size >= 3) { "player capture did not preserve precise yaw: $distinctYaws" }
        check(spacings.all { it in 0.60..0.752 }) { "player capture spacing drifted: $spacings" }
        log.info(
            "TRACES_PLAYER_CAPTURE_VERIFIED captured={} sequence={} backwardPairs={} diagonalPairs={} yaws={} nonOrthogonalYaws={} minSpacing={} maxSpacing={}",
            capturedCounter.get(), sequence.size, backwardPairs, diagonalPairs, distinctYaws, nonOrthogonalYaws, spacings.min(), spacings.max(),
        )
    }

    fun onFluidPlaced(level: ServerLevel, blockPos: BlockPos) {
        erosion(level).onFluidTick(blockPos)
    }

    fun onPlayerLogin(player: ServerPlayer) {
        addLifecycleMarker(player.serverLevel(), player, TraceKind.ARRIVAL, player.position(), player.yRot)
        if (DownedPlayerRevivalBridge.isDowned(player)) unavailableDownedCaptures += player.uuid
        remember(player)
    }

    fun onPlayerLogout(player: ServerPlayer) {
        addLifecycleMarker(player.serverLevel(), player, TraceKind.DEPARTURE, player.position(), player.yRot)
        lastPlayerLocations.remove(player.uuid)
        downedDeathCaptures.remove(player.uuid)?.let { TracesNetwork.discardDeathEcho(player, it.token) }
        unavailableDownedCaptures.remove(player.uuid)
        pendingDeathCaptures.entries.removeIf { it.value.playerId == player.uuid }
        capture(player.serverLevel()).onDimensionTeleport(player)
    }

    fun onPlayerRespawn(player: ServerPlayer) {
        addLifecycleMarker(player.serverLevel(), player, TraceKind.ARRIVAL, player.position(), player.yRot)
        unavailableDownedCaptures.remove(player.uuid)
        remember(player)
        capture(player.serverLevel()).onDimensionTeleport(player)
    }

    fun onPlayerChangedDimension(player: ServerPlayer, from: ResourceKey<Level>) {
        val prior = lastPlayerLocations[player.uuid]
        val oldLevel = server.getLevel(from)
        if (oldLevel != null && prior?.dimension == from.location().toString()) {
            addLifecycleMarker(oldLevel, player, TraceKind.DEPARTURE, prior.position, prior.yaw)
            capture(oldLevel).onDimensionTeleport(player)
        }
        downedDeathCaptures.remove(player.uuid)?.let {
            TracesNetwork.discardDeathEcho(player, it.token)
            unavailableDownedCaptures += player.uuid
        }
        addLifecycleMarker(player.serverLevel(), player, TraceKind.ARRIVAL, player.position(), player.yRot)
        capture(player.serverLevel()).onDimensionTeleport(player)
        remember(player)
    }

    fun onSupportRemoved(level: ServerLevel, blockPos: BlockPos): Int = storage(level).removeBySupport(blockPos)

    fun onPlayerDowned(player: ServerPlayer): DownedDeathCapture {
        val level = player.serverLevel()
        return DownedDeathCapture(
            token = UUID.randomUUID(),
            dimension = level.dimension().location().toString(),
            position = player.position(),
            createdAt = level.gameTime,
        ).also { capture ->
            unavailableDownedCaptures.remove(player.uuid)
            downedDeathCaptures.put(player.uuid, capture)?.let { prior ->
                TracesNetwork.discardDeathEcho(player, prior.token)
            }
            TracesNetwork.freezeDeathEcho(player, capture.token, capture.dimension, capture.position, capture.createdAt)
        }
    }

    fun onPlayerRevived(player: ServerPlayer): UUID? {
        unavailableDownedCaptures.remove(player.uuid)
        return downedDeathCaptures.remove(player.uuid)?.token?.also { TracesNetwork.discardDeathEcho(player, it) }
    }

    private fun remember(player: ServerPlayer) {
        lastPlayerLocations[player.uuid] = PlayerLocation(
            player.serverLevel().dimension().location().toString(),
            player.position(),
            player.yRot.takeIf { it.isFinite() } ?: 0f,
        )
    }

    private fun addLifecycleMarker(
        level: ServerLevel,
        player: ServerPlayer,
        kind: TraceKind,
        position: Vec3,
        yaw: Float,
    ) {
        val surface = TraceSupportResolver.resolve(level, position, LIFECYCLE_SUPPORT_DEPTH)
        val rendered = surface?.position ?: position.add(0.0, 0.012, 0.0)
        val sequence = UUID.randomUUID()
        storage(level).addFootTrace(
            FootTrace(
                id = UUID.randomUUID(),
                levelKey = level.dimension().location().toString(),
                x = rendered.x,
                y = rendered.y,
                z = rendered.z,
                facingYaw = yaw.takeIf { it.isFinite() } ?: 0f,
                movementClass = MovementClass.WALK,
                strength = 1.0f,
                sequenceId = sequence,
                sequenceIndex = 0,
                createdAt = level.gameTime,
                sequenceEpoch = level.gameTime,
                surviving = true,
                sourcePlayerInternal = player.uuid,
                kind = kind,
                support = surface?.support,
            ),
        )
    }

    fun onPlayerDeath(player: ServerPlayer, cause: String) {
        val level = player.serverLevel()
        val death = player.position()
        val levelId = level.dimension().location().toString()
        val heldDowned = downedDeathCaptures.remove(player.uuid)
        val downed = heldDowned?.takeIf { it.dimension == levelId }
        val captureUnavailable = unavailableDownedCaptures.remove(player.uuid) || heldDowned != null && downed == null
        val echoPosition = downed?.position ?: death
        val echoCreatedAt = downed?.createdAt ?: level.gameTime
        val poolPosition = bloodPoolPosition(level, death)
        val pool = BloodPoolRecord(
            id = UUID.randomUUID(),
            ownerId = player.uuid,
            ownerName = player.scoreboardName.take(16),
            x = poolPosition.x,
            y = poolPosition.y,
            z = poolPosition.z,
            createdAt = level.gameTime,
            cause = cause.take(64),
        )
        val data = DeathTraceSavedData.get(level)
        data.addPool(pool, config.maxBloodPools.get())

        pendingDeathCaptures.entries.removeIf { it.value.playerId == player.uuid }
        if (captureUnavailable) {
            log.info("TRACES_DEATH_ECHO_SKIPPED player={} reason=pre_down_capture_unavailable", player.scoreboardName)
            return
        }
        val nonce = UUID.randomUUID()
        pendingDeathCaptures[nonce] = PendingDeathCapture(
            playerId = player.uuid,
            dimension = levelId,
            bloodPoolId = pool.id,
            echoPosition = echoPosition,
            createdAt = echoCreatedAt,
            captureToken = downed?.token,
            expiresAtServerTick = server.tickCount + DEATH_CAPTURE_TIMEOUT_TICKS,
        )
        TracesNetwork.requestDeathEcho(
            player,
            DeathCaptureRequestPacket(nonce, echoPosition.x, echoPosition.y, echoPosition.z, downed?.token),
        )
        log.info(
            "TRACES_DEATH_POOL player={} pool={} cause={} position={},{},{}",
            player.scoreboardName, pool.id, pool.cause, pool.x, pool.y, pool.z,
        )
    }

    fun acceptDeathEcho(player: ServerPlayer, packet: DeathEchoSubmitPacket): DeathEchoRecord {
        val pending = pendingDeathCaptures.remove(packet.nonce)
            ?: throw IllegalArgumentException("death echo request is absent or expired")
        require(pending.playerId == player.uuid) { "death echo request belongs to another player" }
        require(server.tickCount <= pending.expiresAtServerTick) { "death echo request expired" }
        val clip = DeathEchoValidation.decodeSubmission(packet.encodedClip)
        val level = server.allLevels.firstOrNull { it.dimension().location().toString() == pending.dimension }
            ?: throw IllegalStateException("death echo dimension is no longer loaded")
        val echo = DeathEchoRecord(
            id = UUID.randomUUID(),
            bloodPoolId = pending.bloodPoolId,
            ownerId = player.uuid,
            ownerName = player.scoreboardName.take(16),
            x = pending.echoPosition.x,
            y = pending.echoPosition.y,
            z = pending.echoPosition.z,
            createdAt = pending.createdAt,
            encodedClip = packet.encodedClip.copyOf(),
        )
        DeathTraceSavedData.get(level).addEcho(echo, config.maxDeathEchoes.get(), config.maxDeathEchoesPerPlayer.get())
        log.info(
            "TRACES_DEATH_ECHO_ACCEPTED player={} echo={} frames={} bytes={} dimension={}",
            player.scoreboardName, echo.id, clip.frames.size, echo.encodedClip.size, pending.dimension,
        )
        return echo
    }

    fun deathTraces(level: ServerLevel): DeathTraceSavedData = DeathTraceSavedData.get(level)

    fun prepareDeathTraceView(level: ServerLevel, player: ServerPlayer) {
        val latest = DeathTraceSavedData.get(level).poolsWithin(
            -30_000_001.0, 30_000_001.0, -30_000_001.0, 30_000_001.0,
        ).maxByOrNull { it.createdAt } ?: throw IllegalStateException("no death pool exists")
        player.teleportTo(level, latest.x, latest.y + 0.02, latest.z - 8.0, 0f, 25f)
        log.info("TRACES_DEATH_VIEW pool={} camera={},{},{} yaw=0 pitch=25", latest.id, latest.x, latest.y + 0.02, latest.z - 8.0)
    }

    private fun bloodPoolPosition(level: ServerLevel, death: Vec3): Vec3 {
        val x = kotlin.math.floor(death.x).toInt()
        val z = kotlin.math.floor(death.z).toInt()
        val startY = kotlin.math.floor(death.y).toInt().coerceIn(level.minBuildHeight, level.maxBuildHeight - 1)
        for (y in startY downTo maxOf(level.minBuildHeight, startY - 8)) {
            val support = BlockPos(x, y - 1, z)
            val state = level.getBlockState(support)
            val shape = state.getCollisionShape(level, support, CollisionContext.empty())
            if (!shape.isEmpty) {
                val top = shape.max(net.minecraft.core.Direction.Axis.Y)
                return Vec3(death.x, support.y + top + 0.012, death.z)
            }
        }
        return death.add(0.0, 0.012, 0.0)
    }

    fun seedVisualFixture(level: ServerLevel, player: ServerPlayer) {
        val store = storage(level)
        val fixtureAuthor = UUID.nameUUIDFromBytes("traces-visual-author-v11".toByteArray())
        val fixtureAnnotationId = UUID.nameUUIDFromBytes("traces-visual-disconnected-v10".toByteArray())
        buildVisualScene(level)
        player.teleportTo(level, 0.5, 101.0, -7.5, 0.0f, 25.0f)
        if (store.annotationById(fixtureAnnotationId) != null) {
            val fixtureAnnotations = store.queryAnnotations(BlockPos(-20, 0, -20), BlockPos(20, 255, 20)).size
            log.info(
                "TRACES_VISUAL_FIXTURE traces=persisted annotation={} nearbyAnnotations={} camera=0.5,101,-7.5 yaw=0 pitch=25",
                fixtureAnnotationId,
                fixtureAnnotations,
            )
            return
        }

        val sequence = UUID.nameUUIDFromBytes("traces-visual-route-v12".toByteArray())
        val points = buildList<Pair<Vec3, Float>> {
            var x = -1.5
            var z = -3.0
            add(Vec3(x, 101.0, z) to 180f)
            repeat(4) {
                z += 0.75
                add(Vec3(x, 101.0, z) to 180f)
            }
            val diagonalStep = 0.75 / kotlin.math.sqrt(2.0)
            repeat(8) {
                x += diagonalStep
                z += diagonalStep
                add(Vec3(x, 101.0, z) to 135f)
            }
            repeat(6) {
                z += 0.75
                add(Vec3(x, 101.0, z) to 0f)
            }
        }
        points.forEachIndexed { index, (pos, facingYaw) ->
            val surface = TraceSupportResolver.resolve(level, pos, LIFECYCLE_SUPPORT_DEPTH)
                ?: throw IllegalStateException("visual trace fixture has no supporting block at $pos")
            store.addFootTrace(
                FootTrace(
                    id = UUID.nameUUIDFromBytes("traces-visual-v12-$index".toByteArray()),
                    levelKey = level.dimension().location().toString(),
                    x = surface.position.x,
                    y = surface.position.y,
                    z = surface.position.z,
                    facingYaw = facingYaw,
                    movementClass = MovementClass.WALK,
                    strength = 1.0f,
                    sequenceId = sequence,
                    sequenceIndex = index,
                    createdAt = level.gameTime,
                    sequenceEpoch = level.gameTime,
                    surviving = true,
                    sourcePlayerInternal = player.uuid,
                    support = surface.support,
                ),
            )
        }
        val annotationPos = BlockPos(-6, 101, 8)
        store.addAnnotation(
            TraceAnnotation(
                id = fixtureAnnotationId,
                text = "Unlinked note",
                icon = "pin",
                color = 0xF2B84B,
                position = annotationPos,
                targetBlock = annotationPos.below(),
                team = GLOBAL_TEAM,
                revision = 1,
                createdByInternal = fixtureAuthor,
            ),
        )
        attachVisualAnnotationEcho(level, fixtureAnnotationId, revision = 1, owner = fixtureAuthor)
        val fixtureAnnotations = store.queryAnnotations(BlockPos(-20, 0, -20), BlockPos(20, 255, 20)).size
        log.info(
            "TRACES_VISUAL_FIXTURE traces={} backward={} diagonal={} forward={} spacing=0.75 annotation={} nearbyAnnotations={} camera=0.5,101,-7.5 yaw=0 pitch=25",
            points.size, 13, 8, 6, fixtureAnnotationId, fixtureAnnotations,
        )
    }

    fun disconnectVisualFixture(level: ServerLevel, player: ServerPlayer) {
        val store = storage(level)
        val fixtureAuthor = UUID.nameUUIDFromBytes("traces-visual-author-v11".toByteArray())
        val connected = UUID.nameUUIDFromBytes("traces-visual-connected-v10".toByteArray())
        val disconnected = UUID.nameUUIDFromBytes("traces-visual-disconnected-v10".toByteArray())
        store.removeAnnotation(connected)
        if (store.annotationById(disconnected) == null) {
            val pos = BlockPos(-6, 101, 8)
            store.addAnnotation(
                TraceAnnotation(
                    id = disconnected,
                    text = "Unlinked note",
                    icon = "pin",
                    color = 0xF2B84B,
                    position = pos,
                    targetBlock = pos.below(),
                    team = GLOBAL_TEAM,
                    revision = 1,
                    createdByInternal = fixtureAuthor,
                ),
            )
            attachVisualAnnotationEcho(level, disconnected, revision = 1, owner = fixtureAuthor)
        }
        log.info("TRACES_VISUAL_DISCONNECTED annotation={}", disconnected)
    }

    fun occludeVisualFixture(level: ServerLevel) {
        for (x in -1..1) {
            for (y in 101..103) {
                level.setBlockAndUpdate(BlockPos(x, y, 1), Blocks.STONE.defaultBlockState())
            }
        }
        log.info("TRACES_VISUAL_OCCLUDER x=-1..1 y=101..103 z=1")
    }

    fun connectVisualFixture(level: ServerLevel, player: ServerPlayer) {
        val store = storage(level)
        val fixtureAuthor = UUID.nameUUIDFromBytes("traces-visual-author-v11".toByteArray())
        val connected = UUID.nameUUIDFromBytes("traces-visual-connected-v10".toByteArray())
        val disconnected = UUID.nameUUIDFromBytes("traces-visual-disconnected-v10".toByteArray())
        store.removeAnnotation(disconnected)
        store.removeAnnotation(connected)
        val revision = level.gameTime.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
        val pos = BlockPos(3, 101, 9)
        store.addAnnotation(
            TraceAnnotation(
                id = connected,
                text = "Trail workshop",
                icon = "pin",
                color = 0xF2B84B,
                position = pos,
                targetBlock = pos.below(),
                team = GLOBAL_TEAM,
                revision = revision,
                createdByInternal = fixtureAuthor,
            ),
        )
        attachVisualAnnotationEcho(level, connected, revision, fixtureAuthor)
        log.info("TRACES_VISUAL_CONNECTED annotation={} revision={}", connected, revision)
    }

    private fun attachVisualAnnotationEcho(level: ServerLevel, id: UUID, revision: Int, owner: UUID) {
        val pivots = listOf(
            0f to 0f, 0f to 0f, 0f to 0f, 0f to 0f,
            5f to 2f, 5f to 2f, -5f to 2f, -5f to 2f,
            1.9f to 12f, 1.9f to 12f, -1.9f to 12f, -1.9f to 12f,
        )
        val frames = (0 until 60).map { index ->
            val phase = index.toFloat() / 59f
            val wave = kotlin.math.sin(phase * Math.PI * 4.0).toFloat()
            val channels = FloatArray(com.bettercontent.playertraces.echo.EchoClip.BONE_CHANNEL_COUNT)
            pivots.forEachIndexed { part, (x, y) ->
                val offset = part * com.bettercontent.playertraces.echo.EchoClip.CHANNELS_PER_BONE
                channels[offset] = x; channels[offset + 1] = y
                channels[offset + 6] = 1f; channels[offset + 7] = 1f; channels[offset + 8] = 1f
            }
            channels[6 * 9 + 3] = -1.1f + wave * 0.5f
            channels[4 * 9 + 3] = 0.3f - wave * 0.4f
            channels[8 * 9 + 3] = wave * 0.45f
            channels[10 * 9 + 3] = -wave * 0.45f
            com.bettercontent.playertraces.echo.EchoFrame(
                com.bettercontent.playertraces.echo.EchoRoot(phase * 0.8f, 0f, 0f, 0f, wave * 0.12f), channels,
            )
        }
        val encoded = com.bettercontent.playertraces.echo.EchoClipCodec.encodeQuantized(
            com.bettercontent.playertraces.echo.EchoClip(
                com.bettercontent.playertraces.echo.EchoEncoding.BONE,
                com.bettercontent.playertraces.echo.EchoClip.SAMPLE_RATE,
                intArrayOf(),
                frames,
            ),
        )
        annotationEchoes(level).replace(com.bettercontent.playertraces.domain.AnnotationEchoRecord(id, revision, owner, encoded))
    }

    private fun buildVisualScene(level: ServerLevel) {
        level.setWeatherParameters(12000, 0, false, false)
        level.setDayTime(6000L)
        for (x in -9..9) {
            for (z in -24..16) {
                for (y in 101..128) level.setBlockAndUpdate(BlockPos(x, y, z), Blocks.AIR.defaultBlockState())
                val surface = when {
                    x >= 2 -> Blocks.SMOOTH_STONE
                    z >= 9 -> Blocks.MOSS_BLOCK
                    else -> Blocks.GRASS_BLOCK
                }
                level.setBlockAndUpdate(BlockPos(x, 100, z), surface.defaultBlockState())
            }
        }
        for (z in 2..11) {
            for (y in 101..103) {
                level.setBlockAndUpdate(BlockPos(-9, y, z), Blocks.OAK_LEAVES.defaultBlockState())
                level.setBlockAndUpdate(BlockPos(9, y, z), Blocks.OAK_LEAVES.defaultBlockState())
            }
        }
        listOf(BlockPos(-8, 100, -4), BlockPos(8, 100, -4), BlockPos(-8, 100, 14), BlockPos(8, 100, 14)).forEach {
            level.setBlockAndUpdate(it, Blocks.SEA_LANTERN.defaultBlockState())
        }
    }

    fun close() {
        storages.values.forEach { it.close() }
    }

    private data class PendingDeathCapture(
        val playerId: UUID,
        val dimension: String,
        val bloodPoolId: UUID,
        val echoPosition: Vec3,
        val createdAt: Long,
        val captureToken: UUID?,
        val expiresAtServerTick: Int,
    )

    data class DownedDeathCapture(
        val token: UUID,
        val dimension: String,
        val position: Vec3,
        val createdAt: Long,
    )

    private data class PlayerLocation(
        val dimension: String,
        val position: Vec3,
        val yaw: Float,
    )

    companion object {
        private const val DEATH_CAPTURE_TIMEOUT_TICKS = 100
        private const val LIFECYCLE_SUPPORT_DEPTH = 8
    }
}
