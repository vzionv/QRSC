package com.example.qrsc

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application
    private val settings = AppSettings(application)
    private var countdownJob: Job? = null
    private var cacheProcessJob: Job? = null
    private var cacheRefreshJob: Job? = null

    private val _captureCacheItems = MutableStateFlow<List<CaptureCacheRepository.CaptureCacheItem>>(emptyList())
    val captureCacheItems: StateFlow<List<CaptureCacheRepository.CaptureCacheItem>> = _captureCacheItems.asStateFlow()

    init {
        DebugLog.d(app, "QRSC-MainViewModel", "init start")
        FilePacketState.init(application)
        refreshCaptureCacheItems()
        DebugLog.d(app, "QRSC-MainViewModel", "init complete")
    }

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
    val downloadHint: StateFlow<String> = ScannerState.downloadHint
    val processingState: StateFlow<CaptureProcessingState> = ScannerState.processingState
    val keyFrameThreshold: StateFlow<Float> = settings.keyFrameThreshold
    val captureFps: StateFlow<Int> = settings.captureFps
    val autoProcess: StateFlow<Boolean> = settings.autoProcess
    val isAutoThreshold: StateFlow<Boolean> = settings.isAutoThreshold

    fun startScanning() {
        ScannerState.sendCommand(ScannerCommand.Start)
    }

    fun stopScanning() {
        countdownJob?.cancel()
        if (ScannerState.isCacheCapturing.value) {
            // Cache capture active – stop it first; scanning stops after finalize.
            stopCachedCapture(stopScannerAfterFinalize = true)
        } else {
            ScannerState.sendCommand(ScannerCommand.Stop)
        }
    }

    fun toggleCamera() {
        if (!ScannerState.isCacheCapturing.value) {
            ScannerState.sendCommand(ScannerCommand.ToggleCamera)
        }
    }

    fun setScanInterval(intervalMs: Float) {
        ScannerState.sendCommand(ScannerCommand.SetInterval(intervalMs.toLong()))
    }

    fun setKeyFrameThreshold(value: Float) = settings.setKeyFrameThreshold(value)

    fun setCaptureFps(value: Int) = settings.setCaptureFps(value)
    fun setAutoProcess(enabled: Boolean) = settings.setAutoProcess(enabled)
    fun setAutoThreshold(enabled: Boolean) = settings.setAutoThreshold(enabled)

    fun startCachedCapture() {
        val intent = Intent(app, ScannerForegroundService::class.java).apply {
            action = ScannerForegroundService.ACTION_START_CAPTURE
        }
        app.startService(intent)
    }

    fun stopCachedCapture(stopScannerAfterFinalize: Boolean = false) {
        val intent = Intent(app, ScannerForegroundService::class.java).apply {
            action = ScannerForegroundService.ACTION_STOP_CAPTURE
            putExtra(ScannerForegroundService.EXTRA_STOP_SCANNER_AFTER_CAPTURE, stopScannerAfterFinalize)
        }
        app.startService(intent)
        viewModelScope.launch {
            delay(700L)
            refreshCaptureCacheItems()
        }
    }

    fun processCacheItem(item: CaptureCacheRepository.CaptureCacheItem) {
        if (ScannerState.processingState.value !is CaptureProcessingState.Idle) return
        cacheProcessJob?.cancel()
        cacheProcessJob = viewModelScope.launch(Dispatchers.IO) {
            ScannerState.resetProcessingCancellation()
            try {
                val processor = CachedCaptureProcessor(app)
                val effectiveFps = item.fps.takeIf { it > 0 } ?: settings.captureFps.value
                val config = CachedCaptureProcessor.ProcessConfig(effectiveFps, settings.keyFrameThreshold.value, settings.isAutoThreshold.value)
                processor.process(item, config, onProgress = { progress ->
                    ScannerState.updateProcessingState(
                        CaptureProcessingState.Processing(
                            sourceName = item.name,
                            phase = progress.phase,
                            progress = progress.progress,
                            processedFrames = progress.processedFrames,
                            keyFrames = progress.keyFrames,
                            identifiedCount = progress.identifiedCount,
                            addedCount = progress.addedCount,
                            etaMs = progress.etaMs
                        )
                    )
                }, isCancelled = { ScannerState.processingCancelled.value })
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                ScannerState.updateProcessingState(CaptureProcessingState.Failed(e.message ?: "处理失败"))
                delay(1500L)
            } finally {
                ScannerState.updateProcessingState(CaptureProcessingState.Idle)
                ScannerState.resetProcessingCancellation()
                refreshCaptureCacheItems()
            }
        }
    }

    fun refreshCaptureCacheItems() {
        cacheRefreshJob?.cancel()
        cacheRefreshJob = viewModelScope.launch(Dispatchers.IO) {
            val items = CaptureCacheRepository.list(app)
            _captureCacheItems.value = items
            DebugLog.d(app, "QRSC-MainViewModel", "cache items refreshed: ${items.size}")
        }
    }

    fun deleteAllCaptureCache() {
        viewModelScope.launch(Dispatchers.IO) {
            val items = CaptureCacheRepository.list(app)
            items.forEach { CaptureCacheRepository.delete(it) }
            refreshCaptureCacheItems()
        }
    }

    fun deleteCaptureCacheItem(item: CaptureCacheRepository.CaptureCacheItem) {
        viewModelScope.launch(Dispatchers.IO) {
            CaptureCacheRepository.delete(item)
            refreshCaptureCacheItems()
        }
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
            if (ScannerState.isCountingDown.value) ScannerState.lockPreview()
        }
    }

    fun unlockPreview() = ScannerState.unlockPreview()

    fun cancelCountdown() {
        countdownJob?.cancel()
        ScannerState.cancelCountdown()
    }

    fun cancelProcessing() {
        ScannerState.requestProcessingCancellation()
    }

    fun clearDownloadHint() {
        ScannerState.updateDownloadHint("")
    }

    override fun onCleared() {
        countdownJob?.cancel()
        cacheProcessJob?.cancel()
        cacheRefreshJob?.cancel()
        super.onCleared()
    }
}
