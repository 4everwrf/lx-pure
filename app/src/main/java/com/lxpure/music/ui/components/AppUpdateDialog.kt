package com.lxpure.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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

    AlertDialog(
        onDismissRequest = { if (canDismissOutside) onDismiss() },
        containerColor = lxColors.surfaceElevated,
        icon = {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(lxColors.accentSoft),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.SystemUpdate,
                    contentDescription = null,
                    tint = lxColors.accent,
                    modifier = Modifier.size(26.dp)
                )
            }
        },
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "发现新版本 v${info.versionName}",
                    style = MaterialTheme.typography.titleLarge,
                    color = lxColors.textPrimary
                )
                Spacer(Modifier.height(4.dp))
                val sizeLabel = if (info.assetSizeBytes > 0L) {
                    " · ${FormatUtils.formatSize(info.assetSizeBytes)}"
                } else ""
                Text(
                    text = "v$currentVer → v${info.versionName}$sizeLabel",
                    style = MaterialTheme.typography.labelMedium,
                    color = lxColors.accent
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 180.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(lxColors.background.copy(alpha = 0.65f))
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp)
                ) {
                    Text(
                        text = info.releaseNotes,
                        style = MaterialTheme.typography.bodyMedium,
                        color = lxColors.textSecondary
                    )
                }

                when (state) {
                    is AppUpdateState.Downloading -> {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (state.totalBytes > 0L) {
                                LinearProgressIndicator(
                                    progress = { state.progress.coerceIn(0f, 1f) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(50)),
                                    color = lxColors.accent,
                                    trackColor = lxColors.hairline
                                )
                            } else {
                                LinearProgressIndicator(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(50)),
                                    color = lxColors.accent,
                                    trackColor = lxColors.hairline
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "正在下载安装包…",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = lxColors.textSecondary
                                )
                                val progressText = if (state.totalBytes > 0L) {
                                    "${FormatUtils.formatSize(state.downloadedBytes)} / ${FormatUtils.formatSize(state.totalBytes)} (${(state.progress * 100).toInt()}%)"
                                } else {
                                    FormatUtils.formatSize(state.downloadedBytes)
                                }
                                Text(
                                    text = progressText,
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = lxColors.accent
                                )
                            }
                        }
                    }

                    is AppUpdateState.Verifying -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = lxColors.accent
                            )
                            Text(
                                text = "正在校验安装包完整性…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = lxColors.textSecondary
                            )
                        }
                    }

                    is AppUpdateState.ReadyToInstall -> {
                        Text(
                            text = "安装包已就绪，点击「立即安装」即可直接覆盖升级。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = lxColors.success
                        )
                    }

                    is AppUpdateState.Error -> {
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = lxColors.danger
                        )
                    }

                    else -> Unit
                }
            }
        },
        confirmButton = {
            when (state) {
                is AppUpdateState.Available -> {
                    Button(
                        onClick = { onStartDownload(info) },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = lxColors.accent,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Text("立即更新")
                    }
                }

                is AppUpdateState.ReadyToInstall -> {
                    Button(
                        onClick = { onInstallApk(state.apkFile) },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = lxColors.accent,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Text("立即安装")
                    }
                }

                is AppUpdateState.Error -> {
                    Button(
                        onClick = { onStartDownload(info) },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = lxColors.accent,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Text("重试下载")
                    }
                }

                is AppUpdateState.Downloading -> {
                    TextButton(onClick = onCancelDownload) {
                        Text("取消下载", color = lxColors.danger)
                    }
                }

                else -> Unit
            }
        },
        dismissButton = {
            when (state) {
                is AppUpdateState.Available -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { onOpenInBrowser(info.htmlUrl) }) {
                            Text("浏览器下载", color = lxColors.textSecondary)
                        }
                        Spacer(Modifier.width(2.dp))
                        TextButton(onClick = onDismiss) {
                            Text("稍后再说", color = lxColors.textSecondary)
                        }
                    }
                }

                is AppUpdateState.ReadyToInstall -> {
                    TextButton(onClick = onDismiss) {
                        Text("稍后安装", color = lxColors.textSecondary)
                    }
                }

                is AppUpdateState.Error -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { onOpenInBrowser(info.htmlUrl) }) {
                            Text("浏览器下载", color = lxColors.accent)
                        }
                        TextButton(onClick = onDismiss) {
                            Text("关闭", color = lxColors.textSecondary)
                        }
                    }
                }

                else -> Unit
            }
        }
    )
}
