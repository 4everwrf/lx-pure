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

    var body: some View {
        ScrollView {
            if isLoading {
                VStack(spacing: 12) {
                    ProgressView().tint(lx.accent)
                    Text("正在加载歌单...")
                        .font(.system(size: 13))
                        .foregroundColor(lx.textSecondary)
                }
                .frame(maxWidth: .infinity)
                .padding(.top, 100)
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
                .frame(maxWidth: .infinity)
                .padding(32)
            } else if let detail {
                LazyVStack(spacing: 4) {
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
                            onSaveToLibrary(detail)
                        } label: {
                            HStack(spacing: 6) {
                                Image(systemName: "bookmark.fill")
                                Text("收藏歌单")
                            }
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundColor(lx.textPrimary)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 11)
                            .background(lx.surfaceElevated)
                            .clipShape(Capsule())
                            .overlay(Capsule().stroke(lx.hairline, lineWidth: 0.8))
                        }
                        .disabled(detail.tracks.isEmpty)
                    }
                    .padding(.horizontal, 18)
                    .padding(.vertical, 14)

                    ForEach(Array(detail.tracks.enumerated()), id: \.element.id) { idx, track in
                        TrackListRow(
                            track: track,
                            index: idx,
                            isCurrent: track.id == currentTrackId,
                            isPlaying: isPlaying,
                            onTap: { onPlayTracks(detail.tracks, idx) },
                            onMore: { onTrackMore(track) }
                        )
                    }
                }
                .padding(.bottom, bottomInset)
            }
        }
        .background(lx.background)
        .navigationTitle(detail?.playlist.name ?? title)
        .navigationBarTitleDisplayMode(.inline)
        .task(id: "\(loadKey)_\(retryNonce)") {
            isLoading = true
            errorMsg = nil
            let res = await loadDetail()
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
