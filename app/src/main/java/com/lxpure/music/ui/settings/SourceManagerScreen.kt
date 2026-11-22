package com.lxpure.music.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lxpure.music.core.userapi.LxScriptParser
import com.lxpure.music.core.userapi.UserApiManager
import com.lxpure.music.domain.model.UserApiRuntimeStatus
import com.lxpure.music.domain.model.UserApiSource
import com.lxpure.music.ui.components.GlassCard
import com.lxpure.music.ui.components.SimpleTextInputDialog
import com.lxpure.music.ui.components.SmallPillBadge
import com.lxpure.music.ui.theme.LocalLxColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SourceManagerScreen(
    sources: List<UserApiSource>,
    userApiManager: UserApiManager,
    bottomPadding: Dp,
    onBack: () -> Unit,
    onMessage: (String) -> Unit
) {
    val lxColors = LocalLxColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var showUrlDialog by remember { mutableStateOf(false) }
    var showPasteDialog by remember { mutableStateOf(false) }
    var isImporting by remember { mutableStateOf(false) }
    var isUpdatingAll by remember { mutableStateOf(false) }
    var testingId by remember { mutableStateOf<String?>(null) }
    var updatingId by remember { mutableStateOf<String?>(null) }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            isImporting = true
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val content = context.contentResolver.openInputStream(uri)?.bufferedReader(StandardCharsets.UTF_8)?.use {
                        it.readText()
                    } ?: error("无法读取该文件")
                    userApiManager.importRawScript(content).getOrThrow()
                }
            }
            isImporting = false
            result.onSuccess { onMessage("已导入音源：${it.name}") }
                .onFailure { onMessage("导入失败：${it.message}") }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        // Header
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
                Text("自定义音源管理", style = MaterialTheme.typography.titleLarge, color = lxColors.textPrimary)
                Text("100% 兼容洛雪音乐 .js 自定义源协议", style = MaterialTheme.typography.bodyMedium, color = lxColors.textSecondary)
            }
            if (isImporting || isUpdatingAll) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(end = 14.dp).size(20.dp),
                    strokeWidth = 2.dp,
                    color = lxColors.accent
                )
            } else if (sources.isNotEmpty()) {
                TextButton(
                    onClick = {
                        scope.launch {
                            isUpdatingAll = true
                            val summary = userApiManager.updateAllSources()
                            isUpdatingAll = false
                            val msg = when {
                                summary.updatedNames.isNotEmpty() ->
                                    "已更新 ${summary.updatedNames.size} 个音源：${summary.updatedNames.joinToString("、")}"
                                summary.failedCount == 0 && summary.latestCount > 0 ->
                                    "全部 ${summary.latestCount} 个在线音源均已是最新版本"
                                summary.latestCount > 0 ->
                                    "${summary.latestCount} 个已最新，${summary.failedCount} 个拉取失败"
                                else ->
                                    "更新检查失败，请检查网络连接后重试"
                            }
                            onMessage(msg)
                        }
                    },
                    modifier = Modifier.padding(end = 4.dp)
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, tint = lxColors.accent, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("更新音源", style = MaterialTheme.typography.labelMedium, color = lxColors.accent)
                }
            }
        }

        LazyColumn(
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 8.dp, bottom = bottomPadding),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 3 Quick Import Methods
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ImportActionTile(
                        icon = Icons.Rounded.Link,
                        title = "在线链接",
                        subtitle = "粘贴 .js URL",
                        onClick = { showUrlDialog = true },
                        modifier = Modifier.weight(1f)
                    )
                    ImportActionTile(
                        icon = Icons.Rounded.FolderOpen,
                        title = "本地文件",
                        subtitle = "选择 .js 文件",
                        onClick = { filePicker.launch(arrayOf("*/*")) },
                        modifier = Modifier.weight(1f)
                    )
                    ImportActionTile(
                        icon = Icons.Rounded.Code,
                        title = "粘贴脚本",
                        subtitle = "源码导入",
                        onClick = { showPasteDialog = true },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            if (sources.isEmpty()) {
                item {
                    GlassCard {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text("尚未添加任何音源", style = MaterialTheme.typography.titleMedium, color = lxColors.textPrimary)
                            Text(
                                text = "轻雪音乐本身不内置也不提供任何音源脚本，请通过上方三种方式自行导入合规的洛雪协议 .js 自定义源。支持同时启用多个源并按优先级自动切换兜底。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = lxColors.textSecondary,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            } else {
                itemsIndexed(sources, key = { _, s -> s.id }) { index, source ->
                    SourceItemCard(
                        source = source,
                        isTopPriority = index == 0,
                        isTesting = testingId == source.id,
                        isUpdating = updatingId == source.id,
                        onToggleEnabled = { enabled ->
                            scope.launch { userApiManager.toggleEnabled(source.id, enabled) }
                        },
                        onMoveUp = {
                            scope.launch { userApiManager.movePriorityUp(source.id) }
                        },
                        onTestHealth = {
                            scope.launch {
                                testingId = source.id
                                userApiManager.testSourceHealth(source.id)
                                    .onSuccess { ms -> onMessage("${source.name} 解析可用 (${ms}ms)") }
                                    .onFailure { err -> onMessage("${source.name} 测试失败：${err.message}") }
                                testingId = null
                            }
                        },
                        onUpdateSource = {
                            scope.launch {
                                updatingId = source.id
                                val res = userApiManager.updateSingleSource(source.id)
                                updatingId = null
                                when {
                                    res.updated -> onMessage(
                                        "${res.sourceName} 已更新：${LxScriptParser.formatVersionBadge(res.oldVersion)} → ${LxScriptParser.formatVersionBadge(res.newVersion)}"
                                    )
                                    res.alreadyLatest -> onMessage(
                                        "${res.sourceName} 已是最新 (${LxScriptParser.formatVersionBadge(res.newVersion)})"
                                    )
                                    else -> onMessage("${res.sourceName} 更新失败：${res.errorMessage ?: "请稍后重试"}")
                                }
                            }
                        },
                        onDelete = {
                            scope.launch {
                                userApiManager.removeSource(source.id)
                                onMessage("已移除音源：${source.name}")
                            }
                        }
                    )
                }
            }
        }
    }

    if (showUrlDialog) {
        SimpleTextInputDialog(
            title = "在线导入音源",
            placeholder = "https://.../lx-source.js",
            supportingText = "支持 GitHub Raw、CDN 或任意 HTTPS 直链托管的洛雪 .js 音源脚本。",
            confirmLabel = "下载并启用",
            onDismiss = { showUrlDialog = false },
            onConfirm = { url ->
                showUrlDialog = false
                scope.launch {
                    isImporting = true
                    userApiManager.importFromUrl(url)
                        .onSuccess { onMessage("已导入并启用：${it.name}") }
                        .onFailure { onMessage("导入失败：${it.message}") }
                    isImporting = false
                }
            }
        )
    }

    if (showPasteDialog) {
        SimpleTextInputDialog(
            title = "粘贴音源脚本代码",
            placeholder = "粘贴完整的洛雪自定义源 JavaScript 代码…",
            confirmLabel = "保存并启用",
            singleLine = false,
            onDismiss = { showPasteDialog = false },
            onConfirm = { code ->
                showPasteDialog = false
                scope.launch {
                    isImporting = true
                    userApiManager.importRawScript(code)
                        .onSuccess { onMessage("已导入并启用：${it.name}") }
                        .onFailure { onMessage("导入失败：${it.message}") }
                    isImporting = false
                }
            }
        )
    }
}

@Composable
private fun ImportActionTile(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val lxColors = LocalLxColors.current
    GlassCard(modifier = modifier, cornerRadius = 16.dp, onClick = onClick) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(icon, contentDescription = null, tint = lxColors.accent, modifier = Modifier.size(22.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, color = lxColors.textPrimary)
            Text(subtitle, style = MaterialTheme.typography.labelMedium, color = lxColors.textTertiary)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SourceItemCard(
    source: UserApiSource,
    isTopPriority: Boolean,
    isTesting: Boolean,
    isUpdating: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    onMoveUp: () -> Unit,
    onTestHealth: () -> Unit,
    onUpdateSource: () -> Unit,
    onDelete: () -> Unit
) {
    val lxColors = LocalLxColors.current
    val statusColor = when (source.status) {
        UserApiRuntimeStatus.READY -> lxColors.success
        UserApiRuntimeStatus.INITIALIZING -> lxColors.warning
        UserApiRuntimeStatus.ERROR -> lxColors.danger
        UserApiRuntimeStatus.IDLE -> lxColors.textTertiary
    }
    val statusText = when (source.status) {
        UserApiRuntimeStatus.READY -> source.latencyMs?.let { "就绪 · ${it}ms" } ?: "已就绪"
        UserApiRuntimeStatus.INITIALIZING -> "初始化中…"
        UserApiRuntimeStatus.ERROR -> source.errorMessage ?: "初始化失败"
        UserApiRuntimeStatus.IDLE -> "已停用"
    }

    GlassCard(cornerRadius = 18.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(statusColor)
                        )
                        Text(
                            text = source.name,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = lxColors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        SmallPillBadge(
                            text = LxScriptParser.formatVersionBadge(source.version),
                            background = lxColors.hairline,
                            contentColor = lxColors.textSecondary
                        )
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = "$statusText · 作者 ${source.author}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (source.status == UserApiRuntimeStatus.ERROR) lxColors.danger else lxColors.textSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(Modifier.width(8.dp))

                Switch(
                    checked = source.enabled,
                    onCheckedChange = onToggleEnabled,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        checkedTrackColor = lxColors.accent
                    )
                )
            }

            if (source.supportedPlatforms.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    source.supportedPlatforms.forEach { (platform, qualities) ->
                        val maxQ = qualities.lastOrNull()?.uppercase().orEmpty()
                        SmallPillBadge(
                            text = "${platform.uppercase()} ${if (maxQ.isNotEmpty()) "· $maxQ" else ""}".trim(),
                            background = lxColors.accentSoft,
                            contentColor = lxColors.accent
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!isTopPriority) {
                    TextButton(
                        onClick = onMoveUp,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.Rounded.ArrowUpward, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(3.dp))
                        Text("上移", maxLines = 1)
                    }
                }
                TextButton(
                    onClick = onTestHealth,
                    enabled = !isTesting && !isUpdating,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    if (isTesting) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Rounded.Speed, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(3.dp))
                    Text("测速", maxLines = 1)
                }
                TextButton(
                    onClick = onUpdateSource,
                    enabled = !isUpdating && !isTesting,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    if (isUpdating) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(3.dp))
                    Text("更新", maxLines = 1)
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Rounded.DeleteOutline, contentDescription = "删除", tint = lxColors.danger, modifier = Modifier.size(19.dp))
                }
            }
        }
    }
}
