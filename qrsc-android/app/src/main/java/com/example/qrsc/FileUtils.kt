package com.example.qrsc

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale

object FileUtils {

    data class HeaderInfo(
        val version: Int, val isLast: Boolean,
        val total: Int, val index: Int,
        val filename: String, val payload: ByteArray
    )

    private const val MAX_FILENAME_BYTES = 255
    private const val FALLBACK_FILENAME = "decoded.bin"

    fun baseDir(context: Context): File {
        val appContext = context.applicationContext
        val root = appContext.getExternalFilesDir(null) ?: appContext.filesDir
        val dir = File(root, "QRFileTransfer")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun chunksDir(context: Context): File {
        val dir = File(baseDir(context), "chunks")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun decodedDir(context: Context): File {
        val dir = File(baseDir(context), "decoded")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun captureCacheDir(context: Context): File {
        val dir = File(baseDir(context), "capture_cache")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun parseChunkHeader(data: ByteArray): HeaderInfo? {
        if (data.size < 11) return null
        if (data[0] != 0x53.toByte() || data[1] != 0x43.toByte() ||
            data[2] != 0x51.toByte() || data[3] != 0x52.toByte()) return null

        val version = data[4].toInt() and 0xFF
        val flags = data[5].toInt() and 0xFF
        val total = ((data[6].toInt() and 0xFF) shl 8) or (data[7].toInt() and 0xFF)
        val index = ((data[8].toInt() and 0xFF) shl 8) or (data[9].toInt() and 0xFF)
        val fnLen = data[10].toInt() and 0xFF
        if (fnLen == 0 || fnLen > MAX_FILENAME_BYTES) return null
        if (total <= 0 || index !in 0 until total) return null
        if (data.size < 11 + fnLen) return null

        val filename = data.decodeToString(11, 11 + fnLen).trim().ifEmpty { FALLBACK_FILENAME }
        val payload = data.copyOfRange(11 + fnLen, data.size)
        return HeaderInfo(version, (flags and 1) != 0, total, index, filename, payload)
    }

    fun assembleFile(group: FilePacketState.ChunkGroup, context: Context): File? {
        if (!group.isComplete) return null
        val sorted = group.chunks.sortedBy { it.index }
        val dir = decodedDir(context)
        if (!dir.exists() && !dir.mkdirs()) return null
        val outputFile = uniqueFile(dir, sanitizeFileName(group.filename))
        return try {
            FileOutputStream(outputFile).use { out ->
                for (chunk in sorted) out.write(chunk.payload)
                out.flush()
                out.fd.sync()
            }
            outputFile
        } catch (_: Exception) {
            outputFile.delete()
            null
        }
    }

    fun sanitizeFileName(name: String): String {
        val cleaned = name
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace(Regex("[\\u0000-\\u001F\\u007F]"), "_")
            .replace(Regex("[/:*?\"<>|]"), "_")
            .trim(' ', '.')
            .ifEmpty { FALLBACK_FILENAME }
        return cleaned.take(160).ifEmpty { FALLBACK_FILENAME }
    }

    fun stableIdPart(value: String, length: Int = 12): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(Locale.US, byte.toInt() and 0xFF) }
            .take(length.coerceAtLeast(1))
    }

    private fun uniqueFile(dir: File, preferredName: String): File {
        val safeName = sanitizeFileName(preferredName)
        val dot = safeName.lastIndexOf('.')
        val base = if (dot > 0) safeName.substring(0, dot) else safeName
        val ext = if (dot > 0) safeName.substring(dot) else ""
        var candidate = File(dir, safeName)
        var suffix = 1
        while (candidate.exists()) {
            candidate = File(dir, "${base}_$suffix$ext")
            suffix++
        }
        return candidate
    }
}
