import SwiftUI

struct OnlinePlaylistDetailView: View {
    let title: String
    let coverUrl: String?
    let loadKey: String
    let loadDetail: () async -> Result<PlaylistDetailResult, Error>
    let currentTrackId: String?
    let isPlaying: Bool
    let bottomInset: CGFloat
    let onPlayTracks: ([Track], Int) -> Void
    let onSaveToLibrary: (PlaylistDetailResult) -> Void
    let onTrackMore: (Track) -> Void

    @Environment(\.lxPalette) private var lx
    @State private var detail: PlaylistDetailResult?
    @State private var errorMsg: String?
    @State private var isLoading = true
    @State private var retryNonce = 0
    @State private var isSaved = false

    var body: some View {
        Group {
            if isLoading {
                VStack(spacing: 12) {
                    ProgressView().tint(lx.accent)
                    Text("正在加载歌单...")
                        .font(.system(size: 13))
                        .foregroundColor(lx.textSecondary)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if let errorMsg, detail == nil {
                VStack(spacing: 14) {
                    Image(systemName: "wifi.exclamationmark")
                        .font(.system(size: 36))
                        .foregroundColor(lx.textTertiary)
                    Text(errorMsg)
                        .font(.system(size: 14))
                        .foregroundColor(lx.textSecondary)
                        .multilineTextAlignment(.center)
                    Button("重试") {
                        retryNonce += 1
                    }
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundColor(lx.onAccent)
                    .padding(.horizontal, 20)
                    .padding(.vertical, 9)
                    .background(lx.accent)
                    .clipShape(Capsule())
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .padding(32)
            } else if let detail {
                let currentTrackIndex = currentTrackId.flatMap { id in
                    detail.tracks.firstIndex(where: { $0.id == id })
                }
                ScrollViewReader { proxy in
                    ZStack(alignment: .bottomTrailing) {
                        ScrollView {
                            LazyVStack(spacing: 4) {
                                Color.clear
                                    .frame(height: 0)
                                    .id("online_top")

                                // Header card
                                HStack(alignment: .top, spacing: 16) {
                                    AlbumArtworkView(
                                        url: detail.playlist.coverUrl.isEmpty ? coverUrl : detail.playlist.coverUrl,
                                        size: 112,
                                        cornerRadius: 18,
                                        isThumbnail: false
                                    )
                                    VStack(alignment: .leading, spacing: 6) {
                                        Text(detail.playlist.name)
                                            .font(.system(size: 18, weight: .bold))
                                            .foregroundColor(lx.textPrimary)
                                            .lineLimit(2)

                                        if !detail.playlist.author.isEmpty {
                                            Text(detail.playlist.author)
                                                .font(.system(size: 13))
                                                .foregroundColor(lx.textSecondary)
                                                .lineLimit(1)
                                        }

                                        HStack(spacing: 8) {
                                            SmallPillBadge(
                                                text: detail.playlist.source.displayName,
                                                color: lx.accent,
                                                background: lx.accentSoft
                                            )
                                            Text("\(detail.tracks.count) 首")
                                                .font(.system(size: 12))
                                                .foregroundColor(lx.textTertiary)
                                            if !detail.playlist.playCount.isEmpty {
                                                Text(detail.playlist.playCount)
                                                    .font(.system(size: 12))
                                                    .foregroundColor(lx.textTertiary)
                                            }
                                        }
                                    }
                                    Spacer(minLength: 0)
                                }
                                .padding(.horizontal, 18)
                                .padding(.top, 12)

                                if !detail.playlist.description.isEmpty {
                                    Text(detail.playlist.description)
                                        .font(.system(size: 12))
                                        .foregroundColor(lx.textSecondary)
                                        .lineLimit(3)
                                        .frame(maxWidth: .infinity, alignment: .leading)
                                        .padding(.horizontal, 18)
                                        .padding(.top, 8)
                                }

                                HStack(spacing: 12) {
                                    Button {
                                        onPlayTracks(detail.tracks, 0)
                                    } label: {
                                        HStack(spacing: 6) {
                                            Image(systemName: "play.fill")
                                            Text("播放全部")
                                        }
                                        .font(.system(size: 14, weight: .semibold))
                                        .foregroundColor(lx.onAccent)
                                        .frame(maxWidth: .infinity)
                                        .padding(.vertical, 11)
                                        .background(lx.accent)
                                        .clipShape(Capsule())
                                    }
                                    .disabled(detail.tracks.isEmpty)

                                    Button {
                                        guard !isSaved else { return }
                                        isSaved = true
                                        onSaveToLibrary(detail)
                                    } label: {
                                        HStack(spacing: 6) {
                                            Image(systemName: isSaved ? "checkmark.circle.fill" : "bookmark.fill")
                                            Text(isSaved ? "已收藏" : "收藏歌单")
                                        }
                                        .font(.system(size: 14, weight: .semibold))
                                        .foregroundColor(isSaved ? lx.accent : lx.textPrimary)
                                        .frame(maxWidth: .infinity)
                                        .padding(.vertical, 11)
                                        .background(isSaved ? lx.accentSoft : lx.surfaceElevated)
                                        .clipShape(Capsule())
                                        .overlay(Capsule().stroke(isSaved ? lx.accent.opacity(0.35) : lx.hairline, lineWidth: 0.8))
                                    }
                                    .disabled(detail.tracks.isEmpty || isSaved)
                                }
                                .padding(.horizontal, 18)
                                .padding(.vertical, 14)

                                ForEach(Array(detail.tracks.enumerated()), id: \.offset) { idx, track in
                                    TrackListRow(
                                        track: track,
                                        index: idx,
                                        isCurrent: track.id == currentTrackId,
                                        isPlaying: isPlaying,
                                        onTap: { onPlayTracks(detail.tracks, idx) },
                                        onMore: { onTrackMore(track) }
                                    )
                                    .id("online_track_\(idx)")
                                }

                                Color.clear
                                    .frame(height: bottomInset + (detail.tracks.count >= 15 ? 148 : 0))
                                    .id("online_bottom")
                            }
                        }

                        TrackListFastScrollerOverlay(
                            trackCount: detail.tracks.count,
                            currentTrackIndex: currentTrackIndex,
                            bottomInset: bottomInset,
                            onJumpTop: {
                                proxy.scrollTo("online_top", anchor: .top)
                            },
                            onJumpBottom: {
                                proxy.scrollTo("online_bottom", anchor: .bottom)
                            },
                            onJumpToIndex: { idx in
                                if idx >= detail.tracks.count - 1 {
                                    proxy.scrollTo("online_bottom", anchor: .bottom)
                                } else {
                                    proxy.scrollTo("online_track_\(idx)", anchor: .center)
                                }
                            }
                        )
                    }
                }
            }
        }
        .background(lx.background)
        .navigationTitle(detail?.playlist.name ?? title)
        .navigationBarTitleDisplayMode(.inline)
        .task(id: "\(loadKey)_\(retryNonce)") {
            let activeKey = "\(loadKey)_\(retryNonce)"
            isLoading = true
            detail = nil
            errorMsg = nil
            isSaved = false
            let res = await loadDetail()
            guard !Task.isCancelled, "\(loadKey)_\(retryNonce)" == activeKey else { return }
            switch res {
            case .success(let loaded):
                detail = loaded
            case .failure(let err):
                errorMsg = err.localizedDescription
            }
            isLoading = false
        }
    }
}
