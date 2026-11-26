package com.lxpure.music

import androidx.media3.common.Player
import com.lxpure.music.core.player.PlaybackRecoveryPolicy
import com.lxpure.music.core.player.QueueNavigationOutcome
import com.lxpure.music.core.player.QueueNavigator
import com.lxpure.music.data.local.PlaybackSessionStore
import com.lxpure.music.domain.model.MusicSource
import com.lxpure.music.domain.model.PlaybackCursorSnapshot
import com.lxpure.music.domain.model.PlaybackQueueSnapshot
import com.lxpure.music.domain.model.PlaybackQueueSource
import com.lxpure.music.domain.model.QueueSourceType
import com.lxpure.music.domain.model.RepeatMode
import com.lxpure.music.domain.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import kotlin.random.Random

/**
 * Adversarial stress, boundary, persistence, FGS-policy, and 10,000-step fuzz tests
 * for [QueueNavigator], [PlaybackSessionStore], and [PlaybackRecoveryPolicy].
 */
class PlaybackNavigationAdversarialTest {

    // White-box accessors for strict internal invariant checking during stress & fuzz runs
    @Suppress("UNCHECKED_CAST")
    private fun QueueNavigator.inspectShuffleTimeline(): List<Int> {
        val field = QueueNavigator::class.java.getDeclaredField("shuffleTimeline")
        field.isAccessible = true
        return (field.get(this) as List<Int>).toList()
    }

    private fun QueueNavigator.inspectShuffleCursor(): Int {
        val field = QueueNavigator::class.java.getDeclaredField("shuffleCursor")
        field.isAccessible = true
        return field.getInt(this)
    }

    private fun assertShuffleInternalInvariants(
        nav: QueueNavigator,
        queueSize: Int,
        currentIndex: Int,
        mode: RepeatMode,
        context: String,
        requireInitializedIfShuffle: Boolean = false
    ) {
        val timeline = nav.inspectShuffleTimeline()
        val cursor = nav.inspectShuffleCursor()

        assertTrue(
            "[$context] shuffleHistoryDepth (${nav.shuffleHistoryDepth}) must be <= ${QueueNavigator.MAX_SHUFFLE_HISTORY_BEHIND}",
            nav.shuffleHistoryDepth <= QueueNavigator.MAX_SHUFFLE_HISTORY_BEHIND
        )

        if (mode != RepeatMode.SHUFFLE || queueSize <= 0 || currentIndex !in 0 until queueSize) {
            if (mode != RepeatMode.SHUFFLE) {
                assertTrue("[$context] timeline should be empty outside SHUFFLE, got $timeline", timeline.isEmpty())
                assertEquals("[$context] cursor should be -1 outside SHUFFLE", -1, cursor)
                assertEquals("[$context] depth should be 0 outside SHUFFLE", 0, nav.shuffleHistoryDepth)
            }
            return
        }

        if (timeline.isEmpty()) {
            assertFalse("[$context] expected initialized shuffle timeline", requireInitializedIfShuffle)
            assertEquals("[$context] empty timeline must have cursor -1", -1, cursor)
            return
        }

        assertTrue("[$context] cursor $cursor out of bounds for timeline size ${timeline.size}: $timeline", cursor in timeline.indices)
        assertEquals("[$context] timeline[cursor] must equal currentIndex", currentIndex, timeline[cursor])
        assertEquals("[$context] shuffleHistoryDepth must equal cursor", cursor, nav.shuffleHistoryDepth)
        assertTrue(
            "[$context] cursor $cursor exceeded MAX_SHUFFLE_HISTORY_BEHIND",
            cursor <= QueueNavigator.MAX_SHUFFLE_HISTORY_BEHIND
        )

        for (i in timeline.indices) {
            val idx = timeline[i]
            assertTrue(
                "[$context] OOB index $idx at timeline[$i] (queueSize=$queueSize, timeline=$timeline)",
                idx in 0 until queueSize
            )
            if (i > 0) {
                assertNotEquals(
                    "[$context] adjacent duplicate $idx at timeline[${i - 1}..$i] in $timeline",
                    timeline[i - 1],
                    idx
                )
            }
        }
    }

    // =========================================================================
    // 1. Exhaustive small-N transition testing (N = 1, 2, 3, 4) across all 4 modes
    // =========================================================================

    @Test
    fun exhaustiveSmallNTransitionsAcrossAllModesAndThresholds() {
        val thresholdPositions = listOf(
            Long.MIN_VALUE,
            -1_000L,
            -1L,
            0L,
            1L,
            4_999L,
            5_000L,
            5_001L,
            10_000L,
            Long.MAX_VALUE
        )

        for (mode in RepeatMode.entries) {
            for (queueSize in 1..4) {
                for (cur in 0 until queueSize) {
                    val ctx = "mode=$mode, N=$queueSize, cur=$cur"

                    // A. Test previous across every position threshold on a fresh navigator at (queueSize, cur)
                    for (pos in thresholdPositions) {
                        val nav = QueueNavigator(Random(1000 + queueSize * 31 + cur))
                        nav.onQueueReplaced(queueSize, cur, mode)

                        val canPrev = nav.canSkipPrevious(queueSize, cur, mode)
                        val cursorBefore = nav.inspectShuffleCursor()
                        val outcome = nav.navigatePrevious(queueSize, cur, mode, pos)

                        if (pos > QueueNavigator.PREVIOUS_RESTART_THRESHOLD_MS) {
                            assertEquals("$ctx pos=$pos must silently restart", QueueNavigationOutcome.RestartCurrent(null), outcome)
                            assertEquals("$ctx pos=$pos must NOT mutate shuffleCursor", cursorBefore, nav.inspectShuffleCursor())
                        } else {
                            // Verify canSkipPrevious accurately predicts SwitchTo(target != cur) vs RestartCurrent
                            if (canPrev) {
                                assertTrue("$ctx pos=$pos canPrev=true must SwitchTo, got $outcome", outcome is QueueNavigationOutcome.SwitchTo)
                                val target = (outcome as QueueNavigationOutcome.SwitchTo).targetIndex
                                assertTrue("$ctx target $target in bounds", target in 0 until queueSize)
                                assertNotEquals("$ctx target must differ from cur", cur, target)
                                val expectedPrev = when (mode) {
                                    RepeatMode.SEQUENTIAL -> cur - 1
                                    RepeatMode.LIST_LOOP, RepeatMode.SINGLE_LOOP -> if (cur == 0) queueSize - 1 else cur - 1
                                    RepeatMode.SHUFFLE -> error("Fresh shuffle at cursor 0 must not report canSkipPrevious=true")
                                }
                                assertEquals("$ctx unexpected previous target", expectedPrev, target)
                            } else {
                                assertTrue("$ctx pos=$pos canPrev=false must RestartCurrent, got $outcome", outcome is QueueNavigationOutcome.RestartCurrent)
                                val msg = (outcome as QueueNavigationOutcome.RestartCurrent).message
                                val expectedMsg = when {
                                    queueSize == 1 && mode == RepeatMode.SEQUENTIAL -> "已是第一首，已重新播放"
                                    queueSize == 1 -> "当前队列仅 1 首，已重新播放"
                                    mode == RepeatMode.SEQUENTIAL -> "已是第一首，已重新播放"
                                    mode == RepeatMode.SHUFFLE -> "已回到随机播放起点，已重新播放"
                                    else -> error("Unexpected canPrev=false for $ctx")
                                }
                                assertEquals("$ctx boundary toast mismatch", expectedMsg, msg)
                            }
                        }
                    }

                    // B. Test manual & auto navigateNext + canSkipNext + peek + willAutoAdvanceOnEnd
                    for (isAuto in listOf(false, true)) {
                        val nav = QueueNavigator(Random(2000 + queueSize * 31 + cur))
                        nav.onQueueReplaced(queueSize, cur, mode)

                        val canNext = nav.canSkipNext(queueSize, cur, mode)
                        val willAuto = nav.willAutoAdvanceOnEnd(queueSize, cur, mode)
                        val peek1 = nav.peekNextIndexForAutoAdvance(queueSize, cur, mode)
                        val peek2 = nav.peekNextIndexForAutoAdvance(queueSize, cur, mode)
                        assertEquals("$ctx peek must be idempotent", peek1, peek2)

                        val expectedWillAuto = !(mode == RepeatMode.SEQUENTIAL && cur == queueSize - 1)
                        assertEquals("$ctx willAutoAdvanceOnEnd mismatch", expectedWillAuto, willAuto)

                        val outcome = nav.navigateNext(queueSize, cur, mode, isAutoAdvance = isAuto)

                        if (!isAuto) {
                            if (canNext) {
                                assertTrue("$ctx manual canNext=true must SwitchTo, got $outcome", outcome is QueueNavigationOutcome.SwitchTo)
                                val target = (outcome as QueueNavigationOutcome.SwitchTo).targetIndex
                                assertTrue("$ctx target in bounds", target in 0 until queueSize)
                                assertNotEquals("$ctx target must differ from cur", cur, target)
                                if (mode != RepeatMode.SINGLE_LOOP) {
                                    assertEquals("$ctx peek must match manual next", peek1, target)
                                } else {
                                    assertNull("$ctx SINGLE_LOOP peek must be null", peek1)
                                    assertEquals("$ctx SINGLE_LOOP manual next wraps", (cur + 1) % queueSize, target)
                                }
                            } else {
                                when {
                                    mode == RepeatMode.SEQUENTIAL ->
                                        assertEquals("$ctx SEQUENTIAL manual end", QueueNavigationOutcome.StayAtBoundary("已是最后一首"), outcome)
                                    queueSize == 1 ->
                                        assertEquals("$ctx N=1 manual restart", QueueNavigationOutcome.RestartCurrent("当前队列仅 1 首，已重新播放"), outcome)
                                    else -> error("Unexpected canNext=false for $ctx")
                                }
                            }
                        } else {
                            // Auto-advance
                            when {
                                mode == RepeatMode.SEQUENTIAL && cur == queueSize - 1 -> {
                                    assertNull("$ctx SEQUENTIAL end peek must be null", peek1)
                                    assertEquals("$ctx SEQUENTIAL auto end", QueueNavigationOutcome.StopAtEnd("顺序播放已结束"), outcome)
                                }
                                mode == RepeatMode.SINGLE_LOOP || queueSize == 1 -> {
                                    assertNull("$ctx auto restart peek must be null", peek1)
                                    assertEquals("$ctx auto restart", QueueNavigationOutcome.RestartCurrent(null), outcome)
                                }
                                else -> {
                                    assertNotNull("$ctx peek must be non-null for auto switch", peek1)
                                    assertEquals("$ctx auto switch must match peek", QueueNavigationOutcome.SwitchTo(peek1!!), outcome)
                                    assertNotEquals("$ctx auto switch target != cur", cur, peek1)
                                }
                            }
                        }
                    }

                    // C. For SHUFFLE with N >= 2, also test Previous when shuffleCursor > 0 (with history!)
                    if (mode == RepeatMode.SHUFFLE && queueSize >= 2) {
                        for (pos in thresholdPositions) {
                            val nav = QueueNavigator(Random(3000 + queueSize * 31 + cur))
                            nav.onQueueReplaced(queueSize, cur, RepeatMode.SHUFFLE)
                            val nextIdx = (nav.navigateNext(queueSize, cur, RepeatMode.SHUFFLE, false) as QueueNavigationOutcome.SwitchTo).targetIndex
                            assertNotEquals(cur, nextIdx)
                            assertTrue("$ctx after 1 step canSkipPrevious must be true", nav.canSkipPrevious(queueSize, nextIdx, RepeatMode.SHUFFLE))

                            val prevOut = nav.navigatePrevious(queueSize, nextIdx, RepeatMode.SHUFFLE, pos)
                            if (pos > QueueNavigator.PREVIOUS_RESTART_THRESHOLD_MS) {
                                assertEquals(QueueNavigationOutcome.RestartCurrent(null), prevOut)
                                assertEquals(1, nav.inspectShuffleCursor())
                            } else {
                                assertEquals(QueueNavigationOutcome.SwitchTo(cur), prevOut)
                                assertEquals(0, nav.inspectShuffleCursor())
                                assertFalse(nav.canSkipPrevious(queueSize, cur, RepeatMode.SHUFFLE))
                            }
                        }
                    }
                }
            }

            // D. Out-of-bounds and empty queue safety for every mode
            val nav = QueueNavigator(Random(42))
            val invalidCases = listOf(
                0 to -1,
                0 to 0,
                -1 to 0,
                3 to -1,
                3 to 3,
                3 to Int.MAX_VALUE,
                3 to Int.MIN_VALUE
            )
            for ((qs, idx) in invalidCases) {
                assertFalse("mode=$mode qs=$qs idx=$idx canSkipPrevious", nav.canSkipPrevious(qs, idx, mode))
                assertFalse("mode=$mode qs=$qs idx=$idx canSkipNext", nav.canSkipNext(qs, idx, mode))
                assertFalse("mode=$mode qs=$qs idx=$idx willAutoAdvanceOnEnd", nav.willAutoAdvanceOnEnd(qs, idx, mode))
                assertNull("mode=$mode qs=$qs idx=$idx peek", nav.peekNextIndexForAutoAdvance(qs, idx, mode))
                assertEquals(QueueNavigationOutcome.NoOp, nav.navigateNext(qs, idx, mode, false))
                assertEquals(QueueNavigationOutcome.NoOp, nav.navigateNext(qs, idx, mode, true))
                assertEquals(QueueNavigationOutcome.NoOp, nav.navigatePrevious(qs, idx, mode, 0L))
                assertEquals(QueueNavigationOutcome.NoOp, nav.navigatePrevious(qs, idx, mode, 10_000L))
            }
        }
    }

