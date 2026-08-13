package com.bettercontent.playertraces.dto

data class VisibleBloodPoolDto(
    val id: String,
    val ownerName: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val createdAt: Long,
)

data class VisibleDeathEchoDto(
    val id: String,
    val ownerName: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val createdAt: Long,
    val encodedClip: ByteArray,
)
