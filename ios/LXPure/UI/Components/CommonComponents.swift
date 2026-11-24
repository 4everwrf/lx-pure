import SwiftUI
import UIKit

struct AlbumArtworkView: View {
    let url: String?
    let size: CGFloat
    var cornerRadius: CGFloat = 14
    var isThumbnail: Bool = true

    @Environment(\.lxPalette) private var lx
    @State private var loadedImage: UIImage?

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                .fill(lx.surfaceElevated)

            if let loadedImage {
                Image(uiImage: loadedImage)
                    .resizable()
                    .scaledToFill()
            } else {
                Image(systemName: "music.note")
                    .font(.system(size: max(size * 0.34, 16), weight: .medium))
                    .foregroundColor(lx.textTertiary)
            }
        }
        .frame(width: size, height: size)
        .clipShape(RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                .stroke(lx.hairline, lineWidth: 0.6)
        )
        .task(id: "\(url ?? "")_\(isThumbnail)") {
            let expectedId = "\(url ?? "")_\(isThumbnail)"
            if let cached = ImageMemoryCache.shared.cachedImage(for: url, isThumbnail: isThumbnail) {
                loadedImage = cached
                return
            }
            loadedImage = nil
            let img = await ImageMemoryCache.shared.loadImage(from: url, isThumbnail: isThumbnail)
            guard !Task.isCancelled, "\(url ?? "")_\(isThumbnail)" == expectedId else { return }
            loadedImage = img
        }
    }
}

struct GlassCard<Content: View>: View {
    let cornerRadius: CGFloat
    let content: Content
    @Environment(\.lxPalette) private var lx

    init(cornerRadius: CGFloat = 18, @ViewBuilder content: () -> Content) {
        self.cornerRadius = cornerRadius
        self.content = content()
    }

    var body: some View {
        content
            .background(lx.surface)
            .clipShape(RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                    .stroke(lx.hairline, lineWidth: 0.8)
            )
    }
}

struct SmallPillBadge: View {
    let text: String
    var color: Color
    var background: Color

    var body: some View {
        Text(text)
            .font(.system(size: 10, weight: .semibold))
            .foregroundColor(color)
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(background)
            .clipShape(Capsule())
    }
}

struct MiniEqualizerIndicator: View {
    let isPlaying: Bool
    let color: Color

    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        if isPlaying && scenePhase == .active && !reduceMotion {
            TimelineView(.animation(minimumInterval: 0.12, paused: scenePhase != .active || reduceMotion)) { timeline in
                let t = timeline.date.timeIntervalSinceReferenceDate * 6.0
                HStack(alignment: .bottom, spacing: 2) {
                    bar(height: 5 + 9 * abs(sin(t)))
                    bar(height: 5 + 9 * abs(sin(t + 1.3)))
                    bar(height: 5 + 9 * abs(sin(t + 2.6)))
                }
                .frame(width: 14, height: 14, alignment: .bottom)
            }
        } else {
            HStack(alignment: .bottom, spacing: 2) {
                bar(height: 5)
                bar(height: 9)
                bar(height: 6)
            }
            .frame(width: 14, height: 14, alignment: .bottom)
        }
    }

    private func bar(height: CGFloat) -> some View {
        RoundedRectangle(cornerRadius: 1.5)
            .fill(color)
            .frame(width: 3, height: height)
    }
}

struct TrackListRow: View {
    let track: Track
    var index: Int? = nil
    let isCurrent: Bool
    let isPlaying: Bool
    let onTap: () -> Void
    let onMore: () -> Void

    @Environment(\.lxPalette) private var lx

