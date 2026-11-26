package com.lxpure.music

import androidx.media3.common.Player
import com.lxpure.music.core.player.AutoSkipDecision
import com.lxpure.music.core.player.PlaybackForwardingPlayer
import com.lxpure.music.core.player.PlaybackRecoveryPolicy
import com.lxpure.music.core.player.PlaybackService
import com.lxpure.music.core.player.QueueNavigationOutcome
import com.lxpure.music.core.player.QueueNavigator
import com.lxpure.music.core.userapi.ParentChildHttpCallTracker
import com.lxpure.music.core.userapi.SourceRaceCoordinator
import com.lxpure.music.core.userapi.UserApiManager
import com.lxpure.music.domain.model.AudioQuality
import com.lxpure.music.domain.model.MusicSource
import com.lxpure.music.domain.model.RepeatMode
import com.lxpure.music.domain.model.Track
import com.lxpure.music.domain.model.UserApiRuntimeStatus
import com.lxpure.music.domain.model.UserApiSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.random.Random

class MultiSourceRacingAndAutoSkipTest {

    private fun testTrack(id: String = "wy_1001", title: String = "Test Song"): Track = Track(
        id = id,
        title = title,
        artist = "Tester",
        source = MusicSource.WY,
        durationMs = 200_000L
    )

    private fun testSource(
        id: String,
        priority: Int,
        status: UserApiRuntimeStatus = UserApiRuntimeStatus.READY,
        qualities: List<String> = listOf("128k", "320k", "flac")
    ): UserApiSource = UserApiSource(
        id = id,
        name = "Source-$id",
        description = "",
        version = "1.0.0",
        author = "dev",
        homepage = "",
        script = "/* $id */",
        sourceUrl = null,
        enabled = true,
        priority = priority,
        status = status,
        supportedPlatforms = mapOf("wy" to qualities)
    )

    // =========================================================================
    // R2: Direct Previous Track Navigation (< 5s and >= 5s + ForwardingPlayer)
    // =========================================================================

    @Test
    fun directPreviousNavigatesWithoutRestartThresholdAcrossAllModes() {
        val positions = listOf(2_000L, 10_000L, 60_000L)
        for (pos in positions) {
            val nav = QueueNavigator(Random(7))

            // SEQUENTIAL: index 2 -> 1 -> 0 -> restart with boundary message
            assertEquals(QueueNavigationOutcome.SwitchTo(1), nav.navigatePrevious(3, 2, RepeatMode.SEQUENTIAL, pos))
            assertEquals(QueueNavigationOutcome.SwitchTo(0), nav.navigatePrevious(3, 1, RepeatMode.SEQUENTIAL, pos))
            assertEquals(
                QueueNavigationOutcome.RestartCurrent("已是第一首，已重新播放"),
                nav.navigatePrevious(3, 0, RepeatMode.SEQUENTIAL, pos)
            )

            // LIST_LOOP & SINGLE_LOOP: wraps directly even >= 5s
            assertEquals(QueueNavigationOutcome.SwitchTo(2), nav.navigatePrevious(3, 0, RepeatMode.LIST_LOOP, pos))
            assertEquals(QueueNavigationOutcome.SwitchTo(2), nav.navigatePrevious(3, 0, RepeatMode.SINGLE_LOOP, pos))

            // SHUFFLE: steps back through history directly even >= 5s
            nav.onQueueReplaced(4, 0, RepeatMode.SHUFFLE)
            val second = (nav.navigateNext(4, 0, RepeatMode.SHUFFLE, false) as QueueNavigationOutcome.SwitchTo).targetIndex
            assertEquals(QueueNavigationOutcome.SwitchTo(0), nav.navigatePrevious(4, second, RepeatMode.SHUFFLE, pos))
            assertEquals(
                QueueNavigationOutcome.RestartCurrent("已回到随机播放起点，已重新播放"),
                nav.navigatePrevious(4, 0, RepeatMode.SHUFFLE, pos)
            )
        }
    }

