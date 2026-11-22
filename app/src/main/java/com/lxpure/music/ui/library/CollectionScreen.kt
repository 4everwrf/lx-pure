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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
            if (showDeletePlaylistAction && onDeletePlaylist != null) {
                IconButton(onClick = onDeletePlaylist) {
                    Icon(Icons.Rounded.DeleteOutline, contentDescription = "删除歌单", tint = lxColors.danger)
                }
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
            LazyColumn(
                contentPadding = PaddingValues(bottom = bottomPadding)
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
    onDeleteDownload: (String) -> Unit
) {
    val lxColors = LocalLxColors.current

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
                val completedCount = records.count { it.status == DownloadStatus.COMPLETED }
                Text("已完成 $completedCount 首 · 离线播放零流量", style = MaterialTheme.typography.bodyMedium, color = lxColors.textSecondary)
            }
        }

        if (records.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无离线下载曲目", color = lxColors.textSecondary)
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = bottomPadding)) {
                itemsIndexed(records, key = { _, r -> r.trackId }) { _, record ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = record.status == DownloadStatus.COMPLETED) {
                                onPlayTrack(record.track)
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
                                DownloadStatus.FAILED -> "失败：${record.errorMessage ?: "请重试"}"
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
                        IconButton(onClick = { onDeleteDownload(record.trackId) }) {
                            Icon(Icons.Rounded.DeleteOutline, contentDescription = "删除下载", tint = lxColors.textTertiary)
                        }
                    }
                }
            }
        }
    }
}
