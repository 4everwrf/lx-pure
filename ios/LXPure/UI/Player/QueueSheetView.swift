import SwiftUI

struct QueueSheetView: View {
    let queue: [Track]
    let currentIndex: Int
    let isPlaying: Bool
    let repeatMode: RepeatMode
    var queueSource: PlaybackQueueSource? = nil
    let onSelectIndex: (Int) -> Void
    let onRemoveIndex: (Int) -> Void
    let onCycleRepeat: () -> Void
    let onClearQueue: () -> Void
    var onOpenQueueSource: ((PlaybackQueueSource) -> Void)? = nil

    @Environment(\.lxPalette) private var lx
    @Environment(\.dismiss) private var dismiss

    private var canOpenSource: Bool {
        guard let queueSource, onOpenQueueSource != nil else { return false }
        return queueSource.type != .unknown && queueSource.type != .search
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                HStack(spacing: 10) {
                    Button(action: onCycleRepeat) {
                        HStack(spacing: 6) {
                            Image(systemName: repeatMode.sfSymbol)
                                .foregroundColor(lx.accent)
                            Text("\(repeatMode.label) (\(queue.count))")
                                .foregroundColor(lx.textPrimary)
                        }
                        .font(.system(size: 13, weight: .semibold))
                        .padding(.horizontal, 11)
                        .padding(.vertical, 7)
                        .background(lx.accentSoft)
                        .clipShape(Capsule())
                        .contentShape(Capsule())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("播放模式：\(repeatMode.label)，共 \(queue.count) 首")
                    .accessibilityHint("轻点切换播放模式")
                    Spacer()
                    if canOpenSource, let queueSource {
                        Button("原歌单") {
                            dismiss()
                            onOpenQueueSource?(queueSource)
                        }
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundColor(lx.accent)
                    }
                    if !queue.isEmpty {
                        Button("清空队列", role: .destructive) {
                            dismiss()
                            onClearQueue()
                        }
                        .font(.system(size: 13))
                        .foregroundColor(lx.danger)
                    }
                }
                .padding(.horizontal, 18)
                .padding(.vertical, 12)

                if let sourceTitle = queueSource?.title, !sourceTitle.isEmpty {
                    HStack {
                        Text("来自「\(sourceTitle)」")
                            .font(.system(size: 12))
                            .foregroundColor(lx.textSecondary)
                            .lineLimit(1)
                        Spacer()
                    }
                    .padding(.horizontal, 18)
                    .padding(.bottom, 8)
                }

                Divider().overlay(lx.hairline)

                if queue.isEmpty {
                    Spacer()
                    Text("播放队列为空")
                        .font(.system(size: 14))
                        .foregroundColor(lx.textSecondary)
                    Spacer()
                } else {
                    ScrollViewReader { proxy in
                        ScrollView {
                            LazyVStack(spacing: 2) {
                                ForEach(Array(queue.enumerated()), id: \.offset) { idx, track in
                                    let isCurrent = idx == currentIndex
                                    HStack(spacing: 12) {
                                        if isCurrent {
                                            MiniEqualizerIndicator(isPlaying: isPlaying, color: lx.accent)
                                        }
                                        VStack(alignment: .leading, spacing: 2) {
                                            Text(track.title)
                                                .font(.system(size: 14, weight: isCurrent ? .bold : .medium))
                                                .foregroundColor(isCurrent ? lx.accent : lx.textPrimary)
                                                .lineLimit(1)
                                            Text(track.artist)
                                                .font(.system(size: 12))
                                                .foregroundColor(lx.textSecondary)
                                                .lineLimit(1)
                                        }
                                        Spacer()
                                        Button {
                                            if queue.count <= 1 {
                                                dismiss()
                                            }
                                            onRemoveIndex(idx)
                                        } label: {
                                            Image(systemName: "xmark")
                                                .font(.system(size: 12, weight: .semibold))
                                                .foregroundColor(lx.textTertiary)
                                                .frame(width: 36, height: 36)
                                                .contentShape(Rectangle())
                                        }
                                        .buttonStyle(.plain)
                                        .accessibilityLabel("移出队列")
                                    }
                                    .padding(.horizontal, 18)
                                    .padding(.vertical, 10)
                                    .id(idx)
                                    .contentShape(Rectangle())
                                    .onTapGesture {
                                        onSelectIndex(idx)
                                    }
                                }
                            }
                        }
                        .onAppear {
                            if currentIndex >= 0 && currentIndex < queue.count {
                                proxy.scrollTo(currentIndex, anchor: .center)
                            }
                        }
                    }
                }
            }
            .background(lx.surface)
            .navigationTitle("播放队列")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("关闭") { dismiss() }
                }
            }
        }
    }
}
