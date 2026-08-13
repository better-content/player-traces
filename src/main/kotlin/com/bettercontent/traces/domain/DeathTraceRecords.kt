package com.bettercontent.traces.domain

import java.util.UUID

data class BloodPoolRecord(
    val id: UUID,
    val ownerId: UUID,
    val ownerName: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val createdAt: Long,
    val cause: String,
) {
    init {
        require(ownerName.length in 1..16) { "blood pool owner name is invalid" }
        require(cause.length <= 64) { "blood pool cause is too long" }
        require(validWorldPoint(x, y, z)) { "blood pool position is invalid" }
        require(createdAt >= 0) { "blood pool timestamp is invalid" }
    }
}

data class DeathEchoRecord(
    val id: UUID,
    val bloodPoolId: UUID,
    val ownerId: UUID,
    val ownerName: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val createdAt: Long,
    val encodedClip: ByteArray,
) {
    init {
        require(ownerName.length in 1..16) { "death echo owner name is invalid" }
        require(validWorldPoint(x, y, z)) { "death echo position is invalid" }
        require(createdAt >= 0) { "death echo timestamp is invalid" }
        require(encodedClip.size in 1..MAX_ENCODED_ECHO_BYTES) { "death echo payload is invalid" }
    }

    companion object {
        const val MAX_ENCODED_ECHO_BYTES = 64 * 1024
    }
}

internal fun validWorldPoint(x: Double, y: Double, z: Double): Boolean =
    x.isFinite() && y.isFinite() && z.isFinite() &&
        kotlin.math.abs(x) <= 30_000_001.0 && kotlin.math.abs(z) <= 30_000_001.0 && y in -2048.0..2048.0
