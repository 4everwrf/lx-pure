package com.lxpure.music.data.local

import android.content.Context
import android.util.Log
import com.lxpure.music.domain.model.PlaybackCursorSnapshot
import com.lxpure.music.domain.model.PlaybackQueueSnapshot
import com.lxpure.music.domain.model.PlaybackQueueSource
import com.lxpure.music.domain.model.RepeatMode
import com.lxpure.music.domain.model.Track
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicLong
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
            coerceInputValues = true
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
            val trackMatched = cursor == null ||
                cursor.currentTrackId.isNullOrBlank() ||
                currentTrack.id == cursor.currentTrackId

            val durationMs = if (trackMatched) {
                maxOf(cursor?.durationMs ?: 0L, currentTrack.durationMs, 0L)
            } else {
                currentTrack.durationMs.coerceAtLeast(0L)
            }
            var positionMs = if (trackMatched) {
                (cursor?.positionMs ?: 0L).coerceAtLeast(0L)
            } else {
                0L
            }
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
                activeQualityBadge = if (trackMatched) {
                    cursor?.activeQualityBadge ?: currentTrack.bestQualityBadge
                } else {
                    currentTrack.bestQualityBadge
                },
                activeProviderName = if (trackMatched) cursor?.activeProviderName else null
            )
        }
    }

    private val queueLock = ReentrantLock()
    private val cursorLock = ReentrantLock()
    private val queueWriteGen = AtomicLong(0L)
    private val cursorWriteGen = AtomicLong(0L)
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
        val qGen = queueWriteGen.incrementAndGet()
        val cGen = cursorWriteGen.incrementAndGet()
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

        queueLock.withLock {
            if (queueWriteGen.get() == qGen) {
                writeJsonAtomically(queueFile, queueSnapshot)
            }
        }
        cursorLock.withLock {
            if (cursorWriteGen.get() == cGen) {
                writeJsonAtomically(cursorFile, cursorSnapshot)
            }
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
        val cGen = cursorWriteGen.incrementAndGet()
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
        cursorLock.withLock {
            if (cursorWriteGen.get() == cGen) {
                writeJsonAtomically(cursorFile, cursorSnapshot)
            }
        }
    }

    fun loadSession(): RestoredPlaybackSession? = queueLock.withLock {
        cursorLock.withLock {
            val queueSnapshot = readJson<PlaybackQueueSnapshot>(queueFile)
            val cursorSnapshot = readJson<PlaybackCursorSnapshot>(cursorFile)
            resolveRestoredSession(queueSnapshot, cursorSnapshot)
        }
    }

    fun clear() {
        queueWriteGen.incrementAndGet()
        cursorWriteGen.incrementAndGet()
        queueLock.withLock {
            cursorLock.withLock {
                runCatching { if (queueFile.exists()) queueFile.delete() }
                runCatching { if (cursorFile.exists()) cursorFile.delete() }
            }
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private inline fun <reified T> readJson(file: File): T? {
        if (!file.exists() || file.length() <= 0L) return null
        return runCatching {
            BufferedInputStream(file.inputStream(), 16 * 1024).use { input ->
                json.decodeFromStream<T>(input)
            }
        }.onFailure { err ->
            Log.w(TAG, "Failed to decode ${file.name}: ${err.message}")
        }.getOrNull()
    }

    @OptIn(ExperimentalSerializationApi::class)
    private inline fun <reified T> writeJsonAtomically(target: File, value: T) {
        val temp = File(target.parentFile, "${target.name}.tmp")
        runCatching {
            target.parentFile?.mkdirs()
            BufferedOutputStream(temp.outputStream(), 16 * 1024).use { output ->
                json.encodeToStream(value, output)
            }
            if (!temp.renameTo(target)) {
                BufferedOutputStream(target.outputStream(), 16 * 1024).use { output ->
                    json.encodeToStream(value, output)
                }
            }
        }.onFailure { err ->
            Log.w(TAG, "Failed to persist ${target.name}: ${err.message}")
        }
        runCatching {
            if (temp.exists()) temp.delete()
        }
    }
}
