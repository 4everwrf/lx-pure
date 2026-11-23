import SwiftUI

struct LxPurePalette: Equatable {
    let isDark: Bool
    let background: Color
    let surface: Color
    let surfaceElevated: Color
    let glassSurface: Color
    let hairline: Color
    let textPrimary: Color
    let textSecondary: Color
    let textTertiary: Color
    let accent: Color
    let accentSoft: Color
    let onAccent: Color
    let success: Color
    let warning: Color
    let danger: Color

    static let dark = LxPurePalette(
        isDark: true,
        background: Color(hex: 0x0A0B0F),
        surface: Color(hex: 0x13151D),
        surfaceElevated: Color(hex: 0x1B1E29),
        glassSurface: Color(hex: 0x171A24, alpha: 0.86),
        hairline: Color.white.opacity(0.10),
        textPrimary: Color(hex: 0xF8FAFC),
        textSecondary: Color(hex: 0x94A3B8),
        textTertiary: Color(hex: 0x64748B),
        accent: Color(hex: 0x38BDF8),
        accentSoft: Color(hex: 0x38BDF8, alpha: 0.16),
        onAccent: Color(hex: 0x04141F),
        success: Color(hex: 0x34D399),
        warning: Color(hex: 0xFBBF24),
        danger: Color(hex: 0xFB7185)
    )

    static let light = LxPurePalette(
        isDark: false,
        background: Color(hex: 0xF5F6FA),
        surface: Color(hex: 0xFFFFFF),
        surfaceElevated: Color(hex: 0xFFFFFF),
        glassSurface: Color.white.opacity(0.92),
        hairline: Color(hex: 0x0F172A, alpha: 0.08),
        textPrimary: Color(hex: 0x0F172A),
        textSecondary: Color(hex: 0x475569),
        textTertiary: Color(hex: 0x94A3B8),
        accent: Color(hex: 0x0284C7),
        accentSoft: Color(hex: 0x0284C7, alpha: 0.12),
        onAccent: .white,
        success: Color(hex: 0x10B981),
        warning: Color(hex: 0xD97706),
        danger: Color(hex: 0xE11D48)
    )
}

private struct LxPaletteKey: EnvironmentKey {
    static let defaultValue: LxPurePalette = .dark
}

extension EnvironmentValues {
    var lxPalette: LxPurePalette {
        get { self[LxPaletteKey.self] }
        set { self[LxPaletteKey.self] = newValue }
    }
}
