import Foundation
import ImageIO
import UIKit

/// Appends lightweight CDN resize parameters for NetEase/QQ covers so list thumbnails
/// stay fast and memory-efficient.
func optimizeCoverUrlForSize(_ url: String?, isThumbnail: Bool) -> String? {
    guard let url else { return nil }
    let trimmed = url.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !trimmed.isEmpty else { return nil }
    let lower = trimmed.lowercased()
    guard lower.hasPrefix("http://") || lower.hasPrefix("https://") else { return trimmed }

    if lower.contains("music.126.net") && !lower.contains("param=") {
        let dim = isThumbnail ? "200y200" : "640y640"
        let sep = trimmed.contains("?") ? "&" : "?"
        return "\(trimmed)\(sep)param=\(dim)"
    }
    if isThumbnail && lower.contains("y.gtimg.cn/music/photo_new/") {
        for token in ["T002R500x500M000", "T002R800x800M000", "T002R1200x1200M000"] {
            if trimmed.range(of: token, options: .caseInsensitive) != nil {
                return trimmed.replacingOccurrences(of: token, with: "T002R300x300M000", options: .caseInsensitive)
            }
        }
    }
    return trimmed
}

final class ImageMemoryCache: @unchecked Sendable {
    static let shared = ImageMemoryCache()

    private let cache: NSCache<NSString, UIImage> = {
        let c = NSCache<NSString, UIImage>()
        c.totalCostLimit = 24 * 1024 * 1024 // 24 MB memory cap matching Android Coil limit
        c.countLimit = 180
        return c
    }()

    private let imageUrlCache: URLCache
    private let session: URLSession
    private let inFlightLock = NSLock()
    private var inFlightTasks: [String: (id: UUID, task: Task<UIImage?, Never>)] = [:]
    private var inFlightWaiters: [String: Int] = [:]

    private init() {
        let urlCache = URLCache(
            memoryCapacity: 8 * 1024 * 1024,
            diskCapacity: 64 * 1024 * 1024,
            diskPath: "LXPureImageCache"
        )
        self.imageUrlCache = urlCache
        let config = URLSessionConfiguration.default
        config.urlCache = urlCache
        config.requestCachePolicy = .returnCacheDataElseLoad
        config.timeoutIntervalForRequest = 12
        self.session = URLSession(configuration: config)
    }

    func cachedImage(for key: String) -> UIImage? {
        cache.object(forKey: key as NSString)
    }

    func cachedImage(for urlString: String?, isThumbnail: Bool) -> UIImage? {
        guard let optimized = optimizeCoverUrlForSize(urlString, isThumbnail: isThumbnail) else {
            return nil
        }
        let cacheKey = isThumbnail ? "t:\(optimized)" : "f:\(optimized)"
        return cache.object(forKey: cacheKey as NSString)
    }

    /// Drops only in-memory decoded Bitmaps (called on RAM memory warnings without wiping disk cache).
    func clearMemory() {
        cache.removeAllObjects()
    }

    /// Drops both in-memory decoded Bitmaps and the 64 MB persistent disk cover cache (called by user "Clear Cache").
    func clearAll() {
        cache.removeAllObjects()
        imageUrlCache.removeAllCachedResponses()
    }

    func clear() {
        clearAll()
    }

    func diskCacheBytes() -> Int64 {
        Int64(imageUrlCache.currentDiskUsage)
    }