    // =========================================================================
    // 2. Shuffle adversarial sequences
    // =========================================================================

    @Test
    fun shuffleQueueSizeTwoStrictAlternationFor100StepsAndExactBacktrack() {
        for (seed in listOf(0, 1, 42, 9999, -123456)) {
            for (startIndex in listOf(0, 1)) {
                val nav = QueueNavigator(Random(seed))
                var cur = startIndex
                nav.onQueueReplaced(queueSize = 2, startIndex = cur, mode = RepeatMode.SHUFFLE)

                val visited = mutableListOf(cur)
                repeat(100) { step ->
                    val expectedNext = 1 - cur
                    val peek = nav.peekNextIndexForAutoAdvance(2, cur, RepeatMode.SHUFFLE)
                    assertEquals("seed=$seed start=$startIndex step=$step peek", expectedNext, peek)
                    assertTrue(nav.canSkipNext(2, cur, RepeatMode.SHUFFLE))

                    val out = nav.navigateNext(2, cur, RepeatMode.SHUFFLE, isAutoAdvance = (step % 2 == 0))
                    assertEquals(
                        "seed=$seed start=$startIndex step=$step must strictly alternate",
                        QueueNavigationOutcome.SwitchTo(expectedNext),
                        out
                    )
                    assertNotEquals("NEVER two identical indices in a row", cur, expectedNext)
                    cur = expectedNext
                    visited.add(cur)
                    assertShuffleInternalInvariants(nav, 2, cur, RepeatMode.SHUFFLE, "N=2 seed=$seed step=$step", true)
                }

                // Backtrack all 100 steps deterministically
                for (step in 99 downTo 0) {
                    assertTrue(nav.canSkipPrevious(2, cur, RepeatMode.SHUFFLE))
                    val out = nav.navigatePrevious(2, cur, RepeatMode.SHUFFLE, positionMs = 0L)
                    assertEquals(QueueNavigationOutcome.SwitchTo(visited[step]), out)
                    cur = visited[step]
                    assertShuffleInternalInvariants(nav, 2, cur, RepeatMode.SHUFFLE, "N=2 backtrack step=$step", true)
                }

                // At origin
                assertFalse(nav.canSkipPrevious(2, cur, RepeatMode.SHUFFLE))
                assertEquals(
                    QueueNavigationOutcome.RestartCurrent("已回到随机播放起点，已重新播放"),
                    nav.navigatePrevious(2, cur, RepeatMode.SHUFFLE, positionMs = 0L)
                )
            }
        }
    }

    @Test
    fun shuffleBackAndForthPingPongDeterministicHistoryReplay() {
        val nav = QueueNavigator(Random(202601))
        val queueSize = 6
        var cur = 0
        nav.onQueueReplaced(queueSize, cur, RepeatMode.SHUFFLE)

        // Exact sequence: next, next, prev, next, prev, prev, prev
        val t0 = cur
        val t1 = (nav.navigateNext(queueSize, cur, RepeatMode.SHUFFLE, false) as QueueNavigationOutcome.SwitchTo).targetIndex
        assertNotEquals(t0, t1)
        cur = t1

        val t2 = (nav.navigateNext(queueSize, cur, RepeatMode.SHUFFLE, false) as QueueNavigationOutcome.SwitchTo).targetIndex
        assertNotEquals(t1, t2)
        assertNotEquals(t0, t2)
        cur = t2

        // prev > 5s must NOT change cursor or track
        assertEquals(
            QueueNavigationOutcome.RestartCurrent(null),
            nav.navigatePrevious(queueSize, cur, RepeatMode.SHUFFLE, positionMs = 5_001L)
        )
        assertEquals(2, nav.shuffleHistoryDepth)

        // prev -> t1
        assertEquals(QueueNavigationOutcome.SwitchTo(t1), nav.navigatePrevious(queueSize, cur, RepeatMode.SHUFFLE, 5_000L))
        cur = t1
        assertEquals(1, nav.shuffleHistoryDepth)

        // next -> MUST deterministically replay t2 (not draw a random new track!)
        assertEquals(t2, nav.peekNextIndexForAutoAdvance(queueSize, cur, RepeatMode.SHUFFLE))
        assertEquals(QueueNavigationOutcome.SwitchTo(t2), nav.navigateNext(queueSize, cur, RepeatMode.SHUFFLE, false))
        cur = t2
        assertEquals(2, nav.shuffleHistoryDepth)

        // prev -> t1
        assertEquals(QueueNavigationOutcome.SwitchTo(t1), nav.navigatePrevious(queueSize, cur, RepeatMode.SHUFFLE, 0L))
        cur = t1
        assertTrue(nav.canSkipPrevious(queueSize, cur, RepeatMode.SHUFFLE))

        // prev -> t0
        assertEquals(QueueNavigationOutcome.SwitchTo(t0), nav.navigatePrevious(queueSize, cur, RepeatMode.SHUFFLE, 0L))
        cur = t0
        assertFalse(nav.canSkipPrevious(queueSize, cur, RepeatMode.SHUFFLE))

        // prev at start -> restart with start-boundary toast
        assertEquals(
            QueueNavigationOutcome.RestartCurrent("已回到随机播放起点，已重新播放"),
            nav.navigatePrevious(queueSize, cur, RepeatMode.SHUFFLE, 0L)
        )
        assertFalse(nav.canSkipPrevious(queueSize, cur, RepeatMode.SHUFFLE))

        // Forward again replays exact t0 -> t1 -> t2 -> t3 (distinct from t0, t1, t2)
        assertEquals(QueueNavigationOutcome.SwitchTo(t1), nav.navigateNext(queueSize, t0, RepeatMode.SHUFFLE, false))
        assertEquals(QueueNavigationOutcome.SwitchTo(t2), nav.navigateNext(queueSize, t1, RepeatMode.SHUFFLE, false))
        val t3 = (nav.navigateNext(queueSize, t2, RepeatMode.SHUFFLE, false) as QueueNavigationOutcome.SwitchTo).targetIndex
        assertFalse("t3 must not duplicate t0, t1, or t2 in first cycle", t3 in setOf(t0, t1, t2))
        assertShuffleInternalInvariants(nav, queueSize, t3, RepeatMode.SHUFFLE, "ping-pong end", true)
    }

