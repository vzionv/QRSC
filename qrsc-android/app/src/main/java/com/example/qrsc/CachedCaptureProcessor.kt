package com.example.qrsc

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.Log
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.tasks.await
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class CachedCaptureProcessor(context: Context) {
    companion object {
        private const val TAG = "QRSC-Processor"
        private const val SAMPLE_WIDTH = 64
        private const val SAMPLE_HEIGHT = 36
        private const val MIN_DECODE_WIDTH = 320
        private const val MIN_DECODE_HEIGHT = 180
        private const val MICROS_PER_SECOND = 1_000_000L
        private const val PROGRESS_INTERVAL_MS = 250L
        private const val AUTO_SAMPLE_RADIUS = 5
        private const val AUTO_SAMPLE_MIN_DIFFS = 2
    }

    private val appContext = context.applicationContext

    data class Progress(
        val phase: String,
        val progress: Float,
        val processedFrames: Int,
        val keyFrames: Int,
        val identifiedCount: Int,
        val addedCount: Int,
        val etaMs: Long?
    )

    data class ProcessConfig(
        val frameRate: Int,
        val threshold: Float,
        val autoThreshold: Boolean = false
    )

    suspend fun process(
        item: CaptureCacheRepository.CaptureCacheItem,
        config: ProcessConfig,
        onProgress: (Progress) -> Unit,
        isCancelled: () -> Boolean = { false }
    ) {
        val durationMs = max(item.durationMs, 0L)
        if (durationMs == 0L) error("缓存不可读取")

        val effectiveFrameRate = config.frameRate.coerceIn(1, 30)
        val totalFrames = max(1, ((durationMs * effectiveFrameRate + 999L) / 1000L).toInt())

        val retriever = MediaMetadataRetriever()
        val scanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
        )
        val seenPayloads = HashSet<String>(totalFrames.coerceAtMost(4096))
        var previousSample: ByteArray? = null
        var processedFrames = 0
        var keyFrames = 0
        var identifiedCount = 0
        var addedCount = 0
        val startedAtMs = System.currentTimeMillis()
        var lastProgressAtMs = 0L

        try {
            retriever.setDataSource(item.file.absolutePath)

            // 自适应阈值：在视频 1/4, 1/2, 3/4 处采样帧差计算
            val activeThreshold = if (config.autoThreshold) {
                val autoVal = computeAutoThreshold(retriever, totalFrames, effectiveFrameRate)
                Log.d(TAG, "process: ${item.name} duration=${durationMs}ms frames=$totalFrames autoThreshold=${"%.4f".format(autoVal)}")
                autoVal
            } else {
                val t = config.threshold.coerceIn(0f, 1f)
                Log.d(TAG, "process: ${item.name} duration=${durationMs}ms frames=$totalFrames threshold=$t")
                t
            }

            var previousIdentifiedFrame = -1
            var frameIndex = 0
            while (frameIndex < totalFrames) {
                currentCoroutineContext().ensureActive()
                if (isCancelled()) {
                    Log.d(TAG, "process cancelled at frame $frameIndex")
                    emitProgress(item.name, processedFrames, totalFrames, keyFrames, identifiedCount, addedCount, startedAtMs, onProgress)
                    return
                }

                val timeUs = frameIndex * MICROS_PER_SECOND / effectiveFrameRate
                var sampleBitmap: Bitmap? = null
                var decodeBitmap: Bitmap? = null
                try {
                    sampleBitmap = retriever.getSamplingBitmap(timeUs)
                    processedFrames++
                    if (sampleBitmap == null) {
                        lastProgressAtMs = emitProgressIfNeeded(item.name, processedFrames, totalFrames, keyFrames, identifiedCount, addedCount, startedAtMs, lastProgressAtMs, onProgress)
                        frameIndex++
                        continue
                    }

                    val sample = sample(sampleBitmap)
                    val previous = previousSample
                    val isKeyFrame = activeThreshold <= 0f || previous == null || difference(previous, sample) > activeThreshold
                    if (isKeyFrame) {
                        keyFrames++
                        previousSample = sample
                        val bitmapForDecode = if (sampleBitmap.width >= MIN_DECODE_WIDTH && sampleBitmap.height >= MIN_DECODE_HEIGHT) {
                            sampleBitmap
                        } else {
                            decodeBitmap = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                            decodeBitmap
                        }
                        if (bitmapForDecode != null) {
                            val result = decode(bitmapForDecode, scanner, seenPayloads)
                            identifiedCount += result.identified
                            addedCount += result.added

                            // 回退扫描：识别到已缓存的 chunk 但前一个 index 缺失时，填补间隙
                            if (previousIdentifiedFrame >= 0 && result.notAdded.isNotEmpty()) {
                                val needFill = result.notAdded.any { (tid, idx) ->
                                    idx > 0 && !FilePacketState.hasChunkAt(tid, idx - 1)
                                }
                                if (needFill) {
                                    val fillStart = previousIdentifiedFrame + 1
                                    val fillEnd = frameIndex - 1
                                    if (fillStart <= fillEnd) {
                                        Log.d(TAG, "gap fill: frames $fillStart..$fillEnd")
                                        fillGap(retriever, scanner, seenPayloads, effectiveFrameRate, fillStart, fillEnd).let { fr ->
                                            identifiedCount += fr.identified
                                            addedCount += fr.added
                                        }
                                    }
                                }
                            }
                            previousIdentifiedFrame = frameIndex
                        }
                    }
                } finally {
                    decodeBitmap?.takeIf { it !== sampleBitmap && !it.isRecycled }?.recycle()
                    sampleBitmap?.takeIf { !it.isRecycled }?.recycle()
                }

                lastProgressAtMs = emitProgressIfNeeded(item.name, processedFrames, totalFrames, keyFrames, identifiedCount, addedCount, startedAtMs, lastProgressAtMs, onProgress)
                frameIndex++
            }
            emitProgress(item.name, processedFrames, totalFrames, keyFrames, identifiedCount, addedCount, startedAtMs, onProgress)
        } finally {
            scanner.close()
            retriever.release()
        }
    }

    // ---- 自适应阈值 ----

    private fun computeAutoThreshold(
        retriever: MediaMetadataRetriever,
        totalFrames: Int,
        fps: Int
    ): Float {
        val positions = listOf(totalFrames / 4, totalFrames / 2, totalFrames * 3 / 4)
        val groupAvgs = mutableListOf<Float>()

        for (center in positions) {
            val start = max(0, center - AUTO_SAMPLE_RADIUS)
            val end = min(totalFrames - 1, center + AUTO_SAMPLE_RADIUS)
            val diffs = mutableListOf<Float>()
            var prevData: ByteArray? = null
            var prevBmp: Bitmap? = null

            for (i in start..end) {
                val timeUs = i * MICROS_PER_SECOND / fps
                val bmp = retriever.getSamplingBitmap(timeUs)
                if (bmp != null) {
                    val data = sample(bmp)
                    if (prevData != null) {
                        diffs.add(difference(prevData, data))
                    }
                    prevData = data
                    if (prevBmp !== bmp) prevBmp?.recycle()
                    prevBmp = bmp
                }
            }
            prevBmp?.recycle()

            if (diffs.isNotEmpty()) {
                val sorted = diffs.sorted()
                val n = minOf(AUTO_SAMPLE_MIN_DIFFS, sorted.size)
                groupAvgs.add(sorted.take(n).average().toFloat())
            }
        }

        return if (groupAvgs.isEmpty()) 0.08f else groupAvgs.average().toFloat()
    }

    // ---- 回退填补间隙 ----

    private data class GapFillResult(val identified: Int, val added: Int)

    private suspend fun fillGap(
        retriever: MediaMetadataRetriever,
        scanner: BarcodeScanner,
        seenPayloads: MutableSet<String>,
        fps: Int,
        start: Int,
        end: Int
    ): GapFillResult {
        var identified = 0
        var added = 0
        for (i in start..end) {
            val timeUs = i * MICROS_PER_SECOND / fps
            val bmp = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST) ?: continue
            try {
                val result = decode(bmp, scanner, seenPayloads)
                identified += result.identified
                added += result.added
            } finally {
                if (!bmp.isRecycled) bmp.recycle()
            }
        }
        return GapFillResult(identified, added)
    }

    // ---- 解码 ----

    private class DecodeResult {
        var identified = 0
        var added = 0
        val notAdded = mutableListOf<Pair<String, Int>>()
    }

    private suspend fun decode(
        bitmap: Bitmap,
        scanner: BarcodeScanner,
        seenPayloads: MutableSet<String>
    ): DecodeResult {
        val result = DecodeResult()
        val image = InputImage.fromBitmap(bitmap, 0)
        val barcodes = scanner.process(image).await()
        for (barcode in barcodes) {
            val rawValue = barcode.rawValue.orEmpty()
            val rawBytes = barcode.rawBytes
            val key = rawBytes?.let { "${it.size}:${it.contentHashCode()}" } ?: "${rawValue.length}:${rawValue.hashCode()}"
            if (key == "0:0" || !seenPayloads.add(key)) continue
            result.identified++
            val r = QrPayloadHandler.handle(appContext, rawValue, rawBytes, copyText = false) ?: continue
            if (!r.isFileChunk) {
                result.added++
            } else if (r.addedChunk) {
                result.added++
            } else {
                result.notAdded.add(Pair(r.transmissionId, r.chunkIndex))
            }
        }
        return result
    }

    // ---- 进度 ----

    private fun emitProgressIfNeeded(
        sourceName: String, processedFrames: Int, totalFrames: Int, keyFrames: Int,
        identifiedCount: Int, addedCount: Int, startedAtMs: Long, lastProgressAtMs: Long,
        onProgress: (Progress) -> Unit
    ): Long {
        val now = System.currentTimeMillis()
        if (processedFrames >= totalFrames || now - lastProgressAtMs >= PROGRESS_INTERVAL_MS) {
            emitProgress(sourceName, processedFrames, totalFrames, keyFrames, identifiedCount, addedCount, startedAtMs, onProgress)
            return now
        }
        return lastProgressAtMs
    }

    private fun emitProgress(
        sourceName: String, processedFrames: Int, totalFrames: Int, keyFrames: Int,
        identifiedCount: Int, addedCount: Int, startedAtMs: Long, onProgress: (Progress) -> Unit
    ) {
        onProgress(progress(sourceName, processedFrames, totalFrames, keyFrames, identifiedCount, addedCount, startedAtMs))
    }

    private fun progress(
        sourceName: String, processedFrames: Int, totalFrames: Int, keyFrames: Int,
        identifiedCount: Int, addedCount: Int, startedAtMs: Long
    ): Progress {
        val p = (processedFrames.toFloat() / totalFrames.toFloat()).coerceIn(0f, 1f)
        val elapsed = System.currentTimeMillis() - startedAtMs
        val eta = if (processedFrames > 0 && p < 1f) {
            ((elapsed / processedFrames.toFloat()) * (totalFrames - processedFrames)).toLong()
        } else null
        return Progress(
            phase = "处理缓存 $sourceName",
            progress = p, processedFrames = processedFrames, keyFrames = keyFrames,
            identifiedCount = identifiedCount, addedCount = addedCount, etaMs = eta
        )
    }

    // ---- MediaMetadataRetriever / 帧处理 ----

    private fun MediaMetadataRetriever.getSamplingBitmap(timeUs: Long): Bitmap? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            try {
                getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST, SAMPLE_WIDTH, SAMPLE_HEIGHT)
            } catch (_: Exception) {
                getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
            }
        } else {
            getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
        }
    }

    private fun sample(bitmap: Bitmap): ByteArray {
        val scaled = if (bitmap.width == SAMPLE_WIDTH && bitmap.height == SAMPLE_HEIGHT) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, SAMPLE_WIDTH, SAMPLE_HEIGHT, false)
        }
        val pixels = IntArray(SAMPLE_WIDTH * SAMPLE_HEIGHT)
        return try {
            scaled.getPixels(pixels, 0, SAMPLE_WIDTH, 0, 0, SAMPLE_WIDTH, SAMPLE_HEIGHT)
            val result = ByteArray(pixels.size)
            for (index in pixels.indices) {
                val pixel = pixels[index]
                val gray = (((pixel ushr 16) and 0xFF) + ((pixel ushr 8) and 0xFF) + (pixel and 0xFF)) / 3
                result[index] = gray.toByte()
            }
            result
        } finally {
            if (scaled !== bitmap && !scaled.isRecycled) scaled.recycle()
        }
    }

    private fun difference(left: ByteArray, right: ByteArray): Float {
        var total = 0L
        val count = minOf(left.size, right.size)
        for (index in 0 until count) {
            total += abs((left[index].toInt() and 0xFF) - (right[index].toInt() and 0xFF))
        }
        return total.toFloat() / (count * 255f)
    }
}
