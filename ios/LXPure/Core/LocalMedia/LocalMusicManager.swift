import AVFoundation
import Combine
import Foundation

@MainActor
final class LocalMusicManager: ObservableObject {
    @Published private(set) var localTracks: [Track] = []
    @Published private(set) var isScanning: Bool = false

    private nonisolated static let supportedExtensions: Set<String> = [
        "mp3", "flac", "m4a", "wav", "aac", "alac", "aiff", "caf", "mp4"
    ]

    var localMusicDirectory: URL {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        let dir = docs.appendingPathComponent("LocalMusic", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    private var artworkCacheDirectory: URL {
        let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        let dir = caches.appendingPathComponent("LocalArtwork", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    init() {
        Task {
            await scan()
        }
    }

    /// Copies user-selected audio files from the iOS Files picker into `Documents/LocalMusic` and rescans.
    func importFiles(from urls: [URL]) async -> Int {
        let destDir = localMusicDirectory
        var importedCount = 0

        for url in urls {
            let accessed = url.startAccessingSecurityScopedResource()
            defer {
                if accessed { url.stopAccessingSecurityScopedResource() }
            }
            let ext = url.pathExtension.lowercased()
            guard Self.supportedExtensions.contains(ext) else { continue }

            let destUrl = uniqueDestinationURL(for: url.lastPathComponent, in: destDir)
            if (try? FileManager.default.copyItem(at: url, to: destUrl)) != nil {
                importedCount += 1
            }
        }

        _ = await scan()
        return importedCount
    }

    @discardableResult
    func scan() async -> [Track] {
        isScanning = true
        defer { isScanning = false }

        let musicDir = localMusicDirectory
        let artDir = artworkCacheDirectory

        let scanned: [Track] = await Task.detached(priority: .utility) {
            let fm = FileManager.default
            guard let enumerator = fm.enumerator(
                at: musicDir,
                includingPropertiesForKeys: [.isRegularFileKey],
                options: [.skipsHiddenFiles]
            ) else {
                return []
            }

            var audioFiles: [URL] = []
            while let next = enumerator.nextObject() as? URL {
                let ext = next.pathExtension.lowercased()
                if Self.supportedExtensions.contains(ext) {
                    audioFiles.append(next)
                }
            }

            var tracks: [Track] = []
            for fileUrl in audioFiles {
                if let track = await Self.extractMetadata(from: fileUrl, artworkDir: artDir) {
                    tracks.append(track)
                }
            }
            return tracks.sorted { $0.title.localizedStandardCompare($1.title) == .orderedAscending }
        }.value

        localTracks = scanned
        return scanned
    }

    func deleteLocalTrack(_ track: Track) async {
        guard let uri = track.localUri else { return }
        let url = URL(fileURLWithPath: uri)
        try? FileManager.default.removeItem(at: url)
        _ = await scan()
    }

    private func uniqueDestinationURL(for filename: String, in dir: URL) -> URL {
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

    private nonisolated static func extractMetadata(from url: URL, artworkDir: URL) async -> Track? {
        let asset = AVURLAsset(url: url)
        let durationSeconds: Double = (try? await asset.load(.duration).seconds) ?? 0
        let durationMs = durationSeconds.isFinite && durationSeconds > 0 ? Int64(durationSeconds * 1000.0) : 0

        let metadataItems = (try? await asset.load(.commonMetadata)) ?? []
        var title = url.deletingPathExtension().lastPathComponent
        var artist = "本地音频"
        var album = ""
        var coverPath: String?

        let hashId = LxCrypto.md5Hex(url.lastPathComponent)
        for item in metadataItems {
            guard let key = item.commonKey else { continue }
            switch key {
            case .commonKeyTitle:
                if let val = try? await item.load(.stringValue), !val.trimmingCharacters(in: .whitespaces).isEmpty {
                    title = val
                }
            case .commonKeyArtist:
                if let val = try? await item.load(.stringValue), !val.trimmingCharacters(in: .whitespaces).isEmpty {
                    artist = val
                }
            case .commonKeyAlbumName:
                if let val = try? await item.load(.stringValue) {
                    album = val
                }
            case .commonKeyArtwork:
                if let data = try? await item.load(.dataValue), !data.isEmpty {
                    let artUrl = artworkDir.appendingPathComponent("\(hashId).jpg")
                    if !FileManager.default.fileExists(atPath: artUrl.path) {
                        try? data.write(to: artUrl, options: .atomic)
                    }
                    coverPath = artUrl.absoluteString
                }
            default:
                break
            }
        }

        return Track(
            id: "local_\(hashId)",
            title: title,
            artist: artist,
            album: album,
            source: .local,
            songmid: hashId,
            durationMs: durationMs,
            coverUrl: coverPath,
            localUri: url.path
        )
    }
}