    @Test
    fun shufflePlayNextInsertionStressAllPositionsAndLifoOrder() {
        // Helper to simulate PlayerController.playNextInQueue on a tracked ID list
        fun playNextMoveOrInsert(
            queue: MutableList<String>,
            curRef: IntArray,
            nav: QueueNavigator,
            trackId: String
        ): Int {
            var cur = curRef[0]
            val existing = queue.indexOf(trackId)
            assertNotEquals("playNext must not target currently playing track", cur, existing)
            if (existing >= 0) {
                queue.removeAt(existing)
                if (existing < cur) cur--
            }
            val insertAt = (cur + 1).coerceIn(0, queue.size)
            queue.add(insertAt, trackId)
            nav.onTrackInsertedAsPlayNext(
                oldRemovedIndex = existing.takeIf { it >= 0 },
                insertedIndex = insertAt,
                newCurrentIndex = cur,
                newQueueSize = queue.size,
                mode = RepeatMode.SHUFFLE
            )
            curRef[0] = cur
            assertShuffleInternalInvariants(nav, queue.size, cur, RepeatMode.SHUFFLE, "after playNext($trackId)", true)
            return insertAt
        }

        // Case A: Move track from history (i < cursor), move from future (i > cursor),
        // insert brand-new track, and multiple consecutive playNext calls (LIFO order at cursor+1)
        val queue = mutableListOf("A", "B", "C", "D", "E")
        val nav = QueueNavigator(Random(777))
        val curRef = intArrayOf(0)
        nav.onQueueReplaced(queue.size, curRef[0], RepeatMode.SHUFFLE)

        // Advance 2 steps so history has 3 distinct tracks: [h0, h1, h2]
        val h0Id = queue[curRef[0]]
        curRef[0] = (nav.navigateNext(queue.size, curRef[0], RepeatMode.SHUFFLE, false) as QueueNavigationOutcome.SwitchTo).targetIndex
        val h1Id = queue[curRef[0]]
        curRef[0] = (nav.navigateNext(queue.size, curRef[0], RepeatMode.SHUFFLE, false) as QueueNavigationOutcome.SwitchTo).targetIndex
        val h2Id = queue[curRef[0]]

        // Find a future track ID (not in history)
        val futureId = queue.first { it !in setOf(h0Id, h1Id, h2Id) }

        // LIFO stack of 3 consecutive playNext calls:
        // 1st: move history track h0Id -> should play 3rd
        // 2nd: move future track futureId -> should play 2nd
        // 3rd: insert brand-new track "NEW1" -> should play 1st
        playNextMoveOrInsert(queue, curRef, nav, h0Id)
        assertEquals(h0Id, queue[nav.peekNextIndexForAutoAdvance(queue.size, curRef[0], RepeatMode.SHUFFLE)!!])

        playNextMoveOrInsert(queue, curRef, nav, futureId)
        assertEquals(futureId, queue[nav.peekNextIndexForAutoAdvance(queue.size, curRef[0], RepeatMode.SHUFFLE)!!])

        playNextMoveOrInsert(queue, curRef, nav, "NEW1")
        assertEquals("NEW1", queue[nav.peekNextIndexForAutoAdvance(queue.size, curRef[0], RepeatMode.SHUFFLE)!!])

        // Step BACK through history first: must faithfully replay h2Id -> h1Id -> h0Id (even though h0Id moved!)
        val prev1 = (nav.navigatePrevious(queue.size, curRef[0], RepeatMode.SHUFFLE, 0L) as QueueNavigationOutcome.SwitchTo).targetIndex
        assertEquals("Back 1 must be h1Id", h1Id, queue[prev1])
        curRef[0] = prev1

        val prev2 = (nav.navigatePrevious(queue.size, curRef[0], RepeatMode.SHUFFLE, 0L) as QueueNavigationOutcome.SwitchTo).targetIndex
        assertEquals("Back 2 must be h0Id at its remapped index", h0Id, queue[prev2])
        curRef[0] = prev2
        assertFalse(nav.canSkipPrevious(queue.size, curRef[0], RepeatMode.SHUFFLE))

        // Step FORWARD: must replay h1Id -> h2Id -> NEW1 -> futureId -> h0Id (exact LIFO!)
        val expectedForwardIds = listOf(h1Id, h2Id, "NEW1", futureId, h0Id)
        for (expectedId in expectedForwardIds) {
            val peek = nav.peekNextIndexForAutoAdvance(queue.size, curRef[0], RepeatMode.SHUFFLE)!!
            assertEquals("Peek mismatch before $expectedId", expectedId, queue[peek])
            val nextIdx = (nav.navigateNext(queue.size, curRef[0], RepeatMode.SHUFFLE, false) as QueueNavigationOutcome.SwitchTo).targetIndex
            assertEquals("Forward LIFO mismatch", expectedId, queue[nextIdx])
            curRef[0] = nextIdx
            assertShuffleInternalInvariants(nav, queue.size, curRef[0], RepeatMode.SHUFFLE, "forward $expectedId", true)
        }

        // Case B: Inserting when cur == 0 (no prior history) must NOT fabricate previous history
        val navAtZero = QueueNavigator(Random(123))
        val qZero = mutableListOf("T0", "T1", "T2")
        val curZero = intArrayOf(0)
        navAtZero.onQueueReplaced(qZero.size, 0, RepeatMode.SHUFFLE)
        playNextMoveOrInsert(qZero, curZero, navAtZero, "NEW_AT_1")
        assertFalse("cur==0 insert must keep canSkipPrevious=false", navAtZero.canSkipPrevious(qZero.size, curZero[0], RepeatMode.SHUFFLE))
        assertEquals("NEW_AT_1", qZero[navAtZero.peekNextIndexForAutoAdvance(qZero.size, curZero[0], RepeatMode.SHUFFLE)!!])

        // Case C: Inserting when cur == lastIndex (inserts at newQueueSize - 1)
        val navAtLast = QueueNavigator(Random(456))
        val qLast = mutableListOf("L0", "L1", "L2")
        val curLast = intArrayOf(2)
        navAtLast.onQueueReplaced(qLast.size, 2, RepeatMode.SHUFFLE)
        val insertedAtLast = playNextMoveOrInsert(qLast, curLast, navAtLast, "NEW_AT_END")
        assertEquals(3, insertedAtLast)
        assertEquals(2, curLast[0])
        assertEquals(3, navAtLast.peekNextIndexForAutoAdvance(qLast.size, curLast[0], RepeatMode.SHUFFLE))
        assertEquals(QueueNavigationOutcome.SwitchTo(3), navAtLast.navigateNext(qLast.size, curLast[0], RepeatMode.SHUFFLE, false))
        assertShuffleInternalInvariants(navAtLast, qLast.size, 3, RepeatMode.SHUFFLE, "cur==lastIndex", true)

        // Case D: Multi-cycle future (advance into cycle 2, step back to cycle 1, then move future track to playNext)
        val navMulti = QueueNavigator(Random(888))
        val qMulti = mutableListOf("M0", "M1", "M2")
        val curMulti = intArrayOf(0)
        navMulti.onQueueReplaced(3, 0, RepeatMode.SHUFFLE)
        repeat(4) {
            curMulti[0] = (navMulti.navigateNext(3, curMulti[0], RepeatMode.SHUFFLE, false) as QueueNavigationOutcome.SwitchTo).targetIndex
        }
        repeat(4) {
            curMulti[0] = (navMulti.navigatePrevious(3, curMulti[0], RepeatMode.SHUFFLE, 0L) as QueueNavigationOutcome.SwitchTo).targetIndex
        }
        assertEquals(0, navMulti.inspectShuffleCursor())
        // Move non-current tracks repeatedly while future spans 2 full cycles
        playNextMoveOrInsert(qMulti, curMulti, navMulti, "M1")
        playNextMoveOrInsert(qMulti, curMulti, navMulti, "M2")
        playNextMoveOrInsert(qMulti, curMulti, navMulti, "M1")
        // Walk forward 10 steps verifying no adjacent duplicates ever occur
        repeat(10) { step ->
            val nextIdx = (navMulti.navigateNext(qMulti.size, curMulti[0], RepeatMode.SHUFFLE, false) as QueueNavigationOutcome.SwitchTo).targetIndex
            assertNotEquals("multi-cycle playNext step=$step adjacent repeat", curMulti[0], nextIdx)
            curMulti[0] = nextIdx
            assertShuffleInternalInvariants(navMulti, qMulti.size, curMulti[0], RepeatMode.SHUFFLE, "multi-cycle step=$step", true)
        }

        // Case E: PlayerController.playSingle composite (insert at cur+1 then manual select)
        val navSingle = QueueNavigator(Random(321))
        navSingle.onQueueReplaced(3, 1, RepeatMode.SHUFFLE)
        navSingle.onTrackInsertedAsPlayNext(null, insertedIndex = 2, newCurrentIndex = 1, newQueueSize = 4, mode = RepeatMode.SHUFFLE)
        navSingle.onManualSelectIndex(queueSize = 4, targetIndex = 2, mode = RepeatMode.SHUFFLE)
        assertShuffleInternalInvariants(navSingle, 4, 2, RepeatMode.SHUFFLE, "playSingle composite", true)
        assertEquals(QueueNavigationOutcome.SwitchTo(1), navSingle.navigatePrevious(4, 2, RepeatMode.SHUFFLE, 0L))
    }

