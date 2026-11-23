import SwiftUI

/// Fluid ambient mesh gradient background inspired by Apple Music / Salt Player.
/// Smoothly renders cover-derived orbs; only animates continuously when `immersive && enabled`.
struct FluidAmbientBackground: View {
    let palette: AmbientPalette
    let enabled: Bool
    let immersive: Bool

    @Environment(\.lxPalette) private var lx

    var body: some View {
        if immersive && enabled {
            TimelineView(.animation(minimumInterval: 1.0 / 30.0)) { timeline in
                let elapsed = timeline.date.timeIntervalSinceReferenceDate
                let phase = (elapsed.truncatingRemainder(dividingBy: 22.0) / 22.0) * 2.0 * .pi
                ambientCanvas(phase: phase)
            }
            .ignoresSafeArea()
        } else {
            ambientCanvas(phase: 0)
                .ignoresSafeArea()
        }
    }

    private func ambientCanvas(phase: Double) -> some View {
        Canvas { ctx, size in
            let rect = CGRect(origin: .zero, size: size)
            let baseColor = immersive ? Color(hex: 0x090B10) : lx.background
            ctx.fill(Path(rect), with: .color(baseColor))
            guard enabled else { return }

            let w = size.width
            let h = size.height

            if immersive {
                let orb1 = CGPoint(
                    x: w * (0.24 + 0.14 * cos(phase)),
                    y: h * (0.20 + 0.08 * sin(phase))
                )
                let orb2 = CGPoint(
                    x: w * (0.80 + 0.12 * sin(phase * 0.8)),
                    y: h * (0.58 + 0.10 * cos(phase * 0.9))
                )
                let orb3 = CGPoint(
                    x: w * (0.32 + 0.16 * sin(phase + 1.4)),
                    y: h * (0.86 + 0.07 * cos(phase + 1.4))
                )

                drawOrb(&ctx, center: orb1, radius: w * 1.15, color: palette.primary.opacity(0.78))
                drawOrb(&ctx, center: orb2, radius: w * 1.05, color: palette.secondary.opacity(0.62))
                drawOrb(&ctx, center: orb3, radius: w * 0.95, color: palette.accent.opacity(0.34))

                let scrim = Gradient(colors: [
                    Color.black.opacity(0.22),
                    Color.black.opacity(0.32),
                    Color.black.opacity(0.56)
                ])
                ctx.fill(
                    Path(rect),
                    with: .linearGradient(scrim, startPoint: .zero, endPoint: CGPoint(x: 0, y: h))
                )
            } else {
                let alphaScale = lx.isDark ? 0.24 : 0.12
                let topOrb = CGPoint(x: w * 0.22, y: h * 0.04)
                let sideOrb = CGPoint(x: w * 0.88, y: h * 0.12)
                drawOrb(&ctx, center: topOrb, radius: w * 0.90, color: palette.primary.opacity(alphaScale))
                drawOrb(&ctx, center: sideOrb, radius: w * 0.75, color: palette.accent.opacity(alphaScale * 0.7))
            }
        }
        .animation(.easeInOut(duration: 0.9), value: palette)
    }

    private func drawOrb(_ ctx: inout GraphicsContext, center: CGPoint, radius: CGFloat, color: Color) {
        let gradient = Gradient(colors: [color, .clear])
        let circleRect = CGRect(
            x: center.x - radius,
            y: center.y - radius,
            width: radius * 2,
            height: radius * 2
        )
        ctx.fill(
            Path(ellipseIn: circleRect),
            with: .radialGradient(gradient, center: center, startRadius: 0, endRadius: radius)
        )
    }
}
