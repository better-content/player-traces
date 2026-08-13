package com.bettercontent.traces.api

import com.bettercontent.traces.domain.AnnotationViewResult
import com.bettercontent.traces.domain.TraceAnnotation
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import java.util.UUID

interface AnnotationApi {
    fun annotationsWithin(level: Level, boundsMin: BlockPos, boundsMax: BlockPos, viewer: Player?): AnnotationViewResult
    fun create(level: Level, viewer: Player, text: String, icon: String, color: Int, target: BlockPos): TraceAnnotation
    fun update(level: Level, viewer: Player, id: UUID, expectedRevision: Int, text: String?, icon: String?, color: Int?): TraceAnnotation
    fun delete(level: Level, viewer: Player, id: UUID, expectedRevision: Int): Boolean
}
