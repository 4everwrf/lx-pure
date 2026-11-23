import SwiftUI

struct FullPlayerView: View {
    let state: PlayerState
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
    let onDownload: (Track) -> Void
    let onOpenQueue: () -> Void
    let onSelectQuality: (AudioQuality) -> Void
    let onSetSleepTimer: (Int?) -> Void

    @State private var showLyrics = false
    @State private var showTranslation = true
    @State private var showQualitySheet = false
    @State private var showSleepSheet = false
    @State private var isDraggingSlider = false
    @State private var dragPositionMs: Double = 0

    var body: some View {
        if let track = state.currentTrack {
            ZStack {
                FluidAmbientBackground(
                    palette: state.ambientPalette,
                    enabled: fluidAmbientEnabled,
                    immersive: true
                )

                VStack(spacing: 0) {
                    // Top handle & Header bar
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
                                        .foregroundColor(state.sleepTimerRemainingMs != nil ? Color(hex: 0x38BDF8) : .white.opacity(0.85))
                                }
                                .frame(width: 38, height: 38)
                            }
                        }
                    }
                    .padding(.horizontal, 20)

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
                                Text(track.album.isEmpty ? track.artist : "\(track.artist) · \(track.album)")
                                    .font(.system(size: 14))
                                    .foregroundColor(.white.opacity(0.70))
                                    .lineLimit(1)
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .contentShape(Rectangle())

                            Button {
                                onToggleFavorite(track)
                            } label: {
                                Image(systemName: isFavorite ? "heart.fill" : "heart")
                                    .font(.system(size: 21))
                                    .foregroundColor(isFavorite ? Color(hex: 0xFB7185) : .white.opacity(0.82))
                                    .frame(width: 38, height: 38)
                            }
                        }

                        // Progress Slider
                        VStack(spacing: 6) {
                            let maxDuration = Double(max(state.durationMs, track.durationMs, 1))
                            let displayedPos = isDraggingSlider ? dragPositionMs : Double(state.positionMs)

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
                                    if !editing {
                                        onSeek(Int64(dragPositionMs))
                                        isDraggingSlider = false
                                    }
                                }
                            )
                            .tint(.white)

                            HStack {
                                Text(FormatUtils.formatTimeMs(Int64(displayedPos)))
                                    .font(.system(size: 11, weight: .medium).monospacedDigit())
                                    .foregroundColor(.white.opacity(0.65))
                                Spacer()
                                if let remaining = state.sleepTimerRemainingMs {
                                    Text("定时 \(FormatUtils.formatTimeMs(remaining))")
                                        .font(.system(size: 11, weight: .semibold).monospacedDigit())
                                        .foregroundColor(Color(hex: 0x38BDF8))
                                    Spacer()
                                }
                                Text(FormatUtils.formatTimeMs(Int64(maxDuration)))
                                    .font(.system(size: 11, weight: .medium).monospacedDigit())
                                    .foregroundColor(.white.opacity(0.65))
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

                            Spacer()

                            Button(action: onPrevious) {
                                Image(systemName: "backward.fill")
                                    .font(.system(size: 26))
                                    .foregroundColor(.white)
                                    .frame(width: 50, height: 50)
                            }

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

                            Spacer()

                            Button(action: onNext) {
                                Image(systemName: "forward.fill")
                                    .font(.system(size: 26))
                                    .foregroundColor(.white)
                                    .frame(width: 50, height: 50)
                            }

                            Spacer()

                            Button(action: onOpenQueue) {
                                Image(systemName: "list.bullet")
                                    .font(.system(size: 20, weight: .medium))
                                    .foregroundColor(.white.opacity(0.82))
                                    .frame(width: 44, height: 44)
                            }
                        }
                        .padding(.vertical, 4)

                        // Bottom Utility Bar (Lyrics / Translation / Download)
                        HStack(spacing: 28) {
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
            .onChange(of: track.id) { _ in
                isDraggingSlider = false
            }
            .confirmationDialog("选择偏好音质", isPresented: $showQualitySheet, titleVisibility: .visible) {
                ForEach(AudioQuality.allCases) { q in
                    Button(q == preferredQuality ? "✓ \(q.label)" : q.label) {
                        onSelectQuality(q)
                    }
                }
                Button("取消", role: .cancel) {}
            }
            .confirmationDialog("睡眠定时", isPresented: $showSleepSheet, titleVisibility: .visible) {
                ForEach([15, 30, 45, 60, 90], id: \.self) { minVal in
                    Button("\(minVal) 分钟后暂停") { onSetSleepTimer(minVal) }
                }
                if state.sleepTimerRemainingMs != nil {
                    Button("关闭定时", role: .destructive) { onSetSleepTimer(nil) }
                }
                Button("取消", role: .cancel) {}
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
                    size: max(artSize, 180),
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
            let idx = state.activeLyricIndex
            if idx >= 0 && idx < state.lyrics.lines.count {
                Text(state.lyrics.lines[idx].text)
                    .font(.system(size: 14, weight: .medium))
                    .foregroundColor(.white.opacity(0.75))
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
                        .foregroundColor(.white.opacity(0.65))
                }
            } else if state.lyrics.isEmpty {
                VStack(spacing: 8) {
                    Text("纯音乐或暂无歌词")
                        .font(.system(size: 16, weight: .medium))
                        .foregroundColor(.white.opacity(0.72))
                    Text("轻触此处返回封面")
                        .font(.system(size: 12))
                        .foregroundColor(.white.opacity(0.45))
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
                                let isActive = idx == state.activeLyricIndex
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(line.text)
                                        .font(.system(size: isActive ? 22 : 17, weight: isActive ? .bold : .medium))
                                        .foregroundColor(isActive ? .white : .white.opacity(0.42))
                                    if showTranslation, let trans = line.translation, !trans.isEmpty {
                                        Text(trans)
                                            .font(.system(size: isActive ? 14 : 13))
                                            .foregroundColor(isActive ? .white.opacity(0.78) : .white.opacity(0.32))
                                    }
                                }
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .id(idx)
                                .contentShape(Rectangle())
                                .onTapGesture {
                                    onSeek(line.timeMs)
                                }
                                .animation(.easeOut(duration: 0.2), value: isActive)
                            }
                            Spacer().frame(height: 120)
                        }
                        .padding(.horizontal, 28)
                    }
                    .onChange(of: state.activeLyricIndex) { newIdx in
                        if newIdx >= 0 {
                            withAnimation(.easeInOut(duration: 0.35)) {
                                proxy.scrollTo(newIdx, anchor: .center)
                            }
                        }
                    }
                    .onAppear {
                        let cur = state.activeLyricIndex
                        if cur >= 0 {
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
