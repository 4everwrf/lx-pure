import Foundation

/// Normalizes user-supplied custom source script URLs for manual update checks.
/// This application does not bundle, recommend, or hardcode any third-party source URLs.
enum SourceUrlUtils {
    static func buildUpdateCandidateUrls(_ sourceUrl: String?) -> [String] {
        let trimmed = (sourceUrl ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let lower = trimmed.lowercased()
        if lower.hasPrefix("http://") || lower.hasPrefix("https://") {
            return [trimmed]
        }
        return []
    }
}
