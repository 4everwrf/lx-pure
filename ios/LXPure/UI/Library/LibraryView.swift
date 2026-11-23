import SwiftUI

enum LibraryCollectionType: String, Hashable, Identifiable {
    case favorites
    case local
    case downloads
    case history

    var id: String { rawValue }

    var title: String {
        switch self {
        case .favorites: return "我喜欢的"
        case .local: return "本地音乐"
        case .downloads: return "离线下载"
        case .history: return "最近播放"
        }
    }
}

struct LibraryView: View {
    let hasReadySource: Bool
    let favoritesCount: Int
    let localCount: Int
    let downloadsCount: Int
    let recentHistory: [Track]
    let playlists: [UserPlaylist]
    let isImportingPlaylist: Bool
    let bottomInset: CGFloat
    let onOpenSettings: () -> Void
    let onOpenSourceManager: () -> Void
    let onOpenCollection: (LibraryCollectionType) -> Void
    let onOpenPlaylist: (UserPlaylist) -> Void
    let onPlayTrack: (Track) -> Void
    let onCreatePlaylist: (String) -> Void
    let onImportExternalPlaylist: (String) -> Void
    let onDeletePlaylist: (UserPlaylist) -> Void
    var onPlaylistLimitReached: () -> Void = {}

    @Environment(\.lxPalette) private var lx
    @State private var showCreateAlert = false
    @State private var newPlaylistName = ""
    @State private var showImportSheet = false
    @State private var importInput = ""

