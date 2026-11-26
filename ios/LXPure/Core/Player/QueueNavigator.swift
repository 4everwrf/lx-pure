import Foundation

enum QueueNavigationOutcome: Equatable, Sendable {
    case switchTo(Int)
    case restartCurrent(message: String?)
    case stayAtBoundary(message: String)
    case stopAtEnd(message: String?)
    case noOp
}

private struct BoxedRandomNumberGenerator: RandomNumberGenerator {
    private var base: any RandomNumberGenerator

    init(_ base: any RandomNumberGenerator) {
        self.base = base
    }

    mutating func next() -> UInt64 {
        base.next()
    }
}

/// Pure playback queue navigation state machine shared by FloatingMiniPlayer, FullPlayerView,
/// lock-screen remote commands, and gapless preloading.
final class QueueNavigator {
    static let previousRestartThresholdMs: Int64 = 5_000
    static let maxShuffleHistoryBehind = 200

    private var shuffleTimeline: [Int] = []
    private var shuffleCursor: Int = -1
    private var rng: BoxedRandomNumberGenerator

    init(rng: any RandomNumberGenerator = SystemRandomNumberGenerator()) {
        self.rng = BoxedRandomNumberGenerator(rng)
    }

    var shuffleHistoryDepth: Int {
        shuffleCursor > 0 ? shuffleCursor : 0
    }

    func reset() {
        shuffleTimeline.removeAll()
        shuffleCursor = -1
    }

    func onQueueReplaced(queueSize: Int, startIndex: Int, mode: RepeatMode) {
        reset()
        if mode == .shuffle && queueSize > 0 && startIndex >= 0 && startIndex < queueSize {
            buildFreshShuffleTimeline(queueSize: queueSize, startIndex: startIndex)
        }
    }

    func onModeChanged(queueSize: Int, currentIndex: Int, newMode: RepeatMode) {
        if newMode == .shuffle && queueSize > 0 && currentIndex >= 0 && currentIndex < queueSize {
            if isShuffleTimelineValid(queueSize: queueSize) && shuffleTimeline[shuffleCursor] == currentIndex {
                return
            }
            buildFreshShuffleTimeline(queueSize: queueSize, startIndex: currentIndex)
        } else {
            reset()
        }
    }

    func onManualSelectIndex(queueSize: Int, targetIndex: Int, mode: RepeatMode) {
        guard mode == .shuffle else { return }
        guard queueSize > 0 && targetIndex >= 0 && targetIndex < queueSize else {
            reset()
            return
        }
        guard isShuffleTimelineValid(queueSize: queueSize) else {
            buildFreshShuffleTimeline(queueSize: queueSize, startIndex: targetIndex)
            return
        }
        if shuffleTimeline[shuffleCursor] == targetIndex { return }

        var historyPrefix = Array(shuffleTimeline[0...shuffleCursor])
        historyPrefix.append(targetIndex)
        let upcoming = buildShuffledCycle(queueSize: queueSize, excludeIndex: targetIndex)
        shuffleTimeline = historyPrefix + upcoming
        shuffleCursor = historyPrefix.count - 1
        trimHistoryIfNeeded()
    }

    func onPromotedPreloadedNext(queueSize: Int, promotedIndex: Int, mode: RepeatMode) {
        guard mode == .shuffle else { return }
        guard queueSize > 0 && promotedIndex >= 0 && promotedIndex < queueSize else {
            reset()
            return
        }
        if isShuffleTimelineValid(queueSize: queueSize),
           shuffleCursor + 1 < shuffleTimeline.count,
           shuffleTimeline[shuffleCursor + 1] == promotedIndex {
            shuffleCursor += 1
            trimHistoryIfNeeded()
        } else {
            onManualSelectIndex(queueSize: queueSize, targetIndex: promotedIndex, mode: mode)
        }
    }

