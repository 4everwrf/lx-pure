package com.lxpure.music.ui.discover

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lxpure.music.core.musicsdk.MusicAggregator
import com.lxpure.music.core.musicsdk.PlaylistDetailResult
import com.lxpure.music.domain.model.LeaderboardItem
import com.lxpure.music.domain.model.MusicSource
import com.lxpure.music.domain.model.OnlinePlaylist
import com.lxpure.music.domain.model.Track
import com.lxpure.music.ui.components.AlbumArtwork
import kotlinx.coroutines.CancellationException
import com.lxpure.music.ui.components.TrackListFastScroller
import com.lxpure.music.ui.components.TrackListItem
import com.lxpure.music.ui.theme.LocalLxColors

val DISCOVER_SUPPORTED_SOURCES: List<MusicSource> = listOf(MusicSource.WY, MusicSource.TX, MusicSource.KW)

fun normalizeDiscoverSource(source: MusicSource): MusicSource =
    if (source in DISCOVER_SUPPORTED_SOURCES) source else MusicSource.WY

data class DiscoverSessionState(
    val selectedSource: MusicSource = MusicSource.WY,
    val playlistsBySource: Map<MusicSource, List<OnlinePlaylist>> = emptyMap(),
    val generation: Long = 0L,
    val refreshNonce: Int = 0,
    val refreshTargetSource: MusicSource? = null
) {
    val activeSource: MusicSource
        get() = normalizeDiscoverSource(selectedSource)

    val currentPlaylists: List<OnlinePlaylist>
        get() = playlistsBySource[activeSource].orEmpty()

    fun hasCachedPlaylists(source: MusicSource = activeSource): Boolean =
        !playlistsBySource[normalizeDiscoverSource(source)].isNullOrEmpty()

    fun shouldFetchForCurrentState(): Boolean =
        !hasCachedPlaylists(activeSource) || (refreshTargetSource == activeSource && refreshNonce > 0)

    fun selectSource(source: MusicSource): DiscoverSessionState {
        val normalized = normalizeDiscoverSource(source)
        if (normalized == activeSource) return this
        return copy(
            selectedSource = normalized,
            generation = generation + 1L,
            refreshTargetSource = null
        )
    }

    fun requestRefresh(): DiscoverSessionState = copy(
        generation = generation + 1L,
        refreshNonce = refreshNonce + 1,
        refreshTargetSource = activeSource
    )

    fun commitFetchedPlaylists(
        targetSource: MusicSource,
        requestGeneration: Long,
        playlists: List<OnlinePlaylist>
    ): DiscoverSessionState {
        val normalized = normalizeDiscoverSource(targetSource)
        if (normalized != activeSource || requestGeneration != generation) {
            return this
        }
        val deduplicated = playlists.distinctBy { "${it.source.code}_${it.id}" }
        val updatedCache = playlistsBySource.toMutableMap()
        if (deduplicated.isNotEmpty()) {
            updatedCache[normalized] = deduplicated
        } else {
            updatedCache.remove(normalized)
        }
        return copy(
            playlistsBySource = updatedCache,
            refreshTargetSource = null
        )
    }
}

