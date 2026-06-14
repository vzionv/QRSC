package com.example.qrsc

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.camera.core.CameraSelector
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.example.qrsc.camera.CameraManager
import com.example.qrsc.camera.QRCodeAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

class ScannerForegroundService : LifecycleService() {

    companion object {
        private const val CHANNEL_ID = "qr_scanner_channel"
        private const val NOTIFICATION_ID = 1001
        private const val MAX_NOTIFY_TEXT_LEN = 40
        private const val MAX_CAPTURE_MS = 60 * 60 * 1000L
        const val ACTION_TOGGLE_CAMERA = "com.example.qrsc.action.TOGGLE_CAMERA"
        const val ACTION_START_CAPTURE = "com.example.qrsc.action.START_CAPTURE"
        const val ACTION_STOP_CAPTURE = "com.example.qrsc.action.STOP_CAPTURE"
        const val EXTRA_STOP_SCANNER_AFTER_CAPTURE = "com.example.qrsc.extra.STOP_SCANNER_AFTER_CAPTURE"
    }

    private val cameraManager = CameraManager()
    private lateinit var notificationManager: NotificationManager
    private var captureFile: File? = null
    private var autoStopJob: Job? = null
    private var processingJob: Job? = null
    private var pendingProcessAfterFinalize = false
    private var pendingScanStop = false

