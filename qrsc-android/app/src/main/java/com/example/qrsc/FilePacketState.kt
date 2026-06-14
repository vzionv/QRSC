package com.example.qrsc

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

object FilePacketState {

    private const val TAG = "QRSC-FilePacket"

    data class ChunkInfo(
        val index: Int, val total: Int, val filename: String,
        val payload: ByteArray, val rawBytes: ByteArray, val transmissionId: String
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ChunkInfo) return false
            return index == other.index && transmissionId == other.transmissionId
        }
        override fun hashCode(): Int = 31 * index + transmissionId.hashCode()
    }

    data class ChunkGroup(
        val transmissionId: String,
        val filename: String,
        val totalChunks: Int,
        val chunks: List<ChunkInfo>,
        val firstReceivedAt: Long
    ) {
        val receivedCount: Int get() = chunks.size
        val firstMissing: Int
            get() {
                val present = BooleanArray(totalChunks.coerceAtLeast(0))
                for (chunk in chunks) {
                    if (chunk.index in present.indices) present[chunk.index] = true
                }
                for (index in present.indices) if (!present[index]) return index
                return -1
            }
        val isComplete: Boolean
            get() {
                if (totalChunks <= 0 || chunks.size != totalChunks) return false
                val present = BooleanArray(totalChunks)
                for (chunk in chunks) {
                    if (chunk.index !in 0 until totalChunks || present[chunk.index]) return false
                    present[chunk.index] = true
                }
                return true
            }
    }

    private val stateLock = Any()
    private val _chunkGroups = MutableStateFlow<List<ChunkGroup>>(emptyList())
    val chunkGroups: StateFlow<List<ChunkGroup>> = _chunkGroups.asStateFlow()
    private var appContext: Context? = null

    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        FileUtils.chunksDir(app).let { if (!it.exists()) it.mkdirs() }
        recoverFromDisk(app)
    }

    fun addChunk(chunk: ChunkInfo): Boolean {
        if (!isValidChunk(chunk)) return false
        Log.d(TAG, "addChunk: ${chunk.filename} idx=${chunk.index}/${chunk.total} tid=${chunk.transmissionId}")

        val chunkToPersist = synchronized(stateLock) {
            val groups = _chunkGroups.value.toMutableList()
            val groupIndex = groups.indexOfFirst { group ->
                group.transmissionId == chunk.transmissionId ||
                    (group.filename == chunk.filename && group.totalChunks == chunk.total && !group.isComplete)
            }

            if (groupIndex >= 0) {
                val group = groups[groupIndex]
                if (group.chunks.any { it.index == chunk.index }) return@synchronized null
                val normalizedChunk = if (chunk.transmissionId == group.transmissionId) {
                    chunk
                } else {
                    chunk.copy(transmissionId = group.transmissionId)
                }
                val updatedChunks = (group.chunks + normalizedChunk).sortedBy { it.index }
                groups[groupIndex] = group.copy(chunks = updatedChunks)
                _chunkGroups.value = groups.sortedByDescending { it.firstReceivedAt }
                normalizedChunk
            } else {
                val newGroup = ChunkGroup(
                    transmissionId = chunk.transmissionId,
                    filename = chunk.filename,
                    totalChunks = chunk.total,
                    chunks = listOf(chunk),
                    firstReceivedAt = System.currentTimeMillis()
                )
                groups.add(newGroup)
                _chunkGroups.value = groups.sortedByDescending { it.firstReceivedAt }
                chunk
            }
        }

        if (chunkToPersist != null) persistChunk(chunkToPersist)
        return chunkToPersist != null
    }

    fun removeChunk(transmissionId: String, index: Int) {
        val ctx = appContext ?: return
        val shouldDeleteGroup = synchronized(stateLock) {
            val groups = _chunkGroups.value.toMutableList()
            val groupIndex = groups.indexOfFirst { it.transmissionId == transmissionId }
            if (groupIndex < 0) return@synchronized false
            val group = groups[groupIndex]
            val updatedChunks = group.chunks.filterNot { it.index == index }
            if (updatedChunks.isEmpty()) {
                groups.removeAt(groupIndex)
                _chunkGroups.value = groups
                true
            } else {
                groups[groupIndex] = group.copy(chunks = updatedChunks)
                _chunkGroups.value = groups
                false
            }
        }

        val f = File(File(FileUtils.chunksDir(ctx), transmissionId), "chunk_$index.bin")
        f.delete()
        if (shouldDeleteGroup) f.parentFile?.deleteRecursively()
        else f.parentFile?.let { if (it.isDirectory && it.listFiles()?.isEmpty() == true) it.delete() }
    }

    fun removeGroup(transmissionId: String) {
        Log.d(TAG, "removeGroup: $transmissionId")
        val ctx = appContext ?: return
        synchronized(stateLock) {
            val groups = _chunkGroups.value.filterNot { it.transmissionId == transmissionId }
            _chunkGroups.value = groups
        }
        File(FileUtils.chunksDir(ctx), transmissionId).deleteRecursively()
    }

    fun clearAll(): Int {
        Log.d(TAG, "clearAll")
        val ctx = appContext ?: return 0
        val count = synchronized(stateLock) {
            val currentCount = _chunkGroups.value.size
            _chunkGroups.value = emptyList()
            currentCount
        }
        FileUtils.chunksDir(ctx).listFiles()?.forEach { groupDir -> groupDir.deleteRecursively() }
        return count
    }

    private fun persistChunk(chunk: ChunkInfo) {
        val ctx = appContext ?: return
        val groupDir = File(FileUtils.chunksDir(ctx), chunk.transmissionId)
        if (!groupDir.exists() && !groupDir.mkdirs()) return
        val target = File(groupDir, "chunk_${chunk.index}.bin")
        val tmp = File(groupDir, "chunk_${chunk.index}.bin.tmp")
        try {
            tmp.writeBytes(chunk.rawBytes)
            if (!tmp.renameTo(target)) {
                target.delete()
                tmp.renameTo(target)
            }
        } catch (e: Exception) {
            Log.e(TAG, "persistChunk failed", e)
            tmp.delete()
        }
    }

    private fun recoverFromDisk(context: Context) {
        val dir = FileUtils.chunksDir(context)
        val groupDirs = dir.listFiles { f -> f.isDirectory } ?: run {
            _chunkGroups.value = emptyList()
            return
        }
        val recovered = mutableListOf<ChunkGroup>()
        for (groupDir in groupDirs) {
            val tid = groupDir.name
            val chunkFiles = groupDir.listFiles { f ->
                f.isFile && f.name.startsWith("chunk_") && f.name.endsWith(".bin")
            } ?: continue
            if (chunkFiles.isEmpty()) {
                groupDir.deleteRecursively()
                continue
            }

            val chunksByIndex = linkedMapOf<Int, ChunkInfo>()
            var filename: String? = null
            var total: Int? = null
            var firstTs = Long.MAX_VALUE
            for (cf in chunkFiles) {
                try {
                    val raw = cf.readBytes()
                    val header = FileUtils.parseChunkHeader(raw) ?: continue
                    if (filename != null && filename != header.filename) continue
                    if (total != null && total != header.total) continue
                    filename = header.filename
                    total = header.total
                    chunksByIndex.putIfAbsent(
                        header.index,
                        ChunkInfo(header.index, header.total, header.filename, header.payload, raw, tid)
                    )
                    if (cf.lastModified() < firstTs) firstTs = cf.lastModified()
                } catch (_: Exception) {
                    // Ignore unreadable partial files.
                }
            }
            val finalFilename = filename
            val finalTotal = total
            if (finalFilename != null && finalTotal != null && chunksByIndex.isNotEmpty()) {
                recovered.add(
                    ChunkGroup(
                        transmissionId = tid,
                        filename = finalFilename,
                        totalChunks = finalTotal,
                        chunks = chunksByIndex.values.sortedBy { it.index },
                        firstReceivedAt = if (firstTs == Long.MAX_VALUE) groupDir.lastModified() else firstTs
                    )
                )
            } else {
                groupDir.deleteRecursively()
            }
        }
        synchronized(stateLock) {
            _chunkGroups.value = recovered.sortedByDescending { it.firstReceivedAt }
        }
    }

    private fun isValidChunk(chunk: ChunkInfo): Boolean {
        return chunk.total > 0 &&
            chunk.index in 0 until chunk.total &&
            chunk.filename.isNotBlank() &&
            chunk.transmissionId.isNotBlank()
    }

    fun hasChunkAt(transmissionId: String, index: Int): Boolean {
        val group = _chunkGroups.value.find { it.transmissionId == transmissionId } ?: return false
        return group.chunks.any { it.index == index }
    }
}
