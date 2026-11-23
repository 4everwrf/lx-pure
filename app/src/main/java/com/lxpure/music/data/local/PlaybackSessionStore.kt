package com.lxpure.music.data.local

import android.content.Context
import android.util.Log
import com.lxpure.music.domain.model.PlaybackCursorSnapshot
import com.lxpure.music.domain.model.PlaybackQueueSnapshot
import com.lxpure.music.domain.model.PlaybackQueueSource
import com.lxpure.music.domain.model.RepeatMode
import com.lxpure.music.domain.model.Track
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class RestoredPlaybackSession(
    val queue: List<Track>,
    val currentIndex: Int,
    val positionMs: Long,
    val durationMs: Long,
    val repeatMode: RepeatMode,
    val queueSource: PlaybackQueueSource?,
    val activeQualityBadge: String?,
    val activeProviderName: String?
)

/**
 * Atomic, split-file persistence for the active playback queue and cursor position.
 *
 * Kept strictly separate from Room's `lx_pure.db` so database migrations or upgrades
 * can never disturb user-imported custom sources, playlists, or favorites.
 *
 * - `playback_queue_v1.json`: full track list + playlist source metadata (written only when queue mutates)
 * - `playback_cursor_v1.json`: tiny (~150B) playback index/position/repeatMode cursor (fast & low-I/O)
 */
class PlaybackSessionStore(private val baseDir: File) {

    constructor(context: Context) : this(context.filesDir)

    companion object {
        private const val TAG = "PlaybackSessionStore"
        private const val QUEUE_FILE_NAME = "playback_queue_v1.json"
        private const val CURSOR_FILE_NAME = "playback_cursor_v1.json"
        const val MAX_PERSISTED_QUEUE_SIZE = 5_000

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        /**
         * Pure helper that trims huge queues around the active track (if > [MAX_PERSISTED_QUEUE_SIZE])
         * while preserving exact relative ordering and returning the remapped index.
         */
        fun sliceQueueForPersistence(queue: List<Track>, currentIndex: Int): Pair<List<Track>, Int> {
            if (queue.isEmpty()) return emptyList<Track>() to -1
            val safeIdx = currentIndex.coerceIn(0, queue.lastIndex)
            if (queue.size <= MAX_PERSISTED_QUEUE_SIZE) return queue to safeIdx

            val half = MAX_PERSISTED_QUEUE_SIZE / 2
            var start = (safeIdx - half).coerceAtLeast(0)
            var end = start + MAX_PERSISTED_QUEUE_SIZE
            if (end > queue.size) {
                end = queue.size
                start = (end - MAX_PERSISTED_QUEUE_SIZE).coerceAtLeast(0)
            }
            return queue.subList(start, end) to (safeIdx - start)
        }

        /**
         * Pure reconciliation logic combining saved queue and cursor snapshots into a safe
         * [RestoredPlaybackSession]. Survives index drift, missing fields, and near-end timestamps.
         */
        fun resolveRestoredSession(
            queueSnapshot: PlaybackQueueSnapshot?,
            cursorSnapshot: PlaybackCursorSnapshot?
        ): RestoredPlaybackSession? {
            val queue = queueSnapshot?.queue.orEmpty()
            if (queue.isEmpty()) return null

            val cursor = cursorSnapshot
            val resolvedIndex = when {
                cursor != null &&
                    cursor.currentIndex in queue.indices &&
                    (cursor.currentTrackId.isNullOrBlank() || queue[cursor.currentIndex].id == cursor.currentTrackId) ->
                    cursor.currentIndex
                !cursor?.currentTrackId.isNullOrBlank() -> {
                    val found = queue.indexOfFirst { it.id == cursor!!.currentTrackId }
                    if (found >= 0) found else (cursor?.currentIndex ?: 0).coerceIn(0, queue.lastIndex)
                }
                cursor != null -> cursor.currentIndex.coerceIn(0, queue.lastIndex)
                else -> 0
            }

            val currentTrack = queue[resolvedIndex]
            val durationMs = maxOf(cursor?.durationMs ?: 0L, currentTrack.durationMs, 0L)
            var positionMs = (cursor?.positionMs ?: 0L).coerceAtLeast(0L)
            if (durationMs > 5_000L && positionMs >= durationMs - 1_500L) {
                positionMs = (durationMs - 2_000L).coerceAtLeast(0L)
            } else if (durationMs > 0L && positionMs > durationMs) {
                positionMs = durationMs
            }

            val repeatMode = cursor?.repeatMode?.let { modeName ->
                runCatching { RepeatMode.valueOf(modeName) }.getOrNull()
            } ?: RepeatMode.LIST_LOOP

            return RestoredPlaybackSession(
                queue = queue,
                currentIndex = resolvedIndex,
                positionMs = positionMs,
                durationMs = durationMs,
                repeatMode = repeatMode,
                queueSource = queueSnapshot?.queueSource,
                activeQualityBadge = cursor?.activeQualityBadge ?: currentTrack.bestQualityBadge,
                activeProviderName = cursor?.activeProviderName
            )
        }
    }

