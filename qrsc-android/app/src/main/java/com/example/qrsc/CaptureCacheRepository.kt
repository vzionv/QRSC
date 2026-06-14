package com.example.qrsc

import android.content.Context
import android.media.MediaMetadataRetriever
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashMap
import java.util.Locale

private const val CACHE_PREFIX = "sc_"
private const val CACHE_EXT = ".mp4"

object CaptureCacheRepository {
    private const val TAG = "QRSC-CacheRepo"
    private const val MAX_DURATION_CACHE_ENTRIES = 128

    data class CaptureCacheItem(
        val file: File,
        val name: String,
        val createdAtMs: Long,
        val sizeBytes: Long,
        val durationMs: Long,
        val fps: Int
    )

    private data class DurationCacheEntry(
        val modifiedAtMs: Long,
        val sizeBytes: Long,
        val durationMs: Long
    )

    private val nameFormat = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
    private val fpsRegex = Regex("""_fps(\d+)(?:_\d+)?\.mp4$""")
    private val durationCache = object : LinkedHashMap<String, DurationCacheEntry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DurationCacheEntry>?): Boolean {
            return size > MAX_DURATION_CACHE_ENTRIES
        }
    }
    private val durationCacheLock = Any()

    fun createOutputFile(context: Context, fps: Int): File {
        val dir = FileUtils.captureCacheDir(context.applicationContext)
        val timestamp = synchronized(nameFormat) { nameFormat.format(Date()) }
        val stem = "$CACHE_PREFIX${timestamp}_fps${fps.coerceIn(1, 30)}"
        var file = File(dir, "$stem$CACHE_EXT")
        var suffix = 1
        while (file.exists()) {
            file = File(dir, "${stem}_$suffix$CACHE_EXT")
            suffix++
        }
        Log.d(TAG, "createOutputFile: ${file.name}")
        return file
    }

    fun list(context: Context): List<CaptureCacheItem> {
        val files = FileUtils.captureCacheDir(context.applicationContext).listFiles { file ->
            file.isFile && file.name.startsWith(CACHE_PREFIX) && file.name.endsWith(CACHE_EXT)
        } ?: return emptyList()
        val items = files.mapNotNull { toItem(it) }.sortedByDescending { it.createdAtMs }
        Log.d(TAG, "list: ${items.size} items")
        return items
    }

    fun delete(item: CaptureCacheItem): Boolean {
        Log.d(TAG, "delete: ${item.name}")
        synchronized(durationCacheLock) { durationCache.remove(item.file.absolutePath) }
        return !item.file.exists() || item.file.delete()
    }

    fun toItem(file: File): CaptureCacheItem? {
        if (!file.exists() || !file.isFile) return null
        val fps = fpsRegex.find(file.name)?.groupValues?.getOrNull(1)?.toIntOrNull()?.coerceIn(1, 30) ?: 0
        return CaptureCacheItem(
            file = file,
            name = file.nameWithoutExtension,
            createdAtMs = file.lastModified(),
            sizeBytes = file.length(),
            durationMs = readDurationMs(file),
            fps = fps
        )
    }

    private fun readDurationMs(file: File): Long {
        val path = file.absolutePath
        val modified = file.lastModified()
        val size = file.length()
        synchronized(durationCacheLock) {
            val cached = durationCache[path]
            if (cached != null && cached.modifiedAtMs == modified && cached.sizeBytes == size) {
                return cached.durationMs
            }
        }

        val duration = readDurationUncached(file)
        synchronized(durationCacheLock) {
            durationCache[path] = DurationCacheEntry(modified, size, duration)
        }
        return duration
    }

    private fun readDurationUncached(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            retriever.release()
        }
    }
}
