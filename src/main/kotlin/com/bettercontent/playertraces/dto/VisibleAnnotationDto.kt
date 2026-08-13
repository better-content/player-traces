package com.bettercontent.playertraces.dto

data class VisibleAnnotationDto(
    val id: String,
    val text: String,
    val icon: String,
    val color: Int,
    val x: Int,
    val y: Int,
    val z: Int,
    val team: String,
    val revision: Int,
    val seen: Boolean,
    val canEdit: Boolean = false,
    val hasEcho: Boolean = false,
    val echoRevision: Int = 0,
)
