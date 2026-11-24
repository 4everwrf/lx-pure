package com.lxpure.music.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lxpure.music.core.musicsdk.FormatUtils
import com.lxpure.music.domain.model.DownloadRecord
import com.lxpure.music.domain.model.DownloadStatus
import com.lxpure.music.domain.model.Track
import com.lxpure.music.ui.components.AlbumArtwork
import com.lxpure.music.ui.components.TrackListFastScroller
import com.lxpure.music.ui.components.TrackListItem
import com.lxpure.music.ui.theme.LocalLxColors

@Composable
fun CollectionDetailScreen(
    title: String,
    subtitle: String,
    tracks: List<Track>,
    currentTrackId: String?,
    isPlaying: Boolean,
    bottomPadding: Dp,
    isLoading: Boolean = false,
    showScanAction: Boolean = false,
    showClearAction: Boolean = false,
    showDeletePlaylistAction: Boolean = false,
    emptyHint: String = "暂无歌曲",
    onBack: () -> Unit,
    onPlayAll: () -> Unit,
    onPlayTrackAt: (Int) -> Unit,
    onTrackMore: (Track) -> Unit,
    onScanLocal: (() -> Unit)? = null,
    onClearAction: (() -> Unit)? = null,
    onDeletePlaylist: (() -> Unit)? = null
) {
    val lxColors = LocalLxColors.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        // Minimalist Top App Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回", tint = lxColors.textPrimary)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = lxColors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = lxColors.textSecondary
                )
            }
            if (showScanAction && onScanLocal != null) {
                IconButton(onClick = onScanLocal) {
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Rounded.Refresh, contentDescription = "扫描本地音频", tint = lxColors.accent)
                    }
                }
            }
            if (showClearAction && onClearAction != null && tracks.isNotEmpty()) {
                IconButton(onClick = onClearAction) {
                    Icon(Icons.Rounded.DeleteOutline, contentDescription = "清空", tint = lxColors.textSecondary)
                }
            }
            var showDeleteConfirm by remember { mutableStateOf(false) }
            var isDeleting by remember { mutableStateOf(false) }
            if (showDeletePlaylistAction && onDeletePlaylist != null) {
                IconButton(
                    onClick = { if (!isDeleting) showDeleteConfirm = true },
                    enabled = !isDeleting
                ) {
                    Icon(Icons.Rounded.DeleteOutline, contentDescription = "删除歌单", tint = lxColors.danger)
                }
            }
            if (showDeleteConfirm && onDeletePlaylist != null) {
                AlertDialog(
                    onDismissRequest = { showDeleteConfirm = false },
                    title = { Text("删除歌单", style = MaterialTheme.typography.titleLarge) },
                    text = { Text("确定要删除歌单「$title」吗？此操作不可撤销。", style = MaterialTheme.typography.bodyMedium) },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                if (!isDeleting) {
                                    isDeleting = true
                                    showDeleteConfirm = false
                                    onDeletePlaylist()
                                }
                            }
                        ) {
                            Text("删除", color = lxColors.danger)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
                    }
                )
            }
        }

        if (tracks.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(emptyHint, style = MaterialTheme.typography.bodyLarge, color = lxColors.textSecondary)
                    if (showScanAction && onScanLocal != null) {
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = onScanLocal,
                            shape = RoundedCornerShape(50),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = lxColors.accent,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Text("扫描设备本地音乐")
                        }
                    }
                }
            }
        } else {
            val listState = rememberLazyListState()
            val currentTrackIndex = remember(tracks, currentTrackId) {
                currentTrackId?.let { id -> tracks.indexOfFirst { it.id == id } } ?: -1
            }
            val extraDockClearance = when {
                tracks.size < 15 -> 0.dp
                currentTrackIndex >= 0 -> 114.dp
                else -> 74.dp
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = bottomPadding + extraDockClearance)
                ) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = onPlayAll,
                                shape = RoundedCornerShape(50),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = lxColors.accent,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                ),
                                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)
                            ) {
                                Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("播放全部 (${tracks.size})")
                            }
                        }
                    }

                    itemsIndexed(tracks, key = { idx, t -> "${t.id}_$idx" }) { index, track ->
                        TrackListItem(
                            track = track,
                            isCurrent = track.id == currentTrackId,
                            isPlaying = isPlaying && track.id == currentTrackId,
                            onClick = { onPlayTrackAt(index) },
                            onMoreClick = { onTrackMore(track) }
                        )
                    }
                }

                TrackListFastScroller(
                    listState = listState,
                    trackCount = tracks.size,
                    headerItemCount = 1,
                    currentTrackIndex = currentTrackIndex,
                    bottomPadding = bottomPadding,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@Composable
fun DownloadsDetailScreen(
    records: List<DownloadRecord>,
    currentTrackId: String?,
    isPlaying: Boolean,
    bottomPadding: Dp,
    onBack: () -> Unit,
    onPlayTrack: (Track) -> Unit,
    onPlayAllCompleted: ((List<Track>) -> Unit)? = null,
    onRetryDownload: ((Track) -> Unit)? = null,
    onDeleteDownload: (String) -> Unit
) {
    val lxColors = LocalLxColors.current
    val completedTracks = remember(records) {
        records.filter { it.status == DownloadStatus.COMPLETED }.map { it.track }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回", tint = lxColors.textPrimary)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("离线下载", style = MaterialTheme.typography.titleLarge, color = lxColors.textPrimary)
                Text("已完成 ${completedTracks.size} 首 · 离线播放零流量", style = MaterialTheme.typography.bodyMedium, color = lxColors.textSecondary)
            }
        }

        if (records.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无离线下载曲目", color = lxColors.textSecondary)
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = bottomPadding)) {
                if (completedTracks.isNotEmpty() && onPlayAllCompleted != null) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = { onPlayAllCompleted(completedTracks) },
                                shape = RoundedCornerShape(50),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = lxColors.accent,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                ),
                                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)
                            ) {
                                Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("播放全部 (${completedTracks.size})")
                            }
                        }
                    }
                }
                itemsIndexed(records, key = { _, r -> r.trackId }) { _, record ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = record.status == DownloadStatus.COMPLETED || (record.status == DownloadStatus.FAILED && onRetryDownload != null)) {
                                if (record.status == DownloadStatus.COMPLETED) {
                                    onPlayTrack(record.track)
                                } else if (record.status == DownloadStatus.FAILED) {
                                    onRetryDownload?.invoke(record.track)
                                }
                            }
                            .padding(horizontal = 18.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AlbumArtwork(url = record.track.coverUrl, contentDescription = null, size = 48.dp, cornerRadius = 11.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = record.track.title,
                                style = MaterialTheme.typography.titleMedium,
                                color = if (record.trackId == currentTrackId) lxColors.accent else lxColors.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(3.dp))
                            val statusText = when (record.status) {
                                DownloadStatus.QUEUED -> "等待中…"
                                DownloadStatus.DOWNLOADING -> "下载中 ${(record.progress * 100).toInt()}%"
                                DownloadStatus.COMPLETED -> "${record.track.artist} · ${record.quality.badge} · ${FormatUtils.formatSize(record.totalBytes)}"
                                DownloadStatus.FAILED -> "失败：${record.errorMessage ?: "点击重试"}"
                            }
                            Text(
                                text = statusText,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (record.status == DownloadStatus.FAILED) lxColors.danger else lxColors.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (record.status == DownloadStatus.DOWNLOADING) {
                                Spacer(Modifier.height(6.dp))
                                LinearProgressIndicator(
                                    progress = { record.progress },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(3.dp)
                                        .clip(RoundedCornerShape(50)),
                                    color = lxColors.accent,
                                    trackColor = lxColors.hairline
                                )
                            }
                        }
                        if (record.status == DownloadStatus.FAILED && onRetryDownload != null) {
                            IconButton(onClick = { onRetryDownload(record.track) }) {
                                Icon(Icons.Rounded.Refresh, contentDescription = "重试下载", tint = lxColors.accent)
                            }
                        }
                        IconButton(onClick = { onDeleteDownload(record.trackId) }) {
                            Icon(Icons.Rounded.DeleteOutline, contentDescription = "删除下载", tint = lxColors.textTertiary)
                        }
                    }
                }
            }
        }
    }
}
