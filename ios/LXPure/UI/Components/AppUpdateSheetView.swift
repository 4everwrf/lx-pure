import SwiftUI
import UIKit

struct ActivityShareSheet: UIViewControllerRepresentable {
    let activityItems: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: activityItems, applicationActivities: nil)
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}

struct AppUpdateSheetView: View {
    let state: AppUpdateState
    let currentVersion: String
    let isTrollStoreAvailable: Bool
    let onStartDownload: (AppUpdateInfo) -> Void
    let onCancelDownload: () -> Void
    let onInstallViaTrollStore: (AppUpdateInfo) -> Void
    let onOpenShareInstaller: (URL) -> Void
    let onOpenInBrowser: (String) -> Void
    let onDismiss: () -> Void

    @Environment(\.lxPalette) private var lx

    private var info: AppUpdateInfo? {
        switch state {
        case .available(let info): return info
        case .downloading(let info, _, _, _, _): return info
        case .verifying(let info): return info
        case .readyToInstall(let info, _): return info
        case .error(let info, _): return info
        default: return nil
        }
    }

    private var canDismiss: Bool {
        switch state {
        case .downloading, .verifying:
            return false
        default:
            return true
        }
    }

    var body: some View {
        if let info {
            let noteItems = AppUpdateManager.parseReleaseNoteItems(info.releaseNotes)
            VStack(spacing: 0) {
                // Soft ambient gradient header
                HStack(alignment: .center, spacing: 14) {
                    ZStack {
                        RoundedRectangle(cornerRadius: 16, style: .continuous)
                            .fill(
                                LinearGradient(
                                    colors: [lx.accent, Color(hex: 0x6366F1)],
                                    startPoint: .topLeading,
                                    endPoint: .bottomTrailing
                                )
                            )
                            .frame(width: 50, height: 50)
                        Image(systemName: "sparkles")
                            .font(.system(size: 22, weight: .semibold))
                            .foregroundColor(.white)
                    }

                    VStack(alignment: .leading, spacing: 6) {
                        Text("发现新版本")
                            .font(.system(size: 19, weight: .bold))
                            .foregroundColor(lx.textPrimary)

                        HStack(spacing: 6) {
                            Text("v\(AppUpdateManager.normalizeVersion(currentVersion))")
                                .font(.system(size: 11, weight: .medium))
                                .foregroundColor(lx.textSecondary)
                                .padding(.horizontal, 7)
                                .padding(.vertical, 2)
                                .background(lx.hairline)
                                .clipShape(Capsule())

                            Image(systemName: "arrow.right")
                                .font(.system(size: 10, weight: .bold))
                                .foregroundColor(lx.accent)

                            Text("v\(info.versionName)")
                                .font(.system(size: 11, weight: .bold))
                                .foregroundColor(lx.accent)
                                .padding(.horizontal, 8)
                                .padding(.vertical, 2)
                                .background(lx.accentSoft)
                                .clipShape(Capsule())

                            if info.assetSizeBytes > 0 {
                                Text(FormatUtils.formatSize(info.assetSizeBytes))
                                    .font(.system(size: 11))
                                    .foregroundColor(lx.textTertiary)
                            }
                        }
                    }

                    Spacer(minLength: 4)

                    if canDismiss {
                        Button(action: onDismiss) {
                            Image(systemName: "xmark")
                                .font(.system(size: 13, weight: .semibold))
                                .foregroundColor(lx.textTertiary)
                                .frame(width: 30, height: 30)
                                .background(lx.surfaceElevated)
                                .clipShape(Circle())
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal, 22)
                .padding(.top, 22)
                .padding(.bottom, 16)
                .background(
                    LinearGradient(
                        colors: [lx.accent.opacity(lx.isDark ? 0.18 : 0.12), .clear],
                        startPoint: .top,
                        endPoint: .bottom
                    )
                )

                VStack(spacing: 14) {
                    // Structured bullet changelog card
                    ScrollView {
                        VStack(alignment: .leading, spacing: 8) {
                            Text("本版更新")
                                .font(.system(size: 12, weight: .semibold))
                                .foregroundColor(lx.textTertiary)

                            ForEach(Array(noteItems.enumerated()), id: \.offset) { _, item in
                                HStack(alignment: .top, spacing: 8) {
                                    Circle()
                                        .fill(lx.accent)
                                        .frame(width: 5, height: 5)
                                        .padding(.top, 6)
                                    Text(item)
                                        .font(.system(size: 13))
                                        .foregroundColor(lx.textPrimary.opacity(0.88))
                                        .fixedSize(horizontal: false, vertical: true)
                                }
                            }
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(14)
                    }
                    .frame(maxHeight: 176)
                    .background(lx.background.opacity(lx.isDark ? 0.55 : 0.85))
                    .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                    .overlay(
                        RoundedRectangle(cornerRadius: 16, style: .continuous)
                            .stroke(lx.hairline, lineWidth: 0.7)
                    )

                    stateDetailView(info: info)

                    actionButtonsView(info: info)
                }
                .padding(.horizontal, 22)
                .padding(.bottom, 22)
            }
            .background(lx.surface)
        }
    }

    @ViewBuilder
    private func stateDetailView(info: AppUpdateInfo) -> some View {
        switch state {
        case .downloading(_, let downloaded, let total, let progress, let routeLabel):
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    Text("正在下载 · \(routeLabel)")
                        .font(.system(size: 12, weight: .medium))
                        .foregroundColor(lx.textPrimary)
                    Spacer()
                    let text = total > 0
                        ? "\(FormatUtils.formatSize(downloaded)) / \(FormatUtils.formatSize(total)) (\(Int(progress * 100))%)"
                        : FormatUtils.formatSize(downloaded)
                    Text(text)
                        .font(.system(size: 12, weight: .bold).monospacedDigit())
                        .foregroundColor(lx.accent)
                }
                if total > 0 && downloaded > 0 {
                    ProgressView(value: progress, total: 1.0)
                        .tint(lx.accent)
                } else {
                    ProgressView()
                        .progressViewStyle(.linear)
                        .tint(lx.accent)
                }
                if downloaded == 0 {
                    Text("连接中，若直连超时将自动切换国内镜像通道…")
                        .font(.system(size: 11))
                        .foregroundColor(lx.textSecondary)
                }
            }
            .padding(12)
            .background(lx.accentSoft.opacity(0.45))
            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .stroke(lx.accent.opacity(0.25), lineWidth: 0.7)
            )

        case .verifying:
            HStack(spacing: 10) {
                ProgressView().tint(lx.accent)
                Text("正在校验安装包 SHA-256 完整性…")
                    .font(.system(size: 13))
                    .foregroundColor(lx.textPrimary)
                Spacer()
            }
            .padding(12)
            .background(lx.accentSoft.opacity(0.4))
            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))

