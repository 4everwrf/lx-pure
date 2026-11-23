import SwiftUI

struct QueueSheetView: View {
    let queue: [Track]
    let currentIndex: Int
    let isPlaying: Bool
    let repeatMode: RepeatMode
    let onSelectIndex: (Int) -> Void
    let onRemoveIndex: (Int) -> Void
    let onCycleRepeat: () -> Void
    let onClearQueue: () -> Void

    @Environment(\.lxPalette) private var lx
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                HStack {
                    Button(action: onCycleRepeat) {
                        HStack(spacing: 6) {
                            Image(systemName: repeatMode.sfSymbol)
                            Text("\(repeatMode.label) (\(queue.count))")
                        }
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundColor(lx.textPrimary)
                    }
                    Spacer()
                    if !queue.isEmpty {
                        Button("清空队列", role: .destructive) {
                            onClearQueue()
                            dismiss()
                        }
                        .font(.system(size: 13))
                        .foregroundColor(lx.danger)
                    }
                }
                .padding(.horizontal, 18)
                .padding(.vertical, 12)

                Divider().overlay(lx.hairline)

                if queue.isEmpty {
                    Spacer()
                    Text("播放队列为空")
                        .font(.system(size: 14))
                        .foregroundColor(lx.textSecondary)
                    Spacer()
                } else {
                    ScrollView {
                        LazyVStack(spacing: 2) {
                            ForEach(Array(queue.enumerated()), id: \.element.id) { idx, track in
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
                                        onRemoveIndex(idx)
                                    } label: {
                                        Image(systemName: "xmark")
                                            .font(.system(size: 12, weight: .semibold))
                                            .foregroundColor(lx.textTertiary)
                                            .frame(width: 30, height: 30)
                                    }
                                    .buttonStyle(.plain)
                                }
                                .padding(.horizontal, 18)
                                .padding(.vertical, 10)
                                .contentShape(Rectangle())
                                .onTapGesture {
                                    onSelectIndex(idx)
                                }
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
