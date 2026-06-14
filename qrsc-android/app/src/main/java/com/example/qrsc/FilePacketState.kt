package com.example.qrsc

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

object FilePacketState {

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
        val transmissionId: String, val filename: String,
        val totalChunks: Int, val chunks: MutableList<ChunkInfo>, val firstReceivedAt: Long
    ) {
        val receivedCount: Int get() = chunks.size
        val firstMissing: Int
            get() {
                val idxs = chunks.map { it.index }.toSet()
                for (i in 0 until totalChunks) { if (i !in idxs) return i }
                return -1
            }
        val isComplete: Boolean
            get() {
                if (chunks.size != totalChunks) return false
                return chunks.map { it.index }.sorted() == (0 until totalChunks).toList()
            }
    }

    private val _chunkGroups = MutableStateFlow<List<ChunkGroup>>(emptyList())
    val chunkGroups: StateFlow<List<ChunkGroup>> = _chunkGroups.asStateFlow()
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context
        FileUtils.chunksDir(context).let { if (!it.exists()) it.mkdirs() }
        recoverFromDisk(context)
    }

    fun addChunk(chunk: ChunkInfo): Boolean {
        val groups = _chunkGroups.value.toMutableList()
        for (g in groups) {
            if (g.filename == chunk.filename && g.totalChunks == chunk.total) {
                if (g.chunks.any { it.index == chunk.index }) return false
                g.chunks.add(chunk)
                _chunkGroups.value = groups
                persistChunk(chunk)
                return true
            }
        }
        val id = "${chunk.filename}_${chunk.total}_${System.currentTimeMillis()}"
        groups.add(ChunkGroup(id, chunk.filename, chunk.total, mutableListOf(chunk), System.currentTimeMillis()))
        _chunkGroups.value = groups
        persistChunk(chunk)
        return true
    }

    fun removeChunk(transmissionId: String, index: Int) {
        val ctx = appContext ?: return
        val groups = _chunkGroups.value.toMutableList()
        val group = groups.find { it.transmissionId == transmissionId } ?: return
        group.chunks.removeAll { it.index == index }
        if (group.chunks.isEmpty()) {
            groups.removeAll { it.transmissionId == transmissionId }
        }
        _chunkGroups.value = groups

        val f = File(FileUtils.chunksDir(ctx), "$transmissionId/chunk_$index.bin")
        f.delete()
        // Remove parent dir if empty
        f.parentFile?.let { if (it.isDirectory && it.listFiles()?.isEmpty() == true) it.delete() }
    }

    fun removeGroup(transmissionId: String) {
        val ctx = appContext ?: return
        val groups = _chunkGroups.value.toMutableList()
        groups.removeAll { it.transmissionId == transmissionId }
        _chunkGroups.value = groups

        val groupDir = File(FileUtils.chunksDir(ctx), transmissionId)
        if (groupDir.exists()) {
            groupDir.listFiles()?.forEach { it.delete() }
            groupDir.delete()
        }
    }

    fun clearAll(): Int {
        val ctx = appContext ?: return 0
        val count = _chunkGroups.value.size
        _chunkGroups.value = emptyList()
        val dir = FileUtils.chunksDir(ctx)
        // Delete all group subdirs
        dir.listFiles()?.forEach { groupDir ->
            if (groupDir.isDirectory) {
                groupDir.listFiles()?.forEach { it.delete() }
                groupDir.delete()
            }
        }
        return count
    }

    private fun persistChunk(chunk: ChunkInfo) {
        val ctx = appContext ?: return
        val groupDir = File(FileUtils.chunksDir(ctx), chunk.transmissionId)
        if (!groupDir.exists()) groupDir.mkdirs()
        File(groupDir, "chunk_${chunk.index}.bin").writeBytes(chunk.rawBytes)
    }

    private fun recoverFromDisk(context: Context) {
        val dir = FileUtils.chunksDir(context)
        val groupDirs = dir.listFiles { f -> f.isDirectory } ?: return
        val recovered = mutableListOf<ChunkGroup>()
        for (groupDir in groupDirs) {
            val tid = groupDir.name
            val chunkFiles = groupDir.listFiles { f ->
                f.name.startsWith("chunk_") && f.name.endsWith(".bin")
            } ?: continue
            if (chunkFiles.isEmpty()) {
                groupDir.delete()
                continue
            }
            val chunks = mutableListOf<ChunkInfo>()
            var fn = ""; var total = 0; var firstTs = Long.MAX_VALUE
            for (cf in chunkFiles) {
                try {
                    val raw = cf.readBytes()
                    val h = FileUtils.parseChunkHeader(raw) ?: continue
                    chunks.add(ChunkInfo(h.index, h.total, h.filename, h.payload, raw, tid))
                    fn = h.filename; total = h.total
                    if (cf.lastModified() < firstTs) firstTs = cf.lastModified()
                } catch (_: Exception) {}
            }
            if (chunks.isNotEmpty()) recovered.add(ChunkGroup(tid, fn, total, chunks, firstTs))
        }
        if (recovered.isNotEmpty()) _chunkGroups.value = recovered
    }

}