    @Test
    fun shuffleTrackRemovalStressAllPositionsHistoryAndResizeToOne() {
        fun removeAt(queue: MutableList<String>, curRef: IntArray, nav: QueueNavigator, removeIdx: Int) {
            queue.removeAt(removeIdx)
            if (queue.isEmpty()) {
                curRef[0] = -1
                nav.onTrackRemovedFromQueue(removeIdx, -1, 0, RepeatMode.SHUFFLE)
                assertEquals(-1, nav.inspectShuffleCursor())
                assertTrue(nav.inspectShuffleTimeline().isEmpty())
                return
            }
            val cur = curRef[0]
            val newCur = when {
                removeIdx < cur -> cur - 1
                removeIdx == cur -> cur.coerceAtMost(queue.lastIndex)
                else -> cur
            }
            curRef[0] = newCur
            nav.onTrackRemovedFromQueue(removeIdx, newCur, queue.size, RepeatMode.SHUFFLE)
            assertShuffleInternalInvariants(nav, queue.size, newCur, RepeatMode.SHUFFLE, "removeIdx=$removeIdx size=${queue.size}", true)
        }

        // A. Remove cursor-0 track repeatedly until empty -> NEVER fabricate previous history
        val qA = mutableListOf("A0", "A1", "A2", "A3", "A4")
        val curA = intArrayOf(0)
        val navA = QueueNavigator(Random(101))
        navA.onQueueReplaced(qA.size, 0, RepeatMode.SHUFFLE)
        while (qA.isNotEmpty()) {
            assertFalse("Before remove size=${qA.size} canSkipPrevious must be false", navA.canSkipPrevious(qA.size, curA[0], RepeatMode.SHUFFLE))
            removeAt(qA, curA, navA, curA[0])
            if (qA.isNotEmpty()) {
                assertFalse("After cursor-0 remove size=${qA.size} must NOT fabricate history", navA.canSkipPrevious(qA.size, curA[0], RepeatMode.SHUFFLE))
            }
        }
        assertFalse(navA.canSkipPrevious(0, -1, RepeatMode.SHUFFLE))
        assertFalse(navA.canSkipNext(0, -1, RepeatMode.SHUFFLE))

        // B. Remove track before cursor & track after cursor while preserving history
        val qB = mutableListOf("B0", "B1", "B2", "B3", "B4", "B5")
        val curB = intArrayOf(0)
        val navB = QueueNavigator(Random(202))
        navB.onQueueReplaced(qB.size, 0, RepeatMode.SHUFFLE)
        val historyIdsB = mutableListOf(qB[0])
        repeat(3) {
            curB[0] = (navB.navigateNext(qB.size, curB[0], RepeatMode.SHUFFLE, false) as QueueNavigationOutcome.SwitchTo).targetIndex
            historyIdsB.add(qB[curB[0]])
        }
        // Remove a track from earlier in history (index 1 in historyIdsB)
        val removeHistId = historyIdsB[1]
        removeAt(qB, curB, navB, qB.indexOf(removeHistId))
        historyIdsB.remove(removeHistId)
        // Remove a track from upcoming future (not in historyIdsB)
        val removeFutureId = qB.first { it !in historyIdsB }
        removeAt(qB, curB, navB, qB.indexOf(removeFutureId))
        // Stepping back through history must match remaining historyIdsB in exact reverse
        for (i in historyIdsB.lastIndex - 1 downTo 0) {
            val prevIdx = (navB.navigatePrevious(qB.size, curB[0], RepeatMode.SHUFFLE, 0L) as QueueNavigationOutcome.SwitchTo).targetIndex
            assertEquals("Remaining history mismatch at $i", historyIdsB[i], qB[prevIdx])
            curB[0] = prevIdx
        }
        assertFalse(navB.canSkipPrevious(qB.size, curB[0], RepeatMode.SHUFFLE))

        // C. Remove current track with deep history (> 5 tracks played) -> history preserved!
        val qC = (0 until 10).map { "C$it" }.toMutableList()
        val curC = intArrayOf(0)
        val navC = QueueNavigator(Random(303))
        navC.onQueueReplaced(qC.size, 0, RepeatMode.SHUFFLE)
        val historyIdsC = mutableListOf(qC[0])
        repeat(7) {
            curC[0] = (navC.navigateNext(qC.size, curC[0], RepeatMode.SHUFFLE, false) as QueueNavigationOutcome.SwitchTo).targetIndex
            historyIdsC.add(qC[curC[0]])
        }
        assertEquals(7, navC.shuffleHistoryDepth)
        val removedCurrentId = qC[curC[0]]
        removeAt(qC, curC, navC, curC[0])
        val replacementId = qC[curC[0]]
        // Expected history prior to replacementId is historyIdsC without removedCurrentId
        // (and if replacementId was ALREADY the immediately preceding track, they merge instead of duplicating)
        val expectedHistoryWithoutRemoved = historyIdsC.filter { it != removedCurrentId }.toMutableList()
        if (expectedHistoryWithoutRemoved.lastOrNull() != replacementId) {
            expectedHistoryWithoutRemoved.add(replacementId)
        }
        // Collapse any adjacent duplicates in expected model (none possible here since first cycle was distinct)
        assertTrue("Deep history must survive current track removal", navC.shuffleHistoryDepth >= 5)
        for (i in expectedHistoryWithoutRemoved.lastIndex - 1 downTo 0) {
            val prevIdx = (navC.navigatePrevious(qC.size, curC[0], RepeatMode.SHUFFLE, 0L) as QueueNavigationOutcome.SwitchTo).targetIndex
            assertEquals("Deep history backtrack mismatch at $i", expectedHistoryWithoutRemoved[i], qC[prevIdx])
            curC[0] = prevIdx
        }
        assertFalse(navC.canSkipPrevious(qC.size, curC[0], RepeatMode.SHUFFLE))

        // D. Remove track causing two separated history entries of same track to collide: [A, B, A] -> remove B -> [A]
        val navCollide = QueueNavigator(Random(404))
        val qCollide = mutableListOf("X", "Y", "Z")
        val curCollide = intArrayOf(0)
        navCollide.onQueueReplaced(3, 0, RepeatMode.SHUFFLE)
        navCollide.onManualSelectIndex(3, 1, RepeatMode.SHUFFLE) // history [0, 1]
        navCollide.onManualSelectIndex(3, 0, RepeatMode.SHUFFLE) // history [0, 1, 0]
        curCollide[0] = 0
        assertEquals(2, navCollide.shuffleHistoryDepth)
        removeAt(qCollide, curCollide, navCollide, 1) // removes Y(1) -> [X, X] must collapse to [X]!
        assertEquals(0, navCollide.shuffleHistoryDepth)
        assertFalse(navCollide.canSkipPrevious(qCollide.size, curCollide[0], RepeatMode.SHUFFLE))

        // E. Remove the track that was just inserted by playNext
        val navPN = QueueNavigator(Random(505))
        val qPN = mutableListOf("P0", "P1", "P2", "P3")
        val curPN = intArrayOf(1)
        navPN.onQueueReplaced(qPN.size, 1, RepeatMode.SHUFFLE)
        qPN.add(2, "P_NEXT")
        navPN.onTrackInsertedAsPlayNext(null, insertedIndex = 2, newCurrentIndex = 1, newQueueSize = 5, mode = RepeatMode.SHUFFLE)
        assertEquals(2, navPN.peekNextIndexForAutoAdvance(5, 1, RepeatMode.SHUFFLE))
        // Now immediately remove that playNext track at index 2!
        removeAt(qPN, curPN, navPN, 2)
        val afterRemovePeek = navPN.peekNextIndexForAutoAdvance(qPN.size, curPN[0], RepeatMode.SHUFFLE)
        assertNotNull(afterRemovePeek)
        assertTrue(afterRemovePeek!! in qPN.indices)
        assertNotEquals(curPN[0], afterRemovePeek)

        // F. Remove down to queueSize == 1 (after multi-cycle history!) then append back to queueSize == 2
        val navShrink = QueueNavigator(Random(606))
        val qShrink = mutableListOf("S0", "S1", "S2")
        val curShrink = intArrayOf(0)
        navShrink.onQueueReplaced(3, 0, RepeatMode.SHUFFLE)
        repeat(10) {
            curShrink[0] = (navShrink.navigateNext(3, curShrink[0], RepeatMode.SHUFFLE, false) as QueueNavigationOutcome.SwitchTo).targetIndex
        }
        removeAt(qShrink, curShrink, navShrink, (curShrink[0] + 1) % qShrink.size) // size -> 2
        removeAt(qShrink, curShrink, navShrink, (curShrink[0] + 1) % qShrink.size) // size -> 1
        assertEquals(1, qShrink.size)
        assertEquals(0, curShrink[0])
        assertFalse(navShrink.canSkipPrevious(1, 0, RepeatMode.SHUFFLE))
        assertFalse(navShrink.canSkipNext(1, 0, RepeatMode.SHUFFLE))
        assertNull(navShrink.peekNextIndexForAutoAdvance(1, 0, RepeatMode.SHUFFLE))
        assertEquals(listOf(0), navShrink.inspectShuffleTimeline())
        assertEquals(0, navShrink.inspectShuffleCursor())

        // Now append a track back to queueSize == 2
        qShrink.add("S_NEW")
        navShrink.onTrackAppendedToQueue(appendedIndex = 1, currentIndex = 0, newQueueSize = 2, mode = RepeatMode.SHUFFLE)
        assertShuffleInternalInvariants(navShrink, 2, 0, RepeatMode.SHUFFLE, "after shrink->1->append->2", true)
        assertFalse(navShrink.canSkipPrevious(2, 0, RepeatMode.SHUFFLE))
        assertTrue(navShrink.canSkipNext(2, 0, RepeatMode.SHUFFLE))
        assertEquals(1, navShrink.peekNextIndexForAutoAdvance(2, 0, RepeatMode.SHUFFLE))
        assertEquals(QueueNavigationOutcome.SwitchTo(1), navShrink.navigateNext(2, 0, RepeatMode.SHUFFLE, false))
    }

