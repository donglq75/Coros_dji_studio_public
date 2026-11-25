package com.voiceguard

enum class ClipStatus { NO_VOICE, HAS_VOICE, SENSITIVE, SAFE, MASKED, ARCHIVED }

data class VoiceSegment(val startSec: Float, val endSec: Float) {
    val durationSec: Float get() = (endSec - startSec).coerceAtLeast(0f)
}

data class Clip(
    val uri: String,
    val name: String,
    val status: ClipStatus,
    val voiceSec: Float,
    val segments: List<VoiceSegment> = emptyList()
)