    func onTrackInsertedAsPlayNext(
        oldRemovedIndex: Int?,
        insertedIndex: Int,
        newCurrentIndex: Int,
        newQueueSize: Int,
        mode: RepeatMode
    ) {
        guard mode == .shuffle else { return }
        guard newQueueSize > 0,
              newCurrentIndex >= 0 && newCurrentIndex < newQueueSize,
              insertedIndex >= 0 && insertedIndex < newQueueSize else {
            reset()
            return
        }
        if shuffleTimeline.isEmpty ||
            shuffleCursor < 0 ||
            shuffleCursor >= shuffleTimeline.count ||
            (oldRemovedIndex != nil && (oldRemovedIndex! < 0 || oldRemovedIndex! >= newQueueSize)) {
            buildFreshShuffleTimeline(queueSize: newQueueSize, startIndex: newCurrentIndex)
            placePlayNextInShuffle(insertedIndex: insertedIndex)
            return
        }

        var remapped: [Int] = []
        remapped.reserveCapacity(shuffleTimeline.count + 1)
        var newCursor = shuffleCursor
        for i in 0..<shuffleTimeline.count {
            var idx = shuffleTimeline[i]
            if let removed = oldRemovedIndex {
                if idx == removed {
                    if i < shuffleCursor {
                        remapped.append(insertedIndex)
                    } else if i == shuffleCursor {
                        newCursor -= 1
                    }
                    continue
                }
                if idx > removed { idx -= 1 }
            }
            if idx >= insertedIndex { idx += 1 }
            if idx >= 0 && idx < newQueueSize {
                remapped.append(idx)
            } else if i <= shuffleCursor {
                newCursor -= 1
            }
        }

        shuffleTimeline = remapped
        shuffleCursor = newCursor
        collapseAdjacentDuplicates()

        if shuffleCursor < 0 || shuffleCursor >= shuffleTimeline.count || shuffleTimeline[shuffleCursor] != newCurrentIndex {
            buildFreshShuffleTimeline(queueSize: newQueueSize, startIndex: newCurrentIndex)
        }
        placePlayNextInShuffle(insertedIndex: insertedIndex)
    }

    func onTrackAppendedToQueue(
        appendedIndex: Int,
        currentIndex: Int,
        newQueueSize: Int,
        mode: RepeatMode
    ) {
        guard mode == .shuffle else { return }
        guard newQueueSize > 0,
              currentIndex >= 0 && currentIndex < newQueueSize,
              appendedIndex >= 0 && appendedIndex < newQueueSize else {
            reset()
            return
        }
        if newQueueSize <= 1 ||
            appendedIndex == currentIndex ||
            !isShuffleTimelineValid(queueSize: newQueueSize) ||
            shuffleTimeline[shuffleCursor] != currentIndex ||
            shuffleTimeline.contains(appendedIndex) {
            buildFreshShuffleTimeline(queueSize: newQueueSize, startIndex: currentIndex)
            return
        }
        let insertPos: Int
        if shuffleCursor + 1 >= shuffleTimeline.count {
            insertPos = shuffleTimeline.count
        } else {
            insertPos = Int.random(in: (shuffleCursor + 1)...shuffleTimeline.count, using: &rng)
        }
        shuffleTimeline.insert(appendedIndex, at: insertPos)
    }

    func onTrackRemovedFromQueue(
        removedIndex: Int,
        newCurrentIndex: Int,
        newQueueSize: Int,
        mode: RepeatMode
    ) {
        guard newQueueSize > 0 && newCurrentIndex >= 0 && newCurrentIndex < newQueueSize else {
            reset()
            return
        }
        guard mode == .shuffle else { return }
        if removedIndex < 0 || removedIndex > newQueueSize ||
            shuffleTimeline.isEmpty ||
            shuffleCursor < 0 ||
            shuffleCursor >= shuffleTimeline.count {
            buildFreshShuffleTimeline(queueSize: newQueueSize, startIndex: newCurrentIndex)
            return
        }

        var remapped: [Int] = []
        remapped.reserveCapacity(shuffleTimeline.count)
        var newCursor = shuffleCursor
        for i in 0..<shuffleTimeline.count {
            let idx = shuffleTimeline[i]
            if idx == removedIndex {
                if i <= shuffleCursor { newCursor -= 1 }
            } else {
                let mapped = idx > removedIndex ? (idx - 1) : idx
                if mapped >= 0 && mapped < newQueueSize {
                    remapped.append(mapped)
                } else if i <= shuffleCursor {
                    newCursor -= 1
                }
            }
        }

        shuffleTimeline = remapped
        shuffleCursor = newCursor
        collapseAdjacentDuplicates()

        if shuffleTimeline.isEmpty || shuffleCursor < 0 {
            buildFreshShuffleTimeline(queueSize: newQueueSize, startIndex: newCurrentIndex)
            return
        }

        if shuffleCursor >= shuffleTimeline.count {
            shuffleCursor = shuffleTimeline.count - 1
        }
        if shuffleTimeline[shuffleCursor] != newCurrentIndex {
            var preservedHistory: [Int] = []
            preservedHistory.reserveCapacity(shuffleCursor + 2)
            for i in 0...shuffleCursor {
                preservedHistory.append(shuffleTimeline[i])
            }
            if preservedHistory.last != newCurrentIndex {
                preservedHistory.append(newCurrentIndex)
            }
            let upcoming = buildShuffledCycle(queueSize: newQueueSize, excludeIndex: newCurrentIndex)
            shuffleTimeline = preservedHistory + upcoming
            shuffleCursor = preservedHistory.count - 1
            collapseAdjacentDuplicates()
            trimHistoryIfNeeded()
        }
    }