    @Test
    fun shuffleManualSelectIndexStressNoOpsHistoryStaleAndMaxDepth() {
        val nav = QueueNavigator(Random(707))
        val queueSize = 5
        nav.onQueueReplaced(queueSize, 0, RepeatMode.SHUFFLE)

        // 1. Selecting same index is strict no-op (no duplicate in history, upcoming unchanged)
        val timelineBefore = nav.inspectShuffleTimeline()
        val peekBefore = nav.peekNextIndexForAutoAdvance(queueSize, 0, RepeatMode.SHUFFLE)
        nav.onManualSelectIndex(queueSize, 0, RepeatMode.SHUFFLE)
        assertEquals(timelineBefore, nav.inspectShuffleTimeline())
        assertEquals(0, nav.shuffleHistoryDepth)
        assertEquals(peekBefore, nav.peekNextIndexForAutoAdvance(queueSize, 0, RepeatMode.SHUFFLE))
        assertFalse(nav.canSkipPrevious(queueSize, 0, RepeatMode.SHUFFLE))

        // 2. Advance twice: [0, t1, t2], then manually select 0 (already in history!)
        val t1 = (nav.navigateNext(queueSize, 0, RepeatMode.SHUFFLE, false) as QueueNavigationOutcome.SwitchTo).targetIndex
        val t2 = (nav.navigateNext(queueSize, t1, RepeatMode.SHUFFLE, false) as QueueNavigationOutcome.SwitchTo).targetIndex
        nav.onManualSelectIndex(queueSize, 0, RepeatMode.SHUFFLE)
        assertShuffleInternalInvariants(nav, queueSize, 0, RepeatMode.SHUFFLE, "manual select history index", true)
        assertEquals(3, nav.shuffleHistoryDepth)
        assertEquals(QueueNavigationOutcome.SwitchTo(t2), nav.navigatePrevious(queueSize, 0, RepeatMode.SHUFFLE, 0L))
        assertEquals(QueueNavigationOutcome.SwitchTo(t1), nav.navigatePrevious(queueSize, t2, RepeatMode.SHUFFLE, 0L))
        assertEquals(QueueNavigationOutcome.SwitchTo(0), nav.navigatePrevious(queueSize, t1, RepeatMode.SHUFFLE, 0L))
        assertFalse(nav.canSkipPrevious(queueSize, 0, RepeatMode.SHUFFLE))

        // 3. Selecting when timeline was uninitialized (reset)
        nav.reset()
        nav.onManualSelectIndex(queueSize, 3, RepeatMode.SHUFFLE)
        assertShuffleInternalInvariants(nav, queueSize, 3, RepeatMode.SHUFFLE, "manual select after reset", true)
        assertEquals(0, nav.shuffleHistoryDepth)
        assertFalse(nav.canSkipPrevious(queueSize, 3, RepeatMode.SHUFFLE))

        // 4. Selecting when timeline is stale (contained indices >= smaller new queueSize)
        nav.onQueueReplaced(queueSize = 10, startIndex = 0, mode = RepeatMode.SHUFFLE)
        nav.onManualSelectIndex(queueSize = 3, targetIndex = 2, mode = RepeatMode.SHUFFLE)
        assertShuffleInternalInvariants(nav, 3, 2, RepeatMode.SHUFFLE, "manual select stale timeline", true)
        assertEquals(0, nav.shuffleHistoryDepth)

        // 5. Selecting after reaching MAX_SHUFFLE_HISTORY_BEHIND = 200
        var cur = 0
        nav.onQueueReplaced(queueSize = 6, startIndex = cur, mode = RepeatMode.SHUFFLE)
        repeat(QueueNavigator.MAX_SHUFFLE_HISTORY_BEHIND) {
            cur = (nav.navigateNext(6, cur, RepeatMode.SHUFFLE, false) as QueueNavigationOutcome.SwitchTo).targetIndex
        }
        assertEquals(QueueNavigator.MAX_SHUFFLE_HISTORY_BEHIND, nav.shuffleHistoryDepth)
        val prevBeforeManual = cur
        val newTarget = (cur + 1) % 6
        nav.onManualSelectIndex(6, newTarget, RepeatMode.SHUFFLE)
        assertEquals(QueueNavigator.MAX_SHUFFLE_HISTORY_BEHIND, nav.shuffleHistoryDepth)
        assertShuffleInternalInvariants(nav, 6, newTarget, RepeatMode.SHUFFLE, "manual select at max history", true)
        assertEquals(
            QueueNavigationOutcome.SwitchTo(prevBeforeManual),
            nav.navigatePrevious(6, newTarget, RepeatMode.SHUFFLE, 0L)
        )

        // 6. Invalid / OOB manual select resets safely
        nav.onManualSelectIndex(6, -1, RepeatMode.SHUFFLE)
        assertTrue(nav.inspectShuffleTimeline().isEmpty())
        nav.onQueueReplaced(6, 0, RepeatMode.SHUFFLE)
        nav.onManualSelectIndex(6, 6, RepeatMode.SHUFFLE)
        assertTrue(nav.inspectShuffleTimeline().isEmpty())
    }

