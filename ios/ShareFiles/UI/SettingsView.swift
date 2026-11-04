import SwiftUI

/// Full scale of the gauge: what a 2.5 Gbps cable can carry.
private let gaugeMaxBytesPerSecond = 312_500_000.0

struct SettingsView: View {
    @EnvironmentObject private var model: AppModel
    @ObservedObject private var log = AppLog.shared
    @State private var confirmUnpair = false

    var body: some View {
        ScrollView {
            VStack(spacing: 14) {
                SpeedTestCard()

                VStack(alignment: .leading, spacing: 6) {
                    SectionTitle(text: "PC đã ghép")
                    Text(model.paired?.serverName ?? "")
                        .font(.system(size: 18, weight: .semibold))
                        .foregroundStyle(.white)
                    Text(hostText)
                        .font(.system(size: 13))
                        .foregroundStyle(Palette.subtle)
                    Text("Chứng chỉ: \(String((model.paired?.fingerprint ?? "").prefix(16)))")
                        .font(.system(size: 11, design: .monospaced))
                        .foregroundStyle(Palette.faint)
                    Button("Huỷ ghép đôi") { confirmUnpair = true }
                        .buttonStyle(GhostButtonStyle(tint: Palette.danger))
                        .padding(.top, 8)
                }
                .padding(18)
                .frame(maxWidth: .infinity, alignment: .leading)
                .glass()

                VStack(alignment: .leading, spacing: 8) {
                    SectionTitle(text: "Nhật ký")
                    ScrollView {
                        VStack(alignment: .leading, spacing: 2) {
                            if log.lines.isEmpty {
                                Text("Chưa có gì")
                                    .font(.system(size: 12))
                                    .foregroundStyle(Palette.faint)
                            }
                            ForEach(Array(log.lines.reversed().enumerated()), id: \.offset) { _, line in
                                Text(line)
                                    .font(.system(size: 11, design: .monospaced))
                                    .foregroundStyle(Palette.subtle)
                                    .textSelection(.enabled)
                            }
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .frame(maxHeight: 260)
                }
                .padding(18)
                .frame(maxWidth: .infinity, alignment: .leading)
                .glass()
            }
            .padding(16)
        }
        .alert("Huỷ ghép đôi?", isPresented: $confirmUnpair) {
            Button("Huỷ ghép đôi", role: .destructive) { model.unpair() }
            Button("Không", role: .cancel) {}
        } message: {
            Text("Muốn dùng lại thì phải quét mã QR trên PC lần nữa.")
        }
    }

    private var hostText: String {
        if case .online(let host) = model.connection { return host }
        return "Chưa kết nối"
    }
}

private struct SpeedTestCard: View {
    @EnvironmentObject private var model: AppModel
    @State private var running = false
    @State private var phase = ""
    @State private var live = 0.0
    @State private var downloadResult: Double?
    @State private var uploadResult: Double?
    @State private var error: String?
    @AppStorage("speedInBits") private var inBits = false

    var body: some View {
        VStack(spacing: 14) {
            VStack(alignment: .leading, spacing: 2) {
                HStack {
                    SectionTitle(text: "Đo tốc độ tới PC")
                    unitToggle
                }
                Text("Chỉ đo đường truyền, không đụng tới ổ đĩa")
                    .font(.system(size: 12))
                    .foregroundStyle(Palette.faint)
            }

            ZStack {
                gauge
                VStack(spacing: 0) {
                    Text(String(format: "%.0f", inBits ? live * 8 / 1_000_000 : live / 1_000_000))
                        .font(.system(size: 46, weight: .bold))
                        .foregroundStyle(.white)
                    Text(inBits ? "Mbps" : "MB/s")
                        .font(.system(size: 14))
                        .foregroundStyle(Palette.subtle)
                    Text(running ? phase : " ")
                        .font(.system(size: 12))
                        .foregroundStyle(Palette.amber)
                }
            }
            .frame(width: 240, height: 180)

            HStack(spacing: 10) {
                resultTile("Tải về máy", downloadResult)
                resultTile("Gửi lên PC", uploadResult)
            }

            if let error {
                Text(error).font(.system(size: 13)).foregroundStyle(Palette.danger)
            }

            Button(action: start) {
                Label(running ? "Đang đo…" : "Bắt đầu đo", systemImage: "speedometer")
            }
            .buttonStyle(GradientButtonStyle())
            .disabled(running)
        }
        .padding(18)
        .glass()
    }

    /// MB/s for comparing with file copies, Mbps for comparing with router and Wi-Fi link rates.
    private var unitToggle: some View {
        HStack(spacing: 0) {
            ForEach([false, true], id: \.self) { bits in
                let selected = bits == inBits
                Button {
                    inBits = bits
                } label: {
                    Text(bits ? "Mbps" : "MB/s")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(selected ? Color.white : Palette.subtle)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 5)
                        .background(selected ? Palette.violet : Color.clear, in: RoundedRectangle(cornerRadius: 8))
                }
                .buttonStyle(.plain)
            }
        }
        .padding(3)
        .background(Palette.card, in: RoundedRectangle(cornerRadius: 10))
        .overlay(RoundedRectangle(cornerRadius: 10).stroke(Palette.cardBorder, lineWidth: 1))
    }

    private var gauge: some View {
        let sweep = 240.0 / 360.0
        return ZStack {
            Circle()
                .trim(from: 0, to: sweep)
                .stroke(Palette.card, style: StrokeStyle(lineWidth: 15, lineCap: .round))
            Circle()
                .trim(from: 0, to: sweep * min(live / gaugeMaxBytesPerSecond, 1))
                .stroke(
                    AngularGradient(
                        colors: [Palette.violet, Palette.pink, Palette.amber],
                        center: .center,
                        startAngle: .degrees(0),
                        endAngle: .degrees(240)
                    ),
                    style: StrokeStyle(lineWidth: 15, lineCap: .round)
                )
                .animation(.linear(duration: 0.2), value: live)
        }
        .rotationEffect(.degrees(150))
        .frame(width: 210, height: 210)
        .offset(y: 16)
    }

    private func resultTile(_ label: String, _ value: Double?) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(label).font(.system(size: 12)).foregroundStyle(Palette.subtle)
            Text(value.map { ByteFormat.rate($0, inBits: inBits) } ?? "—")
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(.white)
            if let value {
                Text(ByteFormat.rate(value, inBits: !inBits))
                    .font(.system(size: 11))
                    .foregroundStyle(Palette.faint)
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .glass(radius: 16)
    }

    private func start() {
        guard let api = model.api else {
            error = "Chưa kết nối với PC"
            return
        }
        running = true
        error = nil
        downloadResult = nil
        uploadResult = nil

        Task {
            let test = SpeedTest(api: api)
            let report: @Sendable (Double) -> Void = { speed in
                Task { @MainActor in live = speed }
            }
            do {
                phase = "Đang đo tải về"
                let down = try await test.download(onSpeed: report)
                downloadResult = down
                phase = "Đang đo gửi lên"
                let up = try await test.upload(onSpeed: report)
                uploadResult = up
                AppLog.shared.add("Đo tốc độ: tải về \(ByteFormat.rate(down, inBits: inBits)), gửi lên \(ByteFormat.rate(up, inBits: inBits))")
            } catch {
                self.error = "Mất kết nối khi đang đo"
                AppLog.shared.add("Đo tốc độ lỗi: \(error.localizedDescription)")
            }
            live = 0
            running = false
        }
    }
}
