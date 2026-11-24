package com.lxpure.music.ui.library

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lxpure.music.domain.model.Track
import com.lxpure.music.domain.model.UserPlaylist
import com.lxpure.music.ui.components.AlbumArtwork
import com.lxpure.music.ui.components.GlassCard
import com.lxpure.music.ui.components.SimpleTextInputDialog
import com.lxpure.music.ui.theme.LocalLxColors

enum class LibraryCollectionType {
    FAVORITES,
    LOCAL,
    DOWNLOADS,
    HISTORY
}

@Composable
fun LibraryScreen(
    hasReadySource: Boolean,
    favoritesCount: Int,
    localCount: Int,
    downloadsCount: Int,
    recentHistory: List<Track>,
    playlists: List<UserPlaylist>,
    isImportingPlaylist: Boolean,
    bottomPadding: androidx.compose.ui.unit.Dp,
    onOpenSettings: () -> Unit,
    onOpenSourceManager: () -> Unit,
    onOpenCollection: (LibraryCollectionType) -> Unit,
    onOpenPlaylist: (UserPlaylist) -> Unit,
    onPlayTrack: (Track) -> Unit,
    onCreatePlaylist: (String) -> Unit,
    onImportExternalPlaylist: (String) -> Unit,
    onPlaylistLimitReached: () -> Unit = {}
) {
    val lxColors = LocalLxColors.current
    val customPlaylistCount = remember(playlists) {
        com.lxpure.music.data.repository.LibraryRepository.customPlaylistCount(playlists)
    }
    val maxCustomPlaylists = com.lxpure.music.data.repository.LibraryRepository.MAX_CUSTOM_PLAYLISTS
    var showCreateDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(top = 12.dp, bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Minimalist Header
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "音乐库",
                        style = MaterialTheme.typography.headlineLarge,
                        color = lxColors.textPrimary
                    )
                    Text(
                        text = "轻雪 · 纯净聆听",
                        style = MaterialTheme.typography.bodyMedium,
                        color = lxColors.textTertiary
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(
                        onClick = { if (!isImportingPlaylist) showImportDialog = true },
                        enabled = !isImportingPlaylist
                    ) {
                        if (isImportingPlaylist) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.Link,
                                contentDescription = "导入外部歌单",
                                tint = lxColors.textPrimary
                            )
                        }
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Rounded.Settings,
                            contentDescription = "设置",
                            tint = lxColors.textPrimary
                        )
                    }
                }
            }
        }

        // Gentle Cold-Start Source Onboarding Banner (disappears automatically once a source is ready!)
        if (!hasReadySource) {
            item {
                Box(modifier = Modifier.padding(horizontal = 18.dp)) {
                    GlassCard(
                        onClick = onOpenSourceManager,
                        cornerRadius = 20.dp
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(
                                            lxColors.accent.copy(alpha = 0.16f),
                                            Color(0xFF818CF8).copy(alpha = 0.10f)
                                        )
                                    )
                                )
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(lxColors.accent.copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Rounded.Bolt, contentDescription = null, tint = lxColors.accent)
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "导入洛雪自定义源 (.js)",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = lxColors.textPrimary
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = "兼容洛雪 .js 自定义源协议，导入本地文件、脚本代码或自定义链接即可使用",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = lxColors.textSecondary
                                )
                            }
                            Icon(
                                imageVector = Icons.Rounded.ChevronRight,
                                contentDescription = null,
                                tint = lxColors.textTertiary
                            )
                        }
                    }
                }
            }
        }

        // 2x2 Minimalist Bento Grid
        item {
            Column(
                modifier = Modifier.padding(horizontal = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    BentoCollectionCard(
                        title = "我喜欢的",
                        subtitle = "$favoritesCount 首歌曲",
                        icon = Icons.Rounded.Favorite,
                        accentColor = Color(0xFFFB7185),
                        onClick = { onOpenCollection(LibraryCollectionType.FAVORITES) },
                        modifier = Modifier.weight(1f)
                    )
                    BentoCollectionCard(
                        title = "本地音乐",
                        subtitle = "$localCount 首音频",
                        icon = Icons.Rounded.FolderOpen,
                        accentColor = Color(0xFF38BDF8),
                        onClick = { onOpenCollection(LibraryCollectionType.LOCAL) },
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    BentoCollectionCard(
                        title = "离线下载",
                        subtitle = "$downloadsCount 首离线",
                        icon = Icons.Rounded.DownloadDone,
                        accentColor = Color(0xFF34D399),
                        onClick = { onOpenCollection(LibraryCollectionType.DOWNLOADS) },
                        modifier = Modifier.weight(1f)
                    )
                    BentoCollectionCard(
                        title = "最近播放",
                        subtitle = "${recentHistory.size} 条记录",
                        icon = Icons.Rounded.History,
                        accentColor = Color(0xFFA78BFA),
                        onClick = { onOpenCollection(LibraryCollectionType.HISTORY) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // Continue Listening Strip
        if (recentHistory.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "继续聆听",
                        style = MaterialTheme.typography.titleLarge,
                        color = lxColors.textPrimary,
                        modifier = Modifier.padding(horizontal = 20.dp)
                    )
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 18.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(recentHistory.take(10), key = { it.id }) { track ->
                            Column(
                                modifier = Modifier
                                    .width(124.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable { onPlayTrack(track) }
                            ) {
                                AlbumArtwork(
                                    url = track.coverUrl,
                                    contentDescription = track.title,
                                    size = 124.dp,
                                    cornerRadius = 16.dp
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = track.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = lxColors.textPrimary,
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
                        }
                    }
                }
            }
        }

        // User & Imported Playlists Section
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "我的歌单",
                        style = MaterialTheme.typography.titleLarge,
                        color = lxColors.textPrimary
                    )
                    Text(
                        text = "自建 $customPlaylistCount/$maxCustomPlaylists",
                        style = MaterialTheme.typography.labelMedium,
                        color = lxColors.textTertiary
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallActionPill(
                        icon = Icons.Rounded.Link,
                        label = if (isImportingPlaylist) "导入中" else "导入",
                        onClick = { if (!isImportingPlaylist) showImportDialog = true }
                    )
                    SmallActionPill(
                        icon = Icons.Rounded.Add,
                        label = "新建",
                        onClick = {
                            if (customPlaylistCount >= maxCustomPlaylists) {
                                onPlaylistLimitReached()
                            } else {
                                showCreateDialog = true
                            }
                        }
                    )
                }
            }
        }

        if (playlists.isEmpty()) {
            item {
                Box(modifier = Modifier.padding(horizontal = 18.dp)) {
                    GlassCard(onClick = { if (!isImportingPlaylist) showImportDialog = true }) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 28.dp, horizontal = 20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "还没有歌单",
                                style = MaterialTheme.typography.titleMedium,
                                color = lxColors.textPrimary
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "支持一键粘贴网易云、QQ 音乐、酷我歌单分享链接导入",
                                style = MaterialTheme.typography.bodyMedium,
                                color = lxColors.textSecondary,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        } else {
            items(playlists, key = { it.id }) { playlist ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenPlaylist(playlist) }
                        .padding(horizontal = 20.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AlbumArtwork(
                        url = playlist.coverUrl,
                        contentDescription = playlist.name,
                        size = 56.dp,
                        cornerRadius = 14.dp
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = playlist.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = lxColors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "${playlist.trackCount} 首歌曲" + if (!playlist.sourceUrl.isNullOrBlank()) " · 外部导入" else "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = lxColors.textSecondary
                        )
                    }
                    Icon(
                        imageVector = Icons.Rounded.ChevronRight,
                        contentDescription = null,
                        tint = lxColors.textTertiary
                    )
                }
            }
        }
    }

    if (showCreateDialog) {
        SimpleTextInputDialog(
            title = "新建歌单",
            placeholder = "例如：深夜独处、通勤节奏",
            supportingText = "最多支持自建 $maxCustomPlaylists 个歌单（当前 $customPlaylistCount/$maxCustomPlaylists）",
            confirmLabel = "创建",
            onDismiss = { showCreateDialog = false },
            onConfirm = { name ->
                onCreatePlaylist(name)
                showCreateDialog = false
            }
        )
    }

    if (showImportDialog) {
        SimpleTextInputDialog(
            title = "导入外部歌单",
            placeholder = "粘贴网易云 / QQ 音乐 / 酷我歌单链接或分享文案",
            supportingText = "支持直接粘贴完整的 App 分享文本，会自动识别短链并导入全部曲目。",
            confirmLabel = "立即导入",
            singleLine = false,
            onDismiss = { showImportDialog = false },
            onConfirm = { raw ->
                onImportExternalPlaylist(raw)
                showImportDialog = false
            }
        )
    }
}

@Composable
private fun BentoCollectionCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val lxColors = LocalLxColors.current
    GlassCard(
        modifier = modifier,
        cornerRadius = 18.dp,
        onClick = onClick
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(accentColor.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = accentColor, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.height(14.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = lxColors.textPrimary
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = lxColors.textSecondary
            )
        }
    }
}

@Composable
private fun SmallActionPill(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    val lxColors = LocalLxColors.current
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = lxColors.surfaceElevated
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(icon, contentDescription = null, tint = lxColors.accent, modifier = Modifier.size(15.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = lxColors.textPrimary)
        }
    }
}
