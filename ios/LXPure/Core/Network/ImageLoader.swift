import Foundation
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
    if isThumbnail && lower.contains("y.gtimg.cn/music/photo_new/t002r500x500m000") {
        return trimmed.replacingOccurrences(
            of: "T002R500x500M000",
            with: "T002R300x300M000",
            options: .caseInsensitive
        )
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

    private let session: URLSession

    private init() {
        let config = URLSessionConfiguration.default
        config.urlCache = URLCache(
            memoryCapacity: 8 * 1024 * 1024,
            diskCapacity: 64 * 1024 * 1024,
            diskPath: "LXPureImageCache"
        )
        config.requestCachePolicy = .returnCacheDataElseLoad
        config.timeoutIntervalForRequest = 12
        self.session = URLSession(configuration: config)
    }

    func cachedImage(for key: String) -> UIImage? {
        cache.object(forKey: key as NSString)
    }

    func clear() {
        cache.removeAllObjects()
        session.configuration.urlCache?.removeAllCachedResponses()
    }

    func loadImage(from urlString: String?, isThumbnail: Bool) async -> UIImage? {
        guard let optimized = optimizeCoverUrlForSize(urlString, isThumbnail: isThumbnail) else {
            return nil
        }
        if let existing = cache.object(forKey: optimized as NSString) {
            return existing
        }

        // Local file artwork support
        if optimized.hasPrefix("file://"), let fileUrl = URL(string: optimized) {
            if let data = try? Data(contentsOf: fileUrl), let img = UIImage(data: data) {
                store(img, for: optimized)
                return img
            }
            return nil
        }
        if optimized.hasPrefix("/") {
            if let img = UIImage(contentsOfFile: optimized) {
                store(img, for: optimized)
                return img
            }
            return nil
        }

        guard let url = URL(string: optimized) else { return nil }
        var req = URLRequest(url: url, cachePolicy: .returnCacheDataElseLoad, timeoutInterval: 12)
        req.setValue("Mozilla/5.0 (Windows NT 10.0; Win64; x64)", forHTTPHeaderField: "User-Agent")

        guard let (data, resp) = try? await session.data(for: req),
              let httpResp = resp as? HTTPURLResponse,
              (200..<300).contains(httpResp.statusCode),
              let image = UIImage(data: data) else {
            return nil
        }
        store(image, for: optimized)
        return image
    }

    private func store(_ image: UIImage, for key: String) {
        let cost = Int(image.size.width * image.size.height * image.scale * image.scale * 4)
        cache.setObject(image, forKey: key as NSString, cost: max(cost, 1))
    }
}
