import SwiftUI
import UniformTypeIdentifiers

struct CollectionDetailView: View {
    let title: String
    let subtitle: String
    let tracks: [Track]
    let currentTrackId: String?
    let isPlaying: Bool
    let isLocalCollection: Bool
    let isScanningLocal: Bool
    let canClearHistory: Bool
    let canDeleteTracks: Bool
    let bottomInset: CGFloat
    let onPlayTracks: ([Track], Int) -> Void
    let onTrackMore: (Track) -> Void
    var onScanLocal: (() -> Void)? = nil
    var onImportLocalFiles: (([URL]) -> Void)? = nil
    var onClearHistory: (() -> Void)? = nil
    var onRemoveTrack: ((Track) -> Void)? = nil

    @Environment(\.lxPalette) private var lx
    @State private var showAudioFileImporter = false

    var body: some View {
        ScrollView {
            LazyVStack(spacing: 4) {
                // Action header bar
                HStack(spacing: 10) {
                    if !tracks.isEmpty {
                        Button {
                            onPlayTracks(tracks, 0)
                        } label: {
                            HStack(spacing: 6) {
                                Image(systemName: "play.fill")
                                Text("播放全部 (\(tracks.count))")
                            }
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundColor(lx.onAccent)
                            .padding(.horizontal, 16)
                            .padding(.vertical, 9)
                            .background(lx.accent)
                            .clipShape(Capsule())
                        }
                    }

                    if isLocalCollection {
                        Button {
                            showAudioFileImporter = true
                        } label: {
                            HStack(spacing: 5) {
                                Image(systemName: "plus")
                                Text("导入音频")
                            }
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundColor(lx.accent)
                            .padding(.horizontal, 14)
                            .padding(.vertical, 9)
                            .background(lx.accentSoft)
                            .clipShape(Capsule())
                        }

                        Button {
                            onScanLocal?()
                        } label: {
                            if isScanningLocal {
                                ProgressView().tint(lx.accent).frame(width: 32, height: 32)
                            } else {
                                Image(systemName: "arrow.clockwise")
                                    .font(.system(size: 14, weight: .semibold))
                                    .foregroundColor(lx.textPrimary)
                                    .frame(width: 34, height: 34)
                                    .background(lx.surfaceElevated)
                                    .clipShape(Circle())
                            }
                        }
                    }

                    Spacer()

                    if canClearHistory && !tracks.isEmpty {
                        Button("清空", role: .destructive) {
                            onClearHistory?()
                        }
                        .font(.system(size: 13, weight: .medium))
                        .foregroundColor(lx.danger)
                    }
                }
                .padding(.horizontal, 18)
                .padding(.vertical, 12)

                if tracks.isEmpty {
                    GlassCard(cornerRadius: 18) {
                        VStack(spacing: 8) {
                            Image(systemName: isLocalCollection ? "folder.badge.plus" : "music.note.list")
                                .font(.system(size: 32))
                                .foregroundColor(lx.textTertiary)
                            Text(isLocalCollection ? "尚未导入本地音频" : "暂无歌曲")
                                .font(.system(size: 15, weight: .semibold))
                                .foregroundColor(lx.textPrimary)
                            Text(
                                isLocalCollection
                                    ? "点击上方“导入音频”从“文件”导入 FLAC / MP3 / M4A，或在“文件 → 我的 iPhone → 轻雪音乐 → LocalMusic”中放入音频后点刷新。"
                                    : subtitle
                            )
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
                    ForEach(Array(tracks.enumerated()), id: \.element.id) { idx, track in
                        TrackListRow(
                            track: track,
                            index: idx,
                            isCurrent: track.id == currentTrackId,
                            isPlaying: isPlaying,
                            onTap: { onPlayTracks(tracks, idx) },
                            onMore: { onTrackMore(track) }
                        )
                        .contextMenu {
                            if canDeleteTracks, let onRemoveTrack {
                                Button(role: .destructive) {
                                    onRemoveTrack(track)
                                } label: {
                                    Label(isLocalCollection ? "删除本地文件" : "移出歌单", systemImage: "trash")
                                }
                            }
                        }
                    }
                }
            }
            .padding(.bottom, bottomInset)
        }
        .background(lx.background)
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
        .fileImporter(
            isPresented: $showAudioFileImporter,
            allowedContentTypes: [.audio, .mp3, .mpeg4Audio, .wav, .aiff],
            allowsMultipleSelection: true
        ) { result in
            if case .success(let urls) = result {
                onImportLocalFiles?(urls)
            }
        }
    }
}
