package com.lxpure.music.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lxpure.music.core.player.PlayerState
import com.lxpure.music.domain.model.AudioQuality
import com.lxpure.music.domain.model.DownloadRecord
import com.lxpure.music.domain.model.DownloadStatus
import com.lxpure.music.domain.model.RepeatMode
import com.lxpure.music.domain.model.Track
import com.lxpure.music.ui.components.AlbumArtwork
import com.lxpure.music.ui.components.MiniEqualizerIndicator
import com.lxpure.music.ui.components.SyncDialogSystemBars
import com.lxpure.music.ui.theme.FluidAmbientBackground
import com.lxpure.music.ui.theme.LocalLxColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullPlayerScreen(
    state: PlayerState,
    isFavorite: Boolean,
    downloadRecord: DownloadRecord?,
    preferredQuality: AudioQuality,
    fluidAmbientEnabled: Boolean,
    showTranslationDefault: Boolean,
    onCollapse: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onCycleRepeat: () -> Unit,
    onToggleFavorite: (Track) -> Unit,
    onDownload: (Track) -> Unit,
    onOpenQueue: () -> Unit,
    onSelectQuality: (AudioQuality) -> Unit,
    onSetSleepTimer: (Int?) -> Unit
) {
    val track = state.currentTrack ?: return
    var showLyrics by remember { mutableStateOf(false) }
    var showTranslation by remember(showTranslationDefault) { mutableStateOf(showTranslationDefault) }
    var showQualitySheet by remember { mutableStateOf(false) }
    var showSleepSheet by remember { mutableStateOf(false) }

    var isDraggingSlider by remember(track.id) { mutableStateOf(false) }
    var sliderDragMs by remember(track.id) { mutableFloatStateOf(0f) }

    val artworkScale by animateFloatAsState(
        targetValue = if (state.isPlaying) 1.0f else 0.89f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 300f),
        label = "artworkScale"
    )

    Box(modifier = Modifier.fillMaxSize()) {
        FluidAmbientBackground(
            palette = state.ambientPalette,
            enabled = fluidAmbientEnabled,
            immersive = true
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Drag-to-collapse top header
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        var totalDrag = 0f
                        detectVerticalDragGestures(
                            onDragEnd = {
                                if (totalDrag > 90f) onCollapse()
                                totalDrag = 0f
                            },
                            onVerticalDrag = { _, delta ->
                                if (delta > 0f) totalDrag += delta
                            }
                        )
                    }
                    .padding(top = 8.dp, bottom = 6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .width(38.dp)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.28f))
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(onClick = onCollapse) {
                        Icon(
                            imageVector = Icons.Rounded.KeyboardArrowDown,
                            contentDescription = "收起播放页",
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(30.dp)
                        )
                    }

                    // Minimalist source & quality badge pill
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = Color.White.copy(alpha = 0.12f),
                        modifier = Modifier.clickable {
                            if (!track.isLocal) showQualitySheet = true
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = buildString {
                                    append(state.activeProviderName ?: track.source.displayName)
                                    state.activeQualityBadge?.let { append(" · $it") }
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White.copy(alpha = 0.9f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    IconButton(onClick = { showSleepSheet = true }) {
                        Icon(
                            imageVector = Icons.Rounded.Bedtime,
                            contentDescription = "睡眠定时",
                            tint = if (state.sleepTimerRemainingMs != null) {
                                state.ambientPalette.accent
                            } else {
                                Color.White.copy(alpha = 0.72f)
                            },
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }

            // Center Stage: Seamless toggle between Floating Cover & Immersive Lyrics
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                AnimatedContent(
                    targetState = showLyrics,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "coverLyricSwitch"
                ) { lyricsVisible ->
                    if (!lyricsVisible) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.84f)
                                    .aspectRatio(1f)
                                    .scale(artworkScale)
                                    .shadow(
                                        elevation = 32.dp,
                                        shape = RoundedCornerShape(26.dp),
                                        ambientColor = Color.Black.copy(alpha = 0.65f),
                                        spotColor = Color.Black.copy(alpha = 0.65f)
                                    )
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) { showLyrics = true }
                            ) {
                                AlbumArtwork(
                                    url = track.coverUrl,
                                    contentDescription = track.title,
                                    size = 340.dp,
                                    cornerRadius = 26.dp,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }

                            Spacer(Modifier.height(26.dp))

                            // Live single-line lyric teaser right under the cover
                            val currentLine = state.lyrics.lines.getOrNull(state.activeLyricIndex)?.text
                            Text(
                                text = currentLine ?: if (state.isLoadingLyrics) "正在匹配歌词…" else "轻点封面查看沉浸歌词",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = if (currentLine != null) 0.78f else 0.42f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showLyrics = true }
                                    .padding(horizontal = 8.dp)
                            )
                        }
                    } else {
                        ImmersiveLyricsView(
                            state = state,
                            showTranslation = showTranslation,
                            onSeekTo = onSeek,
                            onTapEmpty = { showLyrics = false },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }

            // Track Meta + Favorite Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.headlineMedium.copy(fontSize = 22.sp),
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = buildString {
                            append(track.artist)
                            if (track.album.isNotBlank()) append(" · ${track.album}")
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.68f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                IconButton(onClick = { onToggleFavorite(track) }) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        contentDescription = "喜欢",
                        tint = if (isFavorite) Color(0xFFFB7185) else Color.White.copy(alpha = 0.78f),
                        modifier = Modifier.size(26.dp)
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // Minimalist Capsule Progress Scrubber (replaces heavy default M3 Slider)
            val duration = state.durationMs.coerceAtLeast(1L)
            val displayPos = if (isDraggingSlider) sliderDragMs.toLong() else state.positionMs
            val progressFraction = (displayPos.toFloat() / duration.toFloat()).coerceIn(0f, 1f)

            MinimalCapsuleProgressBar(
                progress = progressFraction,
                isDragging = isDraggingSlider,
                onScrubStart = { fraction ->
                    isDraggingSlider = true
                    sliderDragMs = (fraction * duration).coerceIn(0f, duration.toFloat())
                },
                onScrub = { fraction ->
                    isDraggingSlider = true
                    sliderDragMs = (fraction * duration).coerceIn(0f, duration.toFloat())
                },
                onScrubEnd = { fraction ->
                    val target = (fraction * duration).toLong().coerceIn(0L, duration)
                    isDraggingSlider = false
                    onSeek(target)
                },
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatTime(displayPos),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.65f)
                )
                if (state.sleepTimerRemainingMs != null) {
                    Text(
                        text = "定时 ${formatTime(state.sleepTimerRemainingMs)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = state.ambientPalette.accent
                    )
                }
                Text(
                    text = formatTime(state.durationMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.65f)
                )
            }

            Spacer(Modifier.height(14.dp))

            // Core Transport Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(onClick = onCycleRepeat) {
                    val modeIcon = when (state.repeatMode) {
                        RepeatMode.LIST_LOOP -> Icons.Rounded.Repeat
                        RepeatMode.SHUFFLE -> Icons.Rounded.Shuffle
                        RepeatMode.SINGLE_LOOP -> Icons.Rounded.RepeatOne
                    }
                    Icon(
                        imageVector = modeIcon,
                        contentDescription = state.repeatMode.label,
                        tint = Color.White.copy(alpha = 0.82f),
                        modifier = Modifier.size(24.dp)
                    )
                }

                IconButton(onClick = onPrevious, modifier = Modifier.size(54.dp)) {
                    Icon(
                        imageVector = Icons.Rounded.SkipPrevious,
                        contentDescription = "上一首",
                        tint = Color.White,
                        modifier = Modifier.size(38.dp)
                    )
                }

                Surface(
                    onClick = onTogglePlayPause,
                    shape = CircleShape,
                    color = Color.White,
                    contentColor = Color(0xFF0A0B0F),
                    modifier = Modifier.size(68.dp),
                    shadowElevation = 10.dp
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (state.isBuffering) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(28.dp),
                                strokeWidth = 2.6.dp,
                                color = Color(0xFF0A0B0F)
                            )
                        } else {
                            Icon(
                                imageVector = if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = if (state.isPlaying) "暂停" else "播放",
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    }
                }

                IconButton(onClick = onNext, modifier = Modifier.size(54.dp)) {
                    Icon(
                        imageVector = Icons.Rounded.SkipNext,
                        contentDescription = "下一首",
                        tint = Color.White,
                        modifier = Modifier.size(38.dp)
                    )
                }

                IconButton(onClick = onOpenQueue) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.QueueMusic,
                        contentDescription = "播放队列",
                        tint = Color.White.copy(alpha = 0.82f),
                        modifier = Modifier.size(25.dp)
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            // Minimalist Bottom Utility Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 14.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { showLyrics = !showLyrics }) {
                    Icon(
                        imageVector = Icons.Rounded.Lyrics,
                        contentDescription = "歌词",
                        tint = if (showLyrics) Color.White else Color.White.copy(alpha = 0.55f),
                        modifier = Modifier.size(22.dp)
                    )
                }
                if (state.lyrics.hasTranslation) {
                    IconButton(onClick = { showTranslation = !showTranslation }) {
                        Icon(
                            imageVector = Icons.Rounded.Translate,
                            contentDescription = "翻译歌词",
                            tint = if (showTranslation) Color.White else Color.White.copy(alpha = 0.45f),
                            modifier = Modifier.size(21.dp)
                        )
                    }
                }
                if (!track.isLocal) {
                    IconButton(onClick = { showQualitySheet = true }) {
                        Icon(
                            imageVector = Icons.Rounded.GraphicEq,
                            contentDescription = "音质切换",
                            tint = Color.White.copy(alpha = 0.65f),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    IconButton(onClick = { onDownload(track) }) {
                        when (downloadRecord?.status) {
                            DownloadStatus.QUEUED -> {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = Color.White.copy(alpha = 0.85f)
                                )
                            }
                            DownloadStatus.DOWNLOADING -> {
                                if (downloadRecord.progress > 0.02f) {
                                    CircularProgressIndicator(
                                        progress = { downloadRecord.progress.coerceIn(0f, 1f) },
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.2.dp,
                                        color = Color(0xFF34D399),
                                        trackColor = Color.White.copy(alpha = 0.22f)
                                    )
                                } else {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp,
                                        color = Color.White.copy(alpha = 0.85f)
                                    )
                                }
                            }
                            DownloadStatus.COMPLETED -> {
                                Icon(
                                    imageVector = Icons.Rounded.DownloadDone,
                                    contentDescription = "已离线下载",
                                    tint = Color(0xFF34D399),
                                    modifier = Modifier.size(23.dp)
                                )
                            }
                            else -> {
                                Icon(
                                    imageVector = Icons.Rounded.Download,
                                    contentDescription = "离线下载",
                                    tint = Color.White.copy(alpha = 0.65f),
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showQualitySheet) {
        val lxColors = LocalLxColors.current
        ModalBottomSheet(
            onDismissRequest = { showQualitySheet = false },
            containerColor = lxColors.surfaceElevated
        ) {
            SyncDialogSystemBars(useLightSystemBars = false)
            Column(
                modifier = Modifier
                    .padding(horizontal = 20.dp, vertical = 10.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("切换音质", style = MaterialTheme.typography.titleLarge, color = lxColors.textPrimary)
                Spacer(Modifier.height(6.dp))
                AudioQuality.entries.reversed().forEach { q ->
                    val normalizedBadge = state.activeQualityBadge?.substringBefore(" · ")?.trim()
                    val isCurrent = when {
                        normalizedBadge != null && AudioQuality.entries.any { it.badge.equals(normalizedBadge, ignoreCase = true) } ->
                            q.badge.equals(normalizedBadge, ignoreCase = true)
                        else -> q == preferredQuality
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isCurrent) lxColors.accentSoft else Color.Transparent)
                            .clickable {
                                onSelectQuality(q)
                                showQualitySheet = false
                            }
                            .padding(horizontal = 14.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = q.label,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal
                            ),
                            color = if (isCurrent) lxColors.accent else lxColors.textPrimary
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = q.badge,
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = if (isCurrent) lxColors.accent else lxColors.textSecondary
                            )
                            if (isCurrent) {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = "当前音质",
                                    tint = lxColors.accent,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSleepSheet) {
        val lxColors = LocalLxColors.current
        ModalBottomSheet(
            onDismissRequest = { showSleepSheet = false },
            containerColor = lxColors.surfaceElevated
        ) {
            SyncDialogSystemBars(useLightSystemBars = false)
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp).padding(bottom = 24.dp)) {
                Text("睡眠定时", style = MaterialTheme.typography.titleLarge, color = lxColors.textPrimary)
                Spacer(Modifier.height(10.dp))
                listOf(null to "关闭定时", 15 to "15 分钟后暂停", 30 to "30 分钟后暂停", 45 to "45 分钟后暂停", 60 to "60 分钟后暂停")
                    .forEach { (mins, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    onSetSleepTimer(mins)
                                    showSleepSheet = false
                                }
                                .padding(horizontal = 12.dp, vertical = 14.dp)
                        ) {
                            Text(label, style = MaterialTheme.typography.bodyLarge, color = lxColors.textPrimary)
                        }
                    }
            }
        }
    }
}

/**
 * Apple Music-inspired minimalist capsule scrubber:
 * 4dp hair-thin track at rest, smoothly expands to 8dp when scrubbed, zero bulky thumb bar.
 */
@Composable
private fun MinimalCapsuleProgressBar(
    progress: Float,
    isDragging: Boolean,
    onScrubStart: (Float) -> Unit,
    onScrub: (Float) -> Unit,
    onScrubEnd: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var widthPx by remember { mutableFloatStateOf(1f) }
    var lastFraction by remember { mutableFloatStateOf(progress) }
    val trackHeight by animateDpAsState(
        targetValue = if (isDragging) 8.dp else 4.dp,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 420f),
        label = "capsuleTrackHeight"
    )

    Box(
        modifier = modifier
            .height(28.dp)
            .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val f = (offset.x / widthPx).coerceIn(0f, 1f)
                    lastFraction = f
                    onScrubEnd(f)
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        val f = (offset.x / widthPx).coerceIn(0f, 1f)
                        lastFraction = f
                        onScrubStart(f)
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        val f = (change.position.x / widthPx).coerceIn(0f, 1f)
                        lastFraction = f
                        onScrub(f)
                    },
                    onDragEnd = { onScrubEnd(lastFraction) },
                    onDragCancel = { onScrubEnd(lastFraction) }
                )
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(trackHeight)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = if (isDragging) 0.28f else 0.20f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = if (isDragging) 1.0f else 0.92f))
            )
        }
    }
}