    var body: some View {
        HStack(spacing: 12) {
            if let index {
                if isCurrent {
                    MiniEqualizerIndicator(isPlaying: isPlaying, color: lx.accent)
                        .frame(width: 24)
                } else {
                    Text("\(index + 1)")
                        .font(.system(size: 13, weight: .medium).monospacedDigit())
                        .foregroundColor(lx.textTertiary)
                        .frame(width: 24)
                }
            }

            AlbumArtworkView(url: track.coverUrl, size: 48, cornerRadius: 12, isThumbnail: true)

            VStack(alignment: .leading, spacing: 4) {
                Text(track.title)
                    .font(.system(size: 15, weight: isCurrent ? .bold : .semibold))
                    .foregroundColor(isCurrent ? lx.accent : lx.textPrimary)
                    .lineLimit(1)

                HStack(spacing: 5) {
                    if let badge = track.bestQualityBadge {
                        SmallPillBadge(
                            text: badge,
                            color: badge == "HR" || badge == "SQ" ? lx.warning : lx.accent,
                            background: (badge == "HR" || badge == "SQ" ? lx.warning : lx.accent).opacity(0.14)
                        )
                    }
                    SmallPillBadge(
                        text: track.source.shortLabel,
                        color: lx.textSecondary,
                        background: lx.hairline
                    )
                    Text(track.album.isEmpty ? track.artist : "\(track.artist) · \(track.album)")
                        .font(.system(size: 12))
                        .foregroundColor(lx.textSecondary)
                        .lineLimit(1)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Button(action: onMore) {
                Image(systemName: "ellipsis")
                    .font(.system(size: 15, weight: .medium))
                    .foregroundColor(lx.textTertiary)
                    .frame(width: 34, height: 34)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
        .contentShape(Rectangle())
        .onTapGesture(perform: onTap)
    }
}

struct FloatingMiniPlayer: View {
    let state: PlayerState
    @ObservedObject var progress: PlaybackProgressModel
    let onTap: () -> Void
    let onTogglePlayPause: () -> Void
    let onNext: () -> Void
    let onPrevious: () -> Void
    let onOpenQueue: () -> Void

    @Environment(\.lxPalette) private var lx
    @GestureState private var dragOffsetX: CGFloat = 0

    var body: some View {
        if let track = state.currentTrack {
            let effectiveDuration = max(progress.durationMs, state.durationMs)
            let progressRatio = effectiveDuration > 0
                ? min(max(Double(progress.positionMs) / Double(effectiveDuration), 0), 1)
                : 0

            VStack(spacing: 0) {
                HStack(spacing: 12) {
                    AlbumArtworkView(url: track.coverUrl, size: 42, cornerRadius: 10, isThumbnail: true)

                    VStack(alignment: .leading, spacing: 2) {
                        Text(track.title)
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundColor(lx.textPrimary)
                            .lineLimit(1)
                        Text(state.isBuffering ? "正在解析与缓冲..." : track.artist)
                            .font(.system(size: 12))
                            .foregroundColor(lx.textSecondary)
                            .lineLimit(1)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)

                    Button(action: onTogglePlayPause) {
                        if state.isBuffering {
                            ProgressView()
                                .tint(lx.accent)
                                .scaleEffect(0.85)
                                .frame(width: 34, height: 34)
                        } else {
                            Image(systemName: state.isPlaying ? "pause.fill" : "play.fill")
                                .font(.system(size: 18, weight: .semibold))
                                .foregroundColor(lx.textPrimary)
                                .frame(width: 34, height: 34)
                        }
                    }
                    .buttonStyle(.plain)

                    Button(action: onOpenQueue) {
                        Image(systemName: "list.bullet")
                            .font(.system(size: 16, weight: .medium))
                            .foregroundColor(lx.textSecondary)
                            .frame(width: 32, height: 32)
                    }
                    .buttonStyle(.plain)
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 9)

                GeometryReader { geo in
                    ZStack(alignment: .leading) {
                        Rectangle().fill(lx.hairline)
                        Rectangle()
                            .fill(lx.accent)
                            .frame(width: geo.size.width * progressRatio)
                    }
                }
                .frame(height: 2)
            }
            .background(.ultraThinMaterial)
            .background(lx.glassSurface)
            .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .stroke(lx.hairline, lineWidth: 0.8)
            )
            .shadow(color: Color.black.opacity(lx.isDark ? 0.35 : 0.10), radius: 12, y: 4)
            .offset(x: dragOffsetX * 0.28)
            .animation(.spring(response: 0.3, dampingFraction: 0.78), value: dragOffsetX)
            .contentShape(Rectangle())
            .onTapGesture(perform: onTap)
            .gesture(
                DragGesture(minimumDistance: 16)
                    .updating($dragOffsetX) { value, state, _ in
                        if abs(value.translation.width) > abs(value.translation.height) {
                            state = value.translation.width
                        }
                    }
                    .onEnded { value in
                        guard abs(value.translation.width) > abs(value.translation.height) else { return }
                        let threshold: CGFloat = 55
                        if value.translation.width < -threshold {
                            onNext()
                        } else if value.translation.width > threshold {
                            onPrevious()
                        }
                    }
            )
            .padding(.horizontal, 14)
        }
    }
}

struct TrackActionSheetView: View {
    let track: Track
    let isFavorite: Bool
    let isDownloaded: Bool
    let onPlayNext: () -> Void
    let onAddToQueue: () -> Void
    let onToggleFavorite: () -> Void
    let onAddToPlaylist: () -> Void
    let onDownload: () -> Void
    var onRemoveFromPlaylist: (() -> Void)? = nil

    @Environment(\.lxPalette) private var lx
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 12) {
                AlbumArtworkView(url: track.coverUrl, size: 48, cornerRadius: 12)
                VStack(alignment: .leading, spacing: 3) {
                    Text(track.title)
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundColor(lx.textPrimary)
                        .lineLimit(1)
                    Text("\(track.artist) · \(track.source.displayName)")
                        .font(.system(size: 13))
                        .foregroundColor(lx.textSecondary)
                        .lineLimit(1)
                }
                Spacer()
            }
            .padding(18)

            Divider().overlay(lx.hairline)

            ScrollView {
                VStack(spacing: 2) {
                    sheetRow(icon: "text.insert", title: "下一首播放") {
                        dismiss(); onPlayNext()
                    }
                    sheetRow(icon: "text.append", title: "加入播放队列") {
                        dismiss(); onAddToQueue()
                    }
                    sheetRow(
                        icon: isFavorite ? "heart.fill" : "heart",
                        title: isFavorite ? "取消喜欢" : "添加到我喜欢的",
                        tint: isFavorite ? lx.danger : lx.textPrimary
                    ) {
                        dismiss(); onToggleFavorite()
                    }
                    sheetRow(icon: "text.badge.plus", title: "添加到歌单") {
                        onAddToPlaylist()
                        dismiss()
                    }
                    if !track.isLocal {
                        sheetRow(
                            icon: isDownloaded ? "checkmark.circle.fill" : "arrow.down.circle",
                            title: isDownloaded ? "已离线保存" : "离线下载",
                            tint: isDownloaded ? lx.success : lx.textPrimary
                        ) {
                            dismiss(); onDownload()
                        }
                    }
                    if let onRemoveFromPlaylist {
                        sheetRow(icon: "trash", title: "从当前歌单移除", tint: lx.danger) {
                            dismiss(); onRemoveFromPlaylist()
                        }
                    }
                }
                .padding(.vertical, 8)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .background(lx.surface.ignoresSafeArea())
    }

    private func sheetRow(icon: String, title: String, tint: Color? = nil, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 14) {
                Image(systemName: icon)
                    .font(.system(size: 17))
                    .foregroundColor(tint ?? lx.textPrimary)
                    .frame(width: 24)
                Text(title)
                    .font(.system(size: 15, weight: .medium))
                    .foregroundColor(lx.textPrimary)
                Spacer()
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 13)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

struct AddToPlaylistSheetView: View {
    let track: Track
    let playlists: [UserPlaylist]
    let containedPlaylistIds: Set<Int64>
    let customPlaylistCount: Int
    let maxCustomPlaylists: Int
    let onSelectPlaylist: (UserPlaylist) -> Void
    let onCreateAndAdd: (String) -> Void
    let onLimitReached: () -> Void

    @Environment(\.lxPalette) private var lx
    @Environment(\.dismiss) private var dismiss
    @State private var newName: String = ""
    @State private var showCreate = false
    @State private var hasSubmitted = false

    private var isAtCustomLimit: Bool {
        customPlaylistCount >= maxCustomPlaylists
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(track.title)
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundColor(lx.textPrimary)
                            .lineLimit(1)
                        Text(track.artist)
                            .font(.system(size: 12))
                            .foregroundColor(lx.textSecondary)
                            .lineLimit(1)
                    }
                    .listRowBackground(lx.surface)

                    if isAtCustomLimit {
                        Button {
                            onLimitReached()
                            dismiss()
                        } label: {
                            Label(
                                "自建歌单已达上限 (\(customPlaylistCount)/\(maxCustomPlaylists))",
                                systemImage: "exclamationmark.circle"
                            )
                            .foregroundColor(lx.textTertiary)
                        }
                        .listRowBackground(lx.surface)
                    } else if showCreate {
                        HStack {
                            TextField("输入新歌单名称", text: $newName)
                                .foregroundColor(lx.textPrimary)
                            Button("创建") {
                                let trimmed = newName.trimmingCharacters(in: .whitespacesAndNewlines)
                                guard !hasSubmitted, !trimmed.isEmpty else { return }
                                hasSubmitted = true
                                onCreateAndAdd(trimmed)
                                dismiss()
                            }
                            .buttonStyle(.borderless)
                            .foregroundColor(lx.accent)
                            .disabled(hasSubmitted || newName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                        }
                        .listRowBackground(lx.surface)
                    } else {
                        Button {
                            showCreate = true
                        } label: {
                            Label(
                                "新建歌单并加入 (\(customPlaylistCount)/\(maxCustomPlaylists))",
                                systemImage: "plus.circle.fill"
                            )
                            .foregroundColor(lx.accent)
                        }
                        .listRowBackground(lx.surface)
                    }
                }

                Section("选择歌单（含外部导入）") {
                    if playlists.isEmpty {
                        Text("暂无歌单，点击上方新建（最多自建 \(maxCustomPlaylists) 个）")
                            .foregroundColor(lx.textSecondary)
                            .listRowBackground(lx.surface)
                    } else {
                        ForEach(playlists) { pl in
                            let alreadyIn = containedPlaylistIds.contains(pl.id)
                            let isExternal = !LibraryStore.isCustomPlaylist(sourceUrl: pl.sourceUrl)
                            Button {
                                guard !hasSubmitted else { return }
                                hasSubmitted = true
                                onSelectPlaylist(pl)
                                dismiss()
                            } label: {
                                HStack(spacing: 12) {
                                    AlbumArtworkView(url: pl.coverUrl, size: 42, cornerRadius: 10)
                                    VStack(alignment: .leading, spacing: 3) {
                                        HStack(spacing: 6) {
                                            Text(pl.name)
                                                .font(.system(size: 15, weight: .medium))
                                                .foregroundColor(lx.textPrimary)
                                                .lineLimit(1)
                                            if isExternal {
                                                SmallPillBadge(
                                                    text: "外部导入",
                                                    color: lx.textSecondary,
                                                    background: lx.hairline
                                                )
                                            }
                                        }
                                        Text("\(pl.trackCount) 首歌曲")
                                            .font(.system(size: 12))
                                            .foregroundColor(lx.textSecondary)
                                    }
                                    Spacer()
                                    if alreadyIn {
                                        HStack(spacing: 3) {
                                            Image(systemName: "checkmark.circle.fill")
                                                .font(.system(size: 13))
                                            Text("已加入")
                                                .font(.system(size: 12, weight: .medium))
                                        }
                                        .foregroundColor(lx.success)
                                    } else {
                                        Image(systemName: "plus")
                                            .font(.system(size: 14, weight: .semibold))
                                            .foregroundColor(lx.accent)
                                    }
                                }
                            }
                            .listRowBackground(lx.surface)
                        }
                    }
                }
            }
            .scrollContentBackground(.hidden)
            .background(lx.background)
            .navigationTitle("添加到歌单")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("关闭") { dismiss() }
                }
            }
        }
    }
}

