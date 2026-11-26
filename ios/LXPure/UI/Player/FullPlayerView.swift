import SwiftUI
import UIKit

private struct ArtistPickerData: Identifiable, Equatable {
    let id = UUID()
    let trackTitle: String
    let album: String
    let artists: [String]
}

struct FullPlayerView: View {
    let state: PlayerState
    @ObservedObject var progress: PlaybackProgressModel
    let isFavorite: Bool
    let downloadRecord: DownloadRecord?
    let preferredQuality: AudioQuality
    let fluidAmbientEnabled: Bool
    let showTranslationDefault: Bool
    let onCollapse: () -> Void
    let onTogglePlayPause: () -> Void
    let onNext: () -> Void
    let onPrevious: () -> Void
    let onSeek: (Int64) -> Void
    let onCycleRepeat: () -> Void
    let onToggleFavorite: (Track) -> Void
    let onAddToPlaylist: (Track) -> Void
    let onDownload: (Track) -> Void
    let onOpenQueue: () -> Void
    let onSelectQuality: (AudioQuality) -> Void
    let onSetSleepTimer: (Int?) -> Void
    let onArtistClick: (String) -> Void

    @Environment(\.lxPalette) private var lx
    @State private var showLyrics = false
    @State private var showTranslation = true
    @State private var showQualitySheet = false
    @State private var showSleepSheet = false
    @State private var artistPickerData: ArtistPickerData?
    @State private var pendingSelectedArtist: String?
    @State private var isDraggingSlider = false
    @State private var dragPositionMs: Double = 0
    @State private var pendingSeekTargetMs: Int64?
    @State private var pendingSeekClearTask: Task<Void, Never>?
    @State private var isUserScrollingLyrics = false
    @State private var lyricScrollCooldownTask: Task<Void, Never>?
    @GestureState private var isDraggingLyrics = false

    private var activeLyricIndex: Int {
        state.lyrics.activeIndex(at: progress.positionMs)
    }

    private func isActiveQuality(_ q: AudioQuality) -> Bool {
        if let badge = state.activeQualityBadge?.split(separator: "·").first.map({ String($0).trimmingCharacters(in: .whitespaces) }),
           AudioQuality.allCases.contains(where: { $0.badge.caseInsensitiveCompare(badge) == .orderedSame }) {
            return q.badge.caseInsensitiveCompare(badge) == .orderedSame
        }
        return q == preferredQuality
    }

