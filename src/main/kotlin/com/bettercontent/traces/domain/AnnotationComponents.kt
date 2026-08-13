package com.bettercontent.traces.domain

object AnnotationComponents {
    const val MAX_TEXT_LENGTH = 256

    val icons: Set<String> = linkedSetOf("pin", "warning", "direction", "help", "treasure", "memorial")
    val colors: Map<String, Int> = linkedMapOf(
        "cyan" to 0x35E7FF,
        "orange" to 0xFF9F45,
        "red" to 0xF05252,
        "yellow" to 0xF6D04D,
        "green" to 0x55D66B,
        "blue" to 0x579DFF,
        "purple" to 0xB77BFF,
        "white" to 0xFFFFFF,
    )

    fun validate(text: String, icon: String, color: Int, hasEcho: Boolean) {
        require(text.length <= MAX_TEXT_LENGTH) { "annotation text exceeds 256 characters" }
        require(icon.isEmpty() || icon in icons) { "annotation icon is not supported" }
        require(icon.isEmpty() || color in colors.values) { "annotation color is not supported" }
        require(icon.isNotEmpty() || color == 0) { "annotation color requires an icon" }
        require(text.isNotBlank() || icon.isNotEmpty() || hasEcho) { "annotation must contain text, an icon, or a gesture" }
    }
}

enum class EchoMutation { KEEP, REPLACE, REMOVE }

data class AnnotationEchoRecord(
    val annotationId: java.util.UUID,
    val annotationRevision: Int,
    val ownerId: java.util.UUID,
    val encodedClip: ByteArray,
) {
    init {
        require(annotationRevision >= 1) { "annotation echo revision is invalid" }
        require(encodedClip.size in 1..MAX_ENCODED_BYTES) { "annotation echo exceeds 12 KiB" }
    }

    companion object {
        const val MAX_ENCODED_BYTES = 12 * 1024
        const val MAX_PER_DIMENSION = 2_048
        const val MAX_PER_PLAYER = 64
    }
}