    func canSkipPrevious(queueSize: Int, currentIndex: Int, mode: RepeatMode) -> Bool {
        guard queueSize > 1 && currentIndex >= 0 && currentIndex < queueSize else { return false }
        switch mode {
        case .sequential:
            return currentIndex > 0
        case .shuffle:
            return isShuffleTimelineValid(queueSize: queueSize) &&
                shuffleTimeline[shuffleCursor] == currentIndex &&
                shuffleCursor > 0
        case .listLoop, .singleLoop:
            return true
        }
    }

    func canSkipNext(queueSize: Int, currentIndex: Int, mode: RepeatMode) -> Bool {
        guard queueSize > 1 && currentIndex >= 0 && currentIndex < queueSize else { return false }
        switch mode {
        case .sequential:
            return currentIndex < queueSize - 1
        case .shuffle, .listLoop, .singleLoop:
            return true
        }
    }

    func willAutoAdvanceOnEnd(queueSize: Int, currentIndex: Int, mode: RepeatMode) -> Bool {
        guard queueSize > 0 && currentIndex >= 0 && currentIndex < queueSize else { return false }
        switch mode {
        case .sequential:
            return currentIndex < queueSize - 1
        case .listLoop, .shuffle, .singleLoop:
            return true
        }
    }

    func peekNextIndexForAutoAdvance(queueSize: Int, currentIndex: Int, mode: RepeatMode) -> Int? {
        guard queueSize > 1 && currentIndex >= 0 && currentIndex < queueSize else { return nil }
        switch mode {
        case .singleLoop:
            return nil
        case .sequential:
            return (currentIndex + 1 < queueSize) ? (currentIndex + 1) : nil
        case .listLoop:
            return (currentIndex + 1) % queueSize
        case .shuffle:
            ensureUpcomingShuffleItem(queueSize: queueSize, currentIndex: currentIndex)
            let nextPos = shuffleCursor + 1
            return nextPos < shuffleTimeline.count ? shuffleTimeline[nextPos] : nil
        }
    }

    func navigateNext(
        queueSize: Int,
        currentIndex: Int,
        mode: RepeatMode,
        isAutoAdvance: Bool
    ) -> QueueNavigationOutcome {
        guard queueSize > 0 && currentIndex >= 0 && currentIndex < queueSize else {
            return .noOp
        }

        if queueSize == 1 {
            switch mode {
            case .sequential:
                return isAutoAdvance
                    ? .stopAtEnd(message: "顺序播放已结束")
                    : .stayAtBoundary(message: "已是最后一首")
            case .singleLoop, .listLoop, .shuffle:
                return isAutoAdvance
                    ? .restartCurrent(message: nil)
                    : .restartCurrent(message: "当前队列仅 1 首，已重新播放")
            }
        }

        switch mode {
        case .sequential:
            if currentIndex + 1 < queueSize {
                return .switchTo(currentIndex + 1)
            } else if isAutoAdvance {
                return .stopAtEnd(message: "顺序播放已结束")
            } else {
                return .stayAtBoundary(message: "已是最后一首")
            }
        case .listLoop:
            return .switchTo((currentIndex + 1) % queueSize)
        case .singleLoop:
            return isAutoAdvance
                ? .restartCurrent(message: nil)
                : .switchTo((currentIndex + 1) % queueSize)
        case .shuffle:
            ensureUpcomingShuffleItem(queueSize: queueSize, currentIndex: currentIndex)
            if shuffleCursor + 1 < shuffleTimeline.count {
                shuffleCursor += 1
                let target = shuffleTimeline[shuffleCursor]
                trimHistoryIfNeeded()
                return .switchTo(target)
            } else {
                return .restartCurrent(message: nil)
            }
        }
    }

