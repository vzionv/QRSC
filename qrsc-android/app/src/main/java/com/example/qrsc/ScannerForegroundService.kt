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

class ScannerForegroundService : LifecycleService() {

    companion object {
        private const val CHANNEL_ID = "qr_scanner_channel"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_TOGGLE_CAMERA = "action.TOGGLE_CAMERA"
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
            onQRCodeFound = { rawValue ->
                ScannerState.recordDetection()
                if (rawValue != ScannerState.lastCopiedText) {
                    ScannerState.lastCopiedText = rawValue
                    ScannerState.updateCurrentText(rawValue)
                    copyToClipboard(rawValue)
                    updateNotification(rawValue)
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

    private fun restartCamera() {
        cameraManager.stop()
        startCamera()
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("QR Code", text))
    }

    /** 用最新扫描内容更新通知文本，超出长度截断并以 … 结尾 */
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
                "QRSC Scanner",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "QR scanner service"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("QRSC")
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
