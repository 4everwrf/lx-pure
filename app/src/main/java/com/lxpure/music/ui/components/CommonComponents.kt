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
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
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
import com.lxpure.music.core.player.PlayerState
import com.lxpure.music.domain.model.Track
import com.lxpure.music.domain.model.UserPlaylist
import com.lxpure.music.ui.theme.LocalLxColors
import kotlin.math.roundToInt

/**
 * Synchronizes the status & navigation bar icon contrast for ModalBottomSheet Dialog windows
 * so dark theme / immersive player sheets never flash black status bar icons on light-mode phones.
 */
@Composable
fun SyncDialogSystemBars(useLightSystemBars: Boolean) {
    val view = LocalView.current
    SideEffect {
        val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
        WindowInsetsControllerCompat(window, view).apply {
            isAppearanceLightStatusBars = useLightSystemBars
            isAppearanceLightNavigationBars = useLightSystemBars
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
    val transition = rememberInfiniteTransition(label = "eq")
    val bar1 by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(420, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "b1"
    )
    val bar2 by transition.animateFloat(
        initialValue = 0.9f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(tween(540, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "b2"
    )
    val bar3 by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(tween(360, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "b3"
    )

    Row(
        modifier = modifier.height(14.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        listOf(
            if (isPlaying) bar1 else 0.35f,
            if (isPlaying) bar2 else 0.55f,
            if (isPlaying) bar3 else 0.35f
        ).forEach { fraction ->
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
    onExpand: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onOpenQueue: () -> Unit,
    modifier: Modifier = Modifier
) {
    val track = state.currentTrack ?: return
    val lxColors = LocalLxColors.current
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val animatedOffset by animateFloatAsState(
        targetValue = dragOffset,
        animationSpec = spring(stiffness = 500f, dampingRatio = 0.8f),
        label = "miniSwipe"
    )

    val progress = if (state.durationMs > 0L && !(state.isBuffering && state.positionMs == 0L)) {
        (state.positionMs.toFloat() / state.durationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .offset { IntOffset(animatedOffset.roundToInt(), 0) }
            .pointerInput(track.id) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        when {
                            dragOffset < -90f -> onNext()
                            dragOffset > 90f -> onPrevious()
                        }
                        dragOffset = 0f
                    },
                    onDragCancel = { dragOffset = 0f },
                    onHorizontalDrag = { _, delta ->
                        dragOffset = (dragOffset + delta * 0.65f).coerceIn(-140f, 140f)
                    }
                )
            }
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onExpand),
        shape = RoundedCornerShape(20.dp),
        color = if (lxColors.isDark) Color(0xF2151924) else Color(0xFAFFFFFF),
        border = BorderStroke(0.9.dp, lxColors.hairline),
        shadowElevation = if (lxColors.isDark) 12.dp else 6.dp
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AlbumArtwork(
                    url = track.coverUrl,
                    contentDescription = track.title,
                    size = 44.dp,
                    cornerRadius = 12.dp
                )

                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = lxColors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(1.dp))
                    val activeLine = state.lyrics.lines.getOrNull(state.activeLyricIndex)?.text
                    Text(
                        text = if (state.isPlaying && !activeLine.isNullOrBlank()) activeLine else track.artist,
                        style = MaterialTheme.typography.bodyMedium,
                        color = lxColors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                IconButton(onClick = onTogglePlayPause) {
                    if (state.isBuffering) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = lxColors.accent
                        )
                    } else {
                        Icon(
                            imageVector = if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (state.isPlaying) "暂停" else "播放",
                            tint = lxColors.textPrimary,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }

                IconButton(onClick = onOpenQueue) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.QueueMusic,
                        contentDescription = "播放队列",
                        tint = lxColors.textSecondary,
                        modifier = Modifier.size(22.dp)
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
    onToggleFavorite: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onDownload: () -> Unit
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
            SheetActionRow(
                icon = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                label = if (isFavorite) "取消喜欢" else "添加到我喜欢",
                iconTint = if (isFavorite) lxColors.danger else lxColors.textPrimary
            ) {
                onToggleFavorite(); onDismiss()
            }
            SheetActionRow(Icons.AutoMirrored.Rounded.PlaylistAdd, "收藏到歌单") {
                onDismiss(); onAddToPlaylist()
            }
            if (!track.isLocal) {
                SheetActionRow(Icons.Rounded.Download, "离线下载") {
                    onDownload(); onDismiss()
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
    playlists: List<UserPlaylist>,
    onDismiss: () -> Unit,
    onSelectPlaylist: (Long) -> Unit,
    onCreateNewPlaylist: (String) -> Unit
) {
    val lxColors = LocalLxColors.current
    var showCreateDialog by remember { mutableStateOf(false) }

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
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("收藏到歌单", style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { showCreateDialog = true }) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("新建歌单")
                }
            }
            HorizontalDivider(color = lxColors.hairline)
            if (playlists.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("还没有自建歌单，点击右上角新建", color = lxColors.textSecondary)
                }
            } else {
                playlists.forEach { playlist ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelectPlaylist(playlist.id)
                                onDismiss()
                            }
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AlbumArtwork(url = playlist.coverUrl, contentDescription = null, size = 44.dp, cornerRadius = 10.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(playlist.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${playlist.trackCount} 首歌曲", style = MaterialTheme.typography.bodyMedium, color = lxColors.textSecondary)
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
                onClick = { if (value.isNotBlank()) onConfirm(value.trim()) },
                enabled = value.isNotBlank()
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