    @Test
    fun playbackForwardingPlayerRoutesSeekToPreviousDirectlyAtUnderAndOverFiveSeconds() {
        val baseInvocations = CopyOnWriteArrayList<String>()
        var simulatedPositionMs = 2_000L

        val fakeBasePlayer = Proxy.newProxyInstance(
            Player::class.java.classLoader,
            arrayOf(Player::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "seekToPrevious", "seekToPreviousMediaItem", "seekToDefaultPosition" -> {
                    baseInvocations.add(method.name)
                    null
                }
                "getCurrentPosition" -> simulatedPositionMs
                else -> when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Integer.TYPE -> Player.STATE_READY
                    java.lang.Long.TYPE -> 0L
                    java.lang.Float.TYPE -> 1f
                    Player.Commands::class.java -> Player.Commands.EMPTY
                    else -> null
                }
            }
        } as Player

        val nav = QueueNavigator(Random(42))
        var curMode = RepeatMode.LIST_LOOP
        var curIndex = 2
        val queueSize = 4
        nav.onQueueReplaced(queueSize, curIndex, curMode)

        val forwarding = PlaybackForwardingPlayer(
            basePlayer = fakeBasePlayer,
            hasQueue = { true },
            canSkipPrevious = { nav.canSkipPrevious(queueSize, curIndex, curMode) },
            canSkipNext = { nav.canSkipNext(queueSize, curIndex, curMode) },
            onNext = {
                val out = nav.navigateNext(queueSize, curIndex, curMode, isAutoAdvance = false)
                if (out is QueueNavigationOutcome.SwitchTo) curIndex = out.targetIndex
            },
            onPrevious = {
                val out = nav.navigatePrevious(queueSize, curIndex, curMode, fakeBasePlayer.currentPosition)
                if (out is QueueNavigationOutcome.SwitchTo) curIndex = out.targetIndex
            }
        )

