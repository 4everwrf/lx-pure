import Foundation

enum PlaybackErrorKind: Equatable, Sendable {
    case transientNetwork
    case expiredOrDeniedUrl
    case streamOrSourceBroken
}

struct PlaybackRecoveryPlan: Equatable, Sendable {
    let kind: PlaybackErrorKind
    let excludeCurrentSource: Bool
    let excludeResolvedTrack: Bool
    let evictStreamCache: Bool
    let preferSameSourceFirst: Bool
    let preservePosition: Bool
    let backoffMs: Int64
}

enum PlaybackRecoveryPolicy {
    static let maxRuntimeRetries = 4
    static let maxConsecutiveAutoSkips = 3

    private static let transientUrlErrorCodes: Set<Int> = [
        NSURLErrorTimedOut,
        NSURLErrorCannotFindHost,
        NSURLErrorCannotConnectToHost,
        NSURLErrorNetworkConnectionLost,
        NSURLErrorDNSLookupFailed,
        NSURLErrorNotConnectedToInternet,
        NSURLErrorInternationalRoamingOff,
        NSURLErrorDataNotAllowed
    ]

    static func classify(error: Error?, httpStatusCode: Int? = nil) -> PlaybackErrorKind {
        if let code = httpStatusCode {
            return classifyError(httpStatusCode: code)
        }

        // Walk NSUnderlyingErrorKey / NSMultipleUnderlyingErrorsKey chains because AVFoundation
        // frequently wraps NSURLErrorDomain inside AVFoundationErrorDomain.
        if let rootErr = error as NSError? {
            var queue: [NSError] = [rootErr]
            var visited = 0
            while !queue.isEmpty && visited < 8 {
                let current = queue.removeFirst()
                visited += 1
                let kind = classifyError(
                    errorDomain: current.domain,
                    errorCode: current.code,
                    httpStatusCode: nil,
                    message: current.localizedDescription
                )
                if kind != .streamOrSourceBroken {
                    return kind
                }
                if let underlying = current.userInfo[NSUnderlyingErrorKey] as? NSError {
                    queue.append(underlying)
                }
                if #available(iOS 14.5, *),
                   let multiples = current.userInfo[NSMultipleUnderlyingErrorsKey] as? [NSError] {
                    queue.append(contentsOf: multiples)
                }
            }
        }

        let nsErr = error as NSError?
        return classifyError(
            errorDomain: nsErr?.domain,
            errorCode: nsErr?.code,
            httpStatusCode: httpStatusCode,
            message: error?.localizedDescription
        )
    }

    static func classifyError(
        errorDomain: String? = nil,
        errorCode: Int? = nil,
        httpStatusCode: Int? = nil,
        message: String? = nil
    ) -> PlaybackErrorKind {
        if let code = httpStatusCode {
            switch code {
            case 401, 403, 410: return .expiredOrDeniedUrl
            case 408, 429: return .transientNetwork
            default: return .streamOrSourceBroken
            }
        }

        if errorDomain == NSURLErrorDomain, let code = errorCode {
            if transientUrlErrorCodes.contains(code) {
                return .transientNetwork
            }
            if code == NSURLErrorNoPermissionsToReadFile || code == NSURLErrorUserAuthenticationRequired {
                return .expiredOrDeniedUrl
            }
        }

        let msg = (message ?? "").lowercased()
        if msg.contains("403") || msg.contains("401") || msg.contains("forbidden") || msg.contains("expired") {
            return .expiredOrDeniedUrl
        }
        if msg.contains("timed out") ||
            msg.contains("offline") ||
            msg.contains("network connection") ||
            msg.contains("not connected") ||
            msg.contains("find host") ||
            msg.contains("connection reset") {
            return .transientNetwork
        }

        return .streamOrSourceBroken
    }

    static func decideRecovery(
        kind: PlaybackErrorKind,
        retryAttemptIndex: Int,
        primaryTrackId: String,
        resolvedTrackId: String?,
        wasReady: Bool = true
    ) -> PlaybackRecoveryPlan {
        let isCrossPlatformAlternate = !(resolvedTrackId ?? "").isEmpty && resolvedTrackId != primaryTrackId
        switch kind {
        case .transientNetwork:
            let keepSameSource = wasReady ? (retryAttemptIndex <= 1) : (retryAttemptIndex == 0)
            if keepSameSource {
                return PlaybackRecoveryPlan(
                    kind: kind,
                    excludeCurrentSource: false,
                    excludeResolvedTrack: false,
                    evictStreamCache: false,
                    preferSameSourceFirst: true,
                    preservePosition: true,
                    backoffMs: retryAttemptIndex == 0 ? (wasReady ? 1_200 : 800) : 2_500
                )
            } else {
                return PlaybackRecoveryPlan(
                    kind: kind,
                    excludeCurrentSource: true,
                    excludeResolvedTrack: isCrossPlatformAlternate,
                    evictStreamCache: false,
                    preferSameSourceFirst: false,
                    preservePosition: true,
                    backoffMs: wasReady ? 1_500 : 1_000
                )
            }

        case .expiredOrDeniedUrl:
            if retryAttemptIndex == 0 && wasReady {
                return PlaybackRecoveryPlan(
                    kind: kind,
                    excludeCurrentSource: false,
                    excludeResolvedTrack: false,
                    evictStreamCache: false,
                    preferSameSourceFirst: true,
                    preservePosition: true,
                    backoffMs: 300
                )
            } else {
                return PlaybackRecoveryPlan(
                    kind: kind,
                    excludeCurrentSource: true,
                    excludeResolvedTrack: isCrossPlatformAlternate,
                    evictStreamCache: true,
                    preferSameSourceFirst: false,
                    preservePosition: true,
                    backoffMs: 200
                )
            }

        case .streamOrSourceBroken:
            return PlaybackRecoveryPlan(
                kind: kind,
                excludeCurrentSource: true,
                excludeResolvedTrack: isCrossPlatformAlternate,
                evictStreamCache: true,
                preferSameSourceFirst: false,
                preservePosition: true,
                backoffMs: 250
            )
        }
    }
}