    private var customPlaylistCount: Int {
        playlists.filter { LibraryStore.isCustomPlaylist(sourceUrl: $0.sourceUrl) }.count
    }

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 20) {
                // Header
                HStack(alignment: .center) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("音乐库")
                            .font(.system(size: 28, weight: .bold))
                            .foregroundColor(lx.textPrimary)
                        Text("轻雪 · 纯净聆听")
                            .font(.system(size: 13))
                            .foregroundColor(lx.textTertiary)
                    }
                    Spacer()
                    HStack(spacing: 6) {
                        Button {
                            showImportSheet = true
                        } label: {
                            if isImportingPlaylist {
                                ProgressView()
                                    .tint(lx.accent)
                                    .frame(width: 36, height: 36)
                            } else {
                                Image(systemName: "link")
                                    .font(.system(size: 18, weight: .medium))
                                    .foregroundColor(lx.textPrimary)
                                    .frame(width: 36, height: 36)
                            }
                        }
                        .accessibilityLabel("导入外部歌单")

                        Button(action: onOpenSettings) {
                            Image(systemName: "gearshape")
                                .font(.system(size: 19, weight: .medium))
                                .foregroundColor(lx.textPrimary)
                                .frame(width: 36, height: 36)
                        }
                        .accessibilityLabel("设置")
                    }
                }
                .padding(.horizontal, 20)
                .padding(.top, 10)

                // Cold-Start Source Onboarding Banner
                if !hasReadySource {
                    Button(action: onOpenSourceManager) {
                        GlassCard(cornerRadius: 20) {
                            HStack(spacing: 14) {
                                ZStack {
                                    Circle()
                                        .fill(lx.accent.opacity(0.20))
                                        .frame(width: 40, height: 40)
                                    Image(systemName: "bolt.fill")
                                        .font(.system(size: 18, weight: .semibold))
                                        .foregroundColor(lx.accent)
                                }
                                VStack(alignment: .leading, spacing: 3) {
                                    Text("导入洛雪自定义源 (.js)")
                                        .font(.system(size: 15, weight: .semibold))
                                        .foregroundColor(lx.textPrimary)
                                    Text("兼容洛雪 .js 自定义源协议，导入本地文件、脚本代码或自定义链接即可使用")
                                        .font(.system(size: 12))
                                        .foregroundColor(lx.textSecondary)
                                        .multilineTextAlignment(.leading)
                                }
                                Spacer(minLength: 4)
                                Image(systemName: "chevron.right")
                                    .font(.system(size: 13, weight: .semibold))
                                    .foregroundColor(lx.textTertiary)
                            }
                            .padding(16)
                            .background(
                                LinearGradient(
                                    colors: [lx.accent.opacity(0.16), Color(hex: 0x818CF8, alpha: 0.10)],
                                    startPoint: .leading,
                                    endPoint: .trailing
                                )
                            )
                        }
                    }
                    .buttonStyle(.plain)
                    .padding(.horizontal, 18)
                }

                // 2x2 Minimalist Bento Grid
                VStack(spacing: 12) {
                    HStack(spacing: 12) {
                        bentoCard(
                            title: "我喜欢的",
                            subtitle: "\(favoritesCount) 首歌曲",
                            icon: "heart.fill",
                            accent: Color(hex: 0xFB7185)
                        ) { onOpenCollection(.favorites) }

                        bentoCard(
                            title: "本地音乐",
                            subtitle: "\(localCount) 首音频",
                            icon: "folder.fill",
                            accent: Color(hex: 0x38BDF8)
                        ) { onOpenCollection(.local) }
                    }
                    HStack(spacing: 12) {
                        bentoCard(
                            title: "离线下载",
                            subtitle: "\(downloadsCount) 首离线",
                            icon: "arrow.down.circle.fill",
                            accent: Color(hex: 0x34D399)
                        ) { onOpenCollection(.downloads) }

                        bentoCard(
                            title: "最近播放",
                            subtitle: "\(recentHistory.count) 条记录",
                            icon: "clock.arrow.circlepath",
                            accent: Color(hex: 0xA78BFA)
                        ) { onOpenCollection(.history) }
                    }
                }
                .padding(.horizontal, 18)

                // Continue Listening Strip
                if !recentHistory.isEmpty {
                    VStack(alignment: .leading, spacing: 12) {
                        Text("继续聆听")
                            .font(.system(size: 18, weight: .semibold))
                            .foregroundColor(lx.textPrimary)
                            .padding(.horizontal, 20)

                        ScrollView(.horizontal, showsIndicators: false) {
                            LazyHStack(spacing: 12) {
                                ForEach(recentHistory.prefix(10)) { track in
                                    Button {
                                        onPlayTrack(track)
                                    } label: {
                                        VStack(alignment: .leading, spacing: 6) {
                                            AlbumArtworkView(url: track.coverUrl, size: 124, cornerRadius: 16)
                                            Text(track.title)
                                                .font(.system(size: 14, weight: .semibold))
                                                .foregroundColor(lx.textPrimary)
                                                .lineLimit(1)
                                            Text(track.artist)
                                                .font(.system(size: 12))
                                                .foregroundColor(lx.textSecondary)
                                                .lineLimit(1)
                                        }
                                        .frame(width: 124, alignment: .leading)
                                    }
                                    .buttonStyle(.plain)
                                }
                            }
                            .padding(.horizontal, 18)
                        }
                    }
                }

                // My Playlists
                VStack(alignment: .leading, spacing: 12) {
                    HStack(spacing: 8) {
                        Text("我的歌单")
                            .font(.system(size: 18, weight: .semibold))
                            .foregroundColor(lx.textPrimary)
                        Text("自建 \(customPlaylistCount)/\(LibraryStore.maxCustomPlaylists)")
                            .font(.system(size: 12, weight: .medium))
                            .foregroundColor(lx.textTertiary)
                        Spacer()
                        Button {
                            if customPlaylistCount >= LibraryStore.maxCustomPlaylists {
                                onPlaylistLimitReached()
                            } else {
                                newPlaylistName = ""
                                showCreateAlert = true
                            }
                        } label: {
                            HStack(spacing: 4) {
                                Image(systemName: "plus")
                                Text("新建")
                            }
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundColor(lx.accent)
                            .padding(.horizontal, 12)
                            .padding(.vertical, 6)
                            .background(lx.accentSoft)
                            .clipShape(Capsule())
                        }
                    }
                    .padding(.horizontal, 20)

                    if playlists.isEmpty {
                        GlassCard(cornerRadius: 18) {
                            VStack(spacing: 6) {
                                Text("还没有自建歌单")
                                    .font(.system(size: 15, weight: .semibold))
                                    .foregroundColor(lx.textPrimary)
                                Text("点击右上角“新建”创建歌单（最多自建 \(LibraryStore.maxCustomPlaylists) 个），或点击顶部链接图标导入网易云 / QQ / 酷我歌单")
                                    .font(.system(size: 12))
                                    .foregroundColor(lx.textSecondary)
                                    .multilineTextAlignment(.center)
                            }
                            .frame(maxWidth: .infinity)
                            .padding(.horizontal, 20)
                            .padding(.vertical, 24)
                        }
                        .padding(.horizontal, 18)
                    } else {
                        VStack(spacing: 10) {
                            ForEach(playlists) { pl in
                                let isExternal = !LibraryStore.isCustomPlaylist(sourceUrl: pl.sourceUrl)
                                Button {
                                    onOpenPlaylist(pl)
                                } label: {
                                    GlassCard(cornerRadius: 16) {
                                        HStack(spacing: 12) {
                                            AlbumArtworkView(url: pl.coverUrl, size: 52, cornerRadius: 12)
                                            VStack(alignment: .leading, spacing: 4) {
                                                Text(pl.name)
                                                    .font(.system(size: 15, weight: .semibold))
                                                    .foregroundColor(lx.textPrimary)
                                                    .lineLimit(1)
                                                Text(isExternal ? "\(pl.trackCount) 首歌曲 · 外部导入" : "\(pl.trackCount) 首歌曲")
                                                    .font(.system(size: 12))
                                                    .foregroundColor(lx.textSecondary)
                                            }
                                            Spacer()
                                            Image(systemName: "chevron.right")
                                                .font(.system(size: 13))
                                                .foregroundColor(lx.textTertiary)
                                        }
                                        .padding(12)
                                    }
                                }
                                .buttonStyle(.plain)
                                .contextMenu {
                                    Button(role: .destructive) {
                                        onDeletePlaylist(pl)
                                    } label: {
                                        Label("删除歌单", systemImage: "trash")
                                    }
                                }
                            }
                        }
                        .padding(.horizontal, 18)
                    }
                }
            }
            .padding(.bottom, bottomInset)
        }
        .alert("新建歌单", isPresented: $showCreateAlert) {
            TextField("歌单名称", text: $newPlaylistName)
            Button("取消", role: .cancel) {}
            Button("创建") {
                let name = newPlaylistName.trimmingCharacters(in: .whitespacesAndNewlines)
                if !name.isEmpty { onCreatePlaylist(name) }
            }
        } message: {
            Text("最多支持自建 \(LibraryStore.maxCustomPlaylists) 个歌单（当前 \(customPlaylistCount)/\(LibraryStore.maxCustomPlaylists)）")
        }
        .sheet(isPresented: $showImportSheet) {
            NavigationStack {
                VStack(alignment: .leading, spacing: 14) {
                    Text("支持粘贴网易云、QQ 音乐、酷我歌单分享文案、短链接或歌单 ID，将自动解析并保存到你的音乐库。")
                        .font(.system(size: 13))
                        .foregroundColor(lx.textSecondary)

                    TextEditor(text: $importInput)
                        .frame(minHeight: 120)
                        .padding(10)
                        .background(lx.surfaceElevated)
                        .clipShape(RoundedRectangle(cornerRadius: 14))
                        .overlay(RoundedRectangle(cornerRadius: 14).stroke(lx.hairline, lineWidth: 1))

                    Spacer()
                }
                .padding(18)
                .background(lx.background)
                .navigationTitle("导入外部歌单")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("取消") { showImportSheet = false }
                    }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("导入") {
                            let val = importInput.trimmingCharacters(in: .whitespacesAndNewlines)
                            showImportSheet = false
                            importInput = ""
                            if !val.isEmpty { onImportExternalPlaylist(val) }
                        }
                        .disabled(importInput.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    }
                }
            }
            .presentationDetents([.medium])
        }
    }

    private func bentoCard(
        title: String,
        subtitle: String,
        icon: String,
        accent: Color,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            GlassCard(cornerRadius: 18) {
                VStack(alignment: .leading, spacing: 10) {
                    ZStack {
                        RoundedRectangle(cornerRadius: 10, style: .continuous)
                            .fill(accent.opacity(0.18))
                            .frame(width: 34, height: 34)
                        Image(systemName: icon)
                            .font(.system(size: 16, weight: .semibold))
                            .foregroundColor(accent)
                    }
                    VStack(alignment: .leading, spacing: 2) {
                        Text(title)
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundColor(lx.textPrimary)
                        Text(subtitle)
                            .font(.system(size: 12))
                            .foregroundColor(lx.textSecondary)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(14)
            }
        }
        .buttonStyle(.plain)
    }
}
