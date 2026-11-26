package com.lxpure.music.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.VerticalAlignBottom
import androidx.compose.material.icons.rounded.VerticalAlignTop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowInsetsControllerCompat
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Precision
import com.lxpure.music.core.player.PlaybackProgress
import com.lxpure.music.core.player.PlayerState
import com.lxpure.music.domain.model.RepeatMode as PlayerRepeatMode
import com.lxpure.music.domain.model.Track
import com.lxpure.music.domain.model.UserPlaylist
import com.lxpure.music.ui.theme.LocalLxColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Synchronizes the status & navigation bar icon contrast for ModalBottomSheet Dialog windows
 * so dark theme / immersive player sheets never flash invisible system bar icons.
 */
@Composable
fun SyncDialogSystemBars(
    useLightSystemBars: Boolean = false,
    useLightStatusBars: Boolean = useLightSystemBars,
    useLightNavigationBars: Boolean = useLightSystemBars
) {
    val view = LocalView.current
    SideEffect {
        val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
        WindowInsetsControllerCompat(window, view).apply {
            isAppearanceLightStatusBars = useLightStatusBars
            isAppearanceLightNavigationBars = useLightNavigationBars
        }
    }
}

/**
 * Appends lightweight CDN resize parameters for NetEase/QQ covers so list thumbnails
 * consume ~15 KB instead of 2 MB original images and keep foreground Bitmap PSS low.
 */
fun optimizeCoverUrlForSize(url: String?, isThumbnail: Boolean): String? {
    if (url.isNullOrBlank()) return null
    val trimmed = url.trim()
    if (!trimmed.startsWith("http", ignoreCase = true)) return trimmed

    if (trimmed.contains("music.126.net", ignoreCase = true) && !trimmed.contains("param=")) {
        val dim = if (isThumbnail) "200y200" else "640y640"
        val sep = if (trimmed.contains('?')) "&" else "?"
        return "${trimmed}${sep}param=$dim"
    }
    if (isThumbnail && trimmed.contains("y.gtimg.cn/music/photo_new/T002R500x500M000", ignoreCase = true)) {
        return trimmed.replace("T002R500x500M000", "T002R300x300M000")
    }
    return trimmed
}

