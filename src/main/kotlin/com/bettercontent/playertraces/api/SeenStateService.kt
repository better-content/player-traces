package com.bettercontent.playertraces.api

import java.util.UUID

interface SeenStateService {
    fun seenRevision(player: UUID, annotationId: UUID): Int
    fun setSeen(player: UUID, annotationId: UUID, revision: Int)
}
