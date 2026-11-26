package com.lxpure.music.core.player

import com.lxpure.music.domain.model.RepeatMode
import kotlin.random.Random

/**
 * Deterministic outcome of a previous/next/auto-advance navigation action across all [RepeatMode]s.
 */
sealed class QueueNavigationOutcome {
    data class SwitchTo(val targetIndex: Int) : QueueNavigationOutcome()
    data class RestartCurrent(val message: String? = null) : QueueNavigationOutcome()
    data class StayAtBoundary(val message: String) : QueueNavigationOutcome()
    data class StopAtEnd(val message: String? = null) : QueueNavigationOutcome()
    data object NoOp : QueueNavigationOutcome()
}

/**
 * Pure playback queue navigation state machine shared by MiniPlayer, FullPlayer,
 * MediaSession / lock-screen controls, and gapless preloading.
 *
 * Guarantees consistent behavior across all 4 playback modes:
 * - [RepeatMode.LIST_LOOP]: wraps around at both ends (`last -> 0`, `0 -> last`).
 * - [RepeatMode.SEQUENTIAL]: strict boundaries (`0` has no previous -> restart + toast;
 *   `last` has no next -> manual stay + toast, auto-advance stops cleanly at end).
 * - [RepeatMode.SHUFFLE]: Fisher-Yates no-repeat shuffle deck with bounded history so
 *   Previous steps back through actually played tracks, Next replays forward history before
 *   drawing new shuffled tracks, and cycle rollovers never repeat the current track back-to-back.
 * - [RepeatMode.SINGLE_LOOP]: auto-advance loops current track; manual Previous/Next switches
 *   tracks when `queueSize > 1`, or restarts current track when `queueSize == 1`.
 */