struct ToastBannerView: View {
    let event: PlayerUiEvent
    let onAction: (() -> Void)?

    @Environment(\.lxPalette) private var lx

    var body: some View {
        HStack(spacing: 10) {
            Text(event.message)
                .font(.system(size: 13, weight: .medium))
                .foregroundColor(lx.textPrimary)
                .lineLimit(2)

            if let label = event.actionLabel, let onAction {
                Spacer(minLength: 4)
                Button(label, action: onAction)
                    .font(.system(size: 13, weight: .bold))
                    .foregroundColor(lx.accent)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 11)
        .background(.ultraThinMaterial)
        .background(lx.glassSurface)
        .clipShape(Capsule())
        .overlay(Capsule().stroke(lx.hairline, lineWidth: 0.8))
        .shadow(color: .black.opacity(0.25), radius: 10, y: 4)
        .padding(.horizontal, 20)
    }
}

enum TrackListScrollHelper {
    static func targetIndex(forFraction fraction: Double, trackCount: Int) -> Int {
        guard trackCount > 1 else { return 0 }
        let clamped = min(max(fraction, 0.0), 1.0)
        return min(max(Int((clamped * Double(trackCount - 1)).rounded()), 0), trackCount - 1)
    }
}

struct TrackListFastScrollerOverlay: View {
    let trackCount: Int
    let currentTrackIndex: Int?
    let bottomInset: CGFloat
    let onJumpTop: () -> Void
    let onJumpBottom: () -> Void
    let onJumpToIndex: (Int) -> Void

