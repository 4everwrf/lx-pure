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
        case .downloading(let info, _, _, _): return info
        case .verifying(let info): return info
        case .readyToInstall(let info, _): return info
        case .error(let info, _): return info
        default: return nil
        }
    }

    var body: some View {
        if let info {
            VStack(spacing: 16) {
                VStack(spacing: 8) {
                    ZStack {
                        RoundedRectangle(cornerRadius: 16, style: .continuous)
                            .fill(lx.accentSoft)
                            .frame(width: 52, height: 52)
                        Image(systemName: "arrow.down.app.fill")
                            .font(.system(size: 24, weight: .semibold))
                            .foregroundColor(lx.accent)
                    }

                    Text("发现新版本 v\(info.versionName)")
                        .font(.system(size: 19, weight: .bold))
                        .foregroundColor(lx.textPrimary)

                    let sizeLabel = info.assetSizeBytes > 0 ? " · \(FormatUtils.formatSize(info.assetSizeBytes))" : ""
                    Text("v\(AppUpdateManager.normalizeVersion(currentVersion)) → v\(info.versionName)\(sizeLabel)")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundColor(lx.accent)
                }
                .padding(.top, 8)

                ScrollView {
                    Text(info.releaseNotes)
                        .font(.system(size: 13))
                        .foregroundColor(lx.textSecondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(14)
                }
                .frame(maxHeight: 190)
                .background(lx.background.opacity(0.75))
                .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                        .stroke(lx.hairline, lineWidth: 0.7)
                )

                stateDetailView(info: info)

                actionButtonsView(info: info)
            }
            .padding(20)
            .background(lx.surface)
        }
    }

    @ViewBuilder
    private func stateDetailView(info: AppUpdateInfo) -> some View {
        switch state {
        case .downloading(_, let downloaded, let total, let progress):
            VStack(spacing: 6) {
                ProgressView(value: total > 0 ? progress : 0, total: 1.0)
                    .tint(lx.accent)
                HStack {
                    Text("正在下载安装包…")
                        .font(.system(size: 12))
                        .foregroundColor(lx.textSecondary)
                    Spacer()
                    let text = total > 0
                        ? "\(FormatUtils.formatSize(downloaded)) / \(FormatUtils.formatSize(total)) (\(Int(progress * 100))%)"
                        : FormatUtils.formatSize(downloaded)
                    Text(text)
                        .font(.system(size: 12, weight: .semibold).monospacedDigit())
                        .foregroundColor(lx.accent)
                }
            }

        case .verifying:
            HStack(spacing: 10) {
                ProgressView().tint(lx.accent)
                Text("正在校验安装包完整性…")
                    .font(.system(size: 13))
                    .foregroundColor(lx.textSecondary)
                Spacer()
            }

        case .readyToInstall:
            Text("安装包已下载至「文件 → 轻雪音乐 → Updates」，点击下方按钮选择巨魔 / AltStore / SideStore 或自签工具直接覆盖安装。")
                .font(.system(size: 12))
                .foregroundColor(lx.success)
                .frame(maxWidth: .infinity, alignment: .leading)

        case .error(_, let message):
            Text(message)
                .font(.system(size: 12))
                .foregroundColor(lx.danger)
                .frame(maxWidth: .infinity, alignment: .leading)

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
                    Text("立即更新")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundColor(lx.onAccent)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 13)
                        .background(lx.accent)
                        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                }
                .buttonStyle(.plain)

                HStack(spacing: 14) {
                    Button("浏览器下载") {
                        onOpenInBrowser(info.htmlUrl)
                    }
                    .font(.system(size: 13, weight: .medium))
                    .foregroundColor(lx.textSecondary)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 8)

                    Button("稍后再说") {
                        onDismiss()
                    }
                    .font(.system(size: 13, weight: .medium))
                    .foregroundColor(lx.textSecondary)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 8)
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
                    .background(lx.danger.opacity(0.12))
                    .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
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
                            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
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
                        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                }
                .buttonStyle(.plain)

                Button("关闭") {
                    onDismiss()
                }
                .font(.system(size: 13, weight: .medium))
                .foregroundColor(lx.textSecondary)
                .padding(.vertical, 6)
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
                        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                }
                .buttonStyle(.plain)

                HStack(spacing: 14) {
                    Button("浏览器下载") {
                        onOpenInBrowser(info.htmlUrl)
                    }
                    .font(.system(size: 13, weight: .medium))
                    .foregroundColor(lx.accent)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 8)

                    Button("关闭") {
                        onDismiss()
                    }
                    .font(.system(size: 13, weight: .medium))
                    .foregroundColor(lx.textSecondary)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 8)
                }
            }

        default:
            EmptyView()
        }
    }
}
