package com.lxpure.music

import com.lxpure.music.core.player.QueueNavigationOutcome
import com.lxpure.music.core.player.QueueNavigator
import com.lxpure.music.domain.model.RepeatMode
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class QueueNavigationTest {

    @Test
    fun repeatModeFourModeCycleAndSerialization() {
        assertEquals(RepeatMode.SEQUENTIAL, RepeatMode.LIST_LOOP.next())
        assertEquals(RepeatMode.SHUFFLE, RepeatMode.SEQUENTIAL.next())
        assertEquals(RepeatMode.SINGLE_LOOP, RepeatMode.SHUFFLE.next())
        assertEquals(RepeatMode.LIST_LOOP, RepeatMode.SINGLE_LOOP.next())
        assertEquals("顺序播放", RepeatMode.SEQUENTIAL.label)

        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        for (mode in RepeatMode.entries) {
            val encoded = json.encodeToString(mode)
            val decoded = json.decodeFromString<RepeatMode>(encoded)
            assertEquals(mode, decoded)
        }
    }

    @Test
    fun sequentialModeBoundariesAndAutoAdvance() {
        val nav = QueueNavigator(Random(42))
        nav.onQueueReplaced(queueSize = 3, startIndex = 0, mode = RepeatMode.SEQUENTIAL)

        // At index 0: no previous, has next
        assertFalse(nav.canSkipPrevious(3, 0, RepeatMode.SEQUENTIAL))
        assertTrue(nav.canSkipNext(3, 0, RepeatMode.SEQUENTIAL))
        assertTrue(nav.willAutoAdvanceOnEnd(3, 0, RepeatMode.SEQUENTIAL))
        assertEquals(1, nav.peekNextIndexForAutoAdvance(3, 0, RepeatMode.SEQUENTIAL))

        // Previous at index 0: >5s rewinds silently, <=5s restarts with boundary toast
        assertEquals(
            QueueNavigationOutcome.RestartCurrent(null),
            nav.navigatePrevious(3, 0, RepeatMode.SEQUENTIAL, positionMs = 5_001L)
        )
        assertEquals(
            QueueNavigationOutcome.RestartCurrent("已是第一首，已重新播放"),
            nav.navigatePrevious(3, 0, RepeatMode.SEQUENTIAL, positionMs = 5_000L)
        )
        assertEquals(
            QueueNavigationOutcome.RestartCurrent("已是第一首，已重新播放"),
            nav.navigatePrevious(3, 0, RepeatMode.SEQUENTIAL, positionMs = 0L)
        )

        // Advance 0 -> 1 -> 2
        assertEquals(
            QueueNavigationOutcome.SwitchTo(1),
            nav.navigateNext(3, 0, RepeatMode.SEQUENTIAL, isAutoAdvance = false)
        )
        assertTrue(nav.canSkipPrevious(3, 1, RepeatMode.SEQUENTIAL))
        assertTrue(nav.canSkipNext(3, 1, RepeatMode.SEQUENTIAL))
        assertEquals(
            QueueNavigationOutcome.SwitchTo(0),
            nav.navigatePrevious(3, 1, RepeatMode.SEQUENTIAL, positionMs = 1_500L)
        )
        assertEquals(
            QueueNavigationOutcome.SwitchTo(2),
            nav.navigateNext(3, 1, RepeatMode.SEQUENTIAL, isAutoAdvance = true)
        )

        // At last track (index 2): no next, manual stays, auto-advance stops cleanly
        assertTrue(nav.canSkipPrevious(3, 2, RepeatMode.SEQUENTIAL))
        assertFalse(nav.canSkipNext(3, 2, RepeatMode.SEQUENTIAL))
        assertFalse(nav.willAutoAdvanceOnEnd(3, 2, RepeatMode.SEQUENTIAL))
        assertNull(nav.peekNextIndexForAutoAdvance(3, 2, RepeatMode.SEQUENTIAL))

        assertEquals(
            QueueNavigationOutcome.StayAtBoundary("已是最后一首"),
            nav.navigateNext(3, 2, RepeatMode.SEQUENTIAL, isAutoAdvance = false)
        )
        assertEquals(
            QueueNavigationOutcome.StopAtEnd("顺序播放已结束"),
            nav.navigateNext(3, 2, RepeatMode.SEQUENTIAL, isAutoAdvance = true)
        )
        // After stopAtEnd resets pos to 0, Previous goes cleanly back to index 1
        assertEquals(
            QueueNavigationOutcome.SwitchTo(1),
            nav.navigatePrevious(3, 2, RepeatMode.SEQUENTIAL, positionMs = 0L)
        )
    }

    @Test
    fun listLoopAndSingleLoopWrapAndRestartSemantics() {
        val nav = QueueNavigator(Random(7))

        // LIST_LOOP wraps both ends
        assertTrue(nav.canSkipPrevious(4, 0, RepeatMode.LIST_LOOP))
        assertTrue(nav.canSkipNext(4, 3, RepeatMode.LIST_LOOP))
        assertTrue(nav.willAutoAdvanceOnEnd(4, 3, RepeatMode.LIST_LOOP))
        assertEquals(0, nav.peekNextIndexForAutoAdvance(4, 3, RepeatMode.LIST_LOOP))
        assertEquals(
            QueueNavigationOutcome.SwitchTo(3),
            nav.navigatePrevious(4, 0, RepeatMode.LIST_LOOP, positionMs = 2_000L)
        )
        assertEquals(
            QueueNavigationOutcome.SwitchTo(0),
            nav.navigateNext(4, 3, RepeatMode.LIST_LOOP, isAutoAdvance = false)
        )
        assertEquals(
            QueueNavigationOutcome.SwitchTo(0),
            nav.navigateNext(4, 3, RepeatMode.LIST_LOOP, isAutoAdvance = true)
        )

        // SINGLE_LOOP loops current on auto-advance, switches manually when queueSize > 1
        assertTrue(nav.canSkipPrevious(4, 0, RepeatMode.SINGLE_LOOP))
        assertTrue(nav.canSkipNext(4, 3, RepeatMode.SINGLE_LOOP))
        assertTrue(nav.willAutoAdvanceOnEnd(4, 2, RepeatMode.SINGLE_LOOP))
        assertNull(nav.peekNextIndexForAutoAdvance(4, 2, RepeatMode.SINGLE_LOOP))
        assertEquals(
            QueueNavigationOutcome.RestartCurrent(null),
            nav.navigateNext(4, 2, RepeatMode.SINGLE_LOOP, isAutoAdvance = true)
        )
        assertEquals(
            QueueNavigationOutcome.SwitchTo(3),
            nav.navigateNext(4, 2, RepeatMode.SINGLE_LOOP, isAutoAdvance = false)
        )
        assertEquals(
            QueueNavigationOutcome.SwitchTo(0),
            nav.navigateNext(4, 3, RepeatMode.SINGLE_LOOP, isAutoAdvance = false)
        )
        assertEquals(
            QueueNavigationOutcome.SwitchTo(3),
            nav.navigatePrevious(4, 0, RepeatMode.SINGLE_LOOP, positionMs = 0L)
        )
    }

    @Test
    fun singleTrackQueueAcrossAllModes() {
        val nav = QueueNavigator(Random(99))
        for (mode in RepeatMode.entries) {
            nav.onQueueReplaced(queueSize = 1, startIndex = 0, mode = mode)
            assertFalse("canSkipPrevious for $mode", nav.canSkipPrevious(1, 0, mode))
            assertFalse("canSkipNext for $mode", nav.canSkipNext(1, 0, mode))
            assertNull("peek for $mode", nav.peekNextIndexForAutoAdvance(1, 0, mode))
            assertEquals(mode != RepeatMode.SEQUENTIAL, nav.willAutoAdvanceOnEnd(1, 0, mode))

            // Previous >5s rewinds silently; <=5s restarts with message
            assertEquals(
                QueueNavigationOutcome.RestartCurrent(null),
                nav.navigatePrevious(1, 0, mode, positionMs = 12_000L)
            )
            val prevOutcome = nav.navigatePrevious(1, 0, mode, positionMs = 1_000L)
            assertTrue(prevOutcome is QueueNavigationOutcome.RestartCurrent)
            assertTrue((prevOutcome as QueueNavigationOutcome.RestartCurrent).message != null)

            // Next manual & auto
            if (mode == RepeatMode.SEQUENTIAL) {
                assertEquals(
                    QueueNavigationOutcome.StayAtBoundary("已是最后一首"),
                    nav.navigateNext(1, 0, mode, isAutoAdvance = false)
                )
                assertEquals(
                    QueueNavigationOutcome.StopAtEnd("顺序播放已结束"),
                    nav.navigateNext(1, 0, mode, isAutoAdvance = true)
                )
            } else {
                assertEquals(
                    QueueNavigationOutcome.RestartCurrent("当前队列仅 1 首，已重新播放"),
                    nav.navigateNext(1, 0, mode, isAutoAdvance = false)
                )
                assertEquals(
                    QueueNavigationOutcome.RestartCurrent(null),
                    nav.navigateNext(1, 0, mode, isAutoAdvance = true)
                )
            }
        }
    }

    @Test
    fun shuffleNoRepeatDeckHistoryRolloverAndPreloadSync() {
        val nav = QueueNavigator(Random(12345))
        val queueSize = 8
        var cur = 2
        nav.onQueueReplaced(queueSize = queueSize, startIndex = cur, mode = RepeatMode.SHUFFLE)

        // Start of shuffle: cannot skip previous yet, can skip next
        assertFalse(nav.canSkipPrevious(queueSize, cur, RepeatMode.SHUFFLE))
        assertTrue(nav.canSkipNext(queueSize, cur, RepeatMode.SHUFFLE))
        assertEquals(
            QueueNavigationOutcome.RestartCurrent("已回到随机播放起点，已重新播放"),
            nav.navigatePrevious(queueSize, cur, RepeatMode.SHUFFLE, positionMs = 0L)
        )

        // First full cycle visits every index 0..7 exactly once without repeats
        val firstCycle = mutableListOf(cur)
        repeat(queueSize - 1) {
            val peeked = nav.peekNextIndexForAutoAdvance(queueSize, cur, RepeatMode.SHUFFLE)
            val out = nav.navigateNext(queueSize, cur, RepeatMode.SHUFFLE, isAutoAdvance = false)
            assertTrue(out is QueueNavigationOutcome.SwitchTo)
            val nextIdx = (out as QueueNavigationOutcome.SwitchTo).targetIndex
            assertEquals("peek must match actual navigateNext", peeked, nextIdx)
            assertNotEquals("adjacent tracks must differ", cur, nextIdx)
            cur = nextIdx
            firstCycle.add(cur)
        }
        assertEquals((0 until queueSize).toSet(), firstCycle.toSet())
        assertEquals(queueSize, firstCycle.size)

        // Rollover into second cycle never repeats last track of first cycle back-to-back
        val rolloverOut = nav.navigateNext(queueSize, cur, RepeatMode.SHUFFLE, isAutoAdvance = true)
        val cycle2First = (rolloverOut as QueueNavigationOutcome.SwitchTo).targetIndex
        assertNotEquals(cur, cycle2First)
        cur = cycle2First

        // Step backwards through played history faithfully
        assertTrue(nav.canSkipPrevious(queueSize, cur, RepeatMode.SHUFFLE))
        val backOut1 = nav.navigatePrevious(queueSize, cur, RepeatMode.SHUFFLE, positionMs = 500L)
        assertEquals(QueueNavigationOutcome.SwitchTo(firstCycle.last()), backOut1)
        cur = firstCycle.last()

        val backOut2 = nav.navigatePrevious(queueSize, cur, RepeatMode.SHUFFLE, positionMs = 500L)
        assertEquals(QueueNavigationOutcome.SwitchTo(firstCycle[firstCycle.size - 2]), backOut2)
        cur = firstCycle[firstCycle.size - 2]

        // Stepping forward replays history before drawing new tracks
        assertEquals(
            QueueNavigationOutcome.SwitchTo(firstCycle.last()),
            nav.navigateNext(queueSize, cur, RepeatMode.SHUFFLE, isAutoAdvance = false)
        )
        cur = firstCycle.last()

        // Gapless preload promotion keeps shuffleCursor in sync
        val peekForPreload = nav.peekNextIndexForAutoAdvance(queueSize, cur, RepeatMode.SHUFFLE)
        assertEquals(cycle2First, peekForPreload)
        nav.onPromotedPreloadedNext(queueSize, cycle2First, RepeatMode.SHUFFLE)
        cur = cycle2First
        assertEquals(
            QueueNavigationOutcome.SwitchTo(firstCycle.last()),
            nav.navigatePrevious(queueSize, cur, RepeatMode.SHUFFLE, positionMs = 0L)
        )
    }

    @Test
    fun shuffleQueueMutationsPlayNextAppendRemoveAndManualSelect() {
        val nav = QueueNavigator(Random(2026))
        // Queue [0, 1, 2, 3, 4], start at 0, advance once so history = [0, X]
        nav.onQueueReplaced(queueSize = 5, startIndex = 0, mode = RepeatMode.SHUFFLE)
        val firstNext = (nav.navigateNext(5, 0, RepeatMode.SHUFFLE, false) as QueueNavigationOutcome.SwitchTo).targetIndex
        assertTrue(nav.canSkipPrevious(5, firstNext, RepeatMode.SHUFFLE))

        // 1. Insert brand new track as "play next" at (firstNext + 1) -> queue grows to 6
        val insertIdx = firstNext + 1
        nav.onTrackInsertedAsPlayNext(
            oldRemovedIndex = null,
            insertedIndex = insertIdx,
            newCurrentIndex = firstNext,
            newQueueSize = 6,
            mode = RepeatMode.SHUFFLE
        )
        assertEquals(insertIdx, nav.peekNextIndexForAutoAdvance(6, firstNext, RepeatMode.SHUFFLE))
        val afterPlayNext = (nav.navigateNext(6, firstNext, RepeatMode.SHUFFLE, false) as QueueNavigationOutcome.SwitchTo).targetIndex
        assertEquals(insertIdx, afterPlayNext)

        // 2. Move previously played track (index 0) to "play next" -> history stays intact!
        val curBeforeMove = insertIdx // > 0
        val newCurAfterMove = curBeforeMove - 1
        val movedInsertIdx = newCurAfterMove + 1
        nav.onTrackInsertedAsPlayNext(
            oldRemovedIndex = 0,
            insertedIndex = movedInsertIdx,
            newCurrentIndex = newCurAfterMove,
            newQueueSize = 6,
            mode = RepeatMode.SHUFFLE
        )
        assertEquals(movedInsertIdx, nav.peekNextIndexForAutoAdvance(6, newCurAfterMove, RepeatMode.SHUFFLE))
        assertTrue(nav.canSkipPrevious(6, newCurAfterMove, RepeatMode.SHUFFLE))

        // 3. Remove track at cursor 0 (no prior history) must NOT leak future track into history
        val freshNav = QueueNavigator(Random(77))
        freshNav.onQueueReplaced(queueSize = 3, startIndex = 0, mode = RepeatMode.SHUFFLE)
        freshNav.onTrackRemovedFromQueue(removedIndex = 0, newCurrentIndex = 0, newQueueSize = 2, mode = RepeatMode.SHUFFLE)
        assertFalse(
            "Removing cursor 0 track must not fabricate previous history",
            freshNav.canSkipPrevious(2, 0, RepeatMode.SHUFFLE)
        )
        assertEquals(
            QueueNavigationOutcome.SwitchTo(1),
            freshNav.navigateNext(2, 0, RepeatMode.SHUFFLE, false)
        )

        // 4. Append to 1-track shuffle queue enables skipping to new track
        val singleNav = QueueNavigator(Random(11))
        singleNav.onQueueReplaced(queueSize = 1, startIndex = 0, mode = RepeatMode.SHUFFLE)
        assertFalse(singleNav.canSkipNext(1, 0, RepeatMode.SHUFFLE))
        singleNav.onTrackAppendedToQueue(appendedIndex = 1, currentIndex = 0, newQueueSize = 2, mode = RepeatMode.SHUFFLE)
        assertTrue(singleNav.canSkipNext(2, 0, RepeatMode.SHUFFLE))
        assertEquals(1, singleNav.peekNextIndexForAutoAdvance(2, 0, RepeatMode.SHUFFLE))

        // Manual select in QueueSheet preserves back history
        val selectNav = QueueNavigator(Random(33))
        selectNav.onQueueReplaced(queueSize = 4, startIndex = 0, mode = RepeatMode.SHUFFLE)
        selectNav.onManualSelectIndex(queueSize = 4, targetIndex = 3, mode = RepeatMode.SHUFFLE)
        assertTrue(selectNav.canSkipPrevious(4, 3, RepeatMode.SHUFFLE))
        assertEquals(
            QueueNavigationOutcome.SwitchTo(0),
            selectNav.navigatePrevious(4, 3, RepeatMode.SHUFFLE, positionMs = 0L)
        )
    }

    @Test
    fun roundOneHardeningGuardsAgainstDegenerateAndOobMutations() {
        // P1-1: degenerate/duplicate onTrackAppendedToQueue never inserts currentIndex or creates adjacent duplicates
        val dupAppendNav = QueueNavigator(Random(1))
        dupAppendNav.onQueueReplaced(queueSize = 1, startIndex = 0, mode = RepeatMode.SHUFFLE)
        dupAppendNav.onTrackAppendedToQueue(appendedIndex = 0, currentIndex = 0, newQueueSize = 1, mode = RepeatMode.SHUFFLE)
        dupAppendNav.onTrackAppendedToQueue(appendedIndex = 1, currentIndex = 0, newQueueSize = 2, mode = RepeatMode.SHUFFLE)
        assertEquals(1, dupAppendNav.peekNextIndexForAutoAdvance(2, 0, RepeatMode.SHUFFLE))
        assertEquals(QueueNavigationOutcome.SwitchTo(1), dupAppendNav.navigateNext(2, 0, RepeatMode.SHUFFLE, false))

        // P1-2: out-of-bounds removedIndex recovers cleanly without injecting -1 or >= newQueueSize
        val oobRemoveNav = QueueNavigator(Random(2))
        oobRemoveNav.onQueueReplaced(queueSize = 3, startIndex = 1, mode = RepeatMode.SHUFFLE)
        oobRemoveNav.onTrackRemovedFromQueue(removedIndex = -1, newCurrentIndex = 0, newQueueSize = 2, mode = RepeatMode.SHUFFLE)
        assertEquals(1, oobRemoveNav.peekNextIndexForAutoAdvance(2, 0, RepeatMode.SHUFFLE))
        oobRemoveNav.onTrackRemovedFromQueue(removedIndex = 99, newCurrentIndex = 1, newQueueSize = 2, mode = RepeatMode.SHUFFLE)
        assertEquals(0, oobRemoveNav.peekNextIndexForAutoAdvance(2, 1, RepeatMode.SHUFFLE))

        // P2-1: out-of-bounds oldRemovedIndex in onTrackInsertedAsPlayNext rebuilds safely and still places playNext
        val oobPlayNextNav = QueueNavigator(Random(3))
        oobPlayNextNav.onQueueReplaced(queueSize = 4, startIndex = 0, mode = RepeatMode.SHUFFLE)
        oobPlayNextNav.onTrackInsertedAsPlayNext(
            oldRemovedIndex = -5,
            insertedIndex = 1,
            newCurrentIndex = 0,
            newQueueSize = 4,
            mode = RepeatMode.SHUFFLE
        )
        assertEquals(1, oobPlayNextNav.peekNextIndexForAutoAdvance(4, 0, RepeatMode.SHUFFLE))

        // P2-3: redundant onModeChanged(SHUFFLE) while already in valid SHUFFLE preserves history
        val modeNav = QueueNavigator(Random(4))
        modeNav.onQueueReplaced(queueSize = 5, startIndex = 0, mode = RepeatMode.SHUFFLE)
        val second = (modeNav.navigateNext(5, 0, RepeatMode.SHUFFLE, false) as QueueNavigationOutcome.SwitchTo).targetIndex
        assertTrue(modeNav.canSkipPrevious(5, second, RepeatMode.SHUFFLE))
        modeNav.onModeChanged(queueSize = 5, currentIndex = second, newMode = RepeatMode.SHUFFLE)
        assertTrue(modeNav.canSkipPrevious(5, second, RepeatMode.SHUFFLE))
        assertEquals(QueueNavigationOutcome.SwitchTo(0), modeNav.navigatePrevious(5, second, RepeatMode.SHUFFLE, 0L))
    }

    @Test
    fun shuffleHistoryRemainsBoundedAndEmptyOutOfBoundsSafe() {
        val nav = QueueNavigator(Random(555))
        // Empty & invalid indices never crash
        assertEquals(QueueNavigationOutcome.NoOp, nav.navigateNext(0, -1, RepeatMode.LIST_LOOP, false))
        assertEquals(QueueNavigationOutcome.NoOp, nav.navigatePrevious(0, -1, RepeatMode.SEQUENTIAL, 0L))
        assertEquals(QueueNavigationOutcome.NoOp, nav.navigateNext(3, 5, RepeatMode.SHUFFLE, false))
        assertFalse(nav.canSkipPrevious(0, -1, RepeatMode.LIST_LOOP))
        assertFalse(nav.canSkipNext(0, -1, RepeatMode.LIST_LOOP))
        assertFalse(nav.willAutoAdvanceOnEnd(0, -1, RepeatMode.LIST_LOOP))
        assertNull(nav.peekNextIndexForAutoAdvance(0, -1, RepeatMode.LIST_LOOP))

        // Bounded history over 500 steps
        var cur = 0
        nav.onQueueReplaced(queueSize = 12, startIndex = cur, mode = RepeatMode.SHUFFLE)
        repeat(500) {
            val out = nav.navigateNext(12, cur, RepeatMode.SHUFFLE, isAutoAdvance = false)
            cur = (out as QueueNavigationOutcome.SwitchTo).targetIndex
            assertTrue(cur in 0 until 12)
            assertTrue(
                "Shuffle history depth must stay <= ${QueueNavigator.MAX_SHUFFLE_HISTORY_BEHIND}",
                nav.shuffleHistoryDepth <= QueueNavigator.MAX_SHUFFLE_HISTORY_BEHIND
            )
        }
    }

    @Test
    fun randomizedStressTestAcrossModesAndMutationsNeverViolatesInvariants() {
        val rng = Random(20261126)
        val nav = QueueNavigator(Random(987654))
        var queueSize = 6
        var cur = 0
        var mode = RepeatMode.SHUFFLE
        nav.onQueueReplaced(queueSize, cur, mode)

        repeat(2_000) {
            val op = rng.nextInt(100)
            when {
                op < 30 -> { // Next (manual or auto)
                    val isAuto = rng.nextBoolean()
                    val peek = nav.peekNextIndexForAutoAdvance(queueSize, cur, mode)
                    if (peek != null) {
                        assertTrue(peek in 0 until queueSize)
                        assertNotEquals(cur, peek)
                    }
                    when (val out = nav.navigateNext(queueSize, cur, mode, isAuto)) {
                        is QueueNavigationOutcome.SwitchTo -> {
                            assertTrue(out.targetIndex in 0 until queueSize)
                            if (peek != null && !(mode == RepeatMode.SINGLE_LOOP && !isAuto)) {
                                assertEquals(peek, out.targetIndex)
                            }
                            cur = out.targetIndex
                        }
                        else -> Unit
                    }
                }
                op < 55 -> { // Previous
                    val pos = if (rng.nextInt(5) == 0) 9_000L else 1_000L
                    when (val out = nav.navigatePrevious(queueSize, cur, mode, pos)) {
                        is QueueNavigationOutcome.SwitchTo -> {
                            assertTrue(out.targetIndex in 0 until queueSize)
                            cur = out.targetIndex
                        }
                        else -> Unit
                    }
                }
                op < 65 -> { // Cycle repeat mode
                    mode = mode.next()
                    nav.onModeChanged(queueSize, cur, mode)
                }
                op < 75 -> { // Append track
                    if (queueSize < 20) {
                        queueSize++
                        nav.onTrackAppendedToQueue(queueSize - 1, cur, queueSize, mode)
                    }
                }
                op < 85 -> { // Remove random track
                    if (queueSize > 1) {
                        val removeIdx = rng.nextInt(queueSize)
                        queueSize--
                        cur = when {
                            removeIdx < cur -> cur - 1
                            removeIdx == cur -> cur.coerceAtMost(queueSize - 1)
                            else -> cur
                        }
                        nav.onTrackRemovedFromQueue(removeIdx, cur, queueSize, mode)
                    }
                }
                op < 93 -> { // Play next insert
                    if (queueSize < 20) {
                        val insertAt = (cur + 1).coerceIn(0, queueSize)
                        queueSize++
                        nav.onTrackInsertedAsPlayNext(null, insertAt, cur, queueSize, mode)
                    }
                }
                else -> { // Manual select in queue
                    val target = rng.nextInt(queueSize)
                    nav.onManualSelectIndex(queueSize, target, mode)
                    cur = target
                }
            }
            assertTrue(cur in 0 until queueSize)
            assertTrue(nav.shuffleHistoryDepth <= QueueNavigator.MAX_SHUFFLE_HISTORY_BEHIND)
        }
    }
}