        case .readyToInstall:
            HStack(alignment: .top, spacing: 10) {
                Image(systemName: "checkmark.circle.fill")
                    .font(.system(size: 17))
                    .foregroundColor(lx.success)
                Text("校验通过，安装包已保存至「文件 → 轻雪音乐 → Updates」，覆盖升级不会清除歌单与自定义音源。")
                    .font(.system(size: 12))
                    .foregroundColor(lx.textPrimary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(12)
            .background(lx.success.opacity(0.12))
            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .stroke(lx.success.opacity(0.28), lineWidth: 0.7)
            )

        case .error(_, let message):
            HStack(alignment: .top, spacing: 10) {
                Image(systemName: "wifi.exclamationmark")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundColor(lx.danger)
                    .padding(.top, 1)
                Text(message)
                    .font(.system(size: 12))
                    .foregroundColor(lx.danger)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(12)
            .background(lx.danger.opacity(0.10))
            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .stroke(lx.danger.opacity(0.28), lineWidth: 0.7)
            )

        default:
            EmptyView()
        }
    }

    @ViewBuilder
    private func actionButtonsView(info: AppUpdateInfo) -> some View {
        switch state {
        case .available:
            VStack(spacing: 10) {
                Button {
                    onStartDownload(info)
                } label: {
                    HStack(spacing: 6) {
                        Image(systemName: "arrow.down.circle.fill")
                        Text("立即更新")
                    }
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundColor(lx.onAccent)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 13)
                    .background(lx.accent)
                    .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                }
                .buttonStyle(.plain)

                HStack(spacing: 12) {
                    Button {
                        onOpenInBrowser(info.htmlUrl)
                    } label: {
                        HStack(spacing: 4) {
                            Image(systemName: "safari")
                                .font(.system(size: 12))
                            Text("浏览器下载")
                        }
                        .font(.system(size: 13, weight: .medium))
                        .foregroundColor(lx.textSecondary)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 6)
                    }
                    .buttonStyle(.plain)

                    Rectangle()
                        .fill(lx.hairline)
                        .frame(width: 1, height: 14)

                    Button("稍后再说", action: onDismiss)
                        .font(.system(size: 13, weight: .medium))
                        .foregroundColor(lx.textSecondary)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 6)
                        .buttonStyle(.plain)
                }
            }

        case .downloading:
            Button {
                onCancelDownload()
            } label: {
                Text("取消下载")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundColor(lx.danger)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
                    .background(lx.danger.opacity(0.14))
                    .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            }
            .buttonStyle(.plain)

        case .readyToInstall(_, let localURL):
            VStack(spacing: 10) {
                if isTrollStoreAvailable {
                    Button {
                        onInstallViaTrollStore(info)
                    } label: {
                        Text("通过巨魔商店直接安装")
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundColor(lx.onAccent)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 13)
                            .background(lx.accent)
                            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                    }
                    .buttonStyle(.plain)
                }

                Button {
                    onOpenShareInstaller(localURL)
                } label: {
                    Text(isTrollStoreAvailable ? "打开 / 分享 IPA 安装包" : "立即安装（打开 IPA）")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundColor(isTrollStoreAvailable ? lx.accent : lx.onAccent)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 13)
                        .background(isTrollStoreAvailable ? lx.accentSoft : lx.accent)
                        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                }
                .buttonStyle(.plain)

                Button("稍后安装", action: onDismiss)
                    .font(.system(size: 13, weight: .medium))
                    .foregroundColor(lx.textSecondary)
                    .padding(.vertical, 6)
                    .buttonStyle(.plain)
            }

        case .error:
            VStack(spacing: 10) {
                Button {
                    onStartDownload(info)
                } label: {
                    Text("重试下载")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundColor(lx.onAccent)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 13)
                        .background(lx.accent)
                        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                }
                .buttonStyle(.plain)

                HStack(spacing: 12) {
                    Button {
                        onOpenInBrowser(info.htmlUrl)
                    } label: {
                        HStack(spacing: 4) {
                            Image(systemName: "safari")
                                .font(.system(size: 12))
                            Text("浏览器下载")
                        }
                        .font(.system(size: 13, weight: .medium))
                        .foregroundColor(lx.accent)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 6)
                    }
                    .buttonStyle(.plain)

                    Rectangle()
                        .fill(lx.hairline)
                        .frame(width: 1, height: 14)

                    Button("关闭", action: onDismiss)
                        .font(.system(size: 13, weight: .medium))
                        .foregroundColor(lx.textSecondary)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 6)
                        .buttonStyle(.plain)
                }
            }

        default:
            EmptyView()
        }
    }
}
