package com.bettercontent.playertraces.domain

import java.util.UUID

data class AnnotationViewResult(
    val annotations: List<TraceAnnotation>,
    val unseenCount: Int,
    val viewer: UUID?,
)