    @Test
    fun shufflePromotedPreloadedNextMatchAndMismatchFallback() {
        // 1. Exact match with peek preserves remaining cycle deterministically
        val nav = QueueNavigator(Random(909))
        val queueSize = 5
        nav.onQueueReplaced(queueSize, 0, RepeatMode.SHUFFLE)
        val fullInitialCycle = nav.inspectShuffleTimeline()
        assertEquals(5, fullInitialCycle.size)

        val peek1 = nav.peekNextIndexForAutoAdvance(queueSize, 0, RepeatMode.SHUFFLE)!!
        assertEquals(fullInitialCycle[1], peek1)
        nav.onPromotedPreloadedNext(queueSize, peek1, RepeatMode.SHUFFLE)
        assertEquals(1, nav.inspectShuffleCursor())
        assertEquals(fullInitialCycle, nav.inspectShuffleTimeline())
        assertEquals(fullInitialCycle[2], nav.peekNextIndexForAutoAdvance(queueSize, peek1, RepeatMode.SHUFFLE))
        assertEquals(QueueNavigationOutcome.SwitchTo(0), nav.navigatePrevious(queueSize, peek1, RepeatMode.SHUFFLE, 0L))

        // Re-advance to peek1 via promote
        nav.onPromotedPreloadedNext(queueSize, peek1, RepeatMode.SHUFFLE)
        assertEquals(1, nav.inspectShuffleCursor())

        // 2. Diverging stale preload (promotedIndex != peek and != cur) falls back cleanly
        val expectedNext = nav.peekNextIndexForAutoAdvance(queueSize, peek1, RepeatMode.SHUFFLE)!!
        val stalePromoted = (0 until queueSize).first { it != peek1 && it != expectedNext }
        nav.onPromotedPreloadedNext(queueSize, stalePromoted, RepeatMode.SHUFFLE)
        assertShuffleInternalInvariants(nav, queueSize, stalePromoted, RepeatMode.SHUFFLE, "stale preload mismatch", true)
        assertEquals(2, nav.shuffleHistoryDepth)
        // Stepping back MUST return to peek1 -> 0
        assertEquals(QueueNavigationOutcome.SwitchTo(peek1), nav.navigatePrevious(queueSize, stalePromoted, RepeatMode.SHUFFLE, 0L))
        assertEquals(QueueNavigationOutcome.SwitchTo(0), nav.navigatePrevious(queueSize, peek1, RepeatMode.SHUFFLE, 0L))
        assertFalse(nav.canSkipPrevious(queueSize, 0, RepeatMode.SHUFFLE))

        // 3. Degenerate stale preload equal to current index is no-op (no adjacent duplicate!)
        nav.onQueueReplaced(queueSize, 2, RepeatMode.SHUFFLE)
        nav.onPromotedPreloadedNext(queueSize, 2, RepeatMode.SHUFFLE)
        assertShuffleInternalInvariants(nav, queueSize, 2, RepeatMode.SHUFFLE, "stale preload == cur", true)
        assertEquals(0, nav.shuffleHistoryDepth)

        // 4. Promote on uninitialized timeline rebuilds cleanly; OOB resets cleanly
        nav.reset()
        nav.onPromotedPreloadedNext(queueSize, 4, RepeatMode.SHUFFLE)
        assertShuffleInternalInvariants(nav, queueSize, 4, RepeatMode.SHUFFLE, "promote after reset", true)
        nav.onPromotedPreloadedNext(queueSize, 99, RepeatMode.SHUFFLE)
        assertTrue(nav.inspectShuffleTimeline().isEmpty())
    }

    @Test
    fun shuffleHistoryTrimBoundaryExact200_201_250Steps() {
        val maxBehind = QueueNavigator.MAX_SHUFFLE_HISTORY_BEHIND
        assertEquals(200, maxBehind)

        for (steps in listOf(200, 201, 250)) {
            val nav = QueueNavigator(Random(10_000 + steps))
            val queueSize = 9
            var cur = 4
            nav.onQueueReplaced(queueSize, cur, RepeatMode.SHUFFLE)

            val played = mutableListOf(cur) // played[k] = index after k steps
            repeat(steps) { step ->
                val out = nav.navigateNext(queueSize, cur, RepeatMode.SHUFFLE, isAutoAdvance = (step % 3 == 0))
                assertTrue(out is QueueNavigationOutcome.SwitchTo)
                val nextIdx = (out as QueueNavigationOutcome.SwitchTo).targetIndex
                assertNotEquals(cur, nextIdx)
                cur = nextIdx
                played.add(cur)
                assertShuffleInternalInvariants(nav, queueSize, cur, RepeatMode.SHUFFLE, "steps=$steps at $step", true)
            }

            assertEquals("steps=$steps must cap shuffleHistoryDepth at 200", maxBehind, nav.shuffleHistoryDepth)

            // Press Previous 250 times: first 200 must step back through exact last 200 transitions,
            // and all remaining presses must hit the start-boundary restart toast with canSkipPrevious == false!
            var switchCount = 0
            for (backPress in 1..250) {
                val canPrevBefore = nav.canSkipPrevious(queueSize, cur, RepeatMode.SHUFFLE)
                val out = nav.navigatePrevious(queueSize, cur, RepeatMode.SHUFFLE, positionMs = 0L)
                if (backPress <= maxBehind) {
                    assertTrue("steps=$steps backPress=$backPress must have canSkipPrevious=true", canPrevBefore)
                    val expectedIdx = played[played.lastIndex - backPress]
                    assertEquals(
                        "steps=$steps backPress=$backPress must match exact history",
                        QueueNavigationOutcome.SwitchTo(expectedIdx),
                        out
                    )
                    assertNotEquals(cur, expectedIdx)
                    cur = expectedIdx
                    switchCount++
                } else {
                    assertFalse("steps=$steps backPress=$backPress must have canSkipPrevious=false", canPrevBefore)
                    assertEquals(
                        "steps=$steps backPress=$backPress must hit shuffle origin toast",
                        QueueNavigationOutcome.RestartCurrent("已回到随机播放起点，已重新播放"),
                        out
                    )
                    assertFalse(nav.canSkipPrevious(queueSize, cur, RepeatMode.SHUFFLE))
                }
            }

            assertEquals("steps=$steps must perform exactly 200 distinct history back-steps", 200, switchCount)
            assertEquals("steps=$steps must stop at played[${steps - 200}]", played[steps - 200], cur)
            assertEquals(0, nav.shuffleHistoryDepth)
            assertFalse(nav.canSkipPrevious(queueSize, cur, RepeatMode.SHUFFLE))
        }
    }

    // =========================================================================
    // 3. PlaybackSessionStore persistence round-trip with RepeatMode.SEQUENTIAL
    // =========================================================================