@Composable
fun AlbumArtwork(
    url: String?,
    contentDescription: String?,
    size: Dp,
    cornerRadius: Dp = 12.dp,
    modifier: Modifier = Modifier
) {
    val lxColors = LocalLxColors.current
    val context = LocalContext.current
    val density = LocalDensity.current
    val isSmallListThumb = size <= 72.dp
    val isThumbnail = size <= 104.dp
    val targetPx = remember(size, density, isSmallListThumb, isThumbnail) {
        val raw = with(density) { size.roundToPx() }
        when {
            isSmallListThumb -> raw.coerceIn(96, 160)
            isThumbnail -> raw.coerceIn(120, 260)
            size <= 180.dp -> raw.coerceIn(220, 380)
            else -> raw.coerceIn(360, 720)
        }
    }
    val optimizedUrl = remember(url, isThumbnail) {
        optimizeCoverUrlForSize(url, isThumbnail)
    }
    val shape = RoundedCornerShape(cornerRadius)
    val fallbackBrush = remember(lxColors.isDark) {
        if (lxColors.isDark) {
            Brush.linearGradient(listOf(Color(0xFF1F2937), Color(0xFF111827)))
        } else {
            Brush.linearGradient(listOf(Color(0xFFE2E8F0), Color(0xFFCBD5E1)))
        }
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(fallbackBrush),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Rounded.MusicNote,
            contentDescription = contentDescription,
            tint = lxColors.textTertiary,
            modifier = Modifier.size(size * 0.42f)
        )
        if (!optimizedUrl.isNullOrBlank()) {
            val imageRequest = remember(optimizedUrl, targetPx, isThumbnail) {
                ImageRequest.Builder(context)
                    .data(optimizedUrl)
                    .size(targetPx, targetPx)
                    .precision(Precision.INEXACT)
                    .allowRgb565(isThumbnail)
                    .crossfade(160)
                    .build()
            }
            AsyncImage(
                model = imageRequest,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 18.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val lxColors = LocalLxColors.current
    val shape = RoundedCornerShape(cornerRadius)
    Surface(
        modifier = modifier
            .clip(shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = shape,
        color = lxColors.glassSurface,
        border = BorderStroke(0.8.dp, lxColors.hairline),
        tonalElevation = 0.dp
    ) {
        content()
    }
}

@Composable
fun MiniEqualizerIndicator(
    isPlaying: Boolean,
    color: Color,
    modifier: Modifier = Modifier
) {
    val bar1: Float
    val bar2: Float
    val bar3: Float
    if (isPlaying) {
        val transition = rememberInfiniteTransition(label = "eq")
        bar1 = transition.animateFloat(
            initialValue = 0.25f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(420, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "b1"
        ).value
        bar2 = transition.animateFloat(
            initialValue = 0.9f,
            targetValue = 0.3f,
            animationSpec = infiniteRepeatable(tween(540, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "b2"
        ).value
        bar3 = transition.animateFloat(
            initialValue = 0.4f,
            targetValue = 0.85f,
            animationSpec = infiniteRepeatable(tween(360, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "b3"
        ).value
    } else {
        bar1 = 0.35f
        bar2 = 0.55f
        bar3 = 0.35f
    }

    Row(
        modifier = modifier.height(14.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        listOf(bar1, bar2, bar3).forEach { fraction ->
            Box(
                modifier = Modifier
                    .width(2.5.dp)
                    .fillMaxHeight(fraction)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color)
            )
        }
    }
}

@Composable
fun TrackListItem(
    track: Track,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onMoreClick: () -> Unit,
    indexLabel: Int? = null,
    showSourceBadge: Boolean = false,
    modifier: Modifier = Modifier
) {
    val lxColors = LocalLxColors.current

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (indexLabel != null) {
            Box(
                modifier = Modifier.width(28.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (isCurrent) {
                    MiniEqualizerIndicator(isPlaying = isPlaying, color = lxColors.accent)
                } else {
                    Text(
                        text = indexLabel.toString(),
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                        color = if (indexLabel <= 3) lxColors.accent else lxColors.textTertiary
                    )
                }
            }
        }

        Box(contentAlignment = Alignment.Center) {
            AlbumArtwork(
                url = track.coverUrl,
                contentDescription = track.title,
                size = 48.dp,
                cornerRadius = 11.dp
            )
            if (isCurrent && indexLabel == null) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(Color.Black.copy(alpha = 0.48f)),
                    contentAlignment = Alignment.Center
                ) {
                    MiniEqualizerIndicator(isPlaying = isPlaying, color = lxColors.accent)
                }
            }
        }

        Spacer(Modifier.width(13.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.titleMedium,
                color = if (isCurrent) lxColors.accent else lxColors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                track.bestQualityBadge?.let { badge ->
                    SmallPillBadge(
                        text = badge,
                        background = lxColors.accentSoft,
                        contentColor = lxColors.accent
                    )
                }
                if (showSourceBadge) {
                    SmallPillBadge(
                        text = track.source.shortLabel,
                        background = lxColors.hairline,
                        contentColor = lxColors.textSecondary
                    )
                    if (track.alternates.isNotEmpty()) {
                        SmallPillBadge(
                            text = "+${track.alternates.size}源",
                            background = lxColors.hairline,
                            contentColor = lxColors.textTertiary
                        )
                    }
                }
                val subtitle = buildString {
                    append(track.artist)
                    if (track.album.isNotBlank()) {
                        append(" · ")
                        append(track.album)
                    }
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = lxColors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        IconButton(onClick = onMoreClick) {
            Icon(
                imageVector = Icons.Rounded.MoreVert,
                contentDescription = "更多操作",
                tint = lxColors.textTertiary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
fun SmallPillBadge(
    text: String,
    background: Color,
    contentColor: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(5.dp))
            .background(background)
            .padding(horizontal = 5.dp, vertical = 1.5.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            maxLines = 1,
            softWrap = false
        )
    }
}

/**
 * Floating capsule mini-player hovering above the bottom bar.
 * Supports horizontal swipe left/right to skip tracks and tap to open the full-screen player.
 */
@Composable
fun FloatingMiniPlayer(
    state: PlayerState,
    progressFlow: StateFlow<PlaybackProgress>,
    onExpand: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onOpenQueue: () -> Unit,
    modifier: Modifier = Modifier
) {
    val track = state.currentTrack ?: return
    val lxColors = LocalLxColors.current
    val density = LocalDensity.current
    val swipeTriggerPx = remember(density) { with(density) { 54.dp.toPx() } }
    val swipeClampPx = remember(density) { with(density) { 52.dp.toPx() } }
    val liveProgress by progressFlow.collectAsState()
    var rawDragPx by remember { mutableFloatStateOf(0f) }
    var visualDragOffset by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(track.id) {
        rawDragPx = 0f
        visualDragOffset = 0f
    }
    val currentOnNext by rememberUpdatedState(onNext)
    val currentOnPrevious by rememberUpdatedState(onPrevious)
    val animatedOffset by animateFloatAsState(
        targetValue = visualDragOffset,
        animationSpec = spring(stiffness = 500f, dampingRatio = 0.8f),
        label = "miniSwipe"
    )

    val effectiveDuration = liveProgress.durationMs.takeIf { it > 0L } ?: state.durationMs
    val progress = if (effectiveDuration > 0L && !(state.isBuffering && liveProgress.positionMs == 0L)) {
        (liveProgress.positionMs.toFloat() / effectiveDuration.toFloat()).coerceIn(0f, 1f)
    } else 0f

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .pointerInput(swipeTriggerPx, swipeClampPx) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        when {
                            rawDragPx < -swipeTriggerPx -> currentOnNext()
                            rawDragPx > swipeTriggerPx -> currentOnPrevious()
                        }
                        rawDragPx = 0f
                        visualDragOffset = 0f
                    },
                    onDragCancel = {
                        rawDragPx = 0f
                        visualDragOffset = 0f
                    },
                    onHorizontalDrag = { change, delta ->
                        change.consume()
                        rawDragPx += delta
                        visualDragOffset = (rawDragPx * 0.45f).coerceIn(-swipeClampPx, swipeClampPx)
                    }
                )
            }
            .graphicsLayer { translationX = animatedOffset },
        shape = RoundedCornerShape(20.dp),
        color = if (lxColors.isDark) Color(0xF2151924) else Color(0xFAFFFFFF),
        border = BorderStroke(0.9.dp, lxColors.hairline),
        shadowElevation = if (lxColors.isDark) 12.dp else 6.dp
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Dedicated tap zone for expanding the full-screen player (prevents gap fall-through near transport buttons)
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClickLabel = "展开播放器", onClick = onExpand)
                        .padding(end = 6.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AlbumArtwork(
                        url = track.coverUrl,
                        contentDescription = null,
                        size = 40.dp,
                        cornerRadius = 10.dp
                    )

                    Spacer(Modifier.width(9.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = track.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = lxColors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(1.dp))
                        val activeIdx = remember(state.lyrics, liveProgress.positionMs) {
                            state.lyrics.activeIndexAt(liveProgress.positionMs)
                        }
                        val activeLine = state.lyrics.lines.getOrNull(activeIdx)?.text
                        Text(
                            text = if (state.isPlaying && !activeLine.isNullOrBlank()) activeLine else track.artist,
                            style = MaterialTheme.typography.bodyMedium,
                            color = lxColors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                val boundaryTint = lxColors.textPrimary.copy(alpha = if (lxColors.isDark) 0.36f else 0.45f)
                val playButtonBg = if (lxColors.isDark) {
                    Color.White.copy(alpha = 0.11f)
                } else {
                    lxColors.accentSoft
                }
                val playButtonBorder = if (lxColors.isDark) {
                    lxColors.hairline
                } else {
                    lxColors.accent.copy(alpha = 0.22f)
                }
                val dividerColor = if (lxColors.isDark) {
                    lxColors.hairline
                } else {
                    lxColors.textPrimary.copy(alpha = 0.14f)
                }
                val nextA11yLabel = when {
                    state.canSkipNext -> "下一首"
                    state.repeatMode == PlayerRepeatMode.SEQUENTIAL -> "下一首（已到队列末尾）"
                    else -> "下一首（重新播放当前曲目）"
                }

                // Previous Track
                Box(
                    modifier = Modifier
                        .size(width = 32.dp, height = 44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .semantics {
                            contentDescription = if (state.canSkipPrevious) "上一首" else "上一首（重新播放当前曲目）"
                        }
                        .clickable(role = Role.Button, onClick = onPrevious),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SkipPrevious,
                        contentDescription = null,
                        tint = if (state.canSkipPrevious) lxColors.textPrimary else boundaryTint,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(Modifier.width(2.dp))

                // Play / Pause focal circular control (44dp vertical hit area wrapping 36dp visual circle)
                Box(
                    modifier = Modifier
                        .size(width = 36.dp, height = 44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .semantics {
                            contentDescription = when {
                                state.isBuffering -> "正在缓冲，点击暂停"
                                state.isPlaying -> "暂停"
                                else -> "播放"
                            }
                        }
                        .clickable(role = Role.Button, onClick = onTogglePlayPause),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(playButtonBg)
                            .border(0.6.dp, playButtonBorder, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        if (state.isBuffering) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = lxColors.accent
                            )
                        } else {
                            Icon(
                                imageVector = if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = null,
                                tint = if (lxColors.isDark) lxColors.textPrimary else lxColors.accent,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.width(2.dp))

                // Next Track
                Box(
                    modifier = Modifier
                        .size(width = 32.dp, height = 44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .semantics { contentDescription = nextA11yLabel }
                        .clickable(role = Role.Button, onClick = onNext),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SkipNext,
                        contentDescription = null,
                        tint = if (state.canSkipNext) lxColors.textPrimary else boundaryTint,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(Modifier.width(3.dp))

                // Subtle divider separating transport controls from queue list
                Box(
                    modifier = Modifier
                        .width(0.8.dp)
                        .height(16.dp)
                        .background(dividerColor)
                )

                Spacer(Modifier.width(2.dp))

                // Playback Queue
                Box(
                    modifier = Modifier
                        .size(width = 32.dp, height = 44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .semantics { contentDescription = "播放队列" }
                        .clickable(role = Role.Button, onClick = onOpenQueue),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.QueueMusic,
                        contentDescription = null,
                        tint = lxColors.textSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Ultra-thin progress ribbon at the bottom of the capsule
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(lxColors.hairline)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress)
                        .fillMaxHeight()
                        .background(lxColors.accent)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackActionSheet(
    track: Track,
    isFavorite: Boolean,
    onDismiss: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: (() -> Unit)? = null,
    onToggleFavorite: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onDownload: () -> Unit,
    onRemoveFromPlaylist: (() -> Unit)? = null
) {
    val lxColors = LocalLxColors.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = lxColors.surfaceElevated,
        contentColor = lxColors.textPrimary
    ) {
        SyncDialogSystemBars(useLightSystemBars = !lxColors.isDark)
        Column(modifier = Modifier.padding(bottom = 28.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AlbumArtwork(url = track.coverUrl, contentDescription = null, size = 48.dp, cornerRadius = 11.dp)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${track.artist} · ${track.source.displayName}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = lxColors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = lxColors.hairline
            )
            SheetActionRow(Icons.AutoMirrored.Rounded.PlaylistPlay, "下一首播放") {
                onPlayNext(); onDismiss()
            }
            if (onAddToQueue != null) {
                SheetActionRow(Icons.AutoMirrored.Rounded.QueueMusic, "加入播放队列") {
                    onAddToQueue(); onDismiss()
                }
            }
            SheetActionRow(
                icon = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                label = if (isFavorite) "取消喜欢" else "添加到我喜欢",
                iconTint = if (isFavorite) lxColors.danger else lxColors.textPrimary
            ) {
                onToggleFavorite(); onDismiss()
            }
            SheetActionRow(Icons.AutoMirrored.Rounded.PlaylistAdd, "添加到歌单") {
                onDismiss(); onAddToPlaylist()
            }
            if (!track.isLocal) {
                SheetActionRow(Icons.Rounded.Download, "离线下载") {
                    onDownload(); onDismiss()
                }
            }
            if (onRemoveFromPlaylist != null) {
                SheetActionRow(
                    icon = Icons.Rounded.DeleteOutline,
                    label = "从当前歌单移除",
                    iconTint = lxColors.danger
                ) {
                    onRemoveFromPlaylist(); onDismiss()
                }
            }
        }
    }
}

@Composable
private fun SheetActionRow(
    icon: ImageVector,
    label: String,
    iconTint: Color = LocalLxColors.current.textPrimary,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPlaylistSheet(
    track: Track,
    playlists: List<UserPlaylist>,
    containedPlaylistIds: Set<Long>,
    customPlaylistCount: Int,
    maxCustomPlaylists: Int,
    forceDarkStatusBar: Boolean = false,
    onDismiss: () -> Unit,
    onSelectPlaylist: (UserPlaylist) -> Unit,
    onCreateNewPlaylist: (String) -> Unit,
    onLimitReached: () -> Unit
) {
    val lxColors = LocalLxColors.current
    val isAtCustomLimit = customPlaylistCount >= maxCustomPlaylists
    var showCreateDialog by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = lxColors.surfaceElevated,
        contentColor = lxColors.textPrimary
    ) {
        SyncDialogSystemBars(
            useLightStatusBars = !lxColors.isDark && !forceDarkStatusBar,
            useLightNavigationBars = !lxColors.isDark
        )
        Column(modifier = Modifier.padding(bottom = 28.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("添加到歌单", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "《${track.title}》 · ${track.artist}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = lxColors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = {
                        if (isAtCustomLimit) {
                            onDismiss()
                            onLimitReached()
                        } else {
                            showCreateDialog = true
                        }
                    }
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = null,
                        tint = if (isAtCustomLimit) lxColors.textTertiary else lxColors.accent,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(
                        text = if (isAtCustomLimit) "已达上限" else "新建 ($customPlaylistCount/$maxCustomPlaylists)",
                        color = if (isAtCustomLimit) lxColors.textTertiary else lxColors.accent
                    )
                }
            }
            HorizontalDivider(color = lxColors.hairline)
            if (playlists.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "还没有歌单，点击右上角「新建」创建并加入（最多自建 $maxCustomPlaylists 个）",
                        style = MaterialTheme.typography.bodyMedium,
                        color = lxColors.textSecondary
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 420.dp)
                ) {
                    items(playlists, key = { it.id }) { playlist ->
                        val alreadyIn = playlist.id in containedPlaylistIds
                        val isExternal = !playlist.sourceUrl.isNullOrBlank()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSelectPlaylist(playlist)
                                    onDismiss()
                                }
                                .padding(horizontal = 20.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AlbumArtwork(
                                url = playlist.coverUrl,
                                contentDescription = null,
                                size = 46.dp,
                                cornerRadius = 11.dp
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = playlist.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    if (isExternal) {
                                        SmallPillBadge(
                                            text = "外部导入",
                                            background = lxColors.hairline,
                                            contentColor = lxColors.textSecondary
                                        )
                                    }
                                }
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = "${playlist.trackCount} 首歌曲",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = lxColors.textSecondary
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            if (alreadyIn) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = null,
                                        tint = lxColors.success,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = "已加入",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = lxColors.success
                                    )
                                }
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.Add,
                                    contentDescription = "加入此歌单",
                                    tint = lxColors.accent,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        SimpleTextInputDialog(
            title = "新建歌单",
            placeholder = "输入歌单名称",
            supportingText = "最多支持自建 $maxCustomPlaylists 个歌单（当前 $customPlaylistCount/$maxCustomPlaylists）",
            confirmLabel = "创建并加入",
            onDismiss = { showCreateDialog = false },
            onConfirm = { name ->
                onCreateNewPlaylist(name)
                showCreateDialog = false
                onDismiss()
            }
        )
    }
}

@Composable
fun SimpleTextInputDialog(
    title: String,
    placeholder: String,
    confirmLabel: String = "确定",
    supportingText: String? = null,
    singleLine: Boolean = true,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var value by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!supportingText.isNullOrBlank()) {
                    Text(supportingText, style = MaterialTheme.typography.bodyMedium, color = LocalLxColors.current.textSecondary)
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    placeholder = { Text(placeholder) },
                    singleLine = singleLine,
                    maxLines = if (singleLine) 1 else 6,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val trimmed = value.trim()
                    if (!submitted && trimmed.isNotEmpty()) {
                        submitted = true
                        onConfirm(trimmed)
                    }
                },
                enabled = !submitted && value.isNotBlank()
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/**
 * Pure helper mapping a vertical fast-scroll drag fraction `[0f..1f]` to a 0-based track index.
 */
fun computeFastScrollTrackIndex(fraction: Float, trackCount: Int): Int {
    if (trackCount <= 1) return 0
    return (fraction.coerceIn(0f, 1f) * (trackCount - 1)).roundToInt().coerceIn(0, trackCount - 1)
}

/**
 * Reusable interactive right-edge fast-scroll bar + floating top/bottom/current-track jump dock
 * for long playlists so users can reach the very bottom or any track in a single swipe/tap.
 */
@Composable
fun TrackListFastScroller(
    listState: LazyListState,
    trackCount: Int,
    headerItemCount: Int,
    currentTrackIndex: Int,
    bottomPadding: Dp,
    modifier: Modifier = Modifier
) {
    if (trackCount < 15) return

    val lxColors = LocalLxColors.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var barHeightPx by remember { mutableIntStateOf(0) }
    var scrubJob by remember { mutableStateOf<Job?>(null) }

    val canScrollBackward by remember(listState) {
        derivedStateOf { listState.canScrollBackward }
    }
    val canScrollForward by remember(listState) {
        derivedStateOf { listState.canScrollForward }
    }
    val scrollFraction by remember(listState, trackCount, headerItemCount) {
        derivedStateOf {
            if (!listState.canScrollBackward) return@derivedStateOf 0f
            if (!listState.canScrollForward) return@derivedStateOf 1f
            val visibleCount = (listState.layoutInfo.visibleItemsInfo.size - 1).coerceAtLeast(1)
            val maxFirstTrack = (trackCount - visibleCount).coerceAtLeast(1)
            val currentFirstTrack = (listState.firstVisibleItemIndex - headerItemCount).coerceAtLeast(0)
            (currentFirstTrack.toFloat() / maxFirstTrack.toFloat()).coerceIn(0f, 1f)
        }
    }

    val activeFraction = if (isDragging) dragFraction else scrollFraction
    val dragTrackIdx = computeFastScrollTrackIndex(dragFraction, trackCount)

    fun jumpToListItem(targetItemIndex: Int) {
        scrubJob?.cancel()
        scrubJob = scope.launch {
            val clamped = targetItemIndex.coerceIn(0, (headerItemCount + trackCount - 1).coerceAtLeast(0))
            if (abs(listState.firstVisibleItemIndex - clamped) > 45) {
                listState.scrollToItem(clamped)
            } else {
                listState.animateScrollToItem(clamped)
            }
        }
    }

    fun scrubToY(yPx: Float) {
        val height = barHeightPx.toFloat()
        if (height <= 1f) return
        val fraction = (yPx / height).coerceIn(0f, 1f)
        dragFraction = fraction
        val trackIdx = computeFastScrollTrackIndex(fraction, trackCount)
        val targetItem = if (trackIdx == 0) 0 else headerItemCount + trackIdx
        scrubJob?.cancel()
        scrubJob = scope.launch {
            listState.scrollToItem(targetItem)
        }
    }

    Box(modifier = modifier) {
        // Right-edge interactive fast-scroll thumb (kept strictly in outer 18dp so it never steals TrackListItem's MoreVert taps)
        if (trackCount >= 20) {
            val thumbHeightDp = 46.dp
            val thumbHeightPx = with(density) { thumbHeightDp.toPx() }
            val thumbTopPx = ((barHeightPx - thumbHeightPx).coerceAtLeast(0f) * activeFraction).roundToInt()

            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .padding(top = 12.dp, bottom = bottomPadding + 146.dp)
                    .width(if (isDragging) 28.dp else 18.dp)
                    .onSizeChanged { barHeightPx = it.height }
                    .pointerInput(trackCount, headerItemCount) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()
                            isDragging = true
                            scrubToY(down.position.y)
                            try {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id }
                                    if (change == null || !change.pressed) break
                                    change.consume()
                                    scrubToY(change.position.y)
                                }
                            } finally {
                                isDragging = false
                            }
                        }
                    }
            ) {
                if (isDragging) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 5.dp)
                            .width(3.dp)
                            .fillMaxHeight()
                            .clip(CircleShape)
                            .background(lxColors.hairline)
                    )
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset { IntOffset(0, thumbTopPx) }
                        .padding(end = 3.dp)
                        .width(if (isDragging) 7.dp else 5.dp)
                        .height(thumbHeightDp)
                        .clip(CircleShape)
                        .background(
                            if (isDragging) lxColors.accent else lxColors.textTertiary.copy(alpha = 0.48f)
                        )
                )
            }

            if (isDragging) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 12.dp, end = 38.dp)
                        .offset { IntOffset(0, (thumbTopPx + 4).coerceAtLeast(0)) },
                    shape = RoundedCornerShape(50),
                    color = lxColors.surfaceElevated,
                    border = BorderStroke(0.8.dp, lxColors.accent.copy(alpha = 0.45f)),
                    shadowElevation = 10.dp
                ) {
                    Text(
                        text = "#${dragTrackIdx + 1} / $trackCount",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = lxColors.accent,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }

        // Bottom-right quick jump dock (locate current / top / bottom)
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 14.dp, bottom = bottomPadding + 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (currentTrackIndex in 0 until trackCount) {
                QuickJumpCircleButton(
                    icon = Icons.Rounded.MyLocation,
                    contentDescription = "定位当前播放",
                    tint = lxColors.accent,
                    onClick = { jumpToListItem((headerItemCount + currentTrackIndex - 2).coerceAtLeast(0)) }
                )
            }
            if (canScrollBackward) {
                QuickJumpCircleButton(
                    icon = Icons.Rounded.VerticalAlignTop,
                    contentDescription = "回到顶部",
                    tint = lxColors.textPrimary,
                    onClick = { jumpToListItem(0) }
                )
            }
            if (canScrollForward) {
                QuickJumpCircleButton(
                    icon = Icons.Rounded.VerticalAlignBottom,
                    contentDescription = "直达底部",
                    tint = lxColors.textPrimary,
                    onClick = { jumpToListItem(headerItemCount + trackCount - 1) }
                )
            }
        }
    }
}

@Composable
private fun QuickJumpCircleButton(
    icon: ImageVector,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit
) {
    val lxColors = LocalLxColors.current
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = lxColors.surfaceElevated.copy(alpha = 0.94f),
        border = BorderStroke(0.8.dp, lxColors.hairline),
        shadowElevation = 6.dp,
        modifier = Modifier.size(38.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(19.dp)
            )
        }
    }
}