    @Environment(\.lxPalette) private var lx
    @GestureState private var isDragging = false
    @State private var dragFraction: Double = 0
    @State private var lastScrubIndex: Int = -1

    var body: some View {
        if trackCount >= 15 {
            ZStack(alignment: .bottomTrailing) {
                if trackCount >= 20 {
                    GeometryReader { geo in
                        let barHeight = max(geo.size.height, 1)
                        let thumbHeight: CGFloat = 46
                        let thumbOffset = CGFloat(dragFraction) * max(barHeight - thumbHeight, 0)
                        let activeIdx = TrackListScrollHelper.targetIndex(forFraction: dragFraction, trackCount: trackCount)

                        ZStack(alignment: .topTrailing) {
                            if isDragging {
                                Capsule()
                                    .fill(lx.hairline)
                                    .frame(width: 3, height: barHeight)
                                    .padding(.trailing, 5)

                                Text("#\(activeIdx + 1) / \(trackCount)")
                                    .font(.system(size: 12, weight: .bold).monospacedDigit())
                                    .foregroundColor(lx.accent)
                                    .fixedSize()
                                    .padding(.horizontal, 12)
                                    .padding(.vertical, 6)
                                    .background(lx.surfaceElevated)
                                    .clipShape(Capsule())
                                    .overlay(Capsule().stroke(lx.accent.opacity(0.45), lineWidth: 0.8))
                                    .shadow(color: .black.opacity(0.25), radius: 8, y: 3)
                                    .offset(x: -30, y: max(thumbOffset + 4, 0))
                            }

                            Capsule()
                                .fill(isDragging ? lx.accent : lx.textTertiary.opacity(0.45))
                                .frame(width: isDragging ? 7 : 5, height: thumbHeight)
                                .padding(.trailing, 3)
                                .offset(y: thumbOffset)
                        }
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)
                        .contentShape(Rectangle())
                        .gesture(
                            DragGesture(minimumDistance: 6)
                                .updating($isDragging) { _, state, _ in
                                    state = true
                                }
                                .onChanged { value in
                                    let fraction = min(max(Double(value.location.y / barHeight), 0), 1)
                                    dragFraction = fraction
                                    let idx = TrackListScrollHelper.targetIndex(forFraction: fraction, trackCount: trackCount)
                                    if idx != lastScrubIndex {
                                        lastScrubIndex = idx
                                        onJumpToIndex(idx)
                                    }
                                }
                                .onEnded { _ in
                                    lastScrubIndex = -1
                                }
                        )
                    }
                    // Keep inactive strip strictly in outer 16pt so it never overlaps TrackListRow's ellipsis button
                    .frame(width: isDragging ? 28 : 16)
                    .padding(.top, 12)
                    .padding(.bottom, bottomInset + 148)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .trailing)
                }

                VStack(spacing: 8) {
                    if let idx = currentTrackIndex, idx >= 0 && idx < trackCount {
                        quickJumpButton(icon: "location.fill", tint: lx.accent) {
                            dragFraction = trackCount > 1 ? Double(idx) / Double(trackCount - 1) : 0
                            onJumpToIndex(idx)
                        }
                    }
                    quickJumpButton(icon: "arrow.up.to.line", tint: lx.textPrimary) {
                        dragFraction = 0
                        onJumpTop()
                    }
                    quickJumpButton(icon: "arrow.down.to.line", tint: lx.textPrimary) {
                        dragFraction = 1
                        onJumpBottom()
                    }
                }
                .padding(.trailing, 14)
                .padding(.bottom, bottomInset + 12)
            }
        }
    }

    private func quickJumpButton(icon: String, tint: Color, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: icon)
                .font(.system(size: 14, weight: .semibold))
                .foregroundColor(tint)
                .frame(width: 38, height: 38)
                .background(.ultraThinMaterial)
                .background(lx.surfaceElevated.opacity(0.85))
                .clipShape(Circle())
                .overlay(Circle().stroke(lx.hairline, lineWidth: 0.8))
                .shadow(color: .black.opacity(0.18), radius: 6, y: 2)
        }
        .buttonStyle(.plain)
    }
}
