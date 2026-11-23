import SwiftUI

struct SettingsView: View {
    let settings: AppSettings
    let sources: [UserApiSource]
    let downloadedBytes: Int64
    let cacheBytes: Int64
    var isCheckingUpdate: Bool = false
    let bottomInset: CGFloat
    let onOpenSourceManager: () -> Void
    let onSelectQuality: (AudioQuality) -> Void
    let onToggleAutoFallback: (Bool) -> Void
    let onToggleCellularDataSaver: (Bool) -> Void
    let onSelectTheme: (ThemeMode) -> Void
    let onToggleFluidAmbient: (Bool) -> Void
    let onToggleTranslation: (Bool) -> Void
    let onScanLocalMusic: () -> Void
    let onClearCache: () -> Void
    var onCheckUpdate: () -> Void = {}

    @Environment(\.lxPalette) private var lx

    private var readyCount: Int {
        sources.filter { $0.enabled && $0.status == .ready }.count
    }

    private var appVersion: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.1.1"
    }

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 18) {
                // 1. Featured Custom Source Entry
                Button(action: onOpenSourceManager) {
                    GlassCard(cornerRadius: 20) {
                        HStack(spacing: 14) {
                            ZStack {
                                Circle()
                                    .fill(lx.accentSoft)
                                    .frame(width: 42, height: 42)
                                Image(systemName: "bolt.fill")
                                    .font(.system(size: 18, weight: .semibold))
                                    .foregroundColor(lx.accent)
                            }
                            VStack(alignment: .leading, spacing: 3) {
                                Text("自定义音源管理")
                                    .font(.system(size: 15, weight: .semibold))
                                    .foregroundColor(lx.textPrimary)
                                Text(readyCount > 0 ? "已就绪 \(readyCount) 个音源 · 共导入 \(sources.count) 个" : "点击导入洛雪 .js 音源脚本")
                                    .font(.system(size: 12))
                                    .foregroundColor(readyCount > 0 ? lx.success : lx.textSecondary)
                            }
                            Spacer()
                            Image(systemName: "chevron.right")
                                .font(.system(size: 13, weight: .semibold))
                                .foregroundColor(lx.textTertiary)
                        }
                        .padding(16)
                    }
                }
                .buttonStyle(.plain)

                // 2. Audio & Playback
                settingsGroup(title: "播放与音质") {
                    VStack(alignment: .leading, spacing: 10) {
                        Text("偏好音质（自动向下兼容）")
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundColor(lx.textPrimary)
                        HStack(spacing: 8) {
                            ForEach(AudioQuality.allCases) { q in
                                let active = settings.preferredQuality == q
                                Button {
                                    onSelectQuality(q)
                                } label: {
                                    Text(q.badge)
                                        .font(.system(size: 12, weight: .bold))
                                        .foregroundColor(active ? lx.onAccent : lx.textSecondary)
                                        .frame(maxWidth: .infinity)
                                        .padding(.vertical, 9)
                                        .background(active ? lx.accent : lx.background)
                                        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                                }
                                .buttonStyle(.plain)
                            }
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 14)

                    Divider().overlay(lx.hairline)

                    switchRow(
                        title: "无感自动换源兜底",
                        subtitle: "当前平台或音源解析失败时，后台静默匹配其他可用平台",
                        isOn: Binding(get: { settings.autoFallbackSource }, set: onToggleAutoFallback)
                    )

                    Divider().overlay(lx.hairline)

                    switchRow(
                        title: "移动网络智能省流",
                        subtitle: "使用 4G/5G 流量时自动切换 128K，Wi-Fi 下畅享偏好无损",
                        isOn: Binding(get: { settings.wifiOnlyHighQuality }, set: onToggleCellularDataSaver)
                    )
                }

                // 3. Appearance & Fluid Ambient
                settingsGroup(title: "外观与沉浸视觉") {
                    VStack(alignment: .leading, spacing: 10) {
                        Text("色彩主题")
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundColor(lx.textPrimary)
                        HStack(spacing: 8) {
                            ForEach(ThemeMode.allCases) { mode in
                                let active = settings.themeMode == mode
                                Button {
                                    onSelectTheme(mode)
                                } label: {
                                    Text(mode.label)
                                        .font(.system(size: 12, weight: .semibold))
                                        .foregroundColor(active ? lx.onAccent : lx.textSecondary)
                                        .frame(maxWidth: .infinity)
                                        .padding(.vertical, 9)
                                        .background(active ? lx.accent : lx.background)
                                        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                                }
                                .buttonStyle(.plain)
                            }
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 14)

                    Divider().overlay(lx.hairline)

                    switchRow(
                        title: "流体光影氛围背景",
                        subtitle: "提取专辑封面色彩生成动态渐变光影",
                        isOn: Binding(get: { settings.fluidAmbientBackground }, set: onToggleFluidAmbient)
                    )

                    Divider().overlay(lx.hairline)

                    switchRow(
                        title: "默认显示双语翻译歌词",
                        subtitle: "外语歌曲有翻译时在播放页同步展示",
                        isOn: Binding(get: { settings.showTranslationLyrics }, set: onToggleTranslation)
                    )
                }

                // 4. Local & Storage
                settingsGroup(title: "本地与离线存储") {
                    Button(action: onScanLocalMusic) {
                        HStack {
                            VStack(alignment: .leading, spacing: 3) {
                                Text("扫描本地音乐目录")
                                    .font(.system(size: 15, weight: .semibold))
                                    .foregroundColor(lx.textPrimary)
                                Text("检索“轻雪音乐 / LocalMusic”中的 FLAC / MP3 / M4A")
                                    .font(.system(size: 12))
                                    .foregroundColor(lx.textSecondary)
                            }
                            Spacer()
                            Image(systemName: "chevron.right")
                                .font(.system(size: 13))
                                .foregroundColor(lx.textTertiary)
                        }
                        .padding(.horizontal, 16)
                        .padding(.vertical, 14)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)

                    Divider().overlay(lx.hairline)

                    HStack {
                        VStack(alignment: .leading, spacing: 3) {
                            Text("离线下载占用")
                                .font(.system(size: 15, weight: .semibold))
                                .foregroundColor(lx.textPrimary)
                            Text("可在音乐库 → 离线下载中单独清理曲目")
                                .font(.system(size: 12))
                                .foregroundColor(lx.textSecondary)
                        }
                        Spacer()
                        Text(downloadedBytes > 0 ? FormatUtils.formatSize(downloadedBytes) : "0 MB")
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundColor(lx.accent)
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 14)

                    Divider().overlay(lx.hairline)

                    Button {
                        if cacheBytes > 0 { onClearCache() }
                    } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 3) {
                                Text("边听边存与封面缓存")
                                    .font(.system(size: 15, weight: .semibold))
                                    .foregroundColor(lx.textPrimary)
                                Text("上限 300MB 自动循环淘汰，重听不耗流量 · 点击清理")
                                    .font(.system(size: 12))
                                    .foregroundColor(lx.textSecondary)
                            }
                            Spacer(minLength: 8)
                            Text(cacheBytes > 0 ? "\(FormatUtils.formatSize(cacheBytes)) · 清理" : "0 MB")
                                .font(.system(size: 12, weight: .semibold))
                                .foregroundColor(lx.accent)
                        }
                        .padding(.horizontal, 16)
                        .padding(.vertical, 14)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .disabled(cacheBytes <= 0)
                }

                // 5. About
                settingsGroup(title: "关于轻雪音乐") {
                    Button {
                        if !isCheckingUpdate { onCheckUpdate() }
                    } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 3) {
                                Text("检查新版本")
                                    .font(.system(size: 15, weight: .semibold))
                                    .foregroundColor(lx.textPrimary)
                                Text(isCheckingUpdate ? "正在连接 GitHub 查询最新版本…" : "当前版本 v\(appVersion)")
                                    .font(.system(size: 12))
                                    .foregroundColor(lx.textSecondary)
                            }
                            Spacer()
                            if isCheckingUpdate {
                                ProgressView().tint(lx.accent).scaleEffect(0.8)
                            } else {
                                Image(systemName: "chevron.right")
                                    .font(.system(size: 13))
                                    .foregroundColor(lx.textTertiary)
                            }
                        }
                        .padding(.horizontal, 16)
                        .padding(.vertical, 14)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .disabled(isCheckingUpdate)

                    Divider().overlay(lx.hairline)

                    VStack(alignment: .leading, spacing: 6) {
                        HStack {
                            Text("轻雪音乐 · LX Pure (iOS 纯净版)")
                                .font(.system(size: 15, weight: .semibold))
                                .foregroundColor(lx.textPrimary)
                            Spacer()
                            SmallPillBadge(text: "v\(appVersion)", color: lx.accent, background: lx.accentSoft)
                        }
                        Text("基于 SwiftUI + AVFoundation + 原生 JavaScriptCore 构建。本软件为开源纯净客户端，不内置、不提供、不推荐任何第三方音源脚本或音频资源，遵循 Apache-2.0 协议。")
                            .font(.system(size: 12))
                            .foregroundColor(lx.textSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .padding(16)
                }
            }
            .padding(.horizontal, 18)
            .padding(.top, 10)
            .padding(.bottom, bottomInset)
        }
        .background(lx.background)
        .navigationTitle("设置")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func settingsGroup<Content: View>(title: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title)
                .font(.system(size: 12, weight: .semibold))
                .foregroundColor(lx.textTertiary)
                .padding(.horizontal, 4)
            GlassCard(cornerRadius: 18) {
                VStack(spacing: 0) {
                    content()
                }
            }
        }
    }

    private func switchRow(title: String, subtitle: String, isOn: Binding<Bool>) -> some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundColor(lx.textPrimary)
                Text(subtitle)
                    .font(.system(size: 12))
                    .foregroundColor(lx.textSecondary)
            }
            Spacer()
            Toggle("", isOn: isOn)
                .labelsHidden()
                .tint(lx.accent)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
    }
}
