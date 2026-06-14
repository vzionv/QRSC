package com.example.qrsc

import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 命令：Activity 发给 Service 的控制指令。
 */
sealed class ScannerCommand {
    data object Start : ScannerCommand()
    data object Stop : ScannerCommand()
    data object ToggleCamera : ScannerCommand()
    data class SetInterval(val ms: Long) : ScannerCommand()
}

/**
 * 全局单例，在 Activity（ViewModel）和 ForegroundService 之间共享状态。
 */
object ScannerState {

    private const val TAG = "QRSC-State"
    private const val MIN_SCAN_INTERVAL_MS = 100L
    private const val MAX_SCAN_INTERVAL_MS = 5000L

    // ======= UI 展示状态 =======

    private val _currentText = MutableStateFlow("")
    val currentText: StateFlow<String> = _currentText.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _isFrontCamera = MutableStateFlow(false)
    val isFrontCamera: StateFlow<Boolean> = _isFrontCamera.asStateFlow()

    private val _scanIntervalMs = MutableStateFlow(500L)
    val scanIntervalMs: StateFlow<Long> = _scanIntervalMs.asStateFlow()

    // ======= 预览相关状态 =======

    private val bitmapLock = Any()
    private val _previewBitmap = MutableStateFlow<Bitmap?>(null)
    val previewBitmap: StateFlow<Bitmap?> = _previewBitmap.asStateFlow()

    private val _isPreviewMode = MutableStateFlow(false)
    val isPreviewMode: StateFlow<Boolean> = _isPreviewMode.asStateFlow()

    private val _lockedBitmap = MutableStateFlow<Bitmap?>(null)
    val lockedBitmap: StateFlow<Bitmap?> = _lockedBitmap.asStateFlow()

    private val _countdownSetting = MutableStateFlow(3)
    val countdownSetting: StateFlow<Int> = _countdownSetting.asStateFlow()

    private val _countdownCurrent = MutableStateFlow(0)
    val countdownCurrent: StateFlow<Int> = _countdownCurrent.asStateFlow()

    private val _isCountingDown = MutableStateFlow(false)
    val isCountingDown: StateFlow<Boolean> = _isCountingDown.asStateFlow()

    // ======= 文件传输提示 =======

    private val _downloadHint = MutableStateFlow("")
    val downloadHint: StateFlow<String> = _downloadHint.asStateFlow()
    var lastFileChunkTimeMs: Long = 0L

    // ======= Service 内部使用的去重状态 =======

    var lastCopiedText: String = ""

    // ======= 处理管线状态 =======

    private val _processingState = MutableStateFlow<CaptureProcessingState>(CaptureProcessingState.Idle)
    val processingState: StateFlow<CaptureProcessingState> = _processingState.asStateFlow()

    private val _isBlackScreen = MutableStateFlow(false)
    val isBlackScreen: StateFlow<Boolean> = _isBlackScreen.asStateFlow()

    private val _isCacheCapturing = MutableStateFlow(false)
    val isCacheCapturing: StateFlow<Boolean> = _isCacheCapturing.asStateFlow()

    private val _processingCancelled = MutableStateFlow(false)
    val processingCancelled: StateFlow<Boolean> = _processingCancelled.asStateFlow()

    fun updateProcessingState(state: CaptureProcessingState) {
        Log.d(TAG, "updateProcessingState: $state")
        _processingState.value = state
    }

    fun setIsBlackScreen(enabled: Boolean) {
        Log.d(TAG, "setIsBlackScreen: $enabled")
        _isBlackScreen.value = enabled
    }

    fun setCacheCapturing(enabled: Boolean) {
        Log.d(TAG, "setCacheCapturing: $enabled")
        _isCacheCapturing.value = enabled
    }

    fun requestProcessingCancellation() {
        Log.d(TAG, "requestProcessingCancellation")
        _processingCancelled.value = true
    }

    fun resetProcessingCancellation() {
        Log.d(TAG, "resetProcessingCancellation")
        _processingCancelled.value = false
    }

    // ======= 自适应扫描间隔 =======

    private var lastDetectionTimeMs: Long = 0L

