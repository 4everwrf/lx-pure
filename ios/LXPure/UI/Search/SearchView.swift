import SwiftUI

private enum SearchScope: String, CaseIterable, Identifiable {
    case all = "聚合去重"
    case wy = "网易云"
    case tx = "QQ音乐"
    case kw = "酷我"
    case kg = "酷狗"

    var id: String { rawValue }

    var source: MusicSource? {
        switch self {
        case .all: return nil
        case .wy: return .wy
        case .tx: return .tx
        case .kw: return .kw
        case .kg: return .kg
        }
    }
}

struct SearchView: View {
    let aggregator: MusicAggregator
    let searchHistory: [String]
    let currentTrackId: String?
    let isPlaying: Bool
    let bottomInset: CGFloat
    let onSaveSearchQuery: (String) -> Void
    let onClearSearchHistory: () -> Void
    let onPlayTracks: ([Track], Int) -> Void
    let onTrackMore: (Track) -> Void

    @Environment(\.lxPalette) private var lx
    @State private var query = ""
    @State private var selectedScope: SearchScope = .all
    @State private var results: [Track] = []
    @State private var isSearching = false
    @State private var hasSearched = false
    @FocusState private var isSearchFocused: Bool

    var body: some View {
        VStack(spacing: 0) {
            // Header + Pill Search Bar + Scope Chips
            VStack(alignment: .leading, spacing: 12) {
                Text("搜索")
                    .font(.system(size: 28, weight: .bold))
                    .foregroundColor(lx.textPrimary)
                    .padding(.horizontal, 2)

                HStack(spacing: 10) {
                    Image(systemName: "magnifyingglass")
                        .font(.system(size: 16, weight: .medium))
                        .foregroundColor(lx.textTertiary)

                    TextField("搜索歌曲、歌手或专辑", text: $query)
                        .font(.system(size: 15))
                        .foregroundColor(lx.textPrimary)
                        .submitLabel(.search)
                        .focused($isSearchFocused)
                        .onSubmit {
                            executeSearch(keyword: query, scope: selectedScope)
                        }

                    if !query.isEmpty {
                        Button {
                            query = ""
                            results = []
                            hasSearched = false
                        } label: {
                            Image(systemName: "xmark.circle.fill")
                                .font(.system(size: 16))
                                .foregroundColor(lx.textTertiary)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 11)
                .background(lx.surfaceElevated)
                .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: 18, style: .continuous)
                        .stroke(lx.hairline, lineWidth: 0.8)
                )

                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(SearchScope.allCases) { item in
                            let active = item == selectedScope
                            Button {
                                selectedScope = item
                                if !query.trimmingCharacters(in: .whitespaces).isEmpty {
                                    executeSearch(keyword: query, scope: item)
                                }
                            } label: {
                                Text(item.rawValue)
                                    .font(.system(size: 12, weight: .semibold))
                                    .foregroundColor(active ? lx.onAccent : lx.textSecondary)
                                    .padding(.horizontal, 14)
                                    .padding(.vertical, 7)
                                    .background(active ? lx.accent : lx.surfaceElevated)
                                    .clipShape(Capsule())
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
            }
            .padding(.horizontal, 18)
            .padding(.vertical, 10)

            // Body
            if isSearching {
                Spacer()
                ProgressView()
                    .tint(lx.accent)
                    .scaleEffect(1.1)
                Spacer()
            } else if !hasSearched {
                ScrollView {
                    VStack(alignment: .leading, spacing: 14) {
                        if !searchHistory.isEmpty {
                            HStack {
                                Text("搜索历史")
                                    .font(.system(size: 15, weight: .semibold))
                                    .foregroundColor(lx.textPrimary)
                                Spacer()
                                Button(action: onClearSearchHistory) {
                                    Image(systemName: "trash")
                                        .font(.system(size: 14))
                                        .foregroundColor(lx.textTertiary)
                                }
                            }

                            LazyVGrid(
                                columns: [GridItem(.adaptive(minimum: 84), spacing: 8)],
                                alignment: .leading,
                                spacing: 8
                            ) {
                                ForEach(searchHistory, id: \.self) { term in
                                    Button {
                                        query = term
                                        executeSearch(keyword: term, scope: selectedScope)
                                    } label: {
                                        Text(term)
                                            .font(.system(size: 13))
                                            .foregroundColor(lx.textSecondary)
                                            .lineLimit(1)
                                            .padding(.horizontal, 12)
                                            .padding(.vertical, 7)
                                            .frame(maxWidth: .infinity)
                                            .background(lx.surfaceElevated)
                                            .clipShape(Capsule())
                                    }
                                    .buttonStyle(.plain)
                                }
                            }
                        } else {
                            VStack(spacing: 8) {
                                Image(systemName: "waveform.and.magnifyingglass")
                                    .font(.system(size: 32))
                                    .foregroundColor(lx.textTertiary)
                                Text("多平台聚合检索")
                                    .font(.system(size: 15, weight: .semibold))
                                    .foregroundColor(lx.textPrimary)
                                Text("支持网易云、QQ、酷我、酷狗并发去重搜索与无感跨平台换源")
                                    .font(.system(size: 12))
                                    .foregroundColor(lx.textSecondary)
                                    .multilineTextAlignment(.center)
                            }
                            .frame(maxWidth: .infinity)
                            .padding(.top, 50)
                        }
                    }
                    .padding(.horizontal, 20)
                    .padding(.top, 10)
                    .padding(.bottom, bottomInset)
                }
            } else if results.isEmpty {
                Spacer()
                VStack(spacing: 6) {
                    Text("未找到相关歌曲")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundColor(lx.textPrimary)
                    Text("可尝试切换搜索平台或更换关键词")
                        .font(.system(size: 12))
                        .foregroundColor(lx.textSecondary)
                }
                Spacer()
            } else {
                ScrollView {
                    LazyVStack(spacing: 2) {
                        ForEach(Array(results.enumerated()), id: \.element.id) { idx, track in
                            TrackListRow(
                                track: track,
                                index: nil,
                                isCurrent: track.id == currentTrackId,
                                isPlaying: isPlaying,
                                onTap: { onPlayTracks(results, idx) },
                                onMore: { onTrackMore(track) }
                            )
                        }
                    }
                    .padding(.bottom, bottomInset)
                }
            }
        }
    }

    private func executeSearch(keyword: String, scope: SearchScope) {
        let trimmed = keyword.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        isSearchFocused = false
        onSaveSearchQuery(trimmed)

        Task {
            isSearching = true
            hasSearched = true
            if let source = scope.source {
                results = await aggregator.searchSinglePlatform(source: source, query: trimmed, page: 1, limit: 30)
            } else {
                results = await aggregator.searchAggregated(query: trimmed)
            }
            isSearching = false
        }
    }
}
