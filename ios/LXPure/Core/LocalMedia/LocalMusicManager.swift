import AVFoundation
import Combine
import Foundation

@MainActor
final class LocalMusicManager: ObservableObject {
    @Published private(set) var localTracks: [Track] = []
    @Published private(set) var isScanning: Bool = false

    private var scanGeneration: UInt64 = 0
    private var activeScanCount: Int = 0

    private nonisolated static let supportedExtensions: Set<String> = [
        "mp3", "flac", "m4a", "wav", "aac", "alac", "aiff", "caf", "mp4"
    ]

    nonisolated static var sharedLocalMusicDirectory: URL {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        let dir = docs.appendingPathComponent("LocalMusic", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    nonisolated static var sharedArtworkCacheDirectory: URL {
        let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        let dir = caches.appendingPathComponent("LocalArtwork", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    var localMusicDirectory: URL { Self.sharedLocalMusicDirectory }
    private var artworkCacheDirectory: URL { Self.sharedArtworkCacheDirectory }

    /// Resolves either a container-independent relative subpath under `Documents/LocalMusic`
    /// or a legacy absolute/file:// path whose sandbox container UUID rotated after an app update.
    nonisolated static func resolveStoredLocalFileURL(_ stored: String) -> URL {
        let musicDir = sharedLocalMusicDirectory
        let trimmed = stored.trimmingCharacters(in: .whitespacesAndNewlines)
        if !trimmed.hasPrefix("/") && !trimmed.hasPrefix("file://") {
            return musicDir.appendingPathComponent(trimmed)
        }
        let direct: URL
        if trimmed.hasPrefix("file://") {
            if let parsed = URL(string: trimmed) {
                direct = parsed
            } else {
                let rawPath = String(trimmed.dropFirst("file://".count))
                direct = URL(fileURLWithPath: rawPath.removingPercentEncoding ?? rawPath)
            }
        } else {
            direct = URL(fileURLWithPath: trimmed)
        }
        if FileManager.default.fileExists(atPath: direct.path) {
            return direct
        }
        // Re-anchor across rotated sandbox container UUID
        if let marker = direct.path.range(of: "/Documents/LocalMusic/") {
            let relative = String(direct.path[marker.upperBound...])
            if !relative.isEmpty {
                let reanchored = musicDir.appendingPathComponent(relative)
                if FileManager.default.fileExists(atPath: reanchored.path) {
                    return reanchored
                }
            }
        }
        let fallback = musicDir.appendingPathComponent(direct.lastPathComponent)
        return FileManager.default.fileExists(atPath: fallback.path) ? fallback : direct
    }

    init() {
        Task {
            await scan()
        }
    }

    /// Copies user-selected audio files from the iOS Files picker into `Documents/LocalMusic` off @MainActor
    /// using NSFileCoordinator (safe for iCloud Drive / FileProvider URLs), then rescans.
    func importFiles(from urls: [URL]) async -> Int {
        let destDir = localMusicDirectory
        let importedCount = await Task.detached(priority: .userInitiated) {
            let fm = FileManager.default
            let standardizedDestPrefix = destDir.standardizedFileURL.path + "/"
            var count = 0

            for url in urls {
                let accessed = url.startAccessingSecurityScopedResource()
                defer {
                    if accessed { url.stopAccessingSecurityScopedResource() }
                }
                let ext = url.pathExtension.lowercased()
                guard Self.supportedExtensions.contains(ext) else { continue }

                // Skip files already residing inside Documents/LocalMusic
                if url.standardizedFileURL.path.hasPrefix(standardizedDestPrefix) {
                    count += 1
                    continue
                }

                // Skip exact duplicate (same filename & identical byte size) already imported
                let directDest = destDir.appendingPathComponent(url.lastPathComponent)
                if fm.fileExists(atPath: directDest.path),
                   let srcSize = (try? fm.attributesOfItem(atPath: url.path)[.size] as? Int64),
                   let dstSize = (try? fm.attributesOfItem(atPath: directDest.path)[.size] as? Int64),
                   srcSize > 0 && srcSize == dstSize {
                    continue
                }

                let destUrl = Self.uniqueDestinationURL(for: url.lastPathComponent, in: destDir)
                var coordError: NSError?
                var copied = false
                NSFileCoordinator().coordinate(readingItemAt: url, options: [], error: &coordError) { readableUrl in
                    if (try? fm.copyItem(at: readableUrl, to: destUrl)) != nil {
                        copied = true
                    }
                }
                if !copied && coordError != nil {
                    copied = (try? fm.copyItem(at: url, to: destUrl)) != nil
                }
                if copied {
                    count += 1
                }
            }
            return count
        }.value

        _ = await scan()
        return importedCount
    }

    @discardableResult
    func scan() async -> [Track] {
        scanGeneration &+= 1
        let gen = scanGeneration
        activeScanCount += 1
        isScanning = true
        defer {
            activeScanCount = max(activeScanCount - 1, 0)
            if activeScanCount == 0 {
                isScanning = false
            }
        }

        let musicDir = localMusicDirectory
        let artDir = artworkCacheDirectory

        let scanned: [Track] = await Task.detached(priority: .utility) {
            let fm = FileManager.default
            guard let enumerator = fm.enumerator(
                at: musicDir,
                includingPropertiesForKeys: [.isRegularFileKey, .fileSizeKey, .contentModificationDateKey],
                options: [.skipsHiddenFiles]
            ) else {
                return []
            }

            var audioFiles: [URL] = []
            while let next = enumerator.nextObject() as? URL {
                guard let values = try? next.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey]),
                      values.isRegularFile == true,
                      (values.fileSize ?? 0) > 0 else {
                    continue
                }
                let ext = next.pathExtension.lowercased()
                if Self.supportedExtensions.contains(ext) {
                    audioFiles.append(next)
                }
            }

            var tracks: [Track] = []
            for fileUrl in audioFiles {
                if let track = await Self.extractMetadata(from: fileUrl, musicDir: musicDir, artworkDir: artDir) {
                    tracks.append(track)
                }
            }
            return tracks.sorted { $0.title.localizedStandardCompare($1.title) == .orderedAscending }
        }.value

        // Never let an older slower scan overwrite a newer scan's results
        if gen == scanGeneration {
            localTracks = scanned
            return scanned
        }
        return localTracks
    }

    func deleteLocalTrack(_ track: Track) async {
        guard let uri = track.localUri, !uri.isEmpty else { return }
        let fileUrl = Self.resolveStoredLocalFileURL(uri)
        let artUrl = artworkCacheDirectory.appendingPathComponent("\(track.songmid).jpg")

        await Task.detached(priority: .utility) {
            let fm = FileManager.default
            try? fm.removeItem(at: fileUrl)
            try? fm.removeItem(at: artUrl)
        }.value

        // Increment generation so any in-flight earlier scan cannot resurrect the deleted track
        scanGeneration &+= 1
        localTracks.removeAll { $0.id == track.id }
    }

    private nonisolated static func uniqueDestinationURL(for filename: String, in dir: URL) -> URL {
        let base = (filename as NSString).deletingPathExtension
        let ext = (filename as NSString).pathExtension
        var candidate = dir.appendingPathComponent(filename)
        var counter = 1
        while FileManager.default.fileExists(atPath: candidate.path) {
            let name = ext.isEmpty ? "\(base) (\(counter))" : "\(base) (\(counter)).\(ext)"
            candidate = dir.appendingPathComponent(name)
            counter += 1
        }
        return candidate
    }

    nonisolated static func relativeSubpath(of fileUrl: URL, under rootDir: URL) -> String {
        let rootPath = rootDir.standardizedFileURL.path
        let filePath = fileUrl.standardizedFileURL.path
        let prefix = rootPath.hasSuffix("/") ? rootPath : rootPath + "/"
        if filePath.hasPrefix(prefix) {
            return String(filePath.dropFirst(prefix.count))
        }
        return fileUrl.lastPathComponent
    }

    private nonisolated static func extractMetadata(from url: URL, musicDir: URL, artworkDir: URL) async -> Track? {
        let fm = FileManager.default
        guard let attrs = try? fm.attributesOfItem(atPath: url.path),
              let fileSize = attrs[.size] as? Int64,
              fileSize > 0 else {
            return nil
        }

        let asset = AVURLAsset(url: url)
        let isPlayable = (try? await asset.load(.isPlayable)) ?? false
        let audioTracks = (try? await asset.loadTracks(withMediaType: .audio)) ?? []
        guard isPlayable && !audioTracks.isEmpty else {
            return nil
        }

        let durationSeconds: Double = (try? await asset.load(.duration).seconds) ?? 0
        let clampedSeconds = (durationSeconds.isFinite && durationSeconds > 0)
            ? min(durationSeconds, 86_400.0 * 30.0)
            : 0.0
        let durationMs = Int64(clampedSeconds * 1000.0)

        let metadataItems = (try? await asset.load(.commonMetadata)) ?? []
        var title = url.deletingPathExtension().lastPathComponent
        var collectedArtists: [String] = []
        var album = ""
        var coverPath: String?

        // Hash relative path under Documents/LocalMusic so files in different subdirectories never collide
        let relPath = relativeSubpath(of: url, under: musicDir)
        let hashId = LxCrypto.md5Hex(relPath)
        let audioMtime = attrs[.modificationDate] as? Date

        for item in metadataItems {
            guard let key = item.commonKey else { continue }
            switch key {
            case .commonKeyTitle:
                if let val = try? await item.load(.stringValue), !val.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    title = val
                }
            case .commonKeyArtist:
                if let val = try? await item.load(.stringValue) {
                    let trimmed = val.trimmingCharacters(in: .whitespacesAndNewlines)
                    if !trimmed.isEmpty && !ArtistParser.isUnknownPlaceholder(trimmed) {
                        collectedArtists.append(trimmed)
                    }
                }
            case .commonKeyAlbumName:
                if let val = try? await item.load(.stringValue) {
                    let trimmed = val.trimmingCharacters(in: .whitespacesAndNewlines)
                    album = trimmed.caseInsensitiveCompare("<unknown>") == .orderedSame ? "" : trimmed
                }
            case .commonKeyArtwork:
                if let data = try? await item.load(.dataValue), !data.isEmpty {
                    let artUrl = artworkDir.appendingPathComponent("\(hashId).jpg")
                    let artMtime = (try? fm.attributesOfItem(atPath: artUrl.path)[.modificationDate] as? Date)
                    let needsWrite = !fm.fileExists(atPath: artUrl.path) ||
                        (audioMtime != nil && (artMtime == nil || audioMtime! > artMtime!))
                    if needsWrite {
                        try? data.write(to: artUrl, options: .atomic)
                    }
                    coverPath = artUrl.absoluteString
                }
            default:
                break
            }
        }

        let resolvedArtists: [String]
        if collectedArtists.count > 1 {
            resolvedArtists = ArtistParser.extractArtists(rawArtist: "", structuredArtists: collectedArtists)
        } else if let single = collectedArtists.first {
            resolvedArtists = ArtistParser.parseRawString(single)
        } else {
            resolvedArtists = []
        }
        let artistDisplay = resolvedArtists.isEmpty ? "未知歌手" : resolvedArtists.joined(separator: " / ")

        return Track(
            id: "local_\(hashId)",
            title: title,
            artist: artistDisplay,
            album: album,
            source: .local,
            songmid: hashId,
            durationMs: durationMs,
            coverUrl: coverPath,
            localUri: relPath,
            artistNames: resolvedArtists
        )
    }
}
