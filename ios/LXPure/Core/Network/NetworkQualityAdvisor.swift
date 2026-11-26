import Foundation
import Network

struct EffectiveQualityDecision: Equatable, Sendable {
    let quality: AudioQuality
    let downgradedForCellular: Bool
}

final class NetworkQualityAdvisor: @unchecked Sendable {
    private var monitor: NWPathMonitor?
    private let queue = DispatchQueue(label: "com.lxpure.network.monitor")
    private let lock = NSLock()

    private var _isConnected = true
    private var _isMetered = false

    init(
        startMonitoring: Bool = true,
        initialConnected: Bool = true,
        initialMetered: Bool = false
    ) {
        self._isConnected = initialConnected
        self._isMetered = initialMetered
        if startMonitoring {
            let pathMonitor = NWPathMonitor()
            pathMonitor.pathUpdateHandler = { [weak self] path in
                guard let self else { return }
                self.lock.lock()
                self._isConnected = (path.status == .satisfied)
                let usesWifiOrWired = path.usesInterfaceType(.wifi) || path.usesInterfaceType(.wiredEthernet)
                if usesWifiOrWired {
                    self._isMetered = path.isExpensive || path.isConstrained
                } else {
                    self._isMetered = path.usesInterfaceType(.cellular) || path.isExpensive || path.isConstrained
                }
                self.lock.unlock()
            }
            self.monitor = pathMonitor
            pathMonitor.start(queue: queue)
        }
    }

    deinit {
        monitor?.pathUpdateHandler = nil
        monitor?.cancel()
    }

    func isNetworkConnected() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return _isConnected
    }

    func isMeteredNetwork() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return _isMetered
    }

    func decideQuality(preferred: AudioQuality, cellularDataSaverEnabled: Bool) -> EffectiveQualityDecision {
        Self.decideQualityForNetwork(
            preferred: preferred,
            cellularDataSaverEnabled: cellularDataSaverEnabled,
            isMetered: isMeteredNetwork()
        )
    }

    static func decideQualityForNetwork(
        preferred: AudioQuality,
        cellularDataSaverEnabled: Bool,
        isMetered: Bool
    ) -> EffectiveQualityDecision {
        if cellularDataSaverEnabled && isMetered && preferred.priority > AudioQuality.q128k.priority {
            return EffectiveQualityDecision(quality: .q128k, downgradedForCellular: true)
        }
        return EffectiveQualityDecision(quality: preferred, downgradedForCellular: false)
    }
}
