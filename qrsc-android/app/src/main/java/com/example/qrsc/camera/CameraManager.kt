package com.example.qrsc.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.hardware.camera2.CaptureRequest
import android.hardware.display.DisplayManager
import android.util.Log
import android.util.Range
import android.util.Rational
import android.view.Surface
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@OptIn(ExperimentalGetImage::class)
class QRCodeAnalyzer(
    private val scanIntervalMs: () -> Long,
    private val onQRCodeFound: (String, ByteArray?) -> Unit,
    private val onFrame: ((Bitmap) -> Unit)? = null,
    private val shouldCaptureFrame: () -> Boolean = { true },
    private val shouldScan: () -> Boolean = { true }
) : ImageAnalysis.Analyzer {
    companion object {
        private const val TAG = "QRCodeAnalyzer"
        private const val PREVIEW_INTERVAL_MS = 100L
        private const val PREVIEW_MAX_WIDTH = 640
    }

    private var lastAnalyzeTimeMs: Long = 0L
    private var lastFrameTimeMs: Long = 0L
    @Volatile private var closed = false
    private val scannerLazy = lazy {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        )
    }
    private val scanner: BarcodeScanner get() = scannerLazy.value

    override fun analyze(imageProxy: ImageProxy) {
        if (closed) {
            imageProxy.close()
            return
        }

        val now = System.currentTimeMillis()
        if (onFrame != null && shouldCaptureFrame() && now - lastFrameTimeMs >= PREVIEW_INTERVAL_MS) {
            lastFrameTimeMs = now
            try {
                createPreviewBitmap(imageProxy)?.let { onFrame.invoke(it) }
            } catch (_: Exception) {
                // Preview is best-effort and must never block QR analysis.
            }
        }

        if (!shouldScan() || now - lastAnalyzeTimeMs < scanIntervalMs()) {
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
                    if (closed) return@addOnSuccessListener
                    for (barcode in barcodes) {
                        val rawValue = barcode.rawValue.orEmpty()
                        val rawBytes = barcode.rawBytes
                        if (rawBytes != null || rawValue.isNotEmpty()) {
                            Log.d(TAG, "QR code found")
                            onQRCodeFound(rawValue, rawBytes)
                        }
                    }
                }
                .addOnFailureListener { }
                .addOnCompleteListener { imageProxy.close() }
        } catch (_: Exception) {
            imageProxy.close()
        }
    }

    fun close() {
        closed = true
        if (scannerLazy.isInitialized()) {
            try {
                scanner.close()
            } catch (_: Exception) {
                // Ignore cleanup failures.
            }
        }
    }

    private fun createPreviewBitmap(imageProxy: ImageProxy): Bitmap? {
        val source = imageProxy.toBitmap()
        val cropped = cropIfNeeded(source, imageProxy.cropRect)
        if (cropped !== source && !source.isRecycled) source.recycle()
        val rotated = rotateIfNeeded(cropped, imageProxy.imageInfo.rotationDegrees)
        if (rotated !== cropped && !cropped.isRecycled) cropped.recycle()
        val square = cropCenterSquare(rotated)
        if (square !== rotated && !rotated.isRecycled) rotated.recycle()
        val scaled = scaleDownIfNeeded(square, PREVIEW_MAX_WIDTH)
        if (scaled !== square && !square.isRecycled) square.recycle()
        return scaled
    }

    private fun cropCenterSquare(source: Bitmap): Bitmap {
        val minDim = minOf(source.width, source.height)
        if (source.width == source.height) return source
        val left = (source.width - minDim) / 2
        val top = (source.height - minDim) / 2
        return Bitmap.createBitmap(source, left.coerceAtLeast(0), top.coerceAtLeast(0), minDim, minDim)
    }

    private fun cropIfNeeded(source: Bitmap, cropRect: Rect): Bitmap {
        val left = cropRect.left.coerceIn(0, source.width - 1)
        val top = cropRect.top.coerceIn(0, source.height - 1)
        val right = cropRect.right.coerceIn(left + 1, source.width)
        val bottom = cropRect.bottom.coerceIn(top + 1, source.height)
        if (left == 0 && top == 0 && right == source.width && bottom == source.height) return source
        return Bitmap.createBitmap(source, left, top, right - left, bottom - top)
    }

    private fun rotateIfNeeded(source: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return source
        val matrix = Matrix()
        matrix.postRotate(degrees.toFloat())
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun scaleDownIfNeeded(source: Bitmap, maxWidth: Int): Bitmap {
        if (source.width <= maxWidth) return source
        val targetHeight = (source.height * (maxWidth.toFloat() / source.width)).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, maxWidth, targetHeight, true)
    }
}