@Composable
fun DiscoverScreen(
    aggregator: MusicAggregator,
    discoverState: DiscoverSessionState,
    onSelectSource: (MusicSource) -> Unit,
    onRetryLoad: () -> Unit,
    onCommitPlaylists: (targetSource: MusicSource, requestGeneration: Long, playlists: List<OnlinePlaylist>) -> Unit,
    bottomPadding: Dp,
    onOpenPlaylist: (OnlinePlaylist) -> Unit,
    onOpenLeaderboard: (LeaderboardItem) -> Unit
) {
    val lxColors = LocalLxColors.current
    val onPrimaryColor = MaterialTheme.colorScheme.onPrimary
    val platforms = DISCOVER_SUPPORTED_SOURCES
    val selectedSource = discoverState.activeSource
    val hotPlaylists = discoverState.currentPlaylists
    val latestDiscoverState by rememberUpdatedState(discoverState)
    var isLoading by remember(selectedSource) { mutableStateOf(!discoverState.hasCachedPlaylists(selectedSource)) }

    val leaderboards = remember(selectedSource) { aggregator.getLeaderboards(selectedSource) }

    LaunchedEffect(selectedSource, discoverState.refreshNonce) {
        val snapshot = latestDiscoverState
        if (!snapshot.shouldFetchForCurrentState()) {
            isLoading = false
            return@LaunchedEffect
        }
        val targetSource = snapshot.activeSource
        val reqGen = snapshot.generation
        isLoading = true
        try {
            val fetched = aggregator.getHotPlaylists(targetSource)
            if (latestDiscoverState.generation == reqGen && latestDiscoverState.activeSource == targetSource) {
                onCommitPlaylists(targetSource, reqGen, fetched)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            if (latestDiscoverState.generation == reqGen && latestDiscoverState.activeSource == targetSource) {
                onCommitPlaylists(targetSource, reqGen, emptyList())
            }
        } finally {
            if (latestDiscoverState.generation == reqGen && latestDiscoverState.activeSource == targetSource) {
                isLoading = false
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(top = 12.dp, bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        item {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "发现",
                    style = MaterialTheme.typography.headlineLarge,
                    color = lxColors.textPrimary
                )

                // Minimalist Platform Segmented Pills
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    platforms.forEach { source ->
                        val active = source == selectedSource
                        Surface(
                            onClick = { onSelectSource(source) },
                            shape = RoundedCornerShape(50),
                            color = if (active) lxColors.accent else lxColors.surfaceElevated
                        ) {
                            Text(
                                text = source.displayName,
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = if (active) onPrimaryColor else lxColors.textSecondary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
            }
        }

        // Featured Playlists Carousel
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "精选歌单",
                    style = MaterialTheme.typography.titleLarge,
                    color = lxColors.textPrimary,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )

                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(170.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(strokeWidth = 2.dp, color = lxColors.accent)
                    }
                } else if (hotPlaylists.isEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "歌单加载失败或网络不可达",
                            style = MaterialTheme.typography.bodyMedium,
                            color = lxColors.textSecondary,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedButton(
                            onClick = onRetryLoad,
                            shape = RoundedCornerShape(50),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("重试", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                } else {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 18.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        itemsIndexed(hotPlaylists, key = { idx, p -> "${p.source.code}_${p.id}_$idx" }) { _, playlist ->
                            Column(
                                modifier = Modifier
                                    .width(152.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable { onOpenPlaylist(playlist) }
                            ) {
                                Box {
                                    AlbumArtwork(
                                        url = playlist.coverUrl,
                                        contentDescription = playlist.name,
                                        size = 152.dp,
                                        cornerRadius = 18.dp
                                    )
                                    if (playlist.playCount.isNotBlank()) {
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .padding(8.dp)
                                                .clip(RoundedCornerShape(50))
                                                .background(Color.Black.copy(alpha = 0.55f))
                                                .padding(horizontal = 8.dp, vertical = 3.dp)
                                        ) {
                                            Text(
                                                text = playlist.playCount,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Color.White
                                            )
                                        }
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = playlist.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = lxColors.textPrimary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }

        // Streamlined Bento Leaderboards (2-column grid rows)
        item {
            Column(
                modifier = Modifier.padding(horizontal = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "热门榜单",
                    style = MaterialTheme.typography.titleLarge,
                    color = lxColors.textPrimary,
                    modifier = Modifier.padding(horizontal = 2.dp)
                )

                leaderboards.chunked(2).forEach { pair ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        pair.forEach { board ->
                            val accent = Color(board.accentHex)
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(88.dp)
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(
                                        Brush.linearGradient(
                                            listOf(
                                                accent.copy(alpha = if (lxColors.isDark) 0.34f else 0.20f),
                                                lxColors.surfaceElevated
                                            )
                                        )
                                    )
                                    .clickable { onOpenLeaderboard(board) }
                                    .padding(16.dp)
                            ) {
                                Column(
                                    modifier = Modifier.align(Alignment.CenterStart),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = board.name,
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                        color = lxColors.textPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = board.subtitle,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = lxColors.textSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                        if (pair.size == 1) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun OnlinePlaylistDetailScreen(
    loadKey: String,
    title: String,
    coverUrl: String?,
    loadDetail: suspend () -> Result<PlaylistDetailResult>,
    currentTrackId: String?,
    isPlaying: Boolean,
    bottomPadding: Dp,
    onBack: () -> Unit,
    onPlayTracks: (List<Track>, Int) -> Unit,
    onSaveToLibrary: (PlaylistDetailResult) -> Unit,
    onTrackMore: (Track) -> Unit
) {
    val lxColors = LocalLxColors.current
    val onPrimaryColor = MaterialTheme.colorScheme.onPrimary
    var detail by remember(loadKey) { mutableStateOf<PlaylistDetailResult?>(null) }
    var errorMsg by remember(loadKey) { mutableStateOf<String?>(null) }
    var isLoading by remember(loadKey) { mutableStateOf(true) }
    var isSaved by remember(loadKey) { mutableStateOf(false) }
    var retryNonce by remember(loadKey) { mutableIntStateOf(0) }
    val listState = rememberLazyListState()

    val showTopBarTitle by remember(detail) {
        derivedStateOf {
            detail == null ||
                listState.firstVisibleItemIndex > 0 ||
                listState.firstVisibleItemScrollOffset > 140
        }
    }
    val topBarTitleAlpha by animateFloatAsState(
        targetValue = if (showTopBarTitle) 1f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "detailTopTitleAlpha"
    )

    var detailLoadGen by remember { mutableLongStateOf(0L) }
    LaunchedEffect(loadKey, retryNonce) {
        val gen = ++detailLoadGen
        isLoading = true
        errorMsg = null
        detail = null
        try {
            loadDetail()
                .onSuccess { loaded ->
                    if (detailLoadGen == gen) {
                        detail = loaded
                    }
                }
                .onFailure { err ->
                    if (err is CancellationException) throw err
                    if (detailLoadGen == gen) {
                        val raw = err.message.orEmpty()
                        errorMsg = if (raw.isBlank() || raw.none { it.code > 0x4E00 }) {
                            "加载失败，请检查网络连接后重试"
                        } else {
                            raw
                        }
                    }
                }
        } catch (ce: CancellationException) {
            throw ce
        } finally {
            if (detailLoadGen == gen) {
                isLoading = false
            }
        }
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
            Text(
                text = detail?.playlist?.name ?: title,
                style = MaterialTheme.typography.titleLarge,
                color = lxColors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .alpha(topBarTitleAlpha)
            )
        }

        when {
            isLoading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = lxColors.accent, strokeWidth = 2.4.dp)
                }
            }
            errorMsg != null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text(
                            text = errorMsg ?: "内容加载失败，请稍后重试",
                            style = MaterialTheme.typography.bodyLarge,
                            color = lxColors.textSecondary,
                            textAlign = TextAlign.Center
                        )
                        Button(
                            onClick = { retryNonce++ },
                            shape = RoundedCornerShape(50),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = lxColors.accent,
                                contentColor = onPrimaryColor
                            ),
                            contentPadding = PaddingValues(horizontal = 22.dp, vertical = 10.dp)
                        ) {
                            Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("重新加载", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
            detail != null -> {
                val res = detail!!
                val currentTrackIndex = remember(res.tracks, currentTrackId) {
                    currentTrackId?.let { id -> res.tracks.indexOfFirst { it.id == id } } ?: -1
                }
                val extraDockClearance = when {
                    res.tracks.size < 15 -> 0.dp
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
                                    .padding(horizontal = 20.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AlbumArtwork(
                                    url = res.playlist.coverUrl.ifBlank { coverUrl },
                                    contentDescription = res.playlist.name,
                                    size = 98.dp,
                                    cornerRadius = 18.dp
                                )
                                Spacer(Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = res.playlist.name,
                                        style = MaterialTheme.typography.titleLarge,
                                        color = lxColors.textPrimary,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = buildString {
                                            if (res.playlist.author.isNotBlank()) append(res.playlist.author)
                                            append(" · ${res.tracks.size} 首")
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = lxColors.textSecondary
                                    )
                                    Spacer(Modifier.height(10.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(
                                            onClick = { if (res.tracks.isNotEmpty()) onPlayTracks(res.tracks, 0) },
                                            shape = RoundedCornerShape(50),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = lxColors.accent,
                                                contentColor = onPrimaryColor
                                            ),
                                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                                        ) {
                                            Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("播放全部", style = MaterialTheme.typography.labelMedium)
                                        }
                                        OutlinedButton(
                                            onClick = {
                                                if (!isSaved) {
                                                    isSaved = true
                                                    onSaveToLibrary(res)
                                                }
                                            },
                                            enabled = !isSaved,
                                            shape = RoundedCornerShape(50),
                                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                                        ) {
                                            Icon(Icons.Rounded.BookmarkAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text(if (isSaved) "已收藏" else "收藏", style = MaterialTheme.typography.labelMedium)
                                        }
                                    }
                                }
                            }
                        }

                        itemsIndexed(res.tracks, key = { idx, t -> "${t.id}_$idx" }) { index, track ->
                            TrackListItem(
                                track = track,
                                isCurrent = track.id == currentTrackId,
                                isPlaying = isPlaying && track.id == currentTrackId,
                                indexLabel = index + 1,
                                onClick = { onPlayTracks(res.tracks, index) },
                                onMoreClick = { onTrackMore(track) }
                            )
                        }
                    }

                    TrackListFastScroller(
                        listState = listState,
                        trackCount = res.tracks.size,
                        headerItemCount = 1,
                        currentTrackIndex = currentTrackIndex,
                        bottomPadding = bottomPadding,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}
