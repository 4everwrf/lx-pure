package com.lxpure.music.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lxpure.music.BuildConfig
import com.lxpure.music.core.musicsdk.FormatUtils
import com.lxpure.music.core.update.AppUpdateInfo
import com.lxpure.music.core.update.AppUpdateManager
import com.lxpure.music.core.update.AppUpdateState
import com.lxpure.music.ui.theme.LocalLxColors
import java.io.File

@Composable
fun AppUpdateDialog(
    state: AppUpdateState,
    onStartDownload: (AppUpdateInfo) -> Unit,
    onCancelDownload: () -> Unit,
    onInstallApk: (File) -> Unit,
    onOpenInBrowser: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val lxColors = LocalLxColors.current

    val info = when (state) {
        is AppUpdateState.Available -> state.info
        is AppUpdateState.Downloading -> state.info
        is AppUpdateState.Verifying -> state.info
        is AppUpdateState.ReadyToInstall -> state.info
        is AppUpdateState.Error -> state.info
        else -> null
    } ?: return

    val currentVer = AppUpdateManager.normalizeVersion(BuildConfig.VERSION_NAME)
    val canDismissOutside = state !is AppUpdateState.Downloading && state !is AppUpdateState.Verifying
    val noteItems = remember(info.releaseNotes) {
        AppUpdateManager.parseReleaseNoteItems(info.releaseNotes)
    }

    Dialog(
        onDismissRequest = { if (canDismissOutside) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = canDismissOutside,
            dismissOnClickOutside = canDismissOutside,
            usePlatformDefaultWidth = false
        )
    ) {
        SyncDialogSystemBars(useLightSystemBars = !lxColors.isDark)

        val maxDialogHeight = (LocalConfiguration.current.screenHeightDp * 0.88f).dp
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .widthIn(max = 368.dp)
                .heightIn(max = maxDialogHeight),
            shape = RoundedCornerShape(28.dp),
            color = lxColors.surfaceElevated,
            border = BorderStroke(0.9.dp, lxColors.hairline),
            shadowElevation = 24.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                // Soft ambient gradient header
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    lxColors.accent.copy(alpha = if (lxColors.isDark) 0.20f else 0.14f),
                                    Color.Transparent
                                )
                            )
                        )
                        .padding(start = 22.dp, end = 12.dp, top = 18.dp, bottom = 14.dp)
                ) {
                    if (canDismissOutside) {
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .size(34.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "关闭",
                                tint = lxColors.textTertiary,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(end = if (canDismissOutside) 32.dp else 0.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(50.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(
                                    Brush.linearGradient(
                                        listOf(lxColors.accent, Color(0xFF6366F1))
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AutoAwesome,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(26.dp)
                            )
                        }

                        Spacer(Modifier.width(14.dp))

                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "发现新版本",
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                color = lxColors.textPrimary
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = lxColors.hairline
                                ) {
                                    Text(
                                        text = "v$currentVer",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = lxColors.textSecondary,
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                    )
                                }
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                                    contentDescription = null,
                                    tint = lxColors.accent,
                                    modifier = Modifier.size(13.dp)
                                )
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = lxColors.accentSoft
                                ) {
                                    Text(
                                        text = "v${info.versionName}",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        color = lxColors.accent,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                    )
                                }
                                if (info.assetSizeBytes > 0L) {
                                    Text(
                                        text = FormatUtils.formatSize(info.assetSizeBytes),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = lxColors.textTertiary
                                    )
                                }
                            }
                        }
                    }
                }

                // Body content
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 22.dp)
                        .padding(bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Structured bullet changelog card
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = lxColors.background.copy(alpha = if (lxColors.isDark) 0.55f else 0.85f),
                        border = BorderStroke(0.7.dp, lxColors.hairline)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 176.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "本版更新",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = lxColors.textTertiary
                            )
                            noteItems.forEach { item ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.Top,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .padding(top = 7.dp)
                                            .size(5.dp)
                                            .clip(CircleShape)
                                            .background(lxColors.accent)
                                    )
                                    Text(
                                        text = item,
                                        style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp),
                                        color = lxColors.textPrimary.copy(alpha = 0.88f),
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }

                    // Dynamic state panel
                    when (state) {
                        is AppUpdateState.Downloading -> {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = lxColors.accentSoft.copy(alpha = 0.45f),
                                border = BorderStroke(0.7.dp, lxColors.accent.copy(alpha = 0.25f))
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = "正在下载 · ${state.routeLabel}",
                                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                                            color = lxColors.textPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        val progressText = if (state.totalBytes > 0L) {
                                            "${FormatUtils.formatSize(state.downloadedBytes)} / ${FormatUtils.formatSize(state.totalBytes)} (${(state.progress * 100).toInt()}%)"
                                        } else {
                                            FormatUtils.formatSize(state.downloadedBytes)
                                        }
                                        Text(
                                            text = progressText,
                                            style = MaterialTheme.typography.labelMedium.copy(
                                                fontWeight = FontWeight.Bold,
                                                fontFeatureSettings = "tnum"
                                            ),
                                            color = lxColors.accent,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                    }
                                    if (state.totalBytes > 0L && state.downloadedBytes > 0L) {
                                        LinearProgressIndicator(
                                            progress = { state.progress.coerceIn(0f, 1f) },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(7.dp)
                                                .clip(RoundedCornerShape(50)),
                                            color = lxColors.accent,
                                            trackColor = lxColors.hairline
                                        )
                                    } else {
                                        LinearProgressIndicator(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(7.dp)
                                                .clip(RoundedCornerShape(50)),
                                            color = lxColors.accent,
                                            trackColor = lxColors.hairline
                                        )
                                    }
                                    if (state.downloadedBytes == 0L) {
                                        Text(
                                            text = "连接中，若直连超时将自动切换国内镜像通道…",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = lxColors.textSecondary
                                        )
                                    }
                                }
                            }
                        }

                        is AppUpdateState.Verifying -> {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = lxColors.accentSoft.copy(alpha = 0.4f)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = lxColors.accent
                                    )
                                    Text(
                                        text = if (!info.expectedSha256.isNullOrBlank()) {
                                            "正在校验安装包 SHA-256 完整性…"
                                        } else {
                                            "正在校验安装包结构完整性…"
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = lxColors.textPrimary
                                    )
                                }
                            }
                        }

                        is AppUpdateState.ReadyToInstall -> {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = lxColors.success.copy(alpha = 0.12f),
                                border = BorderStroke(0.7.dp, lxColors.success.copy(alpha = 0.28f))
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 11.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.CheckCircle,
                                        contentDescription = null,
                                        tint = lxColors.success,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        text = "安装包校验通过，覆盖升级不会清除你的歌单与自定义音源。",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = lxColors.textPrimary
                                    )
                                }
                            }
                        }

                        is AppUpdateState.Error -> {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = lxColors.danger.copy(alpha = 0.10f),
                                border = BorderStroke(0.7.dp, lxColors.danger.copy(alpha = 0.28f))
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 11.dp),
                                    verticalAlignment = Alignment.Top,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.WifiOff,
                                        contentDescription = null,
                                        tint = lxColors.danger,
                                        modifier = Modifier
                                            .padding(top = 2.dp)
                                            .size(18.dp)
                                    )
                                    Text(
                                        text = state.message,
                                        style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 19.sp),
                                        color = lxColors.danger
                                    )
                                }
                            }
                        }

                        else -> Unit
                    }

                    // Clean stacked action footer (never wraps or cramps buttons)
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        when (state) {
                            is AppUpdateState.Available -> {
                                Button(
                                    onClick = { onStartDownload(info) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp),
                                    shape = RoundedCornerShape(16.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = lxColors.accent,
                                        contentColor = MaterialTheme.colorScheme.onPrimary
                                    )
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Download,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = "立即更新",
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceEvenly
                                ) {
                                    TextButton(
                                        onClick = { onOpenInBrowser(info.htmlUrl) },
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                                            contentDescription = null,
                                            tint = lxColors.textSecondary,
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text("浏览器下载", color = lxColors.textSecondary)
                                    }
                                    HorizontalDivider(
                                        modifier = Modifier
                                            .width(1.dp)
                                            .height(14.dp),
                                        color = lxColors.hairline
                                    )
                                    TextButton(
                                        onClick = onDismiss,
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Text("稍后再说", color = lxColors.textSecondary)
                                    }
                                }
                            }

                            is AppUpdateState.Downloading -> {
                                Button(
                                    onClick = onCancelDownload,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(46.dp),
                                    shape = RoundedCornerShape(16.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = lxColors.danger.copy(alpha = 0.14f),
                                        contentColor = lxColors.danger
                                    ),
                                    elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp)
                                ) {
                                    Text(
                                        text = "取消下载",
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                                    )
                                }
                            }

                            is AppUpdateState.ReadyToInstall -> {
                                Button(
                                    onClick = { onInstallApk(state.apkFile) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp),
                                    shape = RoundedCornerShape(16.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = lxColors.accent,
                                        contentColor = MaterialTheme.colorScheme.onPrimary
                                    )
                                ) {
                                    Text(
                                        text = "立即安装",
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                                    )
                                }
                                Box(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    TextButton(onClick = onDismiss) {
                                        Text("稍后安装", color = lxColors.textSecondary)
                                    }
                                }
                            }

                            is AppUpdateState.Error -> {
                                Button(
                                    onClick = { onStartDownload(info) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp),
                                    shape = RoundedCornerShape(16.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = lxColors.accent,
                                        contentColor = MaterialTheme.colorScheme.onPrimary
                                    )
                                ) {
                                    Text(
                                        text = "重试下载",
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceEvenly
                                ) {
                                    TextButton(
                                        onClick = { onOpenInBrowser(info.htmlUrl) },
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                                            contentDescription = null,
                                            tint = lxColors.accent,
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text("浏览器下载", color = lxColors.accent)
                                    }
                                    HorizontalDivider(
                                        modifier = Modifier
                                            .width(1.dp)
                                            .height(14.dp),
                                        color = lxColors.hairline
                                    )
                                    TextButton(
                                        onClick = onDismiss,
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Text("关闭", color = lxColors.textSecondary)
                                    }
                                }
                            }

                            else -> Unit
                        }
                    }
                }
            }
        }
    }
}