class CameraManager {
    companion object {
        private const val TAG = "QRSC-CamMgr"
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var currentAnalysis: ImageAnalysis? = null
    private var currentAnalyzer: QRCodeAnalyzer? = null
    private var analysisExecutor: ExecutorService? = null
    private var startGeneration = 0

    fun start(context: Context, lifecycleOwner: LifecycleOwner, lensFacing: Int, analyzer: QRCodeAnalyzer) {
        Log.d(TAG, "start lensFacing=$lensFacing")
        val appContext = context.applicationContext
        val generation = ++startGeneration
        val cameraProviderFuture = ProcessCameraProvider.getInstance(appContext)
        cameraProviderFuture.addListener({
            if (generation != startGeneration) {
                analyzer.close()
                return@addListener
            }
            try {
                cameraProvider = cameraProviderFuture.get()
                bindUseCases(context, lifecycleOwner, lensFacing, analyzer)
            } catch (e: Exception) {
                Log.e(TAG, "start camera provider failed", e)
                analyzer.close()
                videoCapture = null
            }
        }, ContextCompat.getMainExecutor(appContext))
    }

    fun startCapture(context: Context, outputFile: File, onFinalized: (Boolean) -> Unit): Boolean {
        val capture = videoCapture
        if (capture == null) { Log.e(TAG, "startCapture: videoCapture null"); return false }
        if (recording != null) { Log.w(TAG, "startCapture: already recording"); return false }
        Log.d(TAG, "startCapture: ${outputFile.name}")
        capture.targetRotation = displayRotation(context)
        val outputOptions = FileOutputOptions.Builder(outputFile).build()
        return try {
            recording = capture.output.prepareRecording(context.applicationContext, outputOptions)
                .start(ContextCompat.getMainExecutor(context.applicationContext)) { event ->
                    if (event is VideoRecordEvent.Finalize) {
                        val success = !event.hasError()
                        Log.d(TAG, "capture finalized success=$success error=${event.error}")
                        recording = null
                        onFinalized(success)
                    }
                }
            true
        } catch (e: Exception) {
            Log.e(TAG, "startCapture failed", e)
            recording = null
            false
        }
    }

    fun stopCapture() {
        Log.d(TAG, "stopCapture recording=${recording != null}")
        val activeRecording = recording ?: return
        try {
            activeRecording.stop()
        } catch (e: Exception) {
            Log.e(TAG, "stopCapture failed", e)
        } finally {
            recording = null
        }
    }

    fun isRecordingActive(): Boolean = recording != null

    fun stop() {
        Log.d(TAG, "stop")
        startGeneration++
        stopCapture()
        clearAnalyzer()
        try {
            cameraProvider?.unbindAll()
        } catch (_: Exception) {
        }
        cameraProvider = null
        videoCapture = null
        shutdownAnalysisExecutor()
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun bindUseCases(context: Context, lifecycleOwner: LifecycleOwner, lensFacing: Int, analyzer: QRCodeAnalyzer) {
        val provider = cameraProvider ?: run {
            analyzer.close()
            return
        }
        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        clearAnalyzer()
        val rotation = displayRotation(context)
        val capture = createVideoCapture(rotation)
        videoCapture = capture

        val analysis = createAnalysis(analyzer, useFpsRange = true)
        if (tryBindWithViewPort(provider, lifecycleOwner, selector, context, analysis, capture, analyzer)) return

        analysis.clearAnalyzer()
        val fallbackAnalysis = createAnalysis(analyzer, useFpsRange = false)
        if (tryBindWithViewPort(provider, lifecycleOwner, selector, context, fallbackAnalysis, capture, analyzer)) return

        // 最后才尝试无 ViewPort 直绑，录像功能仍保留；仅作为个别机型无法创建 ViewPort 时的保底。
        if (tryBindDirect(provider, lifecycleOwner, selector, fallbackAnalysis, capture, analyzer)) return

        fallbackAnalysis.clearAnalyzer()
        analyzer.close()
        videoCapture = null
        Log.e(TAG, "Camera binding failed completely")
    }

    @Suppress("DEPRECATION")
    private fun createVideoCapture(rotation: Int): VideoCapture<Recorder> {
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.HD))
            .build()
        return VideoCapture.Builder(recorder)
            // CameraX 1.3.x VideoCapture does not support setTargetAspectRatio();
            // aspect matching is controlled by UseCaseGroup/ViewPort instead.
            .build()
            .also { it.targetRotation = rotation }
    }