        assertTrue(forwarding.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS))
        assertTrue(forwarding.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM))

        // < 5s (2_000ms) via notification/headset seekToPrevious() -> directly jumps 2 -> 1
        simulatedPositionMs = 2_000L
        forwarding.seekToPrevious()
        assertEquals(1, curIndex)

        // >= 5s (10_000ms) via seekToPreviousMediaItem() -> directly jumps 1 -> 0 (NOT restarting index 1!)
        simulatedPositionMs = 10_000L
        forwarding.seekToPreviousMediaItem()
        assertEquals(0, curIndex)

        // >= 5s (10_000ms) in SINGLE_LOOP via seekToPrevious() -> directly wraps 0 -> 3
        curMode = RepeatMode.SINGLE_LOOP
        nav.onModeChanged(queueSize, curIndex, curMode)
        forwarding.seekToPrevious()
        assertEquals(3, curIndex)

        // >= 5s (10_000ms) in SHUFFLE with history -> directly steps back in history
        curMode = RepeatMode.SHUFFLE
        nav.onModeChanged(queueSize, curIndex, curMode)
        val nextShuffle = (nav.navigateNext(queueSize, curIndex, curMode, false) as QueueNavigationOutcome.SwitchTo).targetIndex
        curIndex = nextShuffle
        simulatedPositionMs = 10_000L
        forwarding.seekToPrevious()
        assertEquals(3, curIndex)

        assertTrue(
            "Underlying ExoPlayer seekToPrevious must never be invoked so Media3 3s/5s threshold is bypassed",
            baseInvocations.isEmpty()
        )
    }

    // =========================================================================
    // R3: Auto-Skip After All Sources Fail (All 4 Modes, Loop Guard, Notices)
    // =========================================================================

    @Test
    fun autoSkipAdvancesAcrossAllFourModesIncludingSingleLoopAndStopsCleanlyWhenExhausted() {
        val titles = listOf("Song0", "Song1", "Song2")

        // 1. SEQUENTIAL: skips 0 -> 1 -> 2, then stops at queue end
        run {
            val nav = QueueNavigator(Random(1))
            val skipped = mutableSetOf<Int>()
            val d0 = PlaybackRecoveryPolicy.decideAutoSkipOnFailure(
                trackTitle = titles[0], isLocalTrack = false, hasReadySource = true,
                isNetworkConnected = true, preserveExclusions = false, queueSize = 3,
                currentIndex = 0, mode = RepeatMode.SEQUENTIAL, autoSkippedIndices = skipped, queueNavigator = nav
            )
            assertEquals(AutoSkipDecision.SkipTo(1, "《Song0》暂无可用音源，已自动跳至下一首"), d0)
            skipped.add(0)

            val d1 = PlaybackRecoveryPolicy.decideAutoSkipOnFailure(
                trackTitle = titles[1], isLocalTrack = false, hasReadySource = true,
                isNetworkConnected = true, preserveExclusions = false, queueSize = 3,
                currentIndex = 1, mode = RepeatMode.SEQUENTIAL, autoSkippedIndices = skipped, queueNavigator = nav
            )
            assertEquals(AutoSkipDecision.SkipTo(2, "《Song1》暂无可用音源，已自动跳至下一首"), d1)
            skipped.add(1)

            val d2 = PlaybackRecoveryPolicy.decideAutoSkipOnFailure(
                trackTitle = titles[2], isLocalTrack = false, hasReadySource = true,
                isNetworkConnected = true, preserveExclusions = false, queueSize = 3,
                currentIndex = 2, mode = RepeatMode.SEQUENTIAL, autoSkippedIndices = skipped, queueNavigator = nav
            )
            assertEquals(AutoSkipDecision.Stop("《Song2》暂无可用音源，已停止播放", promptImportSource = false), d2)
        }

        // 2. LIST_LOOP & SINGLE_LOOP: both advance to (cur + 1) % size and stop once entire cycle fails
        for (mode in listOf(RepeatMode.LIST_LOOP, RepeatMode.SINGLE_LOOP)) {
            val nav = QueueNavigator(Random(2))
            val skipped = mutableSetOf<Int>()

            val first = PlaybackRecoveryPolicy.decideAutoSkipOnFailure(
                trackTitle = titles[1], isLocalTrack = false, hasReadySource = true,
                isNetworkConnected = true, preserveExclusions = false, queueSize = 3,
                currentIndex = 1, mode = mode, autoSkippedIndices = skipped, queueNavigator = nav
            )
            assertEquals("Mode $mode must skip 1 -> 2", AutoSkipDecision.SkipTo(2, "《Song1》暂无可用音源，已自动跳至下一首"), first)
            skipped.add(1)

            val second = PlaybackRecoveryPolicy.decideAutoSkipOnFailure(
                trackTitle = titles[2], isLocalTrack = false, hasReadySource = true,
                isNetworkConnected = true, preserveExclusions = false, queueSize = 3,
                currentIndex = 2, mode = mode, autoSkippedIndices = skipped, queueNavigator = nav
            )
            assertEquals("Mode $mode must wrap 2 -> 0", AutoSkipDecision.SkipTo(0, "《Song2》暂无可用音源，已自动跳至下一首"), second)
            skipped.add(2)

            val third = PlaybackRecoveryPolicy.decideAutoSkipOnFailure(
                trackTitle = titles[0], isLocalTrack = false, hasReadySource = true,
                isNetworkConnected = true, preserveExclusions = false, queueSize = 3,
                currentIndex = 0, mode = mode, autoSkippedIndices = skipped, queueNavigator = nav
            )
            assertEquals("Mode $mode must stop after all 3 tracks fail", AutoSkipDecision.Stop("《Song0》暂无可用音源，已停止播放"), third)
        }

        // 3. SHUFFLE: visits every track once along shuffle timeline and stops cleanly once cycle exhausted
        run {
            val nav = QueueNavigator(Random(99))
            var cur = 0
            nav.onQueueReplaced(4, cur, RepeatMode.SHUFFLE)
            val skipped = mutableSetOf<Int>()
            val visited = mutableListOf(cur)

            repeat(3) {
                val decision = PlaybackRecoveryPolicy.decideAutoSkipOnFailure(
                    trackTitle = "T$cur", isLocalTrack = false, hasReadySource = true,
                    isNetworkConnected = true, preserveExclusions = false, queueSize = 4,
                    currentIndex = cur, mode = RepeatMode.SHUFFLE, autoSkippedIndices = skipped, queueNavigator = nav
                )
                assertTrue("Expected SkipTo, got $decision", decision is AutoSkipDecision.SkipTo)
                val target = (decision as AutoSkipDecision.SkipTo).targetIndex
                skipped.add(cur)
                val out = nav.navigateNext(4, cur, RepeatMode.SHUFFLE, isAutoAdvance = true)
                assertEquals(QueueNavigationOutcome.SwitchTo(target), out)
                cur = target
                visited.add(cur)
            }
            assertEquals(setOf(0, 1, 2, 3), visited.toSet())

            val terminal = PlaybackRecoveryPolicy.decideAutoSkipOnFailure(
                trackTitle = "T$cur", isLocalTrack = false, hasReadySource = true,
                isNetworkConnected = true, preserveExclusions = false, queueSize = 4,
                currentIndex = cur, mode = RepeatMode.SHUFFLE, autoSkippedIndices = skipped, queueNavigator = nav
            )
            assertEquals(AutoSkipDecision.Stop("《T$cur》暂无可用音源，已停止播放"), terminal)
        }
    }

    @Test
    fun autoSkipGuardCasesSingleTrackOfflineNoSourceManualSwitchAndMaxChainCap() {
        val nav = QueueNavigator(Random(11))

        // 1-track queue stops cleanly across all 4 modes
        for (mode in RepeatMode.entries) {
            val d = PlaybackRecoveryPolicy.decideAutoSkipOnFailure(
                trackTitle = "Solo", isLocalTrack = false, hasReadySource = true,
                isNetworkConnected = true, preserveExclusions = false, queueSize = 1,
                currentIndex = 0, mode = mode, autoSkippedIndices = emptySet(), queueNavigator = nav
            )
            assertEquals(AutoSkipDecision.Stop("《Solo》暂无可用音源，已停止播放"), d)
        }

        // Offline fast-fail (A3): must NOT spin through queue when network is disconnected
        val offline = PlaybackRecoveryPolicy.decideAutoSkipOnFailure(
            trackTitle = "OfflineTrack", isLocalTrack = false, hasReadySource = true,
            isNetworkConnected = false, preserveExclusions = false, queueSize = 5,
            currentIndex = 0, mode = RepeatMode.LIST_LOOP, autoSkippedIndices = emptySet(), queueNavigator = nav,
            fallbackErrorMessage = "网络波动导致播放中断，恢复网络后点击播放可从原位置继续"
        )
        assertEquals(
            AutoSkipDecision.Stop("网络波动导致播放中断，恢复网络后点击播放可从原位置继续", promptImportSource = false),
            offline
        )

        // No ready source: prompts "导入音源" action
        val noSource = PlaybackRecoveryPolicy.decideAutoSkipOnFailure(
            trackTitle = "Any", isLocalTrack = false, hasReadySource = false,
            isNetworkConnected = true, preserveExclusions = false, queueSize = 5,
            currentIndex = 0, mode = RepeatMode.LIST_LOOP, autoSkippedIndices = emptySet(), queueNavigator = nav,
            fallbackErrorMessage = "尚未导入自定义音源，点击前往导入 .js 自定义源"
        )
        assertEquals(
            AutoSkipDecision.Stop("尚未导入自定义音源，点击前往导入 .js 自定义源", promptImportSource = true),
            noSource
        )

        // Manual alternate-source switch (preserveExclusions = true): does not jump to next song
        val manualAlt = PlaybackRecoveryPolicy.decideAutoSkipOnFailure(
            trackTitle = "Any", isLocalTrack = false, hasReadySource = true,
            isNetworkConnected = true, preserveExclusions = true, queueSize = 5,
            currentIndex = 0, mode = RepeatMode.LIST_LOOP, autoSkippedIndices = emptySet(), queueNavigator = nav
        )
        assertEquals(
            AutoSkipDecision.Stop("暂无其他可用音源或平台，点击播放可重新尝试默认音源", promptImportSource = false),
            manualAlt
        )

        // Manual switchSource / exhausted runtime retries after manual switch (allowAutoSkip = false)
        // must suppress auto-skip initially and across runtime retries, whereas normal playback skips.
        var allowAutoSkip = PlaybackRecoveryPolicy.resolveAllowAutoSkipForLoad(
            currentAllowAutoSkip = true,
            isRuntimeFallbackRetry = false,
            preserveExclusions = false,
            allowAutoSkipOnFailure = false // switchSource
        )
        assertFalse(allowAutoSkip)
        // Initial resolution failure on switchSource -> Stop with error message (no skip)
        val switchSourceResolveFail = PlaybackRecoveryPolicy.decideAutoSkipOnFailure(
            trackTitle = "Any", isLocalTrack = false, hasReadySource = true,
            isNetworkConnected = true, preserveExclusions = false, allowAutoSkip = allowAutoSkip,
            queueSize = 5, currentIndex = 0, mode = RepeatMode.LIST_LOOP,
            autoSkippedIndices = emptySet(), queueNavigator = nav,
            fallbackErrorMessage = "指定音源未能解析有效播放地址"
        )
        assertEquals(
            AutoSkipDecision.Stop("指定音源未能解析有效播放地址", promptImportSource = false),
            switchSourceResolveFail
        )

        // Runtime retries after switchSource preserve allowAutoSkip = false
        repeat(PlaybackRecoveryPolicy.MAX_RUNTIME_RETRIES) {
            allowAutoSkip = PlaybackRecoveryPolicy.resolveAllowAutoSkipForLoad(
                currentAllowAutoSkip = allowAutoSkip,
                isRuntimeFallbackRetry = true,
                preserveExclusions = false,
                allowAutoSkipOnFailure = true
            )
            assertFalse("Runtime retry must preserve allowAutoSkip = false for switchSource", allowAutoSkip)
        }
        val switchSourceRuntimeExhausted = PlaybackRecoveryPolicy.decideAutoSkipOnFailure(
            trackTitle = "Any", isLocalTrack = false, hasReadySource = true,
            isNetworkConnected = true, preserveExclusions = false, allowAutoSkip = allowAutoSkip,
            queueSize = 5, currentIndex = 0, mode = RepeatMode.LIST_LOOP,
            autoSkippedIndices = emptySet(), queueNavigator = nav,
            fallbackErrorMessage = "当前曲目播放失败，可尝试切换音质或手动下一首"
        )
        assertEquals(
            AutoSkipDecision.Stop("当前曲目播放失败，可尝试切换音质或手动下一首", promptImportSource = false),
            switchSourceRuntimeExhausted
        )

        // switchToAlternateSource (preserveExclusions = true) also suppresses auto-skip across runtime retries
        var altAllowAutoSkip = PlaybackRecoveryPolicy.resolveAllowAutoSkipForLoad(
            currentAllowAutoSkip = true,
            isRuntimeFallbackRetry = false,
            preserveExclusions = true,
            allowAutoSkipOnFailure = true
        )
        assertFalse(altAllowAutoSkip)
        repeat(PlaybackRecoveryPolicy.MAX_RUNTIME_RETRIES) {
            altAllowAutoSkip = PlaybackRecoveryPolicy.resolveAllowAutoSkipForLoad(
                currentAllowAutoSkip = altAllowAutoSkip,
                isRuntimeFallbackRetry = true,
                preserveExclusions = false,
                allowAutoSkipOnFailure = true
            )
            assertFalse("Runtime retry must preserve allowAutoSkip = false for switchToAlternateSource", altAllowAutoSkip)
        }
        val altRuntimeExhausted = PlaybackRecoveryPolicy.decideAutoSkipOnFailure(
            trackTitle = "Any", isLocalTrack = false, hasReadySource = true,
            isNetworkConnected = true, preserveExclusions = false, allowAutoSkip = altAllowAutoSkip,
            queueSize = 5, currentIndex = 0, mode = RepeatMode.LIST_LOOP,
            autoSkippedIndices = emptySet(), queueNavigator = nav,
            fallbackErrorMessage = "当前曲目播放失败，可尝试切换音质或手动下一首"
        )
        assertEquals(
            AutoSkipDecision.Stop("当前曲目播放失败，可尝试切换音质或手动下一首", promptImportSource = false),
            altRuntimeExhausted
        )

        // Fresh normal playback load resets allowAutoSkip back to true and auto-skips on exhausted retries
        var normalAllowAutoSkip = PlaybackRecoveryPolicy.resolveAllowAutoSkipForLoad(
            currentAllowAutoSkip = altAllowAutoSkip,
            isRuntimeFallbackRetry = false,
            preserveExclusions = false,
            allowAutoSkipOnFailure = true
        )
        assertTrue(normalAllowAutoSkip)
        repeat(PlaybackRecoveryPolicy.MAX_RUNTIME_RETRIES) {
            normalAllowAutoSkip = PlaybackRecoveryPolicy.resolveAllowAutoSkipForLoad(
                currentAllowAutoSkip = normalAllowAutoSkip,
                isRuntimeFallbackRetry = true,
                preserveExclusions = false,
                allowAutoSkipOnFailure = true
            )
            assertTrue(normalAllowAutoSkip)
        }
        val normalRuntimeExhausted = PlaybackRecoveryPolicy.decideAutoSkipOnFailure(
            trackTitle = "Any", isLocalTrack = false, hasReadySource = true,
            isNetworkConnected = true, preserveExclusions = false, allowAutoSkip = normalAllowAutoSkip,
            queueSize = 5, currentIndex = 0, mode = RepeatMode.LIST_LOOP,
            autoSkippedIndices = emptySet(), queueNavigator = nav,
            fallbackErrorMessage = "当前曲目播放失败，可尝试切换音质或手动下一首"
        )
        assertEquals(
            AutoSkipDecision.SkipTo(1, "《Any》暂无可用音源，已自动跳至下一首"),
            normalRuntimeExhausted
        )

        // Cap at min(queueSize, 10) for large queues (15 tracks)
        val largeSkipped = mutableSetOf<Int>()
        for (idx in 0 until 9) {
            val d = PlaybackRecoveryPolicy.decideAutoSkipOnFailure(
                trackTitle = "T$idx", isLocalTrack = false, hasReadySource = true,
                isNetworkConnected = true, preserveExclusions = false, queueSize = 15,
                currentIndex = idx, mode = RepeatMode.LIST_LOOP, autoSkippedIndices = largeSkipped, queueNavigator = nav
            )
            assertTrue("Step $idx should skip", d is AutoSkipDecision.SkipTo)
            largeSkipped.add(idx)
        }
        val cappedStop = PlaybackRecoveryPolicy.decideAutoSkipOnFailure(
            trackTitle = "T9", isLocalTrack = false, hasReadySource = true,
            isNetworkConnected = true, preserveExclusions = false, queueSize = 15,
            currentIndex = 9, mode = RepeatMode.LIST_LOOP, autoSkippedIndices = largeSkipped, queueNavigator = nav
        )
        assertEquals(AutoSkipDecision.Stop("《T9》暂无可用音源，已停止播放"), cappedStop)
    }

    // =========================================================================
    // R4: Multi-Source Racing, Cancellation & Connection / Buffer Tuning
    // =========================================================================

    @Test
    fun multiSourceRacingSelectsFasterLowerPrioritySourceAndCancelsSlowerPriorityZero() = runBlocking {
        val track = testTrack()
        val slowPrimary = testSource("slow0", priority = 0)
        val fastBackup = testSource("fast1", priority = 1)
        val medBackup = testSource("med2", priority = 2)

        val slowStarted = CompletableDeferred<Unit>()
        val slowCancelled = CompletableDeferred<Unit>()
        val medCancelled = CompletableDeferred<Unit>()

        val coordinator = SourceRaceCoordinator(
            isMeteredNetwork = { false },
            isInCooldown = { false },
            markCooldown = { _, _ -> },
            clearCooldown = { },
            fetchStreamUrl = { candidate, _ ->
                when (candidate.source.id) {
                    "slow0" -> {
                        slowStarted.complete(Unit)
                        try {
                            delay(3_000L)
                            Result.success("https://cdn.example.com/slow0.flac")
                        } catch (ce: CancellationException) {
                            slowCancelled.complete(Unit)
                            throw ce
                        }
                    }
                    "fast1" -> {
                        delay(35L)
                        Result.success("https://cdn.example.com/fast1.flac")
                    }
                    else -> {
                        try {
                            delay(1_500L)
                            Result.success("https://cdn.example.com/med2.flac")
                        } catch (ce: CancellationException) {
                            medCancelled.complete(Unit)
                            throw ce
                        }
                    }
                }
            },
            verifyStreamUrl = { true }
        )

        val startMs = System.currentTimeMillis()
        val resolved = coordinator.tryResolveQualityTiersOrdered(
            tracks = listOf(track),
            qualityChain = listOf(AudioQuality.FLAC, AudioQuality.Q320K, AudioQuality.Q128K),
            sources = listOf(slowPrimary, fastBackup, medBackup),
            switchedPlatform = false,
            prunedSourcePlatforms = ConcurrentHashMap.newKeySet(),
            prunedSourceTracks = ConcurrentHashMap.newKeySet(),
            trackMissCounts = ConcurrentHashMap(),
            includeCooling = false,
            initialSwitched = false,
            deadlineMs = startMs + 10_000L
        )
        val elapsedMs = System.currentTimeMillis() - startMs

        assertNotNull(resolved)
        assertEquals("fast1", resolved!!.sourceId)
        assertEquals("https://cdn.example.com/fast1.flac", resolved.url)
        assertFalse("Wave 0 racing win must not trigger switchedSource notice", resolved.switchedSource)
        assertTrue("Expected fast win without waiting for 3s primary, took ${elapsedMs}ms", elapsedMs < 1_000L)

        // Confirm slow0 started and both losing wave tasks were cancelled promptly
        slowStarted.await()
        slowCancelled.await()
        medCancelled.await()
    }

    @Test
    fun multiSourceRacingFallsBackOnInvalidProbeUsingRealHttpServerAndStrictDownwardQuality() = runBlocking {
        // Spin up a local HTTP server serving:
        // /bad.mp3  -> 200 OK text/html error stub (rejected by verifyPlayableAudioUrlWithClient)
        // /good.mp3 -> 206 Partial Content audio/mpeg (accepted by verifyPlayableAudioUrlWithClient)
        val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val serverThread = thread(isDaemon = true) {
            while (!server.isClosed) {
                val sock = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    sock.use { s ->
                        val reader = s.getInputStream().bufferedReader()
                        val requestLine = reader.readLine().orEmpty()
                        while (true) {
                            val line = reader.readLine()
                            if (line.isNullOrEmpty()) break
                        }
                        val out = s.getOutputStream()
                        if (requestLine.contains("/good.mp3")) {
                            val audioBytes = ByteArray(128) { 0x49 }
                            val headers = "HTTP/1.1 206 Partial Content\r\n" +
                                "Content-Type: audio/mpeg\r\n" +
                                "Content-Length: ${audioBytes.size}\r\n" +
                                "Connection: close\r\n\r\n"
                            out.write(headers.toByteArray())
                            out.write(audioBytes)
                        } else {
                            val body = "<html><body>403 forbidden stub</body></html>".toByteArray()
                            val headers = "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: text/html; charset=utf-8\r\n" +
                                "Content-Length: ${body.size}\r\n" +
                                "Connection: close\r\n\r\n"
                            out.write(headers.toByteArray())
                            out.write(body)
                        }
                        out.flush()
                    }
                }
            }
        }

        val probeClient = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build()

        try {
            val baseUrl = "http://127.0.0.1:${server.localPort}"
            val track = testTrack()
            val fastBad = testSource("fastBad", priority = 0, qualities = listOf("flac", "128k"))
            val backupGood = testSource("backupGood", priority = 1, qualities = listOf("flac", "128k"))
            val attemptedQualities = CopyOnWriteArrayList<AudioQuality>()

            val coordinator = SourceRaceCoordinator(
                isMeteredNetwork = { false },
                isInCooldown = { false },
                markCooldown = { _, _ -> },
                clearCooldown = { },
                fetchStreamUrl = { candidate, _ ->
                    attemptedQualities.add(candidate.quality)
                    when {
                        candidate.quality == AudioQuality.FLAC && candidate.source.id == "fastBad" ->
                            Result.success("$baseUrl/bad.mp3")
                        candidate.quality == AudioQuality.FLAC && candidate.source.id == "backupGood" ->
                            Result.failure(IllegalStateException("当前音质不可用"))
                        candidate.quality == AudioQuality.Q128K && candidate.source.id == "backupGood" -> {
                            delay(25L)
                            Result.success("$baseUrl/good.mp3")
                        }
                        else -> Result.success("$baseUrl/bad.mp3")
                    }
                },
                verifyStreamUrl = { url -> UserApiManager.verifyPlayableAudioUrlWithClient(probeClient, url) }
            )

            val resolved = coordinator.tryResolveQualityTiersOrdered(
                tracks = listOf(track),
                qualityChain = AudioQuality.fallbackChain(AudioQuality.FLAC),
                sources = listOf(fastBad, backupGood),
                switchedPlatform = false,
                prunedSourcePlatforms = ConcurrentHashMap.newKeySet(),
                prunedSourceTracks = ConcurrentHashMap.newKeySet(),
                trackMissCounts = ConcurrentHashMap(),
                includeCooling = false,
                initialSwitched = false,
                deadlineMs = System.currentTimeMillis() + 10_000L
            )

            assertNotNull(resolved)
            assertEquals("backupGood", resolved!!.sourceId)
            assertEquals(AudioQuality.Q128K, resolved.quality)
            assertEquals("$baseUrl/good.mp3", resolved.url)
            assertTrue(attemptedQualities.contains(AudioQuality.FLAC))
            assertTrue(attemptedQualities.contains(AudioQuality.Q128K))

            // Preferred source priority: when preferredSourceId is set, tries preferred source first
            val prefResolved = coordinator.tryResolveTracks(
                tracks = listOf(track),
                preferredQuality = AudioQuality.Q128K,
                sources = listOf(fastBad, backupGood),
                switchedPlatform = false,
                preferredSourceId = "backupGood",
                prunedSourcePlatforms = ConcurrentHashMap.newKeySet(),
                prunedSourceTracks = ConcurrentHashMap.newKeySet(),
                trackMissCounts = ConcurrentHashMap(),
                includeCooling = false,
                deadlineMs = System.currentTimeMillis() + 10_000L
            )
            assertNotNull(prefResolved)
            assertEquals("backupGood", prefResolved!!.sourceId)
            assertFalse("Preferred source winning directly should have switchedSource=false", prefResolved.switchedSource)
        } finally {
            runCatching { server.close() }
            serverThread.join(1_000L)
        }
    }

    @Test
    fun parentChildHttpCallTrackerCancelsOnlyLosingParentCallsWhileKeepingOtherRequestsAlive() {
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
        val tracker = ParentChildHttpCallTracker()

        try {
            val url = "http://127.0.0.1:${server.localPort}/hang"
            assertTrue(tracker.registerChild("child_a1", "req_A"))
            assertTrue(tracker.registerChild("child_a2", "req_A"))
            assertTrue(tracker.registerChild("child_b1", "req_B"))

            val callA1 = client.newCall(Request.Builder().url(url).build())
            val callA2 = client.newCall(Request.Builder().url(url).build())
            val callB1 = client.newCall(Request.Builder().url(url).build())

            assertTrue(tracker.attachCall("child_a1", callA1))
            assertTrue(tracker.attachCall("child_a2", callA2))
            assertTrue(tracker.attachCall("child_b1", callB1))

            // Cancel req_A only; req_B is still pending and must stay completely intact!
            tracker.cancelParent("req_A")

            assertTrue(callA1.isCanceled())
            assertTrue(callA2.isCanceled())
            assertTrue(tracker.isCancelled("child_a1"))
            assertFalse("Unrelated pending request req_B must NOT be cancelled", callB1.isCanceled())
            assertFalse(tracker.isCancelled("child_b1"))

            // Any late child registration under cancelled req_A is rejected immediately
            assertFalse(tracker.registerChild("child_a3", "req_A"))
            assertTrue(tracker.registerChild("child_b2", "req_B"))

            tracker.completeChild("child_b1", callB1)
            tracker.completeChild("child_b2", null)
        } finally {
            tracker.destroy()
            runCatching { server.close() }
        }
    }

    @Test
    fun awaitInitialSourcesReadyCapsPrimaryWaitAndSharesStreamPoolAndBufferTuning() = runBlocking {
        // Primary (#0) stays INITIALIZING forever while backup (#1) is already READY
        val initPrimary = testSource("primary", priority = 0, status = UserApiRuntimeStatus.INITIALIZING)
        val readyBackup = testSource("backup", priority = 1, status = UserApiRuntimeStatus.READY)

        val startMs = System.currentTimeMillis()
        UserApiManager.awaitInitialSourcesReady(
            sourcesSnapshot = { listOf(initPrimary, readyBackup) },
            timeoutMs = 4_500L,
            primaryGraceMs = UserApiManager.INITIAL_PRIMARY_GRACE_MS,
            preferredSourceId = null
        )
        val elapsed = System.currentTimeMillis() - startMs
        assertTrue(
            "Should cap wait around 600ms instead of blocking 4.5s, took ${elapsed}ms",
            elapsed in 500L..1_800L
        )

        // Verify PlaybackService startup buffer tuning
        assertEquals(700, PlaybackService.BUFFER_FOR_PLAYBACK_MS)

        // Verify UserApiManager.createProbeClient shares streamHttpClient's ConnectionPool
        val streamPool = ConnectionPool(6, 2, TimeUnit.MINUTES)
        val streamClient = OkHttpClient.Builder().connectionPool(streamPool).build()
        val probeClient = UserApiManager.createProbeClient(streamClient)
        assertSame(
            "probeClient must share streamHttpClient's ConnectionPool for TCP/TLS socket reuse",
            streamPool,
            probeClient.connectionPool
        )
    }
}
