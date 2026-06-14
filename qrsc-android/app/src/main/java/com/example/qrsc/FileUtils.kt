package com.example.qrsc

import android.content.Context
import java.io.File
import java.io.FileOutputStream

object FileUtils {

    data class HeaderInfo(
        val version: Int, val isLast: Boolean,
        val total: Int, val index: Int,
        val filename: String, val payload: ByteArray
    )

    fun baseDir(context: Context): File {
        val dir = File(context.getExternalFilesDir(null), "QRFileTransfer")
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

    fun parseChunkHeader(data: ByteArray): HeaderInfo? {
        if (data.size < 11) return null
        if (data[0] != 0x53.toByte() || data[1] != 0x43.toByte() ||
            data[2] != 0x51.toByte() || data[3] != 0x52.toByte()) return null
        val version = data[4].toInt() and 0xFF
        val flags = data[5].toInt() and 0xFF
        val total = ((data[6].toInt() and 0xFF) shl 8) or (data[7].toInt() and 0xFF)
        val index = ((data[8].toInt() and 0xFF) shl 8) or (data[9].toInt() and 0xFF)
        val fnLen = data[10].toInt() and 0xFF
        if (data.size < 11 + fnLen) return null
        val filename = data.decodeToString(11, 11 + fnLen)
        val payload = data.copyOfRange(11 + fnLen, data.size)
        return HeaderInfo(version, (flags and 1) != 0, total, index, filename, payload)
    }

    fun assembleFile(group: FilePacketState.ChunkGroup, context: Context): File? {
        if (!group.isComplete) return null
        val sorted = group.chunks.sortedBy { it.index }
        val dir = decodedDir(context)
        if (!dir.exists()) dir.mkdirs()
        val outputFile = File(dir, group.filename)
        try {
            val fos = FileOutputStream(outputFile)
            for (chunk in sorted) fos.write(chunk.payload)
            fos.flush()
            fos.fd.sync()
            fos.close()
            return outputFile
        } catch (_: Exception) {
            return null
        }
    }
}