    var body: some View {
        if let track = state.currentTrack {
            ZStack {
                FluidAmbientBackground(
                    palette: state.ambientPalette,
                    enabled: fluidAmbientEnabled,
                    immersive: true
                )

                VStack(spacing: 0) {
                    // Top handle & Header bar (swipe down works in BOTH cover and lyrics modes)
                    VStack(spacing: 10) {
                        Capsule()
                            .fill(Color.white.opacity(0.28))
                            .frame(width: 38, height: 4)
                            .padding(.top, 8)

                        HStack {
                            Button(action: onCollapse) {
                                Image(systemName: "chevron.down")
                                    .font(.system(size: 20, weight: .semibold))
                                    .foregroundColor(.white.opacity(0.88))
                                    .frame(width: 38, height: 38)
                            }
                            .accessibilityLabel("收起播放器")

                            Spacer()

                            Button {
                                if !track.isLocal { showQualitySheet = true }
                            } label: {
                                HStack(spacing: 5) {
                                    Text(providerPillText(for: track))
                                        .font(.system(size: 12, weight: .semibold))
                                        .foregroundColor(.white.opacity(0.92))
                                    if !track.isLocal {
                                        Image(systemName: "chevron.down")
                                            .font(.system(size: 9, weight: .bold))
                                            .foregroundColor(.white.opacity(0.65))
                                    }
                                }
                                .padding(.horizontal, 12)
                                .padding(.vertical, 6)
                                .background(Color.white.opacity(0.14))
                                .clipShape(Capsule())
                            }
                            .buttonStyle(.plain)

                            Spacer()

                            Button {
                                showSleepSheet = true
                            } label: {
                                ZStack {
                                    Image(systemName: "moon.zzz")
                                        .font(.system(size: 18, weight: .medium))
                                        .foregroundColor(progress.sleepTimerRemainingMs != nil ? Color(hex: 0x38BDF8) : .white.opacity(0.85))
                                }
                                .frame(width: 38, height: 38)
                            }
                            .accessibilityLabel("睡眠定时")
                        }
                    }
                    .padding(.horizontal, 20)
                    .contentShape(Rectangle())
                    .gesture(
                        DragGesture(minimumDistance: 24)
                            .onEnded { value in
                                if value.translation.height > 75 && abs(value.translation.height) > abs(value.translation.width) {
                                    onCollapse()
                                }
                            }
                    )

                    // Center Stage: Cover Artwork OR Synced Lyrics
                    Group {
                        if showLyrics {
                            lyricsStageView
                                .transition(.opacity)
                        } else {
                            artworkStageView(track: track)
                                .transition(.opacity)
                        }
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .clipped()
                    .animation(.easeInOut(duration: 0.25), value: showLyrics)

                    // Track info + Favorite + Download + Lyrics toggle
                    VStack(spacing: 14) {
                        HStack(alignment: .center, spacing: 12) {
                            VStack(alignment: .leading, spacing: 4) {
                                Text(track.title)
                                    .font(.system(size: 21, weight: .bold))
                                    .foregroundColor(.white)
                                    .lineLimit(1)
                                PlayerArtistAlbumRow(
                                    trackTitle: track.title,
                                    rawArtist: track.artist,
                                    structuredArtists: track.artistNames,
                                    album: track.album,
                                    onArtistClick: onArtistClick,
                                    onOpenArtistPicker: { parsedArtists in
                                        let cleanedAlbum = track.album
                                            .replacingOccurrences(of: "\r\n", with: " ")
                                            .replacingOccurrences(of: "\r", with: " ")
                                            .replacingOccurrences(of: "\n", with: " ")
                                            .trimmingCharacters(in: .whitespacesAndNewlines)
                                        artistPickerData = ArtistPickerData(
                                            trackTitle: track.title,
                                            album: cleanedAlbum,
                                            artists: parsedArtists
                                        )
                                    }
                                )
                                .equatable()
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)

                            Button {
                                onToggleFavorite(track)
                            } label: {
                                Image(systemName: isFavorite ? "heart.fill" : "heart")
                                    .font(.system(size: 21))
                                    .foregroundColor(isFavorite ? Color(hex: 0xFB7185) : .white.opacity(0.82))
                                    .frame(width: 38, height: 38)
                            }
                            .accessibilityLabel(isFavorite ? "取消喜欢" : "添加到我喜欢的")
                        }

                        // Progress Slider
                        VStack(spacing: 6) {
                            let maxDuration = Double(max(progress.durationMs, state.durationMs, track.durationMs, 1))
                            let displayedPos: Double = {
                                if isDraggingSlider { return dragPositionMs }
                                if let target = pendingSeekTargetMs { return Double(target) }
                                return Double(progress.positionMs)
                            }()

                            Slider(
                                value: Binding(
                                    get: { min(max(displayedPos, 0), maxDuration) },
                                    set: { newVal in
                                        isDraggingSlider = true
                                        dragPositionMs = newVal
                                    }
                                ),
                                in: 0...maxDuration,
                                onEditingChanged: { editing in
                                    if editing {
                                        pendingSeekClearTask?.cancel()
                                        pendingSeekTargetMs = nil
                                        dragPositionMs = Double(progress.positionMs)
                                        isDraggingSlider = true
                                    } else if isDraggingSlider {
                                        let target = Int64(min(max(dragPositionMs, 0), maxDuration))
                                        isDraggingSlider = false
                                        if maxDuration > 1 {
                                            pendingSeekTargetMs = target
                                            onSeek(target)
                                            pendingSeekClearTask?.cancel()
                                            pendingSeekClearTask = Task { @MainActor in
                                                try? await Task.sleep(nanoseconds: 650_000_000)
                                                if !Task.isCancelled && pendingSeekTargetMs == target {
                                                    pendingSeekTargetMs = nil
                                                }
                                            }
                                        }
                                    }
                                }
                            )
                            .id(track.id)
                            .tint(.white)

                            HStack {
                                Text(FormatUtils.formatTimeMs(Int64(displayedPos)))
                                    .font(.system(size: 11, weight: .medium).monospacedDigit())
                                    .foregroundColor(.white.opacity(0.72))
                                Spacer()
                                if let remaining = progress.sleepTimerRemainingMs {
                                    Text("定时 \(FormatUtils.formatTimeMs(remaining))")
                                        .font(.system(size: 11, weight: .semibold).monospacedDigit())
                                        .foregroundColor(Color(hex: 0x38BDF8))
                                    Spacer()
                                }
                                Text(FormatUtils.formatTimeMs(Int64(maxDuration)))
                                    .font(.system(size: 11, weight: .medium).monospacedDigit())
                                    .foregroundColor(.white.opacity(0.72))
                            }
                        }

                        // Primary Transport Controls
                        HStack {
                            Button(action: onCycleRepeat) {
                                Image(systemName: state.repeatMode.sfSymbol)
                                    .font(.system(size: 19, weight: .medium))
                                    .foregroundColor(.white.opacity(0.82))
                                    .frame(width: 44, height: 44)
                            }
                            .accessibilityLabel(state.repeatMode.label)

                            Spacer()

                            Button(action: onPrevious) {
                                Image(systemName: "backward.fill")
                                    .font(.system(size: 26))
                                    .foregroundColor(state.canSkipPrevious ? .white : .white.opacity(0.44))
                                    .frame(width: 50, height: 50)
                            }
                            .accessibilityLabel(state.canSkipPrevious ? "上一首" : "上一首（重新播放当前曲目）")

                            Spacer()

                            Button(action: onTogglePlayPause) {
                                ZStack {
                                    Circle()
                                        .fill(.white)
                                        .frame(width: 66, height: 66)
                                        .shadow(color: .black.opacity(0.28), radius: 12, y: 4)
                                    if state.isBuffering {
                                        ProgressView()
                                            .tint(.black)
                                    } else {
                                        Image(systemName: state.isPlaying ? "pause.fill" : "play.fill")
                                            .font(.system(size: 27, weight: .bold))
                                            .foregroundColor(Color(hex: 0x090B10))
                                            .offset(x: state.isPlaying ? 0 : 2)
                                    }
                                }
                            }
                            .accessibilityLabel(state.isBuffering ? "正在缓冲，点击暂停" : (state.isPlaying ? "暂停" : "播放"))

                            Spacer()

                            Button(action: onNext) {
                                Image(systemName: "forward.fill")
                                    .font(.system(size: 26))
                                    .foregroundColor(state.canSkipNext ? .white : .white.opacity(0.44))
                                    .frame(width: 50, height: 50)
                            }
                            .accessibilityLabel(
                                state.canSkipNext
                                    ? "下一首"
                                    : (state.repeatMode == .sequential ? "下一首（已到队列末尾）" : "下一首（重新播放当前曲目）")
                            )

                            Spacer()

                            Button(action: onOpenQueue) {
                                Image(systemName: "list.bullet")
                                    .font(.system(size: 20, weight: .medium))
                                    .foregroundColor(.white.opacity(0.82))
                                    .frame(width: 44, height: 44)
                            }
                            .accessibilityLabel("播放队列")
                        }
                        .padding(.vertical, 4)

                        // Bottom Utility Bar (Lyrics / Translation / Playlist / Download)
                        HStack(spacing: 12) {
                            Button {
                                showLyrics.toggle()
                            } label: {
                                HStack(spacing: 5) {
                                    Image(systemName: "quote.bubble")
                                    Text(showLyrics ? "封面" : "歌词")
                                }
                                .font(.system(size: 12, weight: .semibold))
                                .foregroundColor(showLyrics ? Color(hex: 0x38BDF8) : .white.opacity(0.75))
                                .padding(.horizontal, 12)
                                .padding(.vertical, 6)
                                .background(Color.white.opacity(showLyrics ? 0.16 : 0.08))
                                .clipShape(Capsule())
                            }

                            if showLyrics && state.lyrics.hasTranslation {
                                Button {
                                    showTranslation.toggle()
                                } label: {
                                    Text("译")
                                        .font(.system(size: 12, weight: .bold))
                                        .foregroundColor(showTranslation ? Color(hex: 0x38BDF8) : .white.opacity(0.65))
                                        .frame(width: 30, height: 30)
                                        .background(Color.white.opacity(showTranslation ? 0.16 : 0.08))
                                        .clipShape(Circle())
                                }
                            }

                            Button {
                                onAddToPlaylist(track)
                            } label: {
                                HStack(spacing: 5) {
                                    Image(systemName: "text.badge.plus")
                                    Text("歌单")
                                }
                                .font(.system(size: 12, weight: .semibold))
                                .foregroundColor(.white.opacity(0.82))
                                .padding(.horizontal, 12)
                                .padding(.vertical, 6)
                                .background(Color.white.opacity(0.08))
                                .clipShape(Capsule())
                            }

                            if !track.isLocal {
                                Button {
                                    onDownload(track)
                                } label: {
                                    HStack(spacing: 5) {
                                        if downloadRecord?.status == .downloading || downloadRecord?.status == .queued {
                                            ProgressView().tint(.white).scaleEffect(0.7)
                                            Text("\(Int((downloadRecord?.progress ?? 0) * 100))%")
                                        } else if downloadRecord?.status == .completed {
                                            Image(systemName: "checkmark.circle.fill")
                                                .foregroundColor(Color(hex: 0x34D399))
                                            Text("已离线")
                                        } else {
                                            Image(systemName: "arrow.down.circle")
                                            Text("离线")
                                        }
                                    }
                                    .font(.system(size: 12, weight: .semibold))
                                    .foregroundColor(.white.opacity(0.80))
                                    .padding(.horizontal, 12)
                                    .padding(.vertical, 6)
                                    .background(Color.white.opacity(0.08))
                                    .clipShape(Capsule())
                                }
                            }
                        }
                        .padding(.bottom, 16)
                    }
                    .padding(.horizontal, 24)
                }
            }
            .preferredColorScheme(.dark)
            .onAppear {
                showTranslation = showTranslationDefault
            }
            .onDisappear {
                pendingSelectedArtist = nil
            }
            .onChange(of: track.id) { _ in
                isDraggingSlider = false
                dragPositionMs = 0
                pendingSeekClearTask?.cancel()
                pendingSeekTargetMs = nil
                lyricScrollCooldownTask?.cancel()
                isUserScrollingLyrics = false
            }
            .onChange(of: progress.positionMs) { newPos in
                if let target = pendingSeekTargetMs, abs(newPos - target) < 1_500 {
                    pendingSeekClearTask?.cancel()
                    pendingSeekTargetMs = nil
                }
            }
            .confirmationDialog("选择偏好音质", isPresented: $showQualitySheet, titleVisibility: .visible) {
                ForEach(AudioQuality.allCases) { q in
                    Button(isActiveQuality(q) ? "✓ \(q.label)" : q.label) {
                        onSelectQuality(q)
                    }
                }
                Button("取消", role: .cancel) {}
            }
            .confirmationDialog("睡眠定时", isPresented: $showSleepSheet, titleVisibility: .visible) {
                ForEach([15, 30, 45, 60, 90], id: \.self) { minVal in
                    Button("\(minVal) 分钟后暂停") { onSetSleepTimer(minVal) }
                }
                if progress.sleepTimerRemainingMs != nil {
                    Button("关闭定时", role: .destructive) { onSetSleepTimer(nil) }
                }
                Button("取消", role: .cancel) {}
            }
            .sheet(item: $artistPickerData, onDismiss: {
                if let selected = pendingSelectedArtist {
                    pendingSelectedArtist = nil
                    onArtistClick(selected)
                }
            }) { data in
                ArtistPickerSheetView(
                    data: data,
                    onSelectArtist: { selected in
                        guard pendingSelectedArtist == nil else { return }
                        pendingSelectedArtist = selected
                        artistPickerData = nil
                    }
                )
                .environment(\.lxPalette, lx)
                .preferredColorScheme(lx.isDark ? .dark : .light)
                .presentationDetents([.medium, .large])
                .presentationDragIndicator(.visible)
            }
        }
    }

    private func artworkStageView(track: Track) -> some View {
        VStack(spacing: 18) {
            Spacer(minLength: 10)
            GeometryReader { geo in
                let artSize = min(geo.size.width - 32, geo.size.height - 24, 330)
                AlbumArtworkView(
                    url: track.coverUrl,
                    size: max(artSize, 80),
                    cornerRadius: 24,
                    isThumbnail: false
                )
                .shadow(color: .black.opacity(0.45), radius: 24, y: 12)
                .scaleEffect(state.isPlaying ? 1.0 : 0.90)
                .animation(.spring(response: 0.45, dampingFraction: 0.72), value: state.isPlaying)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
            .contentShape(Rectangle())
            .onTapGesture { showLyrics = true }
            .gesture(
                DragGesture(minimumDistance: 36)
                    .onEnded { value in
                        let dx = value.translation.width
                        let dy = value.translation.height
                        if abs(dx) > abs(dy) * 1.4 {
                            if dx < -65 { onNext() }
                            else if dx > 65 { onPrevious() }
                        } else if dy > 95 {
                            onCollapse()
                        }
                    }
            )

            // Preview active lyric line beneath cover
            let idx = activeLyricIndex
            if idx >= 0 && idx < state.lyrics.lines.count {
                Text(state.lyrics.lines[idx].text)
                    .font(.system(size: 14, weight: .medium))
                    .foregroundColor(.white.opacity(0.84))
                    .lineLimit(1)
                    .padding(.horizontal, 28)
                    .onTapGesture { showLyrics = true }
            }
            Spacer(minLength: 10)
        }
    }

    private var lyricsStageView: some View {
        Group {
            if state.isLoadingLyrics {
                VStack(spacing: 10) {
                    ProgressView().tint(.white)
                    Text("加载歌词中...")
                        .font(.system(size: 13))
                        .foregroundColor(.white.opacity(0.72))
                }
            } else if state.lyrics.isEmpty {
                VStack(spacing: 8) {
                    Text("纯音乐或暂无歌词")
                        .font(.system(size: 16, weight: .medium))
                        .foregroundColor(.white.opacity(0.78))
                    Text("轻触此处返回封面")
                        .font(.system(size: 12))
                        .foregroundColor(.white.opacity(0.55))
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .contentShape(Rectangle())
                .onTapGesture { showLyrics = false }
            } else {
                ScrollViewReader { proxy in
                    ScrollView(showsIndicators: false) {
                        LazyVStack(alignment: .leading, spacing: 18) {
                            Spacer().frame(height: 80)
                            ForEach(Array(state.lyrics.lines.enumerated()), id: \.offset) { idx, line in
                                let isActive = idx == activeLyricIndex
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(line.text)
                                        .font(.system(size: isActive ? 22 : 17, weight: isActive ? .bold : .medium))
                                        .foregroundColor(isActive ? .white : .white.opacity(0.58))
                                    if showTranslation, let trans = line.translation, !trans.isEmpty {
                                        Text(trans)
                                            .font(.system(size: isActive ? 14 : 13))
                                            .foregroundColor(isActive ? .white.opacity(0.88) : .white.opacity(0.46))
                                    }
                                }
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .id(idx)
                                .contentShape(Rectangle())
                                .onTapGesture {
                                    lyricScrollCooldownTask?.cancel()
                                    isUserScrollingLyrics = false
                                    onSeek(line.timeMs)
                                }
                                .animation(.easeOut(duration: 0.2), value: isActive)
                            }
                            Spacer().frame(height: 120)
                        }
                        .padding(.horizontal, 28)
                    }
                    .simultaneousGesture(
                        DragGesture(minimumDistance: 6)
                            .updating($isDraggingLyrics) { _, state, _ in
                                state = true
                            }
                    )
                    .onChange(of: isDraggingLyrics) { dragging in
                        lyricScrollCooldownTask?.cancel()
                        if dragging {
                            isUserScrollingLyrics = true
                        } else if isUserScrollingLyrics {
                            lyricScrollCooldownTask = Task { @MainActor in
                                try? await Task.sleep(nanoseconds: 3_500_000_000)
                                if !Task.isCancelled {
                                    isUserScrollingLyrics = false
                                }
                            }
                        }
                    }
                    .onChange(of: isUserScrollingLyrics) { scrolling in
                        guard !scrolling else { return }
                        let cur = activeLyricIndex
                        if cur >= 0 && cur < state.lyrics.lines.count {
                            withAnimation(.easeInOut(duration: 0.35)) {
                                proxy.scrollTo(cur, anchor: .center)
                            }
                        } else if cur < 0 && !state.lyrics.lines.isEmpty {
                            withAnimation(.easeInOut(duration: 0.35)) {
                                proxy.scrollTo(0, anchor: .top)
                            }
                        }
                    }
                    .onChange(of: activeLyricIndex) { newIdx in
                        guard !isUserScrollingLyrics else { return }
                        if newIdx >= 0 && newIdx < state.lyrics.lines.count {
                            withAnimation(.easeInOut(duration: 0.35)) {
                                proxy.scrollTo(newIdx, anchor: .center)
                            }
                        } else if newIdx < 0 && !state.lyrics.lines.isEmpty {
                            withAnimation(.easeInOut(duration: 0.35)) {
                                proxy.scrollTo(0, anchor: .top)
                            }
                        }
                    }
                    .onAppear {
                        let cur = activeLyricIndex
                        if cur >= 0 && cur < state.lyrics.lines.count {
                            proxy.scrollTo(cur, anchor: .center)
                        }
                    }
                }
            }
        }
    }

    private func providerPillText(for track: Track) -> String {
        let base = state.activeProviderName ?? track.source.displayName
        if let badge = state.activeQualityBadge {
            return "\(base) · \(badge)"
        }
        return base
    }
}

private struct PlayerArtistAlbumRow: View, Equatable {
    let trackTitle: String
    let rawArtist: String
    let structuredArtists: [String]
    let album: String
    let onArtistClick: (String) -> Void
    let onOpenArtistPicker: ([String]) -> Void

    @Environment(\.legibilityWeight) private var legibilityWeight
    @State private var availableWidth: CGFloat = 0

    static func == (lhs: PlayerArtistAlbumRow, rhs: PlayerArtistAlbumRow) -> Bool {
        lhs.trackTitle == rhs.trackTitle &&
            lhs.rawArtist == rhs.rawArtist &&
            lhs.structuredArtists == rhs.structuredArtists &&
            lhs.album == rhs.album
    }

    private func fontMetrics(size: CGFloat, weight: UIFont.Weight) -> UIFont {
        let adjusted: UIFont.Weight = legibilityWeight == .bold ? .bold : weight
        return UIFont.systemFont(ofSize: size, weight: adjusted)
    }

    private func measureWidth(_ text: String, size: CGFloat, weight: UIFont.Weight) -> Double {
        let font = fontMetrics(size: size, weight: weight)
        let measured = (text as NSString).size(withAttributes: [.font: font])
        return ceil(Double(measured.width))
    }

    var body: some View {
        let artists = ArtistParser.extractArtists(rawArtist: rawArtist, structuredArtists: structuredArtists)
        let trimmedAlbum = album
            .replacingOccurrences(of: "\r\n", with: " ")
            .replacingOccurrences(of: "\r", with: " ")
            .replacingOccurrences(of: "\n", with: " ")
            .trimmingCharacters(in: .whitespacesAndNewlines)

        ZStack(alignment: .leading) {
            if artists.isEmpty {
                let displayArtist = ArtistParser.fallbackDisplayName(rawArtist)
                Text(trimmedAlbum.isEmpty ? displayArtist : "\(displayArtist) · \(trimmedAlbum)")
                    .font(.system(size: 14))
                    .foregroundColor(.white.opacity(0.62))
                    .lineLimit(1)
            } else if availableWidth <= 1 {
                Color.clear.frame(height: 28)
            } else {
                measuredRow(artists: artists, trimmedAlbum: trimmedAlbum, effectiveWidth: Double(availableWidth))
            }
        }
        .frame(maxWidth: .infinity, minHeight: 28, alignment: .leading)
        .background(
            GeometryReader { geo in
                Color.clear
                    .onAppear {
                        if abs(availableWidth - geo.size.width) > 0.5 {
                            availableWidth = geo.size.width
                        }
                    }
                    .onChange(of: geo.size.width) { newWidth in
                        if abs(availableWidth - newWidth) > 0.5 {
                            availableWidth = newWidth
                        }
                    }
            }
        )
    }

    @ViewBuilder
    private func measuredRow(artists: [String], trimmedAlbum: String, effectiveWidth: Double) -> some View {
        let singleChevronW: Double = 14.0
        let chipChromeW: Double = 18.0
        let minChipW: Double = 34.0
        let chipGapW: Double = 6.0
        let badgeChromeW: Double = 32.0
        let albumDisplayText = artists.count == 1 ? " · \(trimmedAlbum)" : "· \(trimmedAlbum)"
        let albumFullW: Double = trimmedAlbum.isEmpty
            ? 0
            : (measureWidth(albumDisplayText, size: 14, weight: .regular) + 2)
        let minAlbumDisplayW: Double = max(
            measureWidth(artists.count == 1 ? " · 专辑…" : "· 专辑…", size: 14, weight: .regular) + 2,
            54.0
        )
        let minTruncatedArtistW: Double = artists.count == 1
            ? max(measureWidth("歌手…", size: 14.5, weight: .medium) + 2, 54.0)
            : max(measureWidth("歌手…", size: 13.5, weight: .medium) + chipChromeW, 64.0)

        let plan = ArtistRowLayoutPlanner.plan(
            artistCount: artists.count,
            maxWidth: effectiveWidth,
            artistWidthAt: { idx in
                let name = artists[idx]
                if artists.count == 1 {
                    return measureWidth(name, size: 14.5, weight: .medium) + 2
                } else {
                    let textW = measureWidth(name, size: 13.5, weight: .medium)
                    return max(textW + chipChromeW, minChipW)
                }
            },
            separatorWidth: chipGapW,
            singleArtistChevronWidth: singleChevronW,
            overflowBadgeWidthForHidden: { hidden in
                measureWidth("+\(hidden)", size: 12, weight: .semibold) + badgeChromeW
            },
            albumFullWidth: albumFullW,
            minAlbumDisplayWidth: minAlbumDisplayW,
            minTruncatedArtistWidth: minTruncatedArtistW,
            albumLeadingGap: artists.count >= 2 ? chipGapW : 0
        )

        if artists.count == 1 {
            let singleArtist = artists[0]
            let artistCap: CGFloat? = plan.singleArtistMaxWidth.map { CGFloat($0) }

            HStack(spacing: 0) {
                Button {
                    onArtistClick(singleArtist)
                } label: {
                    HStack(spacing: 2) {
                        Text(singleArtist)
                            .font(.system(size: 14.5, weight: .medium))
                            .foregroundColor(.white.opacity(0.88))
                            .lineLimit(1)
                            .truncationMode(.tail)
                        Image(systemName: "chevron.right")
                            .font(.system(size: 10.5, weight: .semibold))
                            .foregroundColor(.white.opacity(0.58))
                            .fixedSize()
                    }
                    .padding(.vertical, 5)
                    .padding(.trailing, 4)
                    .frame(minWidth: 34, maxWidth: artistCap, alignment: .leading)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .layoutPriority(1)
                .accessibilityLabel("搜索歌手 \(singleArtist)")

                if plan.showAlbum && !trimmedAlbum.isEmpty {
                    Text(" · \(trimmedAlbum)")
                        .font(.system(size: 14))
                        .foregroundColor(.white.opacity(0.52))
                        .lineLimit(1)
                        .truncationMode(.tail)
                }
            }
        } else if plan.showCompactSummaryOnly {
            Button {
                onOpenArtistPicker(artists)
            } label: {
                HStack(spacing: 3) {
                    Text("共 \(artists.count) 位歌手")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundColor(.white.opacity(0.92))
                        .lineLimit(1)
                        .truncationMode(.tail)
                    Image(systemName: "chevron.right")
                        .font(.system(size: 9.5, weight: .bold))
                        .foregroundColor(.white.opacity(0.72))
                        .fixedSize()
                }
                .padding(.leading, 10)
                .padding(.trailing, 7)
                .padding(.vertical, 4)
                .background(Color.white.opacity(0.16))
                .clipShape(Capsule())
                .padding(.vertical, 3)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("查看全部 \(artists.count) 位歌手")
        } else {
            let visibleArtists = Array(artists.prefix(plan.visibleCount))
            HStack(spacing: 6) {
                ForEach(visibleArtists, id: \.self) { artistName in
                    let isTruncatedFirst = plan.truncateSingleVisibleArtist && artistName == visibleArtists.first
                    Button {
                        onArtistClick(artistName)
                    } label: {
                        Text(artistName)
                            .font(.system(size: 13.5, weight: .medium))
                            .foregroundColor(.white.opacity(0.90))
                            .lineLimit(1)
                            .truncationMode(.tail)
                            .padding(.horizontal, 8)
                            .padding(.vertical, 3.5)
                            .frame(minWidth: 34)
                            .background(Color.white.opacity(0.10))
                            .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                            .padding(.vertical, 3)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .layoutPriority(isTruncatedFirst ? 0 : 1)
                    .accessibilityLabel("搜索歌手 \(artistName)")
                }

                if plan.hiddenCount > 0 {
                    Button {
                        onOpenArtistPicker(artists)
                    } label: {
                        HStack(spacing: 2) {
                            Text("+\(plan.hiddenCount)")
                                .font(.system(size: 12, weight: .semibold))
                                .foregroundColor(.white.opacity(0.92))
                                .lineLimit(1)
                                .fixedSize()
                            Image(systemName: "chevron.right")
                                .font(.system(size: 9.5, weight: .bold))
                                .foregroundColor(.white.opacity(0.72))
                                .fixedSize()
                        }
                        .padding(.leading, 10)
                        .padding(.trailing, 6)
                        .padding(.vertical, 4)
                        .background(Color.white.opacity(0.17))
                        .clipShape(Capsule())
                        .padding(.vertical, 3)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .layoutPriority(2)
                    .accessibilityLabel("展开查看全部 \(artists.count) 位歌手")
                } else if plan.showAlbum && !trimmedAlbum.isEmpty {
                    Text("· \(trimmedAlbum)")
                        .font(.system(size: 14))
                        .foregroundColor(.white.opacity(0.52))
                        .lineLimit(1)
                        .truncationMode(.tail)
                }
            }
        }
    }
}

private struct ArtistPickerSheetView: View {
    let data: ArtistPickerData
    let onSelectArtist: (String) -> Void

    @Environment(\.lxPalette) private var lx

    var body: some View {
        VStack(spacing: 0) {
            HStack(alignment: .center, spacing: 10) {
                VStack(alignment: .leading, spacing: 3) {
                    Text("选择歌手搜索")
                        .font(.system(size: 18, weight: .bold))
                        .foregroundColor(lx.textPrimary)
                    Text(data.album.isEmpty ? "《\(data.trackTitle)》" : "《\(data.trackTitle)》 · \(data.album)")
                        .font(.system(size: 13))
                        .foregroundColor(lx.textSecondary)
                        .lineLimit(1)
                }
                Spacer(minLength: 8)
                Text("共 \(data.artists.count) 位")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundColor(lx.accent)
                    .lineLimit(1)
                    .fixedSize()
                    .padding(.horizontal, 10)
                    .padding(.vertical, 4)
                    .background(lx.accentSoft)
                    .clipShape(Capsule())
                    .layoutPriority(1)
            }
            .padding(.horizontal, 20)
            .padding(.top, 18)
            .padding(.bottom, 12)

            Divider().overlay(lx.hairline)

            ScrollView {
                LazyVStack(spacing: 0) {
                    ForEach(Array(data.artists.enumerated()), id: \.offset) { _, artistName in
                        Button {
                            onSelectArtist(artistName)
                        } label: {
                            HStack(spacing: 13) {
                                ZStack {
                                    Circle()
                                        .fill(lx.accentSoft)
                                        .frame(width: 36, height: 36)
                                    Image(systemName: "person.fill")
                                        .font(.system(size: 15, weight: .medium))
                                        .foregroundColor(lx.accent)
                                }
                                Text(artistName)
                                    .font(.system(size: 15, weight: .medium))
                                    .foregroundColor(lx.textPrimary)
                                    .lineLimit(2)
                                    .multilineTextAlignment(.leading)
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                Image(systemName: "magnifyingglass")
                                    .font(.system(size: 15, weight: .medium))
                                    .foregroundColor(lx.textTertiary)
                            }
                            .padding(.horizontal, 20)
                            .padding(.vertical, 11)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel("搜索歌手 \(artistName)")
                    }
                }
                .padding(.bottom, 24)
            }
        }
        .background(lx.surfaceElevated.ignoresSafeArea())
    }
}
