package com.lxpure.music.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lxpure.music.core.musicsdk.MusicAggregator
import com.lxpure.music.domain.model.MusicSource
import com.lxpure.music.domain.model.Track
import com.lxpure.music.ui.components.TrackListItem
import com.lxpure.music.ui.theme.LocalLxColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private enum class SearchScope(val label: String, val source: MusicSource?) {
    ALL("聚合去重", null),
    WY("网易云", MusicSource.WY),
    TX("QQ音乐", MusicSource.TX),
    KW("酷我", MusicSource.KW),
    KG("酷狗", MusicSource.KG)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    aggregator: MusicAggregator,
    searchHistory: List<String>,
    currentTrackId: String?,
    isPlaying: Boolean,
    bottomPadding: Dp,
    onSaveSearchQuery: (String) -> Unit,
    onClearSearchHistory: () -> Unit,
    onPlayTracks: (List<Track>, Int) -> Unit,
    onTrackMore: (Track) -> Unit
) {
    val lxColors = LocalLxColors.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    var query by remember { mutableStateOf("") }
    var selectedScope by remember { mutableStateOf(SearchScope.ALL) }
    var results by remember { mutableStateOf<List<Track>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var hasSearched by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var searchJob by remember { mutableStateOf<Job?>(null) }
    var searchGen by remember { mutableLongStateOf(0L) }

    fun executeSearch(keyword: String = query, targetScope: SearchScope = selectedScope) {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) return
        focusManager.clearFocus()
        onSaveSearchQuery(trimmed)
        searchJob?.cancel()
        val gen = ++searchGen
        isSearching = true
        hasSearched = true
        searchError = null
        searchJob = scope.launch {
            try {
                val found = if (targetScope.source == null) {
                    aggregator.searchAggregated(trimmed)
                } else {
                    aggregator.searchSinglePlatform(targetScope.source, trimmed, 1, 30)
                }
                if (searchGen == gen) {
                    results = found
                    isSearching = false
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                if (searchGen == gen) {
                    results = emptyList()
                    searchError = "搜索失败，请检查网络连接后重试"
                    isSearching = false
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        // Minimalist Header + Floating Pill Search Bar
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "搜索",
                style = MaterialTheme.typography.headlineLarge,
                color = lxColors.textPrimary,
                modifier = Modifier.padding(horizontal = 2.dp)
            )

            Surface(
                shape = RoundedCornerShape(18.dp),
                color = lxColors.surfaceElevated,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = null,
                        tint = lxColors.textTertiary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Box(modifier = Modifier.weight(1f)) {
                        if (query.isEmpty()) {
                            Text(
                                text = "搜索歌曲、歌手或专辑",
                                style = MaterialTheme.typography.bodyLarge,
                                color = lxColors.textTertiary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = lxColors.textPrimary),
                            cursorBrush = SolidColor(lxColors.accent),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { executeSearch() }),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (query.isNotEmpty()) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "清除",
                            tint = lxColors.textTertiary,
                            modifier = Modifier
                                .size(20.dp)
                                .clickable {
                                    searchGen++
                                    searchJob?.cancel()
                                    searchJob = null
                                    query = ""
                                    results = emptyList()
                                    hasSearched = false
                                    isSearching = false
                                    searchError = null
                                }
                        )
                    }
                }
            }

            // Platform Scope Chips
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(SearchScope.entries) { item ->
                    val active = item == selectedScope
                    Surface(
                        onClick = {
                            selectedScope = item
                            if (query.isNotBlank()) executeSearch(query, item)
                        },
                        shape = RoundedCornerShape(50),
                        color = if (active) lxColors.accent else lxColors.surfaceElevated
                    ) {
                        Text(
                            text = item.label,
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = if (active) MaterialTheme.colorScheme.onPrimary else lxColors.textSecondary,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                        )
                    }
                }
            }
        }

        // Body
        when {
            isSearching -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = lxColors.accent, strokeWidth = 2.4.dp)
                }
            }

            !hasSearched -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .imePadding()
                        .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = bottomPadding),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    if (searchHistory.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("最近搜索", style = MaterialTheme.typography.titleMedium, color = lxColors.textSecondary)
                            IconButton(onClick = onClearSearchHistory, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Rounded.DeleteOutline, contentDescription = "清空历史", tint = lxColors.textTertiary, modifier = Modifier.size(18.dp))
                            }
                        }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            searchHistory.forEach { term ->
                                Surface(
                                    onClick = {
                                        query = term
                                        executeSearch(term)
                                    },
                                    shape = RoundedCornerShape(50),
                                    color = lxColors.surfaceElevated
                                ) {
                                    Text(
                                        text = term,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = lxColors.textPrimary,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                                    )
                                }
                            }
                        }
                    } else {
                        Box(Modifier.fillMaxSize().padding(bottom = 80.dp), contentAlignment = Alignment.Center) {
                            Text(
                                text = "输入歌名或歌手，一键聚合检索全网平台",
                                style = MaterialTheme.typography.bodyMedium,
                                color = lxColors.textTertiary
                            )
                        }
                    }
                }
            }

            searchError != null -> {
                Box(Modifier.fillMaxSize().padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(searchError ?: "搜索失败", color = lxColors.textSecondary)
                        OutlinedButton(
                            onClick = { executeSearch(query, selectedScope) },
                            shape = RoundedCornerShape(50)
                        ) {
                            Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("重试")
                        }
                    }
                }
            }

            results.isEmpty() -> {
                Box(Modifier.fillMaxSize().padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("没有找到相关歌曲，换个关键词或平台试试", color = lxColors.textSecondary)
                        OutlinedButton(
                            onClick = { executeSearch(query, selectedScope) },
                            shape = RoundedCornerShape(50)
                        ) {
                            Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("重新搜索")
                        }
                    }
                }
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.imePadding(),
                    contentPadding = PaddingValues(bottom = bottomPadding)
                ) {
                    itemsIndexed(results, key = { idx, t -> "${t.id}_$idx" }) { index, track ->
                        TrackListItem(
                            track = track,
                            isCurrent = track.id == currentTrackId,
                            isPlaying = isPlaying && track.id == currentTrackId,
                            showSourceBadge = true,
                            onClick = { onPlayTracks(results, index) },
                            onMoreClick = { onTrackMore(track) }
                        )
                    }
                }
            }
        }
    }
}
