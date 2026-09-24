package com.bettercontent.playertraces.storage

import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.storage.LevelResource
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/** Persistent, append-only arena duel traces used by integrations such as Arena Challenges. */
object ArenaDuelArchiveApi {
    private const val ROOT = "data/player_traces/arena_duels"
    private val locks = mutableMapOf<Path, Any>()

    @JvmStatic
    @Synchronized
    fun append(level: ServerLevel, arenaId: String, encodedTrace: ByteArray): Int {
        require(arenaId.isNotBlank()) { "arena id must not be blank" }
        require(encodedTrace.isNotEmpty()) { "arena trace must not be empty" }
        val directory = directory(level, arenaId)
        Files.createDirectories(directory)
        val lock = locks.getOrPut(directory) { Any() }
        synchronized(lock) {
            val index = Files.list(directory).use { paths -> paths.filter { it.fileName.toString().endsWith(".nbt") }.count().toInt() }
            val target = directory.resolve("%020d.nbt".format(index))
            val temporary = Files.createTempFile(directory, ".arena-duel-", ".pending")
            Files.write(temporary, encodedTrace, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE)
            } catch (unsupported: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary, target)
            }
            return index
        }
    }

    @JvmStatic
    fun count(level: ServerLevel, arenaId: String): Int {
        val directory = directory(level, arenaId)
        if (!Files.isDirectory(directory)) return 0
        return Files.list(directory).use { paths -> paths.filter { it.fileName.toString().endsWith(".nbt") }.count().toInt() }
    }

    @JvmStatic
    fun get(level: ServerLevel, arenaId: String, index: Int): ByteArray? {
        if (index < 0) return null
        val path = directory(level, arenaId).resolve("%020d.nbt".format(index))
        return if (Files.isRegularFile(path)) Files.readAllBytes(path) else null
    }

    private fun directory(level: ServerLevel, arenaId: String): Path {
        val digest = MessageDigest.getInstance("SHA-256").digest(arenaId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val dimension = level.dimension().location()
        return level.server.getWorldPath(LevelResource.ROOT).resolve(ROOT)
            .resolve(dimension.namespace).resolve(dimension.path).resolve(digest)
    }
}