class QueueNavigator(
    private val random: Random = Random.Default
) {
    companion object {
        const val MAX_SHUFFLE_HISTORY_BEHIND = 200
    }

    private val shuffleTimeline = ArrayList<Int>()
    private var shuffleCursor: Int = -1

    internal val shuffleHistoryDepth: Int
        get() = if (shuffleCursor > 0) shuffleCursor else 0

    fun reset() {
        shuffleTimeline.clear()
        shuffleCursor = -1
    }

    fun onQueueReplaced(queueSize: Int, startIndex: Int, mode: RepeatMode) {
        reset()
        if (mode == RepeatMode.SHUFFLE && queueSize > 0 && startIndex in 0 until queueSize) {
            buildFreshShuffleTimeline(queueSize, startIndex)
        }
    }

    fun onModeChanged(queueSize: Int, currentIndex: Int, newMode: RepeatMode) {
        if (newMode == RepeatMode.SHUFFLE && queueSize > 0 && currentIndex in 0 until queueSize) {
            if (isShuffleTimelineValid(queueSize) && shuffleTimeline[shuffleCursor] == currentIndex) {
                return
            }
            buildFreshShuffleTimeline(queueSize, currentIndex)
        } else {
            reset()
        }
    }

    fun onManualSelectIndex(queueSize: Int, targetIndex: Int, mode: RepeatMode) {
        if (mode != RepeatMode.SHUFFLE) return
        if (queueSize <= 0 || targetIndex !in 0 until queueSize) {
            reset()
            return
        }
        if (!isShuffleTimelineValid(queueSize)) {
            buildFreshShuffleTimeline(queueSize, targetIndex)
            return
        }
        if (shuffleTimeline[shuffleCursor] == targetIndex) return

        // Preserve played history up to current cursor, place manually chosen track next,
        // and rebuild the upcoming unplayed pool from remaining indices so no track is dropped.
        val historyPrefix = ArrayList<Int>(shuffleCursor + 2)
        for (i in 0..shuffleCursor) {
            historyPrefix.add(shuffleTimeline[i])
        }
        historyPrefix.add(targetIndex)
        val upcoming = buildShuffledCycle(queueSize, excludeIndex = targetIndex)
        shuffleTimeline.clear()
        shuffleTimeline.addAll(historyPrefix)
        shuffleTimeline.addAll(upcoming)
        shuffleCursor = historyPrefix.lastIndex
        trimHistoryIfNeeded()
    }

    fun onPromotedPreloadedNext(queueSize: Int, promotedIndex: Int, mode: RepeatMode) {
        if (mode != RepeatMode.SHUFFLE) return
        if (queueSize <= 0 || promotedIndex !in 0 until queueSize) {
            reset()
            return
        }
        if (isShuffleTimelineValid(queueSize) &&
            shuffleCursor + 1 < shuffleTimeline.size &&
            shuffleTimeline[shuffleCursor + 1] == promotedIndex
        ) {
            shuffleCursor++
            trimHistoryIfNeeded()
        } else {
            onManualSelectIndex(queueSize, promotedIndex, mode)
        }
    }

    fun onTrackInsertedAsPlayNext(
        oldRemovedIndex: Int?,
        insertedIndex: Int,
        newCurrentIndex: Int,
        newQueueSize: Int,
        mode: RepeatMode
    ) {
        if (mode != RepeatMode.SHUFFLE) return
        if (newQueueSize <= 0 || newCurrentIndex !in 0 until newQueueSize || insertedIndex !in 0 until newQueueSize) {
            reset()
            return
        }
        if (shuffleTimeline.isEmpty() ||
            shuffleCursor !in shuffleTimeline.indices ||
            (oldRemovedIndex != null && oldRemovedIndex !in 0 until newQueueSize)
        ) {
            buildFreshShuffleTimeline(newQueueSize, newCurrentIndex)
            placePlayNextInShuffle(insertedIndex)
            return
        }

        // 1. Remap existing indices through removal (if moved) and insertion.
        // For history entries before shuffleCursor, remap moved track to insertedIndex so back-history stays intact.
        val remapped = ArrayList<Int>(shuffleTimeline.size + 1)
        var newCursor = shuffleCursor
        for (i in shuffleTimeline.indices) {
            var idx = shuffleTimeline[i]
            if (oldRemovedIndex != null) {
                if (idx == oldRemovedIndex) {
                    if (i < shuffleCursor) {
                        remapped.add(insertedIndex)
                    } else if (i == shuffleCursor) {
                        newCursor--
                    }
                    continue
                }
                if (idx > oldRemovedIndex) idx--
            }
            if (idx >= insertedIndex) idx++
            if (idx in 0 until newQueueSize) {
                remapped.add(idx)
            } else if (i <= shuffleCursor) {
                newCursor--
            }
        }

        shuffleTimeline.clear()
        shuffleTimeline.addAll(remapped)
        shuffleCursor = newCursor
        collapseAdjacentDuplicates()

        if (shuffleCursor !in shuffleTimeline.indices || shuffleTimeline[shuffleCursor] != newCurrentIndex) {
            buildFreshShuffleTimeline(newQueueSize, newCurrentIndex)
        }
        placePlayNextInShuffle(insertedIndex)
    }

    fun onTrackAppendedToQueue(
        appendedIndex: Int,
        currentIndex: Int,
        newQueueSize: Int,
        mode: RepeatMode
    ) {
        if (mode != RepeatMode.SHUFFLE) return
        if (newQueueSize <= 0 || currentIndex !in 0 until newQueueSize || appendedIndex !in 0 until newQueueSize) {
            reset()
            return
        }
        if (newQueueSize <= 1 ||
            appendedIndex == currentIndex ||
            !isShuffleTimelineValid(newQueueSize) ||
            shuffleTimeline[shuffleCursor] != currentIndex ||
            shuffleTimeline.contains(appendedIndex)
        ) {
            buildFreshShuffleTimeline(newQueueSize, currentIndex)
            return
        }
        val insertPos = if (shuffleCursor + 1 >= shuffleTimeline.size) {
            shuffleTimeline.size
        } else {
            random.nextInt(shuffleCursor + 1, shuffleTimeline.size + 1)
        }
        shuffleTimeline.add(insertPos, appendedIndex)
    }

    fun onTrackRemovedFromQueue(
        removedIndex: Int,
        newCurrentIndex: Int,
        newQueueSize: Int,
        mode: RepeatMode
    ) {
        if (newQueueSize <= 0 || newCurrentIndex !in 0 until newQueueSize) {
            reset()
            return
        }
        if (mode != RepeatMode.SHUFFLE) return
        if (removedIndex !in 0..newQueueSize ||
            shuffleTimeline.isEmpty() ||
            shuffleCursor !in shuffleTimeline.indices
        ) {
            buildFreshShuffleTimeline(newQueueSize, newCurrentIndex)
            return
        }

        val remapped = ArrayList<Int>(shuffleTimeline.size)
        var newCursor = shuffleCursor
        for (i in shuffleTimeline.indices) {
            val idx = shuffleTimeline[i]
            if (idx == removedIndex) {
                if (i <= shuffleCursor) newCursor--
            } else {
                val mapped = if (idx > removedIndex) idx - 1 else idx
                if (mapped in 0 until newQueueSize) {
                    remapped.add(mapped)
                } else if (i <= shuffleCursor) {
                    newCursor--
                }
            }
        }

        shuffleTimeline.clear()
        shuffleTimeline.addAll(remapped)
        shuffleCursor = newCursor
        collapseAdjacentDuplicates()

        if (shuffleTimeline.isEmpty() || shuffleCursor < 0) {
            // Either timeline became empty or cursor 0 (first played track with no prior history) was removed
            buildFreshShuffleTimeline(newQueueSize, newCurrentIndex)
            return
        }

        if (shuffleCursor > shuffleTimeline.lastIndex) {
            shuffleCursor = shuffleTimeline.lastIndex
        }
        if (shuffleTimeline[shuffleCursor] != newCurrentIndex) {
            // Current track was removed and replaced by newCurrentIndex: keep played history up to
            // shuffleCursor, then place newCurrentIndex next and rebuild the remaining shuffle pool.
            val preservedHistory = ArrayList<Int>(shuffleCursor + 2)
            for (i in 0..shuffleCursor) {
                preservedHistory.add(shuffleTimeline[i])
            }
            if (preservedHistory.lastOrNull() != newCurrentIndex) {
                preservedHistory.add(newCurrentIndex)
            }
            val upcoming = buildShuffledCycle(newQueueSize, excludeIndex = newCurrentIndex)
            shuffleTimeline.clear()
            shuffleTimeline.addAll(preservedHistory)
            shuffleTimeline.addAll(upcoming)
            shuffleCursor = preservedHistory.lastIndex
            collapseAdjacentDuplicates()
            trimHistoryIfNeeded()
        }
    }

    fun canSkipPrevious(queueSize: Int, currentIndex: Int, mode: RepeatMode): Boolean {
        if (queueSize <= 1 || currentIndex !in 0 until queueSize) return false
        return when (mode) {
            RepeatMode.SEQUENTIAL -> currentIndex > 0
            RepeatMode.SHUFFLE -> isShuffleTimelineValid(queueSize) &&
                shuffleTimeline[shuffleCursor] == currentIndex &&
                shuffleCursor > 0
            RepeatMode.LIST_LOOP, RepeatMode.SINGLE_LOOP -> true
        }
    }

    fun canSkipNext(queueSize: Int, currentIndex: Int, mode: RepeatMode): Boolean {
        if (queueSize <= 1 || currentIndex !in 0 until queueSize) return false
        return when (mode) {
            RepeatMode.SEQUENTIAL -> currentIndex < queueSize - 1
            RepeatMode.SHUFFLE, RepeatMode.LIST_LOOP, RepeatMode.SINGLE_LOOP -> true
        }
    }

    fun willAutoAdvanceOnEnd(queueSize: Int, currentIndex: Int, mode: RepeatMode): Boolean {
        if (queueSize <= 0 || currentIndex !in 0 until queueSize) return false
        return when (mode) {
            RepeatMode.SEQUENTIAL -> currentIndex < queueSize - 1
            RepeatMode.LIST_LOOP, RepeatMode.SHUFFLE, RepeatMode.SINGLE_LOOP -> true
        }
    }

    /**
     * Peeks the next queue index for gapless preloading without advancing [shuffleCursor].
     * Returns `null` when no different track should be preloaded (e.g. SINGLE_LOOP, single-track queue,
     * or last track in SEQUENTIAL mode).
     */
    fun peekNextIndexForAutoAdvance(queueSize: Int, currentIndex: Int, mode: RepeatMode): Int? {
        if (queueSize <= 1 || currentIndex !in 0 until queueSize) return null
        return when (mode) {
            RepeatMode.SINGLE_LOOP -> null
            RepeatMode.SEQUENTIAL -> if (currentIndex + 1 < queueSize) currentIndex + 1 else null
            RepeatMode.LIST_LOOP -> (currentIndex + 1) % queueSize
            RepeatMode.SHUFFLE -> {
                ensureUpcomingShuffleItem(queueSize, currentIndex)
                shuffleTimeline.getOrNull(shuffleCursor + 1)
            }
        }
    }

    fun navigateNext(
        queueSize: Int,
        currentIndex: Int,
        mode: RepeatMode,
        isAutoAdvance: Boolean
    ): QueueNavigationOutcome {
        if (queueSize <= 0 || currentIndex !in 0 until queueSize) {
            return QueueNavigationOutcome.NoOp
        }

        if (queueSize == 1) {
            return when (mode) {
                RepeatMode.SEQUENTIAL -> if (isAutoAdvance) {
                    QueueNavigationOutcome.StopAtEnd("顺序播放已结束")
                } else {
                    QueueNavigationOutcome.StayAtBoundary("已是最后一首")
                }
                RepeatMode.SINGLE_LOOP, RepeatMode.LIST_LOOP, RepeatMode.SHUFFLE -> if (isAutoAdvance) {
                    QueueNavigationOutcome.RestartCurrent(null)
                } else {
                    QueueNavigationOutcome.RestartCurrent("当前队列仅 1 首，已重新播放")
                }
            }
        }

        return when (mode) {
            RepeatMode.SEQUENTIAL -> {
                if (currentIndex + 1 < queueSize) {
                    QueueNavigationOutcome.SwitchTo(currentIndex + 1)
                } else if (isAutoAdvance) {
                    QueueNavigationOutcome.StopAtEnd("顺序播放已结束")
                } else {
                    QueueNavigationOutcome.StayAtBoundary("已是最后一首")
                }
            }
            RepeatMode.LIST_LOOP -> {
                QueueNavigationOutcome.SwitchTo((currentIndex + 1) % queueSize)
            }
            RepeatMode.SINGLE_LOOP -> {
                if (isAutoAdvance) {
                    QueueNavigationOutcome.RestartCurrent(null)
                } else {
                    QueueNavigationOutcome.SwitchTo((currentIndex + 1) % queueSize)
                }
            }
            RepeatMode.SHUFFLE -> {
                ensureUpcomingShuffleItem(queueSize, currentIndex)
                if (shuffleCursor + 1 < shuffleTimeline.size) {
                    shuffleCursor++
                    val target = shuffleTimeline[shuffleCursor]
                    trimHistoryIfNeeded()
                    QueueNavigationOutcome.SwitchTo(target)
                } else {
                    QueueNavigationOutcome.RestartCurrent(null)
                }
            }
        }
    }

    fun navigatePrevious(
        queueSize: Int,
        currentIndex: Int,
        mode: RepeatMode,
        @Suppress("UNUSED_PARAMETER") positionMs: Long = 0L
    ): QueueNavigationOutcome {
        if (queueSize <= 0 || currentIndex !in 0 until queueSize) {
            return QueueNavigationOutcome.NoOp
        }

        if (queueSize == 1) {
            val msg = if (mode == RepeatMode.SEQUENTIAL) {
                "已是第一首，已重新播放"
            } else {
                "当前队列仅 1 首，已重新播放"
            }
            return QueueNavigationOutcome.RestartCurrent(msg)
        }

        return when (mode) {
            RepeatMode.SEQUENTIAL -> {
                if (currentIndex > 0) {
                    QueueNavigationOutcome.SwitchTo(currentIndex - 1)
                } else {
                    QueueNavigationOutcome.RestartCurrent("已是第一首，已重新播放")
                }
            }
            RepeatMode.LIST_LOOP, RepeatMode.SINGLE_LOOP -> {
                val prevIdx = if (currentIndex <= 0) queueSize - 1 else currentIndex - 1
                QueueNavigationOutcome.SwitchTo(prevIdx)
            }
            RepeatMode.SHUFFLE -> {
                ensureShuffleTimeline(queueSize, currentIndex)
                if (shuffleCursor > 0) {
                    shuffleCursor--
                    QueueNavigationOutcome.SwitchTo(shuffleTimeline[shuffleCursor])
                } else {
                    QueueNavigationOutcome.RestartCurrent("已回到随机播放起点，已重新播放")
                }
            }
        }
    }

    private fun isShuffleTimelineValid(queueSize: Int): Boolean {
        if (shuffleTimeline.isEmpty() || shuffleCursor !in shuffleTimeline.indices) return false
        for (idx in shuffleTimeline) {
            if (idx !in 0 until queueSize) return false
        }
        return true
    }

    private fun ensureShuffleTimeline(queueSize: Int, currentIndex: Int) {
        if (isShuffleTimelineValid(queueSize) && shuffleTimeline[shuffleCursor] == currentIndex) return
        buildFreshShuffleTimeline(queueSize, currentIndex)
    }

    private fun ensureUpcomingShuffleItem(queueSize: Int, currentIndex: Int) {
        ensureShuffleTimeline(queueSize, currentIndex)
        if (queueSize <= 1) return
        if (shuffleCursor + 1 >= shuffleTimeline.size) {
            val nextCycle = buildShuffledCycle(queueSize, excludeFirst = currentIndex)
            shuffleTimeline.addAll(nextCycle)
            trimHistoryIfNeeded()
        }
    }

    private fun buildFreshShuffleTimeline(queueSize: Int, startIndex: Int) {
        shuffleTimeline.clear()
        shuffleTimeline.add(startIndex)
        if (queueSize > 1) {
            shuffleTimeline.addAll(buildShuffledCycle(queueSize, excludeIndex = startIndex))
        }
        shuffleCursor = 0
    }

    /**
     * Generates a Fisher-Yates permutation of `0 until queueSize`.
     * - When [excludeIndex] is set, omits that index (used for the remainder of the initial cycle).
     * - When [excludeFirst] is set (for full subsequent cycles), guarantees the cycle's first
     *   track never repeats [excludeFirst] back-to-back.
     */
    private fun buildShuffledCycle(
        queueSize: Int,
        excludeIndex: Int? = null,
        excludeFirst: Int? = null
    ): List<Int> {
        val list = ArrayList<Int>(queueSize)
        for (i in 0 until queueSize) {
            if (i != excludeIndex) list.add(i)
        }
        for (i in list.lastIndex downTo 1) {
            val j = random.nextInt(i + 1)
            val tmp = list[i]
            list[i] = list[j]
            list[j] = tmp
        }
        if (excludeFirst != null && list.size > 1 && list[0] == excludeFirst) {
            val swapIdx = random.nextInt(1, list.size)
            val tmp = list[0]
            list[0] = list[swapIdx]
            list[swapIdx] = tmp
        }
        return list
    }

    private fun placePlayNextInShuffle(insertedIndex: Int) {
        if (shuffleCursor !in shuffleTimeline.indices) return
        // Remove any upcoming occurrence of insertedIndex so it doesn't play twice in this cycle
        var i = shuffleTimeline.lastIndex
        while (i > shuffleCursor) {
            if (shuffleTimeline[i] == insertedIndex) {
                shuffleTimeline.removeAt(i)
            }
            i--
        }
        if (shuffleTimeline[shuffleCursor] != insertedIndex) {
            shuffleTimeline.add(shuffleCursor + 1, insertedIndex)
        }
    }

    private fun collapseAdjacentDuplicates() {
        if (shuffleTimeline.size <= 1) return
        val collapsed = ArrayList<Int>(shuffleTimeline.size)
        var newCursor = shuffleCursor
        for (i in shuffleTimeline.indices) {
            val v = shuffleTimeline[i]
            if (collapsed.isNotEmpty() && collapsed.last() == v) {
                if (i <= shuffleCursor) newCursor--
            } else {
                collapsed.add(v)
            }
        }
        shuffleTimeline.clear()
        shuffleTimeline.addAll(collapsed)
        shuffleCursor = newCursor.coerceIn(-1, shuffleTimeline.lastIndex)
    }

    private fun trimHistoryIfNeeded() {
        if (shuffleCursor > MAX_SHUFFLE_HISTORY_BEHIND) {
            val trimCount = shuffleCursor - MAX_SHUFFLE_HISTORY_BEHIND
            shuffleTimeline.subList(0, trimCount).clear()
            shuffleCursor -= trimCount
        }
    }
}
