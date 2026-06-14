package com.example.qrsc

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 轻量 ViewModel：状态委托给 ScannerState 单例。
 * Activity / Service 通过 ScannerState 共享状态。
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private var countdownJob: Job? = null

    val currentText: StateFlow<String> = ScannerState.currentText
    val isScanning: StateFlow<Boolean> = ScannerState.isScanning
    val isFrontCamera: StateFlow<Boolean> = ScannerState.isFrontCamera
    val scanIntervalMs: StateFlow<Long> = ScannerState.scanIntervalMs
    val previewBitmap: StateFlow<Bitmap?> = ScannerState.previewBitmap
    val isPreviewMode: StateFlow<Boolean> = ScannerState.isPreviewMode
    val lockedBitmap: StateFlow<Bitmap?> = ScannerState.lockedBitmap
    val countdownSetting: StateFlow<Int> = ScannerState.countdownSetting
    val countdownCurrent: StateFlow<Int> = ScannerState.countdownCurrent
    val isCountingDown: StateFlow<Boolean> = ScannerState.isCountingDown

    fun startScanning() {
        ScannerState.sendCommand(ScannerCommand.Start)
    }

    fun stopScanning() {
        countdownJob?.cancel()
        ScannerState.sendCommand(ScannerCommand.Stop)
    }

    fun toggleCamera() {
        ScannerState.sendCommand(ScannerCommand.ToggleCamera)
    }

    fun setScanInterval(intervalMs: Float) {
        ScannerState.sendCommand(ScannerCommand.SetInterval(intervalMs.toLong()))
    }

    fun togglePreviewMode() {
        countdownJob?.cancel()
        ScannerState.togglePreviewMode()
    }

    fun setCountdownSetting(seconds: Int) = ScannerState.setCountdownSetting(seconds)

    fun startCountdown() {
        countdownJob?.cancel()
        ScannerState.startCountdown()
        val total = ScannerState.countdownSetting.value
        countdownJob = viewModelScope.launch {
            for (i in total downTo 0) {
                if (!ScannerState.isCountingDown.value) return@launch
                ScannerState.onCountdownTick(i)
                if (i > 0) delay(1000L)
            }
            if (ScannerState.isCountingDown.value) {
                ScannerState.lockPreview()
            }
        }
    }

    fun unlockPreview() = ScannerState.unlockPreview()

    fun cancelCountdown() {
        countdownJob?.cancel()
        ScannerState.cancelCountdown()
    }
}