    private fun tryBindWithViewPort(
        provider: ProcessCameraProvider,
        lifecycleOwner: LifecycleOwner,
        selector: CameraSelector,
        context: Context,
        analysis: ImageAnalysis,
        capture: VideoCapture<Recorder>,
        analyzer: QRCodeAnalyzer
    ): Boolean {
        return try {
            provider.unbindAll()
            clearAnalyzer(closeAnalyzer = false)
            provider.bindToLifecycle(lifecycleOwner, selector, createUseCaseGroup(context, analysis, capture))
            currentAnalysis = analysis
            currentAnalyzer = analyzer
            Log.d(TAG, "Camera bound with ViewPort")
            true
        } catch (e: Exception) {
            Log.w(TAG, "ViewPort binding failed", e)
            try { provider.unbindAll() } catch (_: Exception) {}
            false
        }
    }

    private fun tryBindDirect(
        provider: ProcessCameraProvider,
        lifecycleOwner: LifecycleOwner,
        selector: CameraSelector,
        analysis: ImageAnalysis,
        capture: VideoCapture<Recorder>,
        analyzer: QRCodeAnalyzer
    ): Boolean {
        return try {
            provider.unbindAll()
            clearAnalyzer(closeAnalyzer = false)
            provider.bindToLifecycle(lifecycleOwner, selector, analysis, capture)
            currentAnalysis = analysis
            currentAnalyzer = analyzer
            Log.d(TAG, "Camera bound without ViewPort")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Direct binding failed", e)
            analysis.clearAnalyzer()
            try { provider.unbindAll() } catch (_: Exception) {}
            false
        }
    }

    private fun createUseCaseGroup(
        context: Context,
        analysis: ImageAnalysis,
        capture: VideoCapture<Recorder>
    ): UseCaseGroup {
        val rotation = displayRotation(context)
        val aspectRatio = if (rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180) {
            Rational(9, 16)
        } else {
            Rational(16, 9)
        }
        val viewPort = ViewPort.Builder(aspectRatio, rotation)
            .setScaleType(ViewPort.FILL_CENTER)
            .build()
        return UseCaseGroup.Builder()
            .setViewPort(viewPort)
            .addUseCase(analysis)
            .addUseCase(capture)
            .build()
    }

    private fun displayRotation(context: Context): Int {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        return dm?.getDisplay(0)?.rotation ?: Surface.ROTATION_0
    }

    @Suppress("DEPRECATION")
    @OptIn(ExperimentalCamera2Interop::class)
    private fun createAnalysis(analyzer: QRCodeAnalyzer, useFpsRange: Boolean): ImageAnalysis {
        return ImageAnalysis.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_16_9)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .apply {
                Camera2Interop.Extender(this).setCaptureRequestOption(
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
                )
                if (useFpsRange) {
                    val fps = 15.coerceIn(1, 30)
                    Camera2Interop.Extender(this).setCaptureRequestOption(
                        CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                        Range.create(fps, fps)
                    )
                }
            }
            .build()
            .also { it.setAnalyzer(getAnalysisExecutor(), analyzer) }
    }

    private fun getAnalysisExecutor(): ExecutorService {
        val existing = analysisExecutor
        if (existing != null && !existing.isShutdown) return existing
        return Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "QRSC-ImageAnalysis").apply { isDaemon = true }
        }.also { analysisExecutor = it }
    }

    private fun clearAnalyzer(closeAnalyzer: Boolean = true) {
        currentAnalysis?.clearAnalyzer()
        currentAnalysis = null
        if (closeAnalyzer) currentAnalyzer?.close()
        currentAnalyzer = null
    }

    private fun shutdownAnalysisExecutor() {
        analysisExecutor?.shutdown()
        analysisExecutor = null
    }
}
