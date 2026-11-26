package com.lxpure.music

import androidx.media3.common.AudioAttributes
import androidx.media3.common.DeviceInfo
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import com.lxpure.music.core.player.CommandOverridingPlayerListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class PlaybackServiceListenerDelegationTest {

    /**
     * Exhaustively verifies via JVM reflection + dynamic proxy that EVERY SINGLE method declared on
     * Media3's Java `Player.Listener` interface (all 37 default methods) is genuinely overridden
     * in bytecode and forwarded to the underlying delegate by `CommandOverridingPlayerListener`.
     *
     * Guards permanently against the Kotlin `by delegate` Java-default-method pitfall that silently
     * swallowed `onIsPlayingChanged`, `onPlaybackStateChanged`, `onMediaItemTransition`, etc.
     */
    @Test
    fun commandOverridingListenerForwardsEverySinglePlayerListenerMethod() {
        val invokedSignatures = mutableSetOf<String>()

        val recordingDelegate = Proxy.newProxyInstance(
            Player.Listener::class.java.classLoader,
            arrayOf(Player.Listener::class.java)
        ) { _, method, _ ->
            if (method.declaringClass == Player.Listener::class.java) {
                val sig = "${method.name}(${method.parameterTypes.joinToString(",") { it.simpleName }})"
                invokedSignatures.add(sig)
            }
            null
        } as Player.Listener

        var transformCallCount = 0
        var receivedBaseCommands: Player.Commands? = null
        val wrapper = CommandOverridingPlayerListener(
            delegate = recordingDelegate,
            transformCommands = { base ->
                transformCallCount++
                receivedBaseCommands = base
                base
            }
        )

        // 1. Verify at the JVM bytecode level that CommandOverridingPlayerListener explicitly
        // declares every single method from Player.Listener (not inheriting Java default no-ops!).
        val interfaceMethods = Player.Listener::class.java.declaredMethods
            .filter { !it.isSynthetic }
        assertTrue("Expected >= 35 Player.Listener methods, got ${interfaceMethods.size}", interfaceMethods.size >= 35)

        val missingOverrides = mutableListOf<String>()
        for (m in interfaceMethods) {
            val declaredOnWrapper = runCatching {
                CommandOverridingPlayerListener::class.java.getDeclaredMethod(m.name, *m.parameterTypes)
            }.getOrNull()
            if (declaredOnWrapper == null) {
                missingOverrides.add("${m.name}(${m.parameterTypes.joinToString(",") { it.simpleName }})")
            }
        }
        assertEquals(
            "CommandOverridingPlayerListener MUST explicitly override every Player.Listener method",
            emptyList<String>(),
            missingOverrides
        )

        // 2. Dynamically invoke EVERY Player.Listener method on wrapper and confirm delegate receives it!
        val dummyPlayer = Proxy.newProxyInstance(
            Player::class.java.classLoader,
            arrayOf(Player::class.java)
        ) { _, method, _ ->
            when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                java.lang.Float.TYPE -> 0f
                else -> null
            }
        } as Player

        val dummyEvents = Player.Events(androidx.media3.common.FlagSet.Builder().build())
        val dummyPos = Player.PositionInfo(null, 0, null, null, 0, 0L, 0L, -1, -1)
        val dummyError = allocateDummyPlaybackException()
        val emptyCommands = Player.Commands.EMPTY

        for (m in interfaceMethods) {
            val args = m.parameterTypes.map { type ->
                when (type) {
                    java.lang.Boolean.TYPE -> true
                    java.lang.Integer.TYPE -> 1
                    java.lang.Long.TYPE -> 100L
                    java.lang.Float.TYPE -> 0.5f
                    Player::class.java -> dummyPlayer
                    Player.Events::class.java -> dummyEvents
                    Timeline::class.java -> Timeline.EMPTY
                    MediaItem::class.java -> MediaItem.EMPTY
                    Tracks::class.java -> Tracks.EMPTY
                    MediaMetadata::class.java -> MediaMetadata.EMPTY
                    Player.Commands::class.java -> emptyCommands
                    TrackSelectionParameters::class.java -> TrackSelectionParameters.DEFAULT_WITHOUT_CONTEXT
                    PlaybackException::class.java -> dummyError
                    Player.PositionInfo::class.java -> dummyPos
                    PlaybackParameters::class.java -> PlaybackParameters.DEFAULT
                    AudioAttributes::class.java -> AudioAttributes.DEFAULT
                    DeviceInfo::class.java -> DeviceInfo.UNKNOWN
                    VideoSize::class.java -> VideoSize.UNKNOWN
                    java.util.List::class.java -> mutableListOf<Cue>()
                    CueGroup::class.java -> CueGroup.EMPTY_TIME_ZERO
                    Metadata::class.java -> Metadata(emptyList())
                    else -> null
                }
            }.toTypedArray()

            m.invoke(wrapper, *args)
        }

        val expectedSignatures = interfaceMethods.map { m ->
            "${m.name}(${m.parameterTypes.joinToString(",") { it.simpleName }})"
        }.toSet()

        assertEquals(
            "Every single Player.Listener callback must reach the underlying delegate",
            expectedSignatures,
            invokedSignatures
        )
        assertEquals(1, transformCallCount)
        assertSame(emptyCommands, receivedBaseCommands)
    }

    /**
     * End-to-end test through a real Media3 `ForwardingPlayer`:
     * simulates ExoPlayer emitting track switch, buffering, ready, isPlaying=true, auto-advance,
     * and playback end, asserting that the MediaSession-side listener receives every single event
     * without loss, and that `removeListener` unregisters cleanly.
     */
    @Test
    fun realForwardingPlayerPropagatesPrevNextAndAutoPlayEventsEndToEnd() {
        val registeredOnUnderlying = mutableListOf<Player.Listener>()
        val fakeUnderlyingPlayer = Proxy.newProxyInstance(
            Player::class.java.classLoader,
            arrayOf(Player::class.java)
        ) { _, method, args ->
            when (method.name) {
                "addListener" -> {
                    registeredOnUnderlying.add(args!![0] as Player.Listener)
                    null
                }
                "removeListener" -> {
                    registeredOnUnderlying.remove(args!![0] as Player.Listener)
                    null
                }
                else -> when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Integer.TYPE -> Player.STATE_READY
                    java.lang.Long.TYPE -> 0L
                    java.lang.Float.TYPE -> 1f
                    else -> null
                }
            }
        } as Player

        val wrappedMap = java.util.IdentityHashMap<Player.Listener, Player.Listener>()
        var transformInvocations = 0
        val forwardingPlayer = object : ForwardingPlayer(fakeUnderlyingPlayer) {
            override fun addListener(listener: Player.Listener) {
                val proxy = wrappedMap.getOrPut(listener) {
                    CommandOverridingPlayerListener(listener) { base ->
                        transformInvocations++
                        base
                    }
                }
                super.addListener(proxy)
            }

            override fun removeListener(listener: Player.Listener) {
                val proxy = wrappedMap.remove(listener) ?: listener
                super.removeListener(proxy)
            }
        }

        val observedPlaying = mutableListOf<Boolean>()
        val observedStates = mutableListOf<Int>()
        val observedPlayWhenReady = mutableListOf<Boolean>()
        val observedTransitions = mutableListOf<String?>()
        var observedCommandEvents = 0
        var observedEventsCallbacks = 0

        val sessionListener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                observedPlaying.add(isPlaying)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                observedStates.add(playbackState)
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                observedPlayWhenReady.add(playWhenReady)
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                observedTransitions.add(mediaItem?.mediaId)
            }

            override fun onAvailableCommandsChanged(availableCommands: Player.Commands) {
                observedCommandEvents++
            }

            override fun onEvents(player: Player, events: Player.Events) {
                assertSame("onEvents must expose the ForwardingPlayer instance", forwardingPlayer, player)
                observedEventsCallbacks++
            }
        }

        forwardingPlayer.addListener(sessionListener)
        assertEquals(1, registeredOnUnderlying.size)

        // Emit complete lifecycle from underlying ExoPlayer:
        // Prev/Next -> BUFFERING(isPlaying=false) -> READY(isPlaying=true) -> AUTO_TRANSITION -> ENDED(isPlaying=false)
        val underlyingListener = registeredOnUnderlying.single()
        val dummyEvents = Player.Events(androidx.media3.common.FlagSet.Builder().build())
        underlyingListener.onMediaItemTransition(
            MediaItem.Builder().setMediaId("track_next").build(),
            Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED
        )
        underlyingListener.onPlayWhenReadyChanged(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        underlyingListener.onPlaybackStateChanged(Player.STATE_BUFFERING)
        underlyingListener.onIsPlayingChanged(false)
        underlyingListener.onPlaybackStateChanged(Player.STATE_READY)
        underlyingListener.onIsPlayingChanged(true)
        underlyingListener.onEvents(fakeUnderlyingPlayer, dummyEvents)
        underlyingListener.onAvailableCommandsChanged(Player.Commands.EMPTY)
        underlyingListener.onMediaItemTransition(
            MediaItem.Builder().setMediaId("track_auto_next").build(),
            Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
        )
        underlyingListener.onPlaybackStateChanged(Player.STATE_ENDED)
        underlyingListener.onIsPlayingChanged(false)

        assertEquals(listOf("track_next", "track_auto_next"), observedTransitions)
        assertEquals(listOf(true), observedPlayWhenReady)
        assertEquals(listOf(Player.STATE_BUFFERING, Player.STATE_READY, Player.STATE_ENDED), observedStates)
        assertEquals(listOf(false, true, false), observedPlaying)
        assertEquals(1, observedEventsCallbacks)
        assertEquals(1, transformInvocations)
        assertEquals(1, observedCommandEvents)

        // Verify removeListener cleanly unregisters the exact ForwardingListener wrapper
        forwardingPlayer.removeListener(sessionListener)
        assertTrue(registeredOnUnderlying.isEmpty())
        assertTrue(wrappedMap.isEmpty())
    }

    private fun allocateDummyPlaybackException(): PlaybackException {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val theUnsafeField = unsafeClass.getDeclaredField("theUnsafe")
        theUnsafeField.isAccessible = true
        val unsafe = theUnsafeField.get(null)
        val allocateInstance = unsafeClass.getMethod("allocateInstance", Class::class.java)
        return allocateInstance.invoke(unsafe, PlaybackException::class.java) as PlaybackException
    }
}
