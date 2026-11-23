package com.lxpure.music.ui.settings

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lxpure.music.BuildConfig
import com.lxpure.music.core.musicsdk.FormatUtils
import com.lxpure.music.domain.model.AppSettings
import com.lxpure.music.domain.model.AudioQuality
import com.lxpure.music.domain.model.ThemeMode
import com.lxpure.music.domain.model.UserApiRuntimeStatus
import com.lxpure.music.domain.model.UserApiSource
import com.lxpure.music.ui.components.GlassCard
import com.lxpure.music.ui.theme.LocalLxColors

@Composable
fun SettingsScreen(
    settings: AppSettings,
    sources: List<UserApiSource>,
    downloadedBytes: Long,
    cacheBytes: Long,
    isCheckingUpdate: Boolean = false,
    bottomPadding: Dp,
    onBack: () -> Unit,
    onOpenSourceManager: () -> Unit,
    onSelectQuality: (AudioQuality) -> Unit,
    onToggleAutoFallback: (Boolean) -> Unit,
    onToggleCellularDataSaver: (Boolean) -> Unit,
    onSelectTheme: (ThemeMode) -> Unit,
    onToggleFluidAmbient: (Boolean) -> Unit,
    onToggleTranslation: (Boolean) -> Unit,
    onScanLocalMusic: () -> Unit,
    onClearCache: () -> Unit,
    onCheckUpdate: () -> Unit = {}
) {
    val lxColors = LocalLxColors.current
    val readyCount = sources.count { it.enabled && it.status == UserApiRuntimeStatus.READY }

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
            Text("设置", style = MaterialTheme.typography.titleLarge, color = lxColors.textPrimary)
        }

        LazyColumn(
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 6.dp, bottom = bottomPadding),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            // 1. Featured Custom Source Entry
            item {
                GlassCard(onClick = onOpenSourceManager, cornerRadius = 20.dp) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(lxColors.accentSoft),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.Bolt, contentDescription = null, tint = lxColors.accent)
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("自定义音源管理", style = MaterialTheme.typography.titleMedium, color = lxColors.textPrimary)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = if (readyCount > 0) "已就绪 $readyCount 个音源 · 共导入 ${sources.size} 个" else "点击导入洛雪 .js 音源脚本",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (readyCount > 0) lxColors.success else lxColors.textSecondary
                            )
                        }
                        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = lxColors.textTertiary)
                    }
                }
            }

            // 2. Audio & Playback
            item {
                SettingsGroup(title = "播放与音质") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("偏好音质（自动向下兼容）", style = MaterialTheme.typography.titleMedium, color = lxColors.textPrimary)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            AudioQuality.entries.forEach { q ->
                                val active = settings.preferredQuality == q
                                Surface(
                                    onClick = { onSelectQuality(q) },
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (active) lxColors.accent else lxColors.background,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Box(
                                        modifier = Modifier.padding(vertical = 9.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = q.badge,
                                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                            color = if (active) MaterialTheme.colorScheme.onPrimary else lxColors.textSecondary
                                        )
                                    }
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = lxColors.hairline)

                    SettingsSwitchRow(
                        title = "无感自动换源兜底",
                        subtitle = "当前平台或音源解析失败时，后台静默匹配其他可用平台",
                        checked = settings.autoFallbackSource,
                        onCheckedChange = onToggleAutoFallback
                    )

                    HorizontalDivider(color = lxColors.hairline)

                    SettingsSwitchRow(
                        title = "移动网络智能省流",
                        subtitle = "使用 4G/5G 流量时自动切换 128K，Wi-Fi 下畅享偏好无损",
                        checked = settings.wifiOnlyHighQuality,
                        onCheckedChange = onToggleCellularDataSaver
                    )
                }
            }

            // 3. Appearance & Fluid Ambient
            item {
                SettingsGroup(title = "外观与沉浸视觉") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("色彩主题", style = MaterialTheme.typography.titleMedium, color = lxColors.textPrimary)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ThemeMode.entries.forEach { mode ->
                                val active = settings.themeMode == mode
                                Surface(
                                    onClick = { onSelectTheme(mode) },
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (active) lxColors.accent else lxColors.background,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Box(
                                        modifier = Modifier.padding(vertical = 9.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = mode.label,
                                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                            color = if (active) MaterialTheme.colorScheme.onPrimary else lxColors.textSecondary
                                        )
                                    }
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = lxColors.hairline)

                    SettingsSwitchRow(
                        title = "流体光影氛围背景",
                        subtitle = "提取专辑封面色彩生成动态渐变光影",
                        checked = settings.fluidAmbientBackground,
                        onCheckedChange = onToggleFluidAmbient
                    )

                    HorizontalDivider(color = lxColors.hairline)

                    SettingsSwitchRow(
                        title = "默认显示双语翻译歌词",
                        subtitle = "外语歌曲有翻译时在播放页同步展示",
                        checked = settings.showTranslationLyrics,
                        onCheckedChange = onToggleTranslation
                    )
                }
            }

            // 4. Local & Storage
            item {
                SettingsGroup(title = "本地与离线存储") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onScanLocalMusic)
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("扫描设备本地音乐", style = MaterialTheme.typography.titleMedium, color = lxColors.textPrimary)
                            Text("检索手机本地 FLAC / MP3 / M4A 等音频文件", style = MaterialTheme.typography.bodyMedium, color = lxColors.textSecondary)
                        }
                        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = lxColors.textTertiary)
                    }
                    HorizontalDivider(color = lxColors.hairline)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("离线下载占用", style = MaterialTheme.typography.titleMedium, color = lxColors.textPrimary)
                            Text("可在音乐库 → 离线下载中单独清理曲目", style = MaterialTheme.typography.bodyMedium, color = lxColors.textSecondary)
                        }
                        Text(
                            text = if (downloadedBytes > 0L) FormatUtils.formatSize(downloadedBytes) else "0 MB",
                            style = MaterialTheme.typography.labelMedium,
                            color = lxColors.accent
                        )
                    }
                    HorizontalDivider(color = lxColors.hairline)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = cacheBytes > 0L, onClick = onClearCache)
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("边听边存与封面缓存", style = MaterialTheme.typography.titleMedium, color = lxColors.textPrimary)
                            Text("上限 300MB 自动循环淘汰，重听不耗流量 · 点击清理", style = MaterialTheme.typography.bodyMedium, color = lxColors.textSecondary)
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = if (cacheBytes > 0L) "${FormatUtils.formatSize(cacheBytes)} · 清理" else "0 MB",
                            style = MaterialTheme.typography.labelMedium,
                            color = lxColors.accent
                        )
                    }
                }
            }

            // 5. About
            item {
                SettingsGroup(title = "关于轻雪音乐") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !isCheckingUpdate, onClick = onCheckUpdate)
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("检查新版本", style = MaterialTheme.typography.titleMedium, color = lxColors.textPrimary)
                            Text(
                                text = if (isCheckingUpdate) "正在连接 GitHub 查询最新版本…" else "当前版本 v${BuildConfig.VERSION_NAME}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = lxColors.textSecondary
                            )
                        }
                        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = lxColors.textTertiary)
                    }
                    HorizontalDivider(color = lxColors.hairline)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "轻雪音乐 (LX Pure) v${BuildConfig.VERSION_NAME}",
                            style = MaterialTheme.typography.titleMedium,
                            color = lxColors.textPrimary
                        )
                        Text(
                            text = "独立非官方开源项目，基于 Apache-2.0 协议开源。采用 Kotlin + Jetpack Compose + Media3 + QuickJS 原生构建，兼容 lyswhut/lx-music-mobile 自定义源协议（含衍生自该项目的预加载桥接脚本）。本应用仅供学习与技术研究，非商业用途，不内置也不提供任何音源脚本或版权音频内容。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = lxColors.textSecondary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsGroup(
    title: String,
    content: @Composable () -> Unit
) {
    val lxColors = LocalLxColors.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = lxColors.textTertiary,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        GlassCard(cornerRadius = 18.dp) {
            Column(modifier = Modifier.fillMaxWidth()) {
                content()
            }
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val lxColors = LocalLxColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = lxColors.textPrimary)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = lxColors.textSecondary)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = lxColors.accent
            )
        )
    }
}
