import SwiftUI

struct DownloadsDetailView: View {
    let downloads: [DownloadRecord]
    let currentTrackId: String?
    let isPlaying: Bool
    let bottomInset: CGFloat
    let onPlayTracks: ([Track], Int) -> Void
    let onDeleteDownload: (DownloadRecord) -> Void
    let onRetryDownload: (Track) -> Void

    @Environment(\.lxPalette) private var lx

    private var completedTracks: [Track] {
        downloads.filter { $0.status == .completed }.map(\.track)
    }

    var body: some View {
        ScrollView {
            LazyVStack(spacing: 10) {
                if !completedTracks.isEmpty {
                    HStack {
                        Button {
                            onPlayTracks(completedTracks, 0)
                        } label: {
                            HStack(spacing: 6) {
                                Image(systemName: "play.fill")
                                Text("播放全部离线 (\(completedTracks.count))")
                            }
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundColor(lx.onAccent)
                            .padding(.horizontal, 16)
                            .padding(.vertical, 9)
                            .background(lx.accent)
                            .clipShape(Capsule())
                        }
                        Spacer()
                    }
                    .padding(.horizontal, 18)
                    .padding(.top, 10)
                }

                if downloads.isEmpty {
                    GlassCard(cornerRadius: 18) {
                        VStack(spacing: 8) {
                            Image(systemName: "arrow.down.circle")
                                .font(.system(size: 32))
                                .foregroundColor(lx.textTertiary)
                            Text("暂无离线下载")
                                .font(.system(size: 15, weight: .semibold))
                                .foregroundColor(lx.textPrimary)
                            Text("在播放页或歌曲菜单中点击“离线下载”即可将音频保存到本地，离线文件也可在 iOS“文件”App 中管理。")
                                .font(.system(size: 12))
                                .foregroundColor(lx.textSecondary)
                                .multilineTextAlignment(.center)
                        }
                        .frame(maxWidth: .infinity)
                        .padding(28)
                    }
                    .padding(.horizontal, 18)
                    .padding(.top, 20)
                } else {
                    ForEach(downloads) { record in
                        GlassCard(cornerRadius: 16) {
                            VStack(spacing: 8) {
                                HStack(spacing: 12) {
                                    AlbumArtworkView(url: record.track.coverUrl, size: 46, cornerRadius: 11)
                                    VStack(alignment: .leading, spacing: 4) {
                                        Text(record.track.title)
                                            .font(.system(size: 15, weight: .semibold))
                                            .foregroundColor(record.trackId == currentTrackId ? lx.accent : lx.textPrimary)
                                            .lineLimit(1)
                                        HStack(spacing: 6) {
                                            SmallPillBadge(
                                                text: record.quality.badge,
                                                color: lx.accent,
                                                background: lx.accentSoft
                                            )
                                            Text(statusText(for: record))
                                                .font(.system(size: 12))
                                                .foregroundColor(statusColor(for: record))
                                                .lineLimit(1)
                                        }
                                    }
                                    Spacer()

                                    if record.status == .failed {
                                        Button {
                                            onRetryDownload(record.track)
                                        } label: {
                                            Image(systemName: "arrow.clockwise")
                                                .foregroundColor(lx.accent)
                                                .frame(width: 32, height: 32)
                                        }
                                        .buttonStyle(.plain)
                                    }

                                    Button {
                                        onDeleteDownload(record)
                                    } label: {
                                        Image(systemName: "trash")
                                            .font(.system(size: 14))
                                            .foregroundColor(lx.textTertiary)
                                            .frame(width: 32, height: 32)
                                    }
                                    .buttonStyle(.plain)
                                }

                                if record.status == .downloading || record.status == .queued {
                                    ProgressView(value: record.progress)
                                        .tint(lx.accent)
                                }
                            }
                            .padding(12)
                        }
                        .padding(.horizontal, 16)
                        .contentShape(Rectangle())
                        .onTapGesture {
                            if record.status == .completed,
                               let idx = completedTracks.firstIndex(where: { $0.id == record.trackId }) {
                                onPlayTracks(completedTracks, idx)
                            }
                        }
                    }
                }
            }
            .padding(.bottom, bottomInset)
        }
        .background(lx.background)
        .navigationTitle("离线下载")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func statusText(for record: DownloadRecord) -> String {
        switch record.status {
        case .queued: return "排队中..."
        case .downloading: return "下载中 \(Int(record.progress * 100))%"
        case .completed:
            let size = FormatUtils.formatSize(record.totalBytes)
            return size.isEmpty ? "已离线保存" : "已离线 · \(size)"
        case .failed: return record.errorMessage ?? "下载失败"
        }
    }

    private func statusColor(for record: DownloadRecord) -> Color {
        switch record.status {
        case .completed: return lx.success
        case .failed: return lx.danger
        default: return lx.textSecondary
        }
    }
}
