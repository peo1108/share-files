import AVFoundation
import SwiftUI

struct PairView: View {
    @EnvironmentObject private var model: AppModel
    @State private var lastCode: String?
    @State private var showPaste = false
    @State private var pasted = ""

    var body: some View {
        ScrollView {
            VStack(spacing: 8) {
                Text("Share Files")
                    .font(.system(size: 34, weight: .bold))
                    .foregroundStyle(.white)
                Text("Mở Share Files trên PC rồi quét mã QR để ghép đôi")
                    .font(.system(size: 15))
                    .foregroundStyle(Palette.subtle)
                    .multilineTextAlignment(.center)
                    .padding(.bottom, 16)

                ZStack {
                    QRScannerView { code in
                        guard code != lastCode else { return }
                        lastCode = code
                        model.pair(text: code)
                    }
                    if case .working(let name) = model.pairing {
                        VStack(spacing: 14) {
                            ProgressView().tint(Palette.pink).controlSize(.large)
                            Text("Đang ghép đôi với \(name)").foregroundStyle(.white)
                        }
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                        .background(Palette.night.opacity(0.75))
                    }
                }
                .aspectRatio(1, contentMode: .fit)
                .glass(radius: 28)

                if case .failed(let message) = model.pairing {
                    Text(message)
                        .font(.system(size: 14))
                        .foregroundStyle(Palette.danger)
                        .multilineTextAlignment(.center)
                        .padding(.top, 10)
                    Button("Thử lại") {
                        lastCode = nil
                        model.dismissPairingError()
                    }
                    .foregroundStyle(.white)
                }

                Button {
                    pasted = UIPasteboard.general.string ?? ""
                    showPaste = true
                } label: {
                    Label("Dán link ghép đôi", systemImage: "doc.on.clipboard")
                }
                .buttonStyle(GhostButtonStyle())
                .padding(.top, 14)
            }
            .frame(maxWidth: 480)
            .padding(24)
            .frame(maxWidth: .infinity)
        }
        .alert("Dán link ghép đôi", isPresented: $showPaste) {
            TextField("sharefiles://pair?...", text: $pasted)
            Button("Ghép đôi") { model.pair(text: pasted) }
            Button("Huỷ", role: .cancel) {}
        }
    }
}

/// Camera preview that reports the text of every QR code it sees.
struct QRScannerView: UIViewRepresentable {
    let onCode: (String) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onCode: onCode) }

    func makeUIView(context: Context) -> PreviewView {
        let view = PreviewView()
        view.backgroundColor = .clear
        context.coordinator.attach(to: view)
        return view
    }

    func updateUIView(_ uiView: PreviewView, context: Context) {
        context.coordinator.onCode = onCode
    }

    static func dismantleUIView(_ uiView: PreviewView, coordinator: Coordinator) {
        coordinator.stop()
    }

    final class PreviewView: UIView {
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
        var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
    }

    final class Coordinator: NSObject, AVCaptureMetadataOutputObjectsDelegate {
        var onCode: (String) -> Void
        private let session = AVCaptureSession()
        private let queue = DispatchQueue(label: "sharefiles.camera")

        init(onCode: @escaping (String) -> Void) {
            self.onCode = onCode
        }

        func attach(to view: PreviewView) {
            view.previewLayer.session = session
            view.previewLayer.videoGravity = .resizeAspectFill

            AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                guard granted else {
                    AppLog.shared.add("Chưa được cấp quyền camera, dùng nút Dán link để ghép đôi")
                    return
                }
                self?.queue.async { self?.configureAndStart() }
            }
        }

        private func configureAndStart() {
            guard let camera = AVCaptureDevice.default(for: .video),
                  let input = try? AVCaptureDeviceInput(device: camera),
                  session.canAddInput(input) else { return }

            let output = AVCaptureMetadataOutput()
            session.beginConfiguration()
            session.addInput(input)
            if session.canAddOutput(output) {
                session.addOutput(output)
                output.setMetadataObjectsDelegate(self, queue: .main)
                output.metadataObjectTypes = [.qr]
            }
            session.commitConfiguration()
            session.startRunning()
        }

        func stop() {
            queue.async { [session] in session.stopRunning() }
        }

        func metadataOutput(
            _ output: AVCaptureMetadataOutput,
            didOutput metadataObjects: [AVMetadataObject],
            from connection: AVCaptureConnection
        ) {
            for case let code as AVMetadataMachineReadableCodeObject in metadataObjects {
                if let text = code.stringValue { onCode(text) }
            }
        }
    }
}
