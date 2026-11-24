import CoreGraphics
import SwiftUI
import UIKit

enum AmbientColorExtractor {
    /// Extracts a cohesive dark-rich ambient palette (primary, secondary, vibrant accent) from artwork.
    static func extractPalette(from image: UIImage) -> AmbientPalette {
        guard let cgImage = image.cgImage else { return .default }
        let width = 24
        let height = 24
        let bytesPerPixel = 4
        let bytesPerRow = bytesPerPixel * width
        var rawBytes = [UInt8](repeating: 0, count: width * height * bytesPerPixel)

        let drew = rawBytes.withUnsafeMutableBytes { rawBuffer -> Bool in
            guard let baseAddress = rawBuffer.baseAddress,
                  let ctx = CGContext(
                      data: baseAddress,
                      width: width,
                      height: height,
                      bitsPerComponent: 8,
                      bytesPerRow: bytesPerRow,
                      space: CGColorSpaceCreateDeviceRGB(),
                      bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
                  ) else {
                return false
            }
            ctx.interpolationQuality = .low
            ctx.draw(cgImage, in: CGRect(x: 0, y: 0, width: width, height: height))
            return true
        }
        guard drew else { return .default }

        var sumR: Double = 0, sumG: Double = 0, sumB: Double = 0, count: Double = 0
        var bestAccent: (r: Double, g: Double, b: Double, score: Double) = (0.22, 0.74, 0.97, -1)
        var bestSecondary: (r: Double, g: Double, b: Double, score: Double) = (0.19, 0.18, 0.51, -1)

        for y in 0..<height {
            for x in 0..<width {
                let offset = (y * width + x) * 4
                let a = Double(rawBytes[offset + 3]) / 255.0
                if a < 0.5 { continue }
                let r = Double(rawBytes[offset]) / 255.0
                let g = Double(rawBytes[offset + 1]) / 255.0
                let b = Double(rawBytes[offset + 2]) / 255.0

                let maxC = max(r, max(g, b))
                let minC = min(r, min(g, b))
                let sat = maxC > 0 ? (maxC - minC) / maxC : 0
                let lum = 0.299 * r + 0.587 * g + 0.114 * b

                sumR += r
                sumG += g
                sumB += b
                count += 1

                let vibrantScore = sat * 1.6 + (1.0 - abs(lum - 0.52))
                if vibrantScore > bestAccent.score && lum > 0.18 && lum < 0.88 {
                    bestAccent = (r, g, b, vibrantScore)
                }

                let deepScore = sat * 1.1 + (1.0 - abs(lum - 0.30))
                if deepScore > bestSecondary.score && lum > 0.08 && lum < 0.65 {
                    bestSecondary = (r, g, b, deepScore)
                }
            }
        }

        guard count > 0 else { return .default }
        let avgR = sumR / count
        let avgG = sumG / count
        let avgB = sumB / count

        // Clamp primary & secondary luminance strictly below 0.34 so white text/lyrics remain crisp even on white covers
        let primary = Color(
            red: clamp(avgR * 0.55 + bestSecondary.r * 0.25, 0.07, 0.34),
            green: clamp(avgG * 0.55 + bestSecondary.g * 0.25, 0.07, 0.34),
            blue: clamp(avgB * 0.55 + bestSecondary.b * 0.25, 0.09, 0.42)
        )
        let secondary = Color(
            red: clamp(bestSecondary.r * 0.62, 0.06, 0.32),
            green: clamp(bestSecondary.g * 0.62, 0.06, 0.32),
            blue: clamp(bestSecondary.b * 0.68, 0.08, 0.40)
        )
        let accent = Color(
            red: clamp(bestAccent.r, 0.32, 0.94),
            green: clamp(bestAccent.g, 0.38, 0.94),
            blue: clamp(bestAccent.b, 0.48, 0.98)
        )

        return AmbientPalette(primary: primary, secondary: secondary, accent: accent)
    }

    private static func clamp(_ val: Double, _ low: Double, _ high: Double) -> Double {
        min(max(val, low), high)
    }
}