    func loadImage(from urlString: String?, isThumbnail: Bool) async -> UIImage? {
        guard let optimized = optimizeCoverUrlForSize(urlString, isThumbnail: isThumbnail) else {
            return nil
        }
        let cacheKey = isThumbnail ? "t:\(optimized)" : "f:\(optimized)"
        if let existing = cache.object(forKey: cacheKey as NSString) {
            return existing
        }

        let maxPixelSize = isThumbnail ? 320 : 1024
        if Task.isCancelled { return nil }

        // Coalesce concurrent requests with waiter-counted cancellation so rapid scrolling
        // cancels orphaned off-screen thumbnail downloads & decodes promptly.
        let (entryId, sharedTask): (UUID, Task<UIImage?, Never>) = {
            inFlightLock.lock()
            defer { inFlightLock.unlock() }
            if let running = inFlightTasks[cacheKey] {
                inFlightWaiters[cacheKey, default: 0] += 1
                return running
            }
            let newId = UUID()
            let created = Task.detached(priority: .utility) { [weak self] () -> UIImage? in
                guard let self else { return nil }
                defer {
                    self.inFlightLock.lock()
                    if self.inFlightTasks[cacheKey]?.id == newId {
                        self.inFlightTasks.removeValue(forKey: cacheKey)
                        self.inFlightWaiters.removeValue(forKey: cacheKey)
                    }
                    self.inFlightLock.unlock()
                }
                return await self.fetchAndDecode(optimized: optimized, cacheKey: cacheKey, maxPixelSize: maxPixelSize)
            }
            inFlightTasks[cacheKey] = (newId, created)
            inFlightWaiters[cacheKey] = 1
            return (newId, created)
        }()

        let releaseOnce = ImageWaiterReleaseFlag()
        let releaseWaiter: @Sendable () -> Void = { [weak self] in
            guard releaseOnce.claim(), let self else { return }
            let taskToCancel: Task<UIImage?, Never>? = {
                self.inFlightLock.lock()
                defer { self.inFlightLock.unlock() }
                guard self.inFlightTasks[cacheKey]?.id == entryId else { return nil }
                let remaining = max((self.inFlightWaiters[cacheKey] ?? 1) - 1, 0)
                if remaining == 0 {
                    self.inFlightWaiters.removeValue(forKey: cacheKey)
                    return self.inFlightTasks.removeValue(forKey: cacheKey)?.task
                } else {
                    self.inFlightWaiters[cacheKey] = remaining
                    return nil
                }
            }()
            taskToCancel?.cancel()
        }

        return await withTaskCancellationHandler {
            let result = await sharedTask.value
            releaseWaiter()
            return Task.isCancelled ? nil : result
        } onCancel: {
            releaseWaiter()
        }
    }

    private func fetchAndDecode(optimized: String, cacheKey: String, maxPixelSize: Int) async -> UIImage? {
        // Local file artwork support (with sandbox container-UUID rotation fallback for LocalArtwork)
        if optimized.hasPrefix("file://") || optimized.hasPrefix("/") {
            let fileUrl = Self.resolveLocalImageURL(optimized)
            if let data = try? Data(contentsOf: fileUrl),
               let img = Self.decodeDownsampledImage(from: data, maxPixelSize: maxPixelSize) {
                store(img, for: cacheKey)
                return img
            }
            return nil
        }

        guard let url = LxJsRuntime.parseLenientURL(optimized) else { return nil }
        var req = URLRequest(url: url, cachePolicy: .returnCacheDataElseLoad, timeoutInterval: 12)
        req.setValue("Mozilla/5.0 (Windows NT 10.0; Win64; x64)", forHTTPHeaderField: "User-Agent")

        guard let (data, resp) = try? await session.data(for: req),
              let httpResp = resp as? HTTPURLResponse,
              (200..<300).contains(httpResp.statusCode),
              let image = Self.decodeDownsampledImage(from: data, maxPixelSize: maxPixelSize) else {
            return nil
        }
        store(image, for: cacheKey)
        return image
    }

    private static func resolveLocalImageURL(_ raw: String) -> URL {
        let direct: URL
        if raw.hasPrefix("file://") {
            if let parsed = URL(string: raw) {
                direct = parsed
            } else {
                let stripped = String(raw.dropFirst("file://".count))
                direct = URL(fileURLWithPath: stripped.removingPercentEncoding ?? stripped)
            }
        } else {
            direct = URL(fileURLWithPath: raw)
        }
        if FileManager.default.fileExists(atPath: direct.path) {
            return direct
        }
        if direct.path.contains("/LocalArtwork/") {
            let reanchored = LocalMusicManager.sharedArtworkCacheDirectory
                .appendingPathComponent(direct.lastPathComponent)
            if FileManager.default.fileExists(atPath: reanchored.path) {
                return reanchored
            }
        }
        return direct
    }

    private static func decodeDownsampledImage(from data: Data, maxPixelSize: Int) -> UIImage? {
        let sourceOptions: [CFString: Any] = [kCGImageSourceShouldCache: false]
        guard let source = CGImageSourceCreateWithData(data as CFData, sourceOptions as CFDictionary) else {
            return UIImage(data: data)
        }
        let thumbOptions: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: maxPixelSize
        ]
        if let cgImage = CGImageSourceCreateThumbnailAtIndex(source, 0, thumbOptions as CFDictionary) {
            return UIImage(cgImage: cgImage)
        }
        return UIImage(data: data)
    }

    private func store(_ image: UIImage, for key: String) {
        let pixelW = image.cgImage?.width ?? Int(image.size.width * image.scale)
        let pixelH = image.cgImage?.height ?? Int(image.size.height * image.scale)
        let cost = max(pixelW * pixelH * 4, 1)
        cache.setObject(image, forKey: key as NSString, cost: cost)
    }
}

private final class ImageWaiterReleaseFlag: @unchecked Sendable {
    private let lock = NSLock()
    private var released = false

    func claim() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        if released { return false }
        released = true
        return true
    }
}