    private val ioLock = ReentrantLock()
    private val queueFile: File get() = File(baseDir, QUEUE_FILE_NAME)
    private val cursorFile: File get() = File(baseDir, CURSOR_FILE_NAME)

    fun saveQueueAndCursor(
        queue: List<Track>,
        queueSource: PlaybackQueueSource?,
        currentIndex: Int,
        positionMs: Long,
        durationMs: Long,
        repeatMode: RepeatMode,
        activeQualityBadge: String?,
        activeProviderName: String?
    ) {
        if (queue.isEmpty()) {
            clear()
            return
        }
        val (slicedQueue, slicedIndex) = sliceQueueForPersistence(queue, currentIndex)
        val now = System.currentTimeMillis()
        val queueSnapshot = PlaybackQueueSnapshot(
            version = 1,
            queue = slicedQueue,
            queueSource = queueSource,
            savedAt = now
        )
        val cursorSnapshot = PlaybackCursorSnapshot(
            version = 1,
            currentIndex = slicedIndex,
            currentTrackId = slicedQueue.getOrNull(slicedIndex)?.id,
            positionMs = positionMs.coerceAtLeast(0L),
            durationMs = durationMs.coerceAtLeast(0L),
            repeatMode = repeatMode.name,
            activeQualityBadge = activeQualityBadge,
            activeProviderName = activeProviderName,
            updatedAt = now
        )

        ioLock.withLock {
            writeAtomically(queueFile, json.encodeToString(queueSnapshot))
            writeAtomically(cursorFile, json.encodeToString(cursorSnapshot))
        }
    }

    fun saveCursorOnly(
        currentIndex: Int,
        currentTrackId: String?,
        positionMs: Long,
        durationMs: Long,
        repeatMode: RepeatMode,
        activeQualityBadge: String?,
        activeProviderName: String?
    ) {
        if (currentIndex < 0 || currentTrackId.isNullOrBlank()) return
        val cursorSnapshot = PlaybackCursorSnapshot(
            version = 1,
            currentIndex = currentIndex,
            currentTrackId = currentTrackId,
            positionMs = positionMs.coerceAtLeast(0L),
            durationMs = durationMs.coerceAtLeast(0L),
            repeatMode = repeatMode.name,
            activeQualityBadge = activeQualityBadge,
            activeProviderName = activeProviderName,
            updatedAt = System.currentTimeMillis()
        )
        ioLock.withLock {
            writeAtomically(cursorFile, json.encodeToString(cursorSnapshot))
        }
    }

    fun loadSession(): RestoredPlaybackSession? = ioLock.withLock {
        val queueSnapshot = readJson<PlaybackQueueSnapshot>(queueFile)
        val cursorSnapshot = readJson<PlaybackCursorSnapshot>(cursorFile)
        resolveRestoredSession(queueSnapshot, cursorSnapshot)
    }

    fun clear() = ioLock.withLock {
        runCatching { if (queueFile.exists()) queueFile.delete() }
        runCatching { if (cursorFile.exists()) cursorFile.delete() }
    }

    private inline fun <reified T> readJson(file: File): T? {
        if (!file.exists() || file.length() <= 0L) return null
        return runCatching {
            json.decodeFromString<T>(file.readText(Charsets.UTF_8))
        }.onFailure { err ->
            Log.w(TAG, "Failed to decode ${file.name}: ${err.message}")
        }.getOrNull()
    }

    private fun writeAtomically(target: File, content: String) {
        runCatching {
            target.parentFile?.mkdirs()
            val temp = File(target.parentFile, "${target.name}.tmp")
            temp.writeText(content, Charsets.UTF_8)
            if (!temp.renameTo(target)) {
                target.writeText(content, Charsets.UTF_8)
                temp.delete()
            }
        }.onFailure { err ->
            Log.w(TAG, "Failed to persist ${target.name}: ${err.message}")
        }
    }
}