    fun recordDetection() {
        lastDetectionTimeMs = System.currentTimeMillis()
    }

    fun getAdaptiveScanIntervalMs(): Long {
        val base = scanIntervalMs.value
        if (lastDetectionTimeMs == 0L) return base

        val sinceLastDetection = System.currentTimeMillis() - lastDetectionTimeMs
        if (sinceLastDetection <= 0L) return base

        val multiplier = when {
            sinceLastDetection < 30_000L  -> 1.0
            sinceLastDetection < 60_000L  -> 1.5
            sinceLastDetection < 120_000L -> 2.0
            sinceLastDetection < 300_000L -> 3.0
            else                         -> 5.0
        }
        return (base * multiplier).toLong().coerceIn(MIN_SCAN_INTERVAL_MS, MAX_SCAN_INTERVAL_MS)
    }

    // ======= 命令通道：Activity → Service =======

    private val _commands = MutableSharedFlow<ScannerCommand>(extraBufferCapacity = 2)
    val commands: SharedFlow<ScannerCommand> = _commands.asSharedFlow()

    // ======= Activity 调用 =======

    fun sendCommand(command: ScannerCommand) {
        Log.d(TAG, "sendCommand: $command")
        when (command) {
            is ScannerCommand.Start -> _isScanning.value = true
            is ScannerCommand.Stop -> applyStoppedState()
            is ScannerCommand.ToggleCamera -> _isFrontCamera.value = !_isFrontCamera.value
            is ScannerCommand.SetInterval -> _scanIntervalMs.value = command.ms.coerceIn(MIN_SCAN_INTERVAL_MS, MAX_SCAN_INTERVAL_MS)
        }
        _commands.tryEmit(command)
    }

    private fun applyStoppedState() {
        _isScanning.value = false
        _processingState.value = CaptureProcessingState.Idle
        _isPreviewMode.value = false
        _isCountingDown.value = false
        _countdownCurrent.value = 0
        _downloadHint.value = ""
        clearPreviewBitmaps()
    }

    // ======= Service 调用 =======

    fun updateCurrentText(text: String) {
        Log.d(TAG, "updateCurrentText: len=${text.length}")
        _currentText.value = text
    }

    fun updateDownloadHint(text: String) {
        Log.d(TAG, "updateDownloadHint: $text")
        _downloadHint.value = text
        if (text.isNotEmpty()) lastFileChunkTimeMs = System.currentTimeMillis()
    }

    // ======= 预览操作方法 =======

    fun togglePreviewMode() {
        _isPreviewMode.value = !_isPreviewMode.value
        if (!_isPreviewMode.value) {
            _isCountingDown.value = false
            _countdownCurrent.value = 0
            clearPreviewBitmaps()
        }
    }

    fun setCountdownSetting(seconds: Int) {
        if (!_isCountingDown.value) {
            _countdownSetting.value = seconds.coerceIn(1, 10)
        }
    }

    fun startCountdown() {
        _isCountingDown.value = true
        _countdownCurrent.value = _countdownSetting.value
    }

    fun onCountdownTick(remaining: Int) {
        _countdownCurrent.value = remaining
    }

    fun lockPreview() {
        synchronized(bitmapLock) {
            _lockedBitmap.value = _previewBitmap.value
            _isCountingDown.value = false
            _countdownCurrent.value = 0
        }
    }

    fun unlockPreview() {
        synchronized(bitmapLock) {
            _lockedBitmap.value = null
        }
    }

    fun cancelCountdown() {
        _isCountingDown.value = false
        _countdownCurrent.value = 0
    }

    fun updatePreviewBitmap(bitmap: Bitmap?) {
        synchronized(bitmapLock) {
            if (bitmap == null) {
                clearPreviewBitmapsLocked()
                return
            }
            if (_previewBitmap.value === bitmap) return
            _previewBitmap.value = bitmap
        }
    }

    private fun clearPreviewBitmaps() {
        synchronized(bitmapLock) { clearPreviewBitmapsLocked() }
    }

    private fun clearPreviewBitmapsLocked() {
        _previewBitmap.value = null
        _lockedBitmap.value = null
    }
}