    func navigatePrevious(
        queueSize: Int,
        currentIndex: Int,
        mode: RepeatMode,
        positionMs: Int64
    ) -> QueueNavigationOutcome {
        guard queueSize > 0 && currentIndex >= 0 && currentIndex < queueSize else {
            return .noOp
        }

        if positionMs > Self.previousRestartThresholdMs {
            return .restartCurrent(message: nil)
        }

        if queueSize == 1 {
            let msg = mode == .sequential ? "已是第一首，已重新播放" : "当前队列仅 1 首，已重新播放"
            return .restartCurrent(message: msg)
        }

        switch mode {
        case .sequential:
            if currentIndex > 0 {
                return .switchTo(currentIndex - 1)
            } else {
                return .restartCurrent(message: "已是第一首，已重新播放")
            }
        case .listLoop, .singleLoop:
            let prevIdx = currentIndex <= 0 ? (queueSize - 1) : (currentIndex - 1)
            return .switchTo(prevIdx)
        case .shuffle:
            ensureShuffleTimeline(queueSize: queueSize, currentIndex: currentIndex)
            if shuffleCursor > 0 {
                shuffleCursor -= 1
                return .switchTo(shuffleTimeline[shuffleCursor])
            } else {
                return .restartCurrent(message: "已回到随机播放起点，已重新播放")
            }
        }
    }

    private func isShuffleTimelineValid(queueSize: Int) -> Bool {
        guard !shuffleTimeline.isEmpty && shuffleCursor >= 0 && shuffleCursor < shuffleTimeline.count else {
            return false
        }
        return shuffleTimeline.allSatisfy { $0 >= 0 && $0 < queueSize }
    }

    private func ensureShuffleTimeline(queueSize: Int, currentIndex: Int) {
        if isShuffleTimelineValid(queueSize: queueSize) && shuffleTimeline[shuffleCursor] == currentIndex {
            return
        }
        buildFreshShuffleTimeline(queueSize: queueSize, startIndex: currentIndex)
    }

    private func ensureUpcomingShuffleItem(queueSize: Int, currentIndex: Int) {
        ensureShuffleTimeline(queueSize: queueSize, currentIndex: currentIndex)
        guard queueSize > 1 else { return }
        if shuffleCursor + 1 >= shuffleTimeline.count {
            let nextCycle = buildShuffledCycle(queueSize: queueSize, excludeFirst: currentIndex)
            shuffleTimeline.append(contentsOf: nextCycle)
            trimHistoryIfNeeded()
        }
    }

    private func buildFreshShuffleTimeline(queueSize: Int, startIndex: Int) {
        shuffleTimeline = [startIndex]
        if queueSize > 1 {
            shuffleTimeline.append(contentsOf: buildShuffledCycle(queueSize: queueSize, excludeIndex: startIndex))
        }
        shuffleCursor = 0
    }

    private func buildShuffledCycle(
        queueSize: Int,
        excludeIndex: Int? = nil,
        excludeFirst: Int? = nil
    ) -> [Int] {
        var list: [Int] = []
        list.reserveCapacity(queueSize)
        for i in 0..<queueSize where i != excludeIndex {
            list.append(i)
        }
        if list.count > 1 {
            for i in stride(from: list.count - 1, through: 1, by: -1) {
                let j = Int.random(in: 0...i, using: &rng)
                if i != j {
                    list.swapAt(i, j)
                }
            }
        }
        if let excludeFirst, list.count > 1, list[0] == excludeFirst {
            let swapIdx = Int.random(in: 1..<list.count, using: &rng)
            list.swapAt(0, swapIdx)
        }
        return list
    }

    private func placePlayNextInShuffle(insertedIndex: Int) {
        guard shuffleCursor >= 0 && shuffleCursor < shuffleTimeline.count else { return }
        var i = shuffleTimeline.count - 1
        while i > shuffleCursor {
            if shuffleTimeline[i] == insertedIndex {
                shuffleTimeline.remove(at: i)
            }
            i -= 1
        }
        if shuffleTimeline[shuffleCursor] != insertedIndex {
            shuffleTimeline.insert(insertedIndex, at: shuffleCursor + 1)
        }
    }

    private func collapseAdjacentDuplicates() {
        guard shuffleTimeline.count > 1 else { return }
        var collapsed: [Int] = []
        collapsed.reserveCapacity(shuffleTimeline.count)
        var newCursor = shuffleCursor
        for i in 0..<shuffleTimeline.count {
            let v = shuffleTimeline[i]
            if let last = collapsed.last, last == v {
                if i <= shuffleCursor { newCursor -= 1 }
            } else {
                collapsed.append(v)
            }
        }
        shuffleTimeline = collapsed
        shuffleCursor = min(max(newCursor, -1), shuffleTimeline.count - 1)
    }

    private func trimHistoryIfNeeded() {
        if shuffleCursor > Self.maxShuffleHistoryBehind {
            let trimCount = shuffleCursor - Self.maxShuffleHistoryBehind
            shuffleTimeline.removeFirst(trimCount)
            shuffleCursor -= trimCount
        }
    }
}
