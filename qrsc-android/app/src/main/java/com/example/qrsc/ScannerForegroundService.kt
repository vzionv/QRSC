package com.example.qrsc

import android.util.Log
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.camera.core.CameraSelector
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.example.qrsc.camera.CameraManager
import com.example.qrsc.camera.QRCodeAnalyzer
import java.io.File

class ScannerForegroundService : LifecycleService() {

    companion object {
        private const val TAG = "ScannerService"
        private const val CHANNEL_ID = "qr_scanner_channel"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_TOGGLE_CAMERA = "com.example.qrsc.action.TOGGLE_CAMERA"
        private const val MAX_NOTIFY_TEXT_LEN = 40
    }

    private val cameraManager = CameraManager()
    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val result = super.onStartCommand(intent, flags, startId)

        when {
            intent?.action == ACTION_TOGGLE_CAMERA -> {
                restartCamera()
            }
            else -> {
                startForeground(NOTIFICATION_ID, buildNotification("正在扫描二维码…"))
                startCamera()
            }
        }
        return result
    }

    override fun onDestroy() {
        cameraManager.stop()
        super.onDestroy()
    }

    private fun startCamera() {
        val analyzer = QRCodeAnalyzer(
            scanIntervalMs = { ScannerState.getAdaptiveScanIntervalMs() },
            onQRCodeFound = { rawValue, rawBytes ->
                ScannerState.recordDetection()

                val chunkData = when {
                    rawBytes != null -> rawBytes
                    rawValue.isNotEmpty() -> rawValue.toByteArray(Charsets.ISO_8859_1)
                    else -> return@QRCodeAnalyzer
                }

                val header = FileUtils.parseChunkHeader(chunkData)
                if (header != null) {
                    handleFileChunk(header, chunkData)
                } else {
                    if (rawValue.isNotEmpty() && rawValue != ScannerState.lastCopiedText) {
                        ScannerState.lastCopiedText = rawValue
                        ScannerState.updateCurrentText(rawValue)
                        copyToClipboard(rawValue)
                        updateNotification(rawValue)
                    }
                }
            },
            shouldCaptureFrame = { ScannerState.isPreviewMode.value },
            onFrame = { bitmap -> ScannerState.updatePreviewBitmap(bitmap) }
        )
        val lensFacing = if (ScannerState.isFrontCamera.value)
            CameraSelector.LENS_FACING_FRONT
        else
            CameraSelector.LENS_FACING_BACK

        cameraManager.start(this, this, lensFacing, analyzer)
    }

    private fun handleFileChunk(header: FileUtils.HeaderInfo, rawBytes: ByteArray) {
        val text = "Receiving: ${header.filename} (${header.index + 1}/${header.total})"
        Log.d(TAG, text)

        ScannerState.updateDownloadHint("File: ${header.filename}  chunk ${header.index + 1}/${header.total}")
        ScannerState.updateCurrentText("Receiving file: ${header.filename}")
        updateNotification(text)

        // Build transmissionId for grouping
        val transmissionId = makeTransmissionId(header.filename, header.total)

        // Create chunk info
        val chunk = FilePacketState.ChunkInfo(
            index = header.index,
            total = header.total,
            filename = header.filename,
            payload = header.payload,
            rawBytes = rawBytes,
            transmissionId = transmissionId
        )

        // Persist and update state
        FilePacketState.addChunk(chunk)
    }

    /**
     * Build a transmission ID that groups chunks from the same file transmission.
     * Uses the existing FilePacketState to find an incomplete group for same filename+total.
     */
    private fun makeTransmissionId(filename: String, total: Int): String {
        // Find existing incomplete group
        val existing = FilePacketState.chunkGroups.value.find { g ->
            g.filename == filename && g.totalChunks == total && !g.isComplete
        }
        return existing?.transmissionId ?: "${filename}_${total}_${System.currentTimeMillis()}"
    }

    private fun restartCamera() {
        cameraManager.stop()
        startCamera()
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("QR Code", text))
    }

    private fun updateNotification(text: String) {
        val display = if (text.length > MAX_NOTIFY_TEXT_LEN)
            text.take(MAX_NOTIFY_TEXT_LEN) + "…"
        else
            text
        notificationManager.notify(NOTIFICATION_ID, buildNotification(display))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "QR文件传输",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "扫描服务运行中"
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
