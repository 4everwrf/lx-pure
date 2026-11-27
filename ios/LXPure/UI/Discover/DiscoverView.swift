import SwiftUI

@MainActor
final class DiscoverState: ObservableObject {
    static let supportedSources: [MusicSource] = [.wy, .tx, .kw, .soda]

    @Published var selectedSource: MusicSource = .wy
    @Published private(set) var hotPlaylistsBySource: [MusicSource: [OnlinePlaylist]] = [:]
    @Published private(set) var isLoading = false

    private var loadGeneration: Int = 0

    var hotPlaylists: [OnlinePlaylist] {
        hotPlaylistsBySource[selectedSource] ?? []
    }

    init(initialSource: MusicSource = .wy) {
        self.selectedSource = Self.normalizeSource(initialSource)
    }

    static func normalizeSource(_ source: MusicSource) -> MusicSource {
        supportedSources.contains(source) ? source : .wy
    }

    func selectSource(_ source: MusicSource) {
        let normalized = Self.normalizeSource(source)
        guard normalized != selectedSource else { return }
        selectedSource = normalized
        loadGeneration += 1
        isLoading = false
    }

    func loadHotPlaylistsIfNeeded(aggregator: MusicAggregator, force: Bool = false) async {
        let targetSource = selectedSource
        if !force, let cached = hotPlaylistsBySource[targetSource], !cached.isEmpty {
            isLoading = false
            return
        }
        if Task.isCancelled {
            return
        }

        loadGeneration += 1
        let requestGeneration = loadGeneration
        isLoading = true

        let fetched = await aggregator.getHotPlaylists(source: targetSource)

        guard loadGeneration == requestGeneration, selectedSource == targetSource else {
            return
        }
        isLoading = false
        guard !Task.isCancelled else {
            return
        }

        var seen = Set<String>()
        let deduped = fetched.filter { seen.insert("\($0.source.code)_\($0.id)").inserted }
        if !deduped.isEmpty {
            hotPlaylistsBySource[targetSource] = deduped
        } else if force {
            hotPlaylistsBySource.removeValue(forKey: targetSource)
        }
    }
}

struct DiscoverView: View {
    @ObservedObject var state: DiscoverState
    let aggregator: MusicAggregator
    let bottomInset: CGFloat
    let onOpenPlaylist: (OnlinePlaylist) -> Void
    let onOpenLeaderboard: (LeaderboardItem) -> Void

    @Environment(\.lxPalette) private var lx
    private let platforms: [MusicSource] = DiscoverState.supportedSources
    @State private var retryNonce = 0
    @State private var lastConsumedRetryNonce = 0