@Composable
private fun ImmersiveLyricsView(
    state: PlayerState,
    showTranslation: Boolean,
    onSeekTo: (Long) -> Unit,
    onTapEmpty: () -> Unit,
    modifier: Modifier = Modifier
) {
    val lines = state.lyrics.lines
    val activeIdx = state.activeLyricIndex
    val listState = rememberLazyListState()
    val density = LocalDensity.current

    if (lines.isEmpty()) {
        Box(
            modifier = modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onTapEmpty
            ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (state.isLoadingLyrics) "正在加载歌词…" else "暂无歌词\n轻点返回封面",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White.copy(alpha = 0.55f),
                textAlign = TextAlign.Center
            )
        }
        return
    }

    BoxWithConstraints(modifier = modifier) {
        val viewportHeightPx = with(density) { maxHeight.toPx() }
        val anchorOffsetPx = -(viewportHeightPx * 0.34f).toInt()

        LaunchedEffect(activeIdx, anchorOffsetPx) {
            if (activeIdx in lines.indices) {
                listState.animateScrollToItem(
                    index = activeIdx,
                    scrollOffset = anchorOffsetPx
                )
            }
        }

        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = 120.dp, bottom = 240.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                .drawWithContent {
                    drawContent()
                    drawRect(
                        brush = Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.12f to Color.Black,
                            0.85f to Color.Black,
                            1f to Color.Transparent
                        ),
                        blendMode = BlendMode.DstIn
                    )
                }
        ) {
            itemsIndexed(lines) { index, line ->
                val isActive = index == activeIdx
                val scale by animateFloatAsState(
                    targetValue = if (isActive) 1.04f else 0.96f,
                    animationSpec = spring(stiffness = 380f),
                    label = "lrcScale"
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onSeekTo(line.timeMs) }
                        .padding(start = 4.dp, end = 14.dp, top = 4.dp, bottom = 4.dp)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            transformOrigin = TransformOrigin(0f, 0.5f)
                        }
                ) {
                    Text(
                        text = line.text,
                        fontSize = if (isActive) 22.sp else 18.sp,
                        lineHeight = if (isActive) 30.sp else 25.sp,
                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                        color = if (isActive) Color.White else Color.White.copy(alpha = 0.36f)
                    )
                    if (showTranslation && !line.translation.isNullOrBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = line.translation,
                            fontSize = if (isActive) 15.sp else 13.sp,
                            lineHeight = 20.sp,
                            color = if (isActive) Color.White.copy(alpha = 0.80f) else Color.White.copy(alpha = 0.28f)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueBottomSheet(
    state: PlayerState,
    forceDarkStatusBar: Boolean = false,
    onDismiss: () -> Unit,
    onSelectIndex: (Int) -> Unit,
    onRemoveIndex: (Int) -> Unit,
    onClearQueue: () -> Unit
) {
    val lxColors = LocalLxColors.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = lxColors.surfaceElevated,
        contentColor = lxColors.textPrimary
    ) {
        SyncDialogSystemBars(useLightSystemBars = !lxColors.isDark && !forceDarkStatusBar)
        Column(modifier = Modifier.padding(bottom = 24.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("当前播放队列", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "${state.queue.size} 首歌曲 · ${state.repeatMode.label}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = lxColors.textSecondary
                    )
                }
                if (state.queue.isNotEmpty()) {
                    TextButton(onClick = {
                        onClearQueue()
                        onDismiss()
                    }) {
                        Icon(Icons.Rounded.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("清空")
                    }
                }
            }
            HorizontalDivider(color = lxColors.hairline)

            if (state.queue.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("队列空空如也", color = lxColors.textSecondary)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.height(380.dp)
                ) {
                    itemsIndexed(state.queue, key = { idx, t -> "${t.id}_$idx" }) { index, track ->
                        val isCurrent = index == state.currentIndex
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectIndex(index) }
                                .padding(horizontal = 20.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isCurrent) {
                                MiniEqualizerIndicator(isPlaying = state.isPlaying, color = lxColors.accent)
                                Spacer(Modifier.width(10.dp))
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = track.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (isCurrent) lxColors.accent else lxColors.textPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = track.artist,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = lxColors.textSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(onClick = { onRemoveIndex(index) }) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "移出队列",
                                    tint = lxColors.textTertiary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    if (ms <= 0L) return "00:00"
    val totalSec = ms / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return "%02d:%02d".format(m, s)
}
