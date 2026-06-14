package com.example.qrsc

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AppSettings(context: Context) {
    companion object {
        private const val PREFS_NAME = "qrsc_settings"
        private const val KEY_FRAME_THRESHOLD = "key_frame_threshold"
        private const val KEY_CAPTURE_FPS = "capture_fps"
        private const val KEY_AUTO_PROCESS = "auto_process"
        private const val KEY_AUTO_THRESHOLD = "auto_threshold"
        const val DEFAULT_FRAME_THRESHOLD = 0.08f
        const val DEFAULT_CAPTURE_FPS = 30
        const val DEFAULT_AUTO_PROCESS = true
        const val DEFAULT_AUTO_THRESHOLD = false
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _keyFrameThreshold = MutableStateFlow(
        prefs.getFloat(KEY_FRAME_THRESHOLD, DEFAULT_FRAME_THRESHOLD)
    )
    val keyFrameThreshold: StateFlow<Float> = _keyFrameThreshold.asStateFlow()

    private val _captureFps = MutableStateFlow(
        prefs.getInt(KEY_CAPTURE_FPS, DEFAULT_CAPTURE_FPS)
    )
    val captureFps: StateFlow<Int> = _captureFps.asStateFlow()

    private val _autoProcess = MutableStateFlow(
        prefs.getBoolean(KEY_AUTO_PROCESS, DEFAULT_AUTO_PROCESS)
    )
    val autoProcess: StateFlow<Boolean> = _autoProcess.asStateFlow()

    private val _isAutoThreshold = MutableStateFlow(
        prefs.getBoolean(KEY_AUTO_THRESHOLD, DEFAULT_AUTO_THRESHOLD)
    )
    val isAutoThreshold: StateFlow<Boolean> = _isAutoThreshold.asStateFlow()

    fun setKeyFrameThreshold(value: Float) {
        val normalized = value.coerceIn(0f, 1f)
        prefs.edit().putFloat(KEY_FRAME_THRESHOLD, normalized).apply()
        _keyFrameThreshold.value = normalized
    }

    fun setCaptureFps(value: Int) {
        val normalized = value.coerceIn(1, 30)
        prefs.edit().putInt(KEY_CAPTURE_FPS, normalized).apply()
        _captureFps.value = normalized
    }

    fun setAutoProcess(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_PROCESS, enabled).apply()
        _autoProcess.value = enabled
    }

    fun setAutoThreshold(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_THRESHOLD, enabled).apply()
        _isAutoThreshold.value = enabled
    }
}
