// SPDX-License-Identifier: MIT
// Copyright © 2026 Tsuyoshi Kobayashi(legrs4073)
import AVFoundation
import SwiftUI

/// 撮影画面 (Android 版 CapActivity). 露出・ISO・ピントを固定して RAW を連続撮影し, 平均を保存する
struct CaptureView: View {
    @EnvironmentObject private var profiles: DeviceProfileStore
    @StateObject private var camera = CameraModel()

    @State private var name = ""
    @State private var count = 10
    // 露出とISOは対数のスライダー
    @State private var logExposure = log10(0.1)
    @State private var logISO = log10(800.0)
    @State private var lensPosition: Double = 1
    @State private var zoom: CGFloat = 1

    private var exposureSeconds: Double { pow(10, logExposure) }
    private var iso: Float { Float(pow(10, logISO)) }

    var body: some View {
        VStack(spacing: 8) {
            CameraPreview(session: camera.session)
                .scaleEffect(zoom)
                .clipped()
                .overlay(alignment: .topLeading) {
                    Text(camera.cameraDescription)
                        .font(.caption2)
                        .padding(4)
                        .background(.black.opacity(0.5))
                        .foregroundStyle(.white)
                }
                .onTapGesture(count: 2) { zoom = zoom == 1 ? 4 : 1 }

            Form {
                Section {
                    TextField("Sequence Name", text: $name)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    Stepper("枚数: \(count)", value: $count, in: 1...500)
                }
                Section {
                    LabeledSlider(title: String(format: "露出 %.2f ms", exposureSeconds * 1000), value: $logExposure,
                                  range: log10(camera.exposureRange.lowerBound)...log10(camera.exposureRange.upperBound))
                    LabeledSlider(title: "ISO \(Int(iso))", value: $logISO,
                                  range: log10(Double(camera.isoRange.lowerBound))...log10(Double(camera.isoRange.upperBound)))
                    LabeledSlider(title: String(format: "ピント %.2f (0 = 最短, 1 = 無限遠)", lensPosition),
                                  value: $lensPosition, range: 0...1)
                } footer: {
                    Text("iPhone は1枚の露出時間の上限が短いので, 暗い天体は枚数を増やしてください. プレビューはダブルタップで拡大します.")
                }
                Section {
                    Button(action: capture) {
                        Text("CAPTURE").frame(maxWidth: .infinity)
                    }
                    .disabled(!canCapture)
                    Text(camera.status.message)
                        .font(.footnote)
                        .foregroundStyle(isFailure ? .red : .secondary)
                }
            }
        }
        .navigationTitle("Capture")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            UIApplication.shared.isIdleTimerDisabled = true // 長時間撮影で画面が消えないように
            camera.start(cameraId: profiles.profile.camera?.id)
        }
        .onDisappear {
            UIApplication.shared.isIdleTimerDisabled = false
            camera.stop()
        }
        .onChange(of: camera.status) { s in
            if s == .ready { applySettings() }
        }
        .onChange(of: logExposure) { _ in applySettings() }
        .onChange(of: logISO) { _ in applySettings() }
        .onChange(of: lensPosition) { _ in applySettings() }
    }

    private var isFailure: Bool {
        switch camera.status {
        case .failed, .unavailable: return true
        default: return false
        }
    }

    private var canCapture: Bool {
        guard Storage.isValidName(name.trimmingCharacters(in: .whitespaces)) else { return false }
        switch camera.status {
        case .ready, .finished, .failed: return true
        default: return false
        }
    }

    private func applySettings() {
        camera.apply(iso: iso, exposureSeconds: exposureSeconds, lensPosition: Float(lensPosition))
    }

    private func capture() {
        applySettings()
        camera.capture(name: name.trimmingCharacters(in: .whitespaces), count: count)
    }
}

struct LabeledSlider: View {
    let title: String
    @Binding var value: Double
    let range: ClosedRange<Double>

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title).font(.footnote)
            Slider(value: $value, in: range.lowerBound < range.upperBound ? range : range.lowerBound...(range.lowerBound + 1))
        }
    }
}

/// AVCaptureVideoPreviewLayer を SwiftUI に置く
struct CameraPreview: UIViewRepresentable {
    let session: AVCaptureSession

    final class PreviewView: UIView {
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
        var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
    }

    func makeUIView(context: Context) -> PreviewView {
        let v = PreviewView()
        v.previewLayer.session = session
        v.previewLayer.videoGravity = .resizeAspect
        v.backgroundColor = .black
        return v
    }

    func updateUIView(_ uiView: PreviewView, context: Context) {}
}
