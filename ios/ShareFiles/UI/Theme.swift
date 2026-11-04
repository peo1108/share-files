import SwiftUI

enum Palette {
    static let night = Color(red: 0x0B / 255, green: 0x0A / 255, blue: 0x1F / 255)
    static let dusk = Color(red: 0x1A / 255, green: 0x10 / 255, blue: 0x40 / 255)
    static let violet = Color(red: 0x7C / 255, green: 0x4D / 255, blue: 0xFF / 255)
    static let pink = Color(red: 0xFF / 255, green: 0x4D / 255, blue: 0x9D / 255)
    static let amber = Color(red: 0xFF / 255, green: 0xB5 / 255, blue: 0x47 / 255)
    static let cyan = Color(red: 0x22 / 255, green: 0xD3 / 255, blue: 0xEE / 255)
    static let success = Color(red: 0x34 / 255, green: 0xD3 / 255, blue: 0x99 / 255)
    static let danger = Color(red: 0xFB / 255, green: 0x71 / 255, blue: 0x85 / 255)
    static let subtle = Color.white.opacity(0.7)
    static let faint = Color.white.opacity(0.5)
    static let card = Color.white.opacity(0.08)
    static let cardBorder = Color.white.opacity(0.14)

    static let accent = LinearGradient(
        colors: [violet, pink, amber],
        startPoint: .topLeading,
        endPoint: .bottomTrailing
    )
    static let accentHorizontal = LinearGradient(colors: [violet, pink, amber], startPoint: .leading, endPoint: .trailing)
}

/// The slowly drifting aurora behind every screen.
struct AuroraBackground: View {
    @State private var drifted = false

    var body: some View {
        GeometryReader { proxy in
            let unit = max(proxy.size.width, proxy.size.height)
            ZStack {
                LinearGradient(colors: [Palette.night, Palette.dusk], startPoint: .topLeading, endPoint: .bottomTrailing)
                blob(Palette.violet, opacity: 0.60, size: unit * 1.1)
                    .position(
                        x: proxy.size.width * (drifted ? 0.30 : 0.05),
                        y: proxy.size.height * (drifted ? 0.12 : 0.02)
                    )
                blob(Palette.pink, opacity: 0.45, size: unit * 1.2)
                    .position(
                        x: proxy.size.width * (drifted ? 0.70 : 1.0),
                        y: proxy.size.height * (drifted ? 0.84 : 0.98)
                    )
                blob(Palette.cyan, opacity: 0.30, size: unit * 0.84)
                    .position(
                        x: proxy.size.width * (drifted ? 0.75 : 0.95),
                        y: proxy.size.height * (drifted ? 0.17 : 0.05)
                    )
            }
            .drawingGroup()
        }
        .ignoresSafeArea()
        .onAppear {
            withAnimation(.linear(duration: 20).repeatForever(autoreverses: true)) {
                drifted = true
            }
        }
    }

    private func blob(_ color: Color, opacity: Double, size: CGFloat) -> some View {
        Circle()
            .fill(RadialGradient(
                colors: [color.opacity(opacity), color.opacity(0)],
                center: .center,
                startRadius: 0,
                endRadius: size / 2
            ))
            .frame(width: size, height: size)
    }
}

extension View {
    func glass(radius: CGFloat = 22) -> some View {
        background(Palette.card, in: RoundedRectangle(cornerRadius: radius, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: radius, style: .continuous).stroke(Palette.cardBorder, lineWidth: 1))
            .clipShape(RoundedRectangle(cornerRadius: radius, style: .continuous))
    }
}

struct GradientButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 15, weight: .semibold))
            .foregroundStyle(.white)
            .frame(maxWidth: .infinity, minHeight: 50)
            .padding(.horizontal, 14)
            .background(Palette.accent, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .opacity(!isEnabled ? 0.4 : configuration.isPressed ? 0.75 : 1)
    }
}

struct GhostButtonStyle: ButtonStyle {
    var tint: Color = .white
    var expands = false

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 14, weight: .medium))
            .foregroundStyle(tint)
            .frame(maxWidth: expands ? .infinity : nil, minHeight: expands ? 50 : 40)
            .padding(.horizontal, 14)
            .background(Palette.card, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(Palette.cardBorder, lineWidth: 1))
            .opacity(configuration.isPressed ? 0.7 : 1)
    }
}

struct GradientProgress: View {
    let fraction: Double

    var body: some View {
        GeometryReader { proxy in
            ZStack(alignment: .leading) {
                Capsule().fill(Palette.card)
                Capsule()
                    .fill(Palette.accentHorizontal)
                    .frame(width: proxy.size.width * min(max(fraction, 0), 1))
            }
        }
        .frame(height: 6)
        .animation(.linear(duration: 0.3), value: fraction)
    }
}

struct SectionTitle: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.system(size: 16, weight: .semibold))
            .foregroundStyle(.white)
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}