    @Test
    fun playbackSessionStoreSequentialRoundTripSaveQueueAndSaveCursorOnly() {
        val tempDir = Files.createTempDirectory("lx_seq_session_adversarial").toFile()
        try {
            val store = PlaybackSessionStore(tempDir)
            val tracks = listOf(
                Track(id = "wy_10", title = "序章", artist = "歌手甲", album = "专辑一", source = MusicSource.WY, songmid = "10", durationMs = 200_000L),
                Track(id = "tx_20", title = "中篇", artist = "歌手乙", album = "专辑二", source = MusicSource.TX, songmid = "20", durationMs = 240_000L),
                Track(id = "kw_30", title = "终章", artist = "歌手丙", album = "专辑三", source = MusicSource.KW, songmid = "30", durationMs = 180_000L)
            )
            val queueSource = PlaybackQueueSource(
                type = QueueSourceType.USER_PLAYLIST,
                title = "顺序播放测试歌单",
                userPlaylistId = 99L
            )

            // 1. saveQueueAndCursor with RepeatMode.SEQUENTIAL -> cold reload
            store.saveQueueAndCursor(
                queue = tracks,
                queueSource = queueSource,
                currentIndex = 0,
                positionMs = 12_345L,
                durationMs = 200_000L,
                repeatMode = RepeatMode.SEQUENTIAL,
                activeQualityBadge = "SQ",
                activeProviderName = "音源A"
            )
            val afterFullSave = PlaybackSessionStore(tempDir).loadSession()
            assertNotNull(afterFullSave)
            assertEquals(RepeatMode.SEQUENTIAL, afterFullSave!!.repeatMode)
            assertEquals(0, afterFullSave.currentIndex)
            assertEquals(12_345L, afterFullSave.positionMs)
            assertEquals(3, afterFullSave.queue.size)
            assertEquals("顺序播放测试歌单", afterFullSave.queueSource?.title)

            // 2. saveCursorOnly updating track & progress while keeping RepeatMode.SEQUENTIAL -> cold reload
            store.saveCursorOnly(
                currentIndex = 2,
                currentTrackId = "kw_30",
                positionMs = 88_000L,
                durationMs = 180_000L,
                repeatMode = RepeatMode.SEQUENTIAL,
                activeQualityBadge = "HR",
                activeProviderName = "音源B"
            )
            val afterCursorSave = PlaybackSessionStore(tempDir).loadSession()
            assertNotNull(afterCursorSave)
            assertEquals(RepeatMode.SEQUENTIAL, afterCursorSave!!.repeatMode)
            assertEquals(2, afterCursorSave.currentIndex)
            assertEquals("kw_30", afterCursorSave.queue[afterCursorSave.currentIndex].id)
            assertEquals(88_000L, afterCursorSave.positionMs)
            assertEquals("HR", afterCursorSave.activeQualityBadge)
            assertEquals("音源B", afterCursorSave.activeProviderName)

            // 3. Cycle through every RepeatMode via saveCursorOnly and land back on SEQUENTIAL
            for (mode in listOf(RepeatMode.LIST_LOOP, RepeatMode.SHUFFLE, RepeatMode.SINGLE_LOOP, RepeatMode.SEQUENTIAL)) {
                store.saveCursorOnly(
                    currentIndex = 1,
                    currentTrackId = "tx_20",
                    positionMs = 45_000L,
                    durationMs = 240_000L,
                    repeatMode = mode,
                    activeQualityBadge = "HQ",
                    activeProviderName = "音源C"
                )
                val reloaded = PlaybackSessionStore(tempDir).loadSession()
                assertNotNull(reloaded)
                assertEquals("Mode $mode must survive saveCursorOnly", mode, reloaded!!.repeatMode)
            }

            // 4. Pure resolveRestoredSession checks: SEQUENTIAL near-end clamping & unknown-mode fallback
            val clampedSequential = PlaybackSessionStore.resolveRestoredSession(
                queueSnapshot = PlaybackQueueSnapshot(queue = tracks, queueSource = queueSource),
                cursorSnapshot = PlaybackCursorSnapshot(
                    currentIndex = 2,
                    currentTrackId = "kw_30",
                    positionMs = 179_500L,
                    durationMs = 180_000L,
                    repeatMode = RepeatMode.SEQUENTIAL.name
                )
            )
            assertNotNull(clampedSequential)
            assertEquals(RepeatMode.SEQUENTIAL, clampedSequential!!.repeatMode)
            assertEquals(178_000L, clampedSequential.positionMs)

            val unknownModeFallback = PlaybackSessionStore.resolveRestoredSession(
                queueSnapshot = PlaybackQueueSnapshot(queue = tracks, queueSource = queueSource),
                cursorSnapshot = PlaybackCursorSnapshot(
                    currentIndex = 0,
                    currentTrackId = "wy_10",
                    positionMs = 1_000L,
                    durationMs = 200_000L,
                    repeatMode = "FUTURE_UNKNOWN_MODE"
                )
            )
            assertEquals(RepeatMode.LIST_LOOP, unknownModeFallback!!.repeatMode)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    // =========================================================================
    // 4. PlaybackRecoveryPolicy FGS integration with SEQUENTIAL end-of-queue
    // =========================================================================

    @Test
    fun playbackRecoveryPolicyForegroundServiceIntegrationWithSequentialEndOfQueue() {
        // Explicit FGS release when SEQUENTIAL ends at last track (willAutoAdvanceOnEnd == false)
        assertFalse(
            "FGS must be released cleanly when sequential playback ends at last track",
            PlaybackRecoveryPolicy.shouldHoldForegroundDuringTransition(
                isPlaybackOngoing = true,
                isTransitioningOrRecovering = false,
                playbackState = Player.STATE_ENDED,
                playWhenReady = true,
                willAutoAdvanceOnEnd = false,
                hasPlayerError = false,
                canAutoRecoverFromError = false
            )
        )

        // Explicit FGS hold when next track will auto-advance (willAutoAdvanceOnEnd == true)
        assertTrue(
            "FGS must stay held during STATE_ENDED when queue will auto-advance",
            PlaybackRecoveryPolicy.shouldHoldForegroundDuringTransition(
                isPlaybackOngoing = true,
                isTransitioningOrRecovering = false,
                playbackState = Player.STATE_ENDED,
                playWhenReady = true,
                willAutoAdvanceOnEnd = true,
                hasPlayerError = false,
                canAutoRecoverFromError = false
            )
        )

        // End-to-end integration with QueueNavigator.willAutoAdvanceOnEnd across all 4 modes & queue positions
        val nav = QueueNavigator(Random(42))
        for (mode in RepeatMode.entries) {
            for (queueSize in 1..4) {
                for (cur in 0 until queueSize) {
                    nav.onQueueReplaced(queueSize, cur, mode)
                    val willAuto = nav.willAutoAdvanceOnEnd(queueSize, cur, mode)
                    val holdAtEnd = PlaybackRecoveryPolicy.shouldHoldForegroundDuringTransition(
                        isPlaybackOngoing = true,
                        isTransitioningOrRecovering = false,
                        playbackState = Player.STATE_ENDED,
                        playWhenReady = true,
                        willAutoAdvanceOnEnd = willAuto,
                        hasPlayerError = false,
                        canAutoRecoverFromError = false
                    )
                    val isSequentialEnd = mode == RepeatMode.SEQUENTIAL && cur == queueSize - 1
                    assertEquals(
                        "mode=$mode N=$queueSize cur=$cur holdAtEnd mismatch",
                        !isSequentialEnd,
                        holdAtEnd
                    )

                    // If user paused (playWhenReady == false) at STATE_ENDED, FGS must release regardless of mode
                    assertFalse(
                        PlaybackRecoveryPolicy.shouldHoldForegroundDuringTransition(
                            isPlaybackOngoing = true,
                            isTransitioningOrRecovering = false,
                            playbackState = Player.STATE_ENDED,
                            playWhenReady = false,
                            willAutoAdvanceOnEnd = willAuto,
                            hasPlayerError = false,
                            canAutoRecoverFromError = false
                        )
                    )
                }
            }
        }
    }

    // =========================================================================
    // 5. 10,000-step high-intensity randomized fuzz test across all operations
    // =========================================================================

    @Test
    fun tenThousandStepHighIntensityRandomizedFuzzTest() {
        runFuzzSuite(seed = 2026112601L, navSeed = 135792468, steps = 10_000, maxQueueSize = 18)
    }

    @Test
    fun threeThousandStepHighChurnSmallQueueFuzzTest() {
        // Ultra-high collision & empty/resize density (queue size fluctuates heavily in 0..4)
        runFuzzSuite(seed = 9988776655L, navSeed = 246813579, steps = 3_000, maxQueueSize = 4)
    }

    private fun runFuzzSuite(seed: Long, navSeed: Int, steps: Int, maxQueueSize: Int) {
        val rng = Random(seed)
        val nav = QueueNavigator(Random(navSeed))
        val queue = mutableListOf("T0", "T1", "T2", "T3", "T4")
        var nextTrackSerial = 5
        var cur = 0
        var mode = RepeatMode.SHUFFLE
        nav.onQueueReplaced(queue.size, cur, mode)

        for (step in 1..steps) {
            val tag = "seed=$seed step=$step mode=$mode size=${queue.size} cur=$cur"
            val op = rng.nextInt(100)

            when {
                // 0..19: navigateNext (manual or auto)
                op < 20 -> {
                    val isAuto = rng.nextBoolean()
                    val canNextBefore = nav.canSkipNext(queue.size, cur, mode)
                    val peekBefore = nav.peekNextIndexForAutoAdvance(queue.size, cur, mode)
                    val peekAgain = nav.peekNextIndexForAutoAdvance(queue.size, cur, mode)
                    assertEquals("$tag peek idempotency", peekBefore, peekAgain)

                    val out = nav.navigateNext(queue.size, cur, mode, isAutoAdvance = isAuto)
                    if (queue.isEmpty()) {
                        assertEquals(QueueNavigationOutcome.NoOp, out)
                    } else if (!isAuto) {
                        if (canNextBefore) {
                            assertTrue("$tag manual canNext=true must SwitchTo, got $out", out is QueueNavigationOutcome.SwitchTo)
                            val target = (out as QueueNavigationOutcome.SwitchTo).targetIndex
                            assertTrue("$tag target OOB", target in queue.indices)
                            assertNotEquals("$tag manual next must switch track", cur, target)
                            if (mode != RepeatMode.SINGLE_LOOP) {
                                assertEquals("$tag manual next must match peek", peekBefore, target)
                            }
                            cur = target
                        } else {
                            assertFalse("$tag canNext=false must not SwitchTo", out is QueueNavigationOutcome.SwitchTo)
                        }
                    } else {
                        if (peekBefore != null) {
                            assertEquals("$tag auto next must match peek", QueueNavigationOutcome.SwitchTo(peekBefore), out)
                            assertNotEquals("$tag auto next must differ from cur", cur, peekBefore)
                            cur = peekBefore
                        } else {
                            assertFalse("$tag peek=null auto next must not SwitchTo", out is QueueNavigationOutcome.SwitchTo)
                        }
                    }
                }

                // 20..37: navigatePrevious (varied positions across 5,000ms boundary)
                op < 38 -> {
                    val pos = when (rng.nextInt(6)) {
                        0 -> -1L
                        1 -> 0L
                        2 -> 4_999L
                        3 -> 5_000L
                        4 -> 5_001L
                        else -> 25_000L
                    }
                    val canPrevBefore = nav.canSkipPrevious(queue.size, cur, mode)
                    val cursorBefore = nav.inspectShuffleCursor()
                    val out = nav.navigatePrevious(queue.size, cur, mode, positionMs = pos)
                    if (queue.isEmpty()) {
                        assertEquals(QueueNavigationOutcome.NoOp, out)
                    } else if (pos > QueueNavigator.PREVIOUS_RESTART_THRESHOLD_MS) {
                        assertEquals("$tag pos=$pos silent restart", QueueNavigationOutcome.RestartCurrent(null), out)
                        assertEquals("$tag pos=$pos must not move shuffleCursor", cursorBefore, nav.inspectShuffleCursor())
                    } else if (canPrevBefore) {
                        assertTrue("$tag canPrev=true must SwitchTo, got $out", out is QueueNavigationOutcome.SwitchTo)
                        val target = (out as QueueNavigationOutcome.SwitchTo).targetIndex
                        assertTrue("$tag prev target OOB", target in queue.indices)
                        assertNotEquals("$tag prev target must differ from cur", cur, target)
                        cur = target
                    } else {
                        assertTrue("$tag canPrev=false must RestartCurrent, got $out", out is QueueNavigationOutcome.RestartCurrent)
                        assertNotNull("$tag boundary restart must have message", (out as QueueNavigationOutcome.RestartCurrent).message)
                    }
                }

                // 38..45: onPromotedPreloadedNext (matching peek OR diverging stale preload OR == cur)
                op < 46 -> {
                    if (queue.size >= 2) {
                        val peek = nav.peekNextIndexForAutoAdvance(queue.size, cur, mode)
                        val promotedIdx = when {
                            peek != null && rng.nextInt(10) < 6 -> peek // matching preload
                            rng.nextInt(5) == 0 -> cur // degenerate stale == cur
                            else -> rng.nextInt(queue.size) // arbitrary stale mismatch
                        }
                        nav.onPromotedPreloadedNext(queue.size, promotedIdx, mode)
                        cur = promotedIdx
                    }
                }

                // 46..53: onManualSelectIndex (including same index, history, future)
                op < 54 -> {
                    if (queue.isNotEmpty()) {
                        val target = if (rng.nextInt(4) == 0) cur else rng.nextInt(queue.size)
                        nav.onManualSelectIndex(queue.size, target, mode)
                        cur = target
                    }
                }

                // 54..63: onTrackInsertedAsPlayNext with oldRemovedIndex == null (brand-new insert)
                op < 64 -> {
                    if (queue.isNotEmpty() && queue.size < maxQueueSize) {
                        val insertAt = (cur + 1).coerceIn(0, queue.size)
                        queue.add(insertAt, "T${nextTrackSerial++}")
                        nav.onTrackInsertedAsPlayNext(
                            oldRemovedIndex = null,
                            insertedIndex = insertAt,
                            newCurrentIndex = cur,
                            newQueueSize = queue.size,
                            mode = mode
                        )
                        if (mode == RepeatMode.SHUFFLE) {
                            assertEquals(
                                "$tag brand-new playNext must be peeked next",
                                insertAt,
                                nav.peekNextIndexForAutoAdvance(queue.size, cur, mode)
                            )
                        }
                        // Occasionally complete the PlayerController.playSingle composite
                        if (rng.nextInt(4) == 0) {
                            nav.onManualSelectIndex(queue.size, insertAt, mode)
                            cur = insertAt
                        }
                    }
                }

                // 64..73: onTrackInsertedAsPlayNext with oldRemovedIndex != null (move existing track!)
                op < 74 -> {
                    if (queue.size >= 2) {
                        val candidates = (0 until queue.size).filter { it != cur }
                        val existing = candidates[rng.nextInt(candidates.size)]
                        val movedTrack = queue.removeAt(existing)
                        if (existing < cur) cur--
                        val insertAt = (cur + 1).coerceIn(0, queue.size)
                        queue.add(insertAt, movedTrack)
                        nav.onTrackInsertedAsPlayNext(
                            oldRemovedIndex = existing,
                            insertedIndex = insertAt,
                            newCurrentIndex = cur,
                            newQueueSize = queue.size,
                            mode = mode
                        )
                        if (mode == RepeatMode.SHUFFLE) {
                            assertEquals(
                                "$tag moved playNext must be peeked next",
                                insertAt,
                                nav.peekNextIndexForAutoAdvance(queue.size, cur, mode)
                            )
                        }
                    }
                }

                // 74..81: onTrackAppendedToQueue
                op < 82 -> {
                    if (queue.isEmpty()) {
                        queue.add("T${nextTrackSerial++}")
                        cur = 0
                        nav.onQueueReplaced(queue.size, cur, mode)
                    } else if (queue.size < maxQueueSize) {
                        queue.add("T${nextTrackSerial++}")
                        nav.onTrackAppendedToQueue(
                            appendedIndex = queue.lastIndex,
                            currentIndex = cur,
                            newQueueSize = queue.size,
                            mode = mode
                        )
                    }
                }

                // 82..91: onTrackRemovedFromQueue (including down to 0!)
                op < 92 -> {
                    if (queue.isNotEmpty()) {
                        val removeIdx = rng.nextInt(queue.size)
                        queue.removeAt(removeIdx)
                        if (queue.isEmpty()) {
                            cur = -1
                            nav.onTrackRemovedFromQueue(removeIdx, -1, 0, mode)
                        } else {
                            cur = when {
                                removeIdx < cur -> cur - 1
                                removeIdx == cur -> cur.coerceAtMost(queue.lastIndex)
                                else -> cur
                            }
                            nav.onTrackRemovedFromQueue(removeIdx, cur, queue.size, mode)
                        }
                    }
                }

                // 92..95: onModeChanged
                op < 96 -> {
                    mode = RepeatMode.entries[rng.nextInt(RepeatMode.entries.size)]
                    nav.onModeChanged(queue.size, cur, mode)
                }

                // 96..99: onQueueReplaced (including size 0 -> repopulate, 1, 2, ..maxQueueSize)
                else -> {
                    val newSize = rng.nextInt(0, maxQueueSize + 1)
                    queue.clear()
                    repeat(newSize) { queue.add("T${nextTrackSerial++}") }
                    cur = if (newSize == 0) -1 else rng.nextInt(newSize)
                    nav.onQueueReplaced(queue.size, cur, mode)
                }
            }

            // ===== Post-step universal invariant verification =====
            if (queue.isEmpty()) {
                assertEquals("$tag empty queue must have cur == -1", -1, cur)
                assertFalse("$tag empty canSkipPrevious", nav.canSkipPrevious(0, -1, mode))
                assertFalse("$tag empty canSkipNext", nav.canSkipNext(0, -1, mode))
                assertFalse("$tag empty willAutoAdvanceOnEnd", nav.willAutoAdvanceOnEnd(0, -1, mode))
                assertNull("$tag empty peek", nav.peekNextIndexForAutoAdvance(0, -1, mode))
                assertTrue("$tag empty queue must reset timeline", nav.inspectShuffleTimeline().isEmpty())
                assertEquals("$tag empty queue must reset cursor", -1, nav.inspectShuffleCursor())
            } else {
                assertTrue("$tag cur $cur out of bounds 0 until ${queue.size}", cur in queue.indices)
                assertShuffleInternalInvariants(nav, queue.size, cur, mode, tag)

                val canPrev = nav.canSkipPrevious(queue.size, cur, mode)
                val canNext = nav.canSkipNext(queue.size, cur, mode)
                val willAuto = nav.willAutoAdvanceOnEnd(queue.size, cur, mode)
                val peek = nav.peekNextIndexForAutoAdvance(queue.size, cur, mode)

                // Re-check internal invariants AFTER peek (peek may extend cycle on rollover!)
                assertShuffleInternalInvariants(nav, queue.size, cur, mode, "$tag after-peek")

                when {
                    queue.size == 1 -> {
                        assertFalse("$tag N=1 canPrev", canPrev)
                        assertFalse("$tag N=1 canNext", canNext)
                        assertNull("$tag N=1 peek", peek)
                        assertEquals("$tag N=1 willAuto", mode != RepeatMode.SEQUENTIAL, willAuto)
                    }
                    mode == RepeatMode.SEQUENTIAL -> {
                        assertEquals("$tag SEQ canPrev", cur > 0, canPrev)
                        assertEquals("$tag SEQ canNext", cur < queue.lastIndex, canNext)
                        assertEquals("$tag SEQ willAuto", cur < queue.lastIndex, willAuto)
                        assertEquals("$tag SEQ peek", if (cur + 1 < queue.size) cur + 1 else null, peek)
                    }
                    mode == RepeatMode.LIST_LOOP -> {
                        assertTrue("$tag LOOP canPrev", canPrev)
                        assertTrue("$tag LOOP canNext", canNext)
                        assertTrue("$tag LOOP willAuto", willAuto)
                        assertEquals("$tag LOOP peek", (cur + 1) % queue.size, peek)
                    }
                    mode == RepeatMode.SINGLE_LOOP -> {
                        assertTrue("$tag SINGLE canPrev", canPrev)
                        assertTrue("$tag SINGLE canNext", canNext)
                        assertTrue("$tag SINGLE willAuto", willAuto)
                        assertNull("$tag SINGLE peek", peek)
                    }
                    mode == RepeatMode.SHUFFLE -> {
                        assertTrue("$tag SHUFFLE canNext", canNext)
                        assertTrue("$tag SHUFFLE willAuto", willAuto)
                        assertNotNull("$tag SHUFFLE peek", peek)
                        assertTrue("$tag SHUFFLE peek OOB", peek!! in queue.indices)
                        assertNotEquals("$tag SHUFFLE peek must differ from cur", cur, peek)
                        assertEquals("$tag SHUFFLE canPrev matches cursor>0", nav.inspectShuffleCursor() > 0, canPrev)
                    }
                }
            }
        }
    }
}
