package com.example.qrsc

sealed interface CaptureProcessingState {
    data object Idle : CaptureProcessingState
    data class Capturing(val sourceName: String, val startedAtMs: Long) : CaptureProcessingState
    data class Processing(
        val sourceName: String,
        val phase: String,
        val progress: Float,
        val processedFrames: Int,
        val keyFrames: Int,
        val identifiedCount: Int,
        val addedCount: Int,
        val etaMs: Long?
    ) : CaptureProcessingState
    data class Failed(val message: String) : CaptureProcessingState
}