    override fun onCreate() {
        super.onCreate()
        DebugLog.d(this, "QRSC-Service", "onCreate")
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannel()
        FilePacketState.init(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        DebugLog.d(this, "QRSC-Service", "onStartCommand action=${intent?.action} startId=$startId")
        startForeground(NOTIFICATION_ID, buildNotification("正在采集二维码…"))
        when (intent?.action) {
            ACTION_TOGGLE_CAMERA -> restartCamera()
            ACTION_START_CAPTURE -> startCachedCapture()
            ACTION_STOP_CAPTURE -> {
                if (intent.getBooleanExtra(EXTRA_STOP_SCANNER_AFTER_CAPTURE, false)) pendingScanStop = true
                stopCachedCapture(processAfterStop = true)
            }
            else -> startCamera()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        DebugLog.d(this, "QRSC-Service", "onDestroy")
        autoStopJob?.cancel()
        processingJob?.cancel()
        val unfinishedCapture = captureFile
        captureFile = null
        pendingProcessAfterFinalize = false
        pendingScanStop = false
        ScannerState.setCacheCapturing(false)
        cameraManager.stop()
        unfinishedCapture?.takeIf { it.exists() && it.length() == 0L }?.delete()
        super.onDestroy()
    }

    private fun startCamera() {
        val analyzer = QRCodeAnalyzer(
            scanIntervalMs = { ScannerState.getAdaptiveScanIntervalMs() },
            onQRCodeFound = { rawValue, rawBytes ->
                ScannerState.recordDetection()
                val result = QrPayloadHandler.handle(this, rawValue, rawBytes, copyText = true) ?: return@QRCodeAnalyzer
                updateNotification(result.notificationText)
            },
            shouldCaptureFrame = {
                ScannerState.isPreviewMode.value && ScannerState.lockedBitmap.value == null
            },
            onFrame = { bitmap -> ScannerState.updatePreviewBitmap(bitmap) },
            shouldScan = {
                !ScannerState.isCacheCapturing.value && ScannerState.processingState.value is CaptureProcessingState.Idle
            }
        )
        val lensFacing = if (ScannerState.isFrontCamera.value) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        cameraManager.start(this, this, lensFacing, analyzer)
    }

    private fun restartCamera() {
        if (ScannerState.isCacheCapturing.value) return
        cameraManager.stop()
        startCamera()
    }

    private fun startCachedCapture() {
        if (ScannerState.processingState.value !is CaptureProcessingState.Idle) return
        if (captureFile != null || cameraManager.isRecordingActive()) return
        val fps = AppSettings(this).captureFps.value
        val file = CaptureCacheRepository.createOutputFile(this, fps)
        DebugLog.d(this, "QRSC-Service", "start cache capture: ${file.name}, fps=$fps")
        captureFile = file
        val started = cameraManager.startCapture(this, file) { success ->
            val finalizedFile = captureFile
            val shouldProcess = pendingProcessAfterFinalize
            val shouldStopScanner = pendingScanStop
            captureFile = null
            autoStopJob?.cancel()
            autoStopJob = null
            ScannerState.setCacheCapturing(false)
            if (shouldStopScanner) ScannerState.sendCommand(ScannerCommand.Stop)
            pendingProcessAfterFinalize = false
            pendingScanStop = false

            if (success && shouldProcess && finalizedFile != null) {
                processFile(finalizedFile, stopWhenDone = shouldStopScanner)
            } else if (success && finalizedFile != null) {
                ScannerState.updateProcessingState(CaptureProcessingState.Idle)
                if (shouldStopScanner || !ScannerState.isScanning.value) stopSelf()
            } else {
                finalizedFile?.delete()
                ScannerState.updateProcessingState(CaptureProcessingState.Failed("缓存采集未完成"))
                lifecycleScope.launch {
                    delay(1500L)
                    ScannerState.updateProcessingState(CaptureProcessingState.Idle)
                    if (shouldStopScanner || !ScannerState.isScanning.value) stopSelf()
                }
            }
        }
        if (!started) {
            captureFile = null
            file.delete()
            DebugLog.d(this, "QRSC-Service", "cache capture failed to start")
            ScannerState.updateProcessingState(CaptureProcessingState.Failed("请先开始采集"))
            lifecycleScope.launch {
                delay(1500L)
                ScannerState.updateProcessingState(CaptureProcessingState.Idle)
            }
            return
        }
        ScannerState.setCacheCapturing(true)
        updateNotification("缓存采集中")
        autoStopJob?.cancel()
        autoStopJob = lifecycleScope.launch {
            delay(MAX_CAPTURE_MS)
            stopCachedCapture(processAfterStop = true)
        }
    }

    private fun stopCachedCapture(processAfterStop: Boolean) {
        val file = captureFile
        if (file == null) {
            if (pendingScanStop) {
                ScannerState.sendCommand(ScannerCommand.Stop)
                pendingScanStop = false
                stopSelf()
            }
            return
        }
        val shouldProcess = processAfterStop && AppSettings(this).autoProcess.value
        DebugLog.d(this, "QRSC-Service", "stop cache capture, process=$shouldProcess")
        pendingProcessAfterFinalize = shouldProcess
        autoStopJob?.cancel()
        autoStopJob = null
        ScannerState.setCacheCapturing(false)
        ScannerState.updateProcessingState(CaptureProcessingState.Capturing(file.nameWithoutExtension, System.currentTimeMillis()))
        lifecycleScope.launch(Dispatchers.IO) {
            cameraManager.stopCapture()
        }
    }

    private fun processFile(file: File, stopWhenDone: Boolean) {
        val item = CaptureCacheRepository.toItem(file) ?: run {
            DebugLog.d(this, "QRSC-Service", "skip processing unreadable cache: ${file.name}")
            ScannerState.updateProcessingState(CaptureProcessingState.Idle)
            if (stopWhenDone || !ScannerState.isScanning.value) stopSelf()
            return
        }
        DebugLog.d(this, "QRSC-Service", "process cache: ${item.name}, duration=${item.durationMs}ms, size=${item.sizeBytes}")
        processingJob?.cancel()
        processingJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                ScannerState.resetProcessingCancellation()
                val settings = AppSettings(this@ScannerForegroundService)
                val processor = CachedCaptureProcessor(this@ScannerForegroundService)
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
                ScannerState.resetProcessingCancellation()
                ScannerState.updateProcessingState(CaptureProcessingState.Idle)
                if (stopWhenDone || !ScannerState.isScanning.value) stopSelf()
            }
        }
    }

    private fun updateNotification(text: String) {
        val display = if (text.length > MAX_NOTIFY_TEXT_LEN) text.take(MAX_NOTIFY_TEXT_LEN) + "…" else text
        notificationManager.notify(NOTIFICATION_ID, buildNotification(display))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "QR文件传输", NotificationManager.IMPORTANCE_LOW).apply {
                description = "采集服务运行中"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("QR文件传输")
        .setContentText(text)
        .setSmallIcon(android.R.drawable.ic_menu_camera)
        .setOngoing(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        .build()
}