    private var leaderboards: [LeaderboardItem] {
        aggregator.getLeaderboards(source: state.selectedSource)
    }

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 22) {
                VStack(alignment: .leading, spacing: 14) {
                    Text("发现")
                        .font(.system(size: 28, weight: .bold))
                        .foregroundColor(lx.textPrimary)

                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 10) {
                            ForEach(platforms) { source in
                                let active = source == state.selectedSource
                                Button {
                                    state.selectSource(source)
                                } label: {
                                    Text(source.displayName)
                                        .font(.system(size: 12, weight: .semibold))
                                        .foregroundColor(active ? lx.onAccent : lx.textSecondary)
                                        .lineLimit(1)
                                        .padding(.horizontal, 16)
                                        .padding(.vertical, 8)
                                        .background(active ? lx.accent : lx.surfaceElevated)
                                        .clipShape(Capsule())
                                }
                                .buttonStyle(.plain)
                            }
                        }
                    }
                }
                .padding(.horizontal, 20)
                .padding(.top, 10)

                // Featured Playlists Carousel
                VStack(alignment: .leading, spacing: 12) {
                    Text("精选歌单")
                        .font(.system(size: 18, weight: .semibold))
                        .foregroundColor(lx.textPrimary)
                        .padding(.horizontal, 20)

                    if state.isLoading {
                        HStack {
                            Spacer()
                            ProgressView().tint(lx.accent)
                            Spacer()
                        }
                        .frame(height: 170)
                    } else if state.hotPlaylists.isEmpty {
                        VStack(alignment: .leading, spacing: 10) {
                            Text("歌单加载失败或网络不可达，可重试或切换平台")
                                .font(.system(size: 13))
                                .foregroundColor(lx.textSecondary)
                            Button {
                                retryNonce += 1
                            } label: {
                                HStack(spacing: 5) {
                                    Image(systemName: "arrow.clockwise")
                                    Text("重新加载")
                                }
                                .font(.system(size: 12, weight: .semibold))
                                .foregroundColor(lx.accent)
                                .padding(.horizontal, 14)
                                .padding(.vertical, 7)
                                .background(lx.accentSoft)
                                .clipShape(Capsule())
                            }
                            .buttonStyle(.plain)
                        }
                        .padding(.horizontal, 20)
                    } else {
                        ScrollView(.horizontal, showsIndicators: false) {
                            LazyHStack(alignment: .top, spacing: 14) {
                                ForEach(Array(state.hotPlaylists.enumerated()), id: \.offset) { _, playlist in
                                    Button {
                                        onOpenPlaylist(playlist)
                                    } label: {
                                        VStack(alignment: .leading, spacing: 8) {
                                            ZStack(alignment: .topTrailing) {
                                                AlbumArtworkView(url: playlist.coverUrl, size: 152, cornerRadius: 18)
                                                if !playlist.playCount.isEmpty {
                                                    Text(playlist.playCount)
                                                        .font(.system(size: 10, weight: .semibold))
                                                        .foregroundColor(.white)
                                                        .padding(.horizontal, 8)
                                                        .padding(.vertical, 3)
                                                        .background(Color.black.opacity(0.55))
                                                        .clipShape(Capsule())
                                                        .padding(8)
                                                }
                                            }
                                            Text(playlist.name)
                                                .font(.system(size: 14, weight: .semibold))
                                                .foregroundColor(lx.textPrimary)
                                                .lineLimit(2)
                                                .multilineTextAlignment(.leading)
                                        }
                                        .frame(width: 152, alignment: .leading)
                                    }
                                    .buttonStyle(.plain)
                                }
                            }
                            .padding(.horizontal, 18)
                        }
                        .id(state.selectedSource)
                    }
                }

                // 2-Column Bento Leaderboards
                VStack(alignment: .leading, spacing: 12) {
                    Text("热门榜单")
                        .font(.system(size: 18, weight: .semibold))
                        .foregroundColor(lx.textPrimary)
                        .padding(.horizontal, 2)

                    let columns = [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)]
                    LazyVGrid(columns: columns, spacing: 12) {
                        ForEach(leaderboards) { board in
                            let accent = Color(argb: board.accentHex)
                            Button {
                                onOpenLeaderboard(board)
                            } label: {
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(board.name)
                                        .font(.system(size: 15, weight: .bold))
                                        .foregroundColor(lx.textPrimary)
                                        .lineLimit(1)
                                    Text(board.subtitle)
                                        .font(.system(size: 12))
                                        .foregroundColor(lx.textSecondary)
                                        .lineLimit(1)
                                }
                                .frame(maxWidth: .infinity, minHeight: 84, alignment: .leading)
                                .padding(.horizontal, 16)
                                .background(
                                    LinearGradient(
                                        colors: [accent.opacity(lx.isDark ? 0.34 : 0.20), lx.surfaceElevated],
                                        startPoint: .topLeading,
                                        endPoint: .bottomTrailing
                                    )
                                )
                                .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                                .overlay(
                                    RoundedRectangle(cornerRadius: 18, style: .continuous)
                                        .stroke(lx.hairline, lineWidth: 0.7)
                                )
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
                .padding(.horizontal, 18)
            }
            .padding(.bottom, bottomInset)
        }
        .task(id: "\(state.selectedSource.rawValue)_\(retryNonce)") {
            let shouldForce = retryNonce != lastConsumedRetryNonce
            lastConsumedRetryNonce = retryNonce
            await state.loadHotPlaylistsIfNeeded(aggregator: aggregator, force: shouldForce)
        }
    }
}
