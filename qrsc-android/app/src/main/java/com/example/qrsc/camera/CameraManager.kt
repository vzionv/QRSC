package com.example.qrsc.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.camera2.CaptureRequest
import android.util.Log
import android.util.Range
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage

/**
 * CameraX ImageAnalysis.Analyzer that uses ML Kit to scan QR codes.
 * Supports configurable scan interval throttling.
 */
@OptIn(ExperimentalGetImage::class)
class QRCodeAnalyzer(
    private val scanIntervalMs: () -> Long,
    private val onQRCodeFound: (String, ByteArray?) -> Unit,
    private val onFrame: ((Bitmap) -> Unit)? = null,
    private val shouldCaptureFrame: () -> Boolean = { true }
) : ImageAnalysis.Analyzer {

    companion object {
        private const val TAG = "QRCodeAnalyzer"
    }

    private var lastAnalyzeTimeMs: Long = 0L
    private var lastFrameTimeMs: Long = 0L

    private val scanner by lazy {
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        BarcodeScanning.getClient(options)
    }

    override fun analyze(imageProxy: ImageProxy) {
        val now = System.currentTimeMillis()

        // 预览帧捕获，独立于 QR 扫描节流，约 15 FPS
        if (onFrame != null && shouldCaptureFrame() && now - lastFrameTimeMs >= 66L) {
            lastFrameTimeMs = now
            try {
                val bitmap = imageProxy.toBitmap()
                val rotation = imageProxy.imageInfo.rotationDegrees
                val rotated = if (rotation != 0) rotateBitmap(bitmap, rotation) else bitmap
                onFrame?.invoke(rotated)
            } catch (_: Exception) { }
        }

        val interval = scanIntervalMs()

        if (now - lastAnalyzeTimeMs < interval) {
            imageProxy.close()
            return
        }
        lastAnalyzeTimeMs = now

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        try {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            scanner.process(image)
                .addOnSuccessListener { barcodes ->
                    for (barcode in barcodes) {
                        val rawValue = barcode.rawValue ?: ""
                        val rawBytes = barcode.rawBytes
                        // Check rawBytes first for file chunks, fall back to rawValue for text
                        if (rawBytes != null || rawValue.isNotEmpty()) {
                            val display = if (rawValue.isNotEmpty()) rawValue.take(50) else "(binary ${rawBytes?.size ?: 0}B)"
                            Log.d(TAG, "QR code found: $display")
                            onQRCodeFound(rawValue, rawBytes)
                        }
                    }
                }
                .addOnFailureListener {
                    // 扫描失败，静默忽略
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        } catch (e: Exception) {
            imageProxy.close()
        }
    }
}

private fun rotateBitmap(source: Bitmap, degrees: Int): Bitmap {
    val matrix = Matrix()
    matrix.postRotate(degrees.toFloat())
    return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
}

/**
 * Manages CameraX lifecycle: starting and stopping the camera.
 * Does NOT use PreviewView — only binds ImageAnalysis.
 */
class CameraManager {

    companion object {
        private const val TAG = "CameraManager"
    }

    private var cameraProvider: ProcessCameraProvider? = null

    /**
     * Start the camera with the specified lens facing.
     */
    fun start(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        lensFacing: Int,
        analyzer: QRCodeAnalyzer
    ) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindUseCases(context, lifecycleOwner, lensFacing, analyzer)
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * Stop the camera and release resources.
     */
    fun stop() {
        cameraProvider?.unbindAll()
        cameraProvider = null
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun bindUseCases(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        lensFacing: Int,
        analyzer: QRCodeAnalyzer
    ) {
        val provider = cameraProvider ?: return

        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build()

        val imageAnalysis = ImageAnalysis.Builder()
            .setTargetResolution(Size(1280, 720))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .apply {
                Camera2Interop.Extender(this).setCaptureRequestOption(
                    CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                    Range.create(10, 15)
                )
            }
            .build()
            .also {
                it.setAnalyzer(ContextCompat.getMainExecutor(context), analyzer)
            }

        try {
            provider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                imageAnalysis
            )
            Log.d(TAG, "Camera bound with FPS range [10, 15]")
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "FPS range [10, 15] unsupported on this device, retrying without frame rate cap")
            provider.unbindAll()

            val fallbackAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(1280, 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(ContextCompat.getMainExecutor(context), analyzer)
                }

            try {
                provider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    fallbackAnalysis
                )
                Log.d(TAG, "Camera bound without frame rate cap (fallback)")
            } catch (e2: Exception) {
                Log.e(TAG, "Camera binding failed in fallback", e2)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Camera binding failed", e)
        }
    }
}
