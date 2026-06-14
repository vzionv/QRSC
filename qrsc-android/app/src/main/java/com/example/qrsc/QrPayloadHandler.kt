package com.example.qrsc

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log

object QrPayloadHandler {
    private const val TAG = "QrPayloadHandler"

    data class Result(
        val isFileChunk: Boolean,
        val displayText: String,
        val notificationText: String,
        val addedChunk: Boolean,
        val chunkIndex: Int = -1,
        val transmissionId: String = ""
    )

    fun handle(
        context: Context,
        rawValue: String,
        rawBytes: ByteArray?,
        copyText: Boolean
    ): Result? {
        val appContext = context.applicationContext
        val chunkData = when {
            rawBytes != null -> rawBytes
            rawValue.isNotEmpty() -> rawValue.toByteArray(Charsets.ISO_8859_1)
            else -> return null
        }

        val header = FileUtils.parseChunkHeader(chunkData)
        if (header != null) {
            val transmissionId = makeTransmissionId(header.filename, header.total)
            val chunk = FilePacketState.ChunkInfo(
                index = header.index,
                total = header.total,
                filename = header.filename,
                payload = header.payload,
                rawBytes = chunkData,
                transmissionId = transmissionId
            )
            val added = FilePacketState.addChunk(chunk)
            val text = "Receiving: ${header.filename} (${header.index + 1}/${header.total})"
            Log.d(TAG, text)
            ScannerState.updateDownloadHint("File: ${header.filename}  chunk ${header.index + 1}/${header.total}")
            ScannerState.updateCurrentText("Receiving file: ${header.filename}")
            return Result(true, "Receiving file: ${header.filename}", text, added, header.index, transmissionId)
        }

        if (rawValue.isEmpty()) return null
        if (copyText && rawValue != ScannerState.lastCopiedText) {
            ScannerState.lastCopiedText = rawValue
            val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("QR Code", rawValue))
        }
        ScannerState.updateCurrentText(rawValue)
        return Result(false, rawValue, rawValue, false)
    }

    private fun makeTransmissionId(filename: String, total: Int): String {
        val existing = FilePacketState.chunkGroups.value.find { group ->
            group.filename == filename && group.totalChunks == total && !group.isComplete
        }
        if (existing != null) return existing.transmissionId

        val safeName = FileUtils.sanitizeFileName(filename).substringBeforeLast('.').take(48).ifEmpty { "file" }
        val fingerprint = FileUtils.stableIdPart("$filename:$total:${System.currentTimeMillis()}")
        return "${safeName}_${total}_$fingerprint"
    }
}
