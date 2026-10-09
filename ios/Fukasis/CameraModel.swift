// SPDX-License-Identifier: MIT
// Copyright © 2026 Tsuyoshi Kobayashi(legrs4073)
import AVFoundation
import FukasisCore
import UIKit

/// RAW (Bayer) の連続撮影と積算. Android 版 Cam.java に相当する.
///
/// iPhone では
/// - 1枚の露出時間の上限が Android より短い (activeFormat.maxExposureDuration. 機種による) ので, 枚数を増やして積算する
/// - ProRAW ではなく Bayer RAW (AVCapturePhotoOutput.isBayerRAWPixelFormat) を使う. 色の配列はピクセル形式から分かる
final class CameraModel: NSObject, ObservableObject {
    enum Status: Equatable {
        case idle
        case unavailable(String)
        case ready
        case capturing(done: Int, total: Int)
        case finished(String)
        case failed(String)

        var message: String {
            switch self {
            case .idle: return "カメラを準備しています…"
            case .unavailable(let m): return m
            case .ready: return "準備完了"
            case .capturing(let done, let total): return "撮影中… \(done)/\(total)"
            case .finished(let m): return m
            case .failed(let m): return "失敗: " + m
            }
        }
    }

    // UI に出す値 (main thread で更新する)
    @Published private(set) var status: Status = .idle
    @Published private(set) var isoRange: ClosedRange<Float> = 50...3200
    @Published private(set) var exposureRange: ClosedRange<Double> = 0.001...1
    @Published private(set) var cameraDescription = ""
    @Published private(set) var cfa: CFA?

    let session = AVCaptureSession()
    private let photoOutput = AVCapturePhotoOutput()
    private var device: AVCaptureDevice?
    private var rawFormat: OSType?
    private let sessionQueue = DispatchQueue(label: "fukasis.camera.session")
    private var configured = false

    // 撮影中のシーケンス (sessionQueue からだけ触る)
    private var stacker: RawStacker?
    private var capturing = false
    private var sequenceName = ""
    private var total = 0
    private var done = 0
    private var failures = 0
    private var iso: Float = 0
    private var exposure: Double = 0
    private var lensPosition: Float = 0
    private static let maxConsecutiveFailures = 3

    private func publish(_ s: Status) {
        DispatchQueue.main.async { self.status = s }
    }

    /// カメラの権限を確認して, セッションを作って開始する
    func start(cameraId: String?) {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            sessionQueue.async { self.configureAndRun(cameraId: cameraId) }
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { granted in
                if granted {
                    self.sessionQueue.async { self.configureAndRun(cameraId: cameraId) }
                } else {
                    self.publish(.unavailable("カメラの権限がないため撮影できません"))
                }
            }
        default:
            publish(.unavailable("カメラの権限がありません (設定アプリで許可してください)"))
        }
    }

    func stop() {
        sessionQueue.async {
            if self.session.isRunning {
                self.session.stopRunning()
            }
        }
    }

    static func deviceType(for cameraId: String?) -> AVCaptureDevice.DeviceType {
        switch cameraId?.lowercased() {
        case "ultrawide": return .builtInUltraWideCamera
        case "telephoto": return .builtInTelephotoCamera
        default: return .builtInWideAngleCamera
        }
    }

    static func cfa(of format: OSType) -> CFA? {
        switch format {
        case kCVPixelFormatType_14Bayer_RGGB: return .rggb
        case kCVPixelFormatType_14Bayer_GRBG: return .grbg
        case kCVPixelFormatType_14Bayer_GBRG: return .gbrg
        case kCVPixelFormatType_14Bayer_BGGR: return .bggr
        default: return nil
        }
    }

    private func configureAndRun(cameraId: String?) {
        if !configured {
            session.beginConfiguration()
            session.sessionPreset = .photo
            let type = Self.deviceType(for: cameraId)
            guard let dev = AVCaptureDevice.default(type, for: .video, position: .back),
                  let input = try? AVCaptureDeviceInput(device: dev),
                  session.canAddInput(input), session.canAddOutput(photoOutput) else {
                session.commitConfiguration()
                publish(.unavailable("背面カメラ (\(cameraId ?? "wide")) を使えません"))
                return
            }
            session.addInput(input)
            session.addOutput(photoOutput)
            session.commitConfiguration()

            // RAW の形式は出力をセッションにつないでから分かる
            guard let raw = photoOutput.availableRawPhotoPixelFormatTypes.first(where: {
                AVCapturePhotoOutput.isBayerRAWPixelFormat($0)
            }) else {
                publish(.unavailable("このカメラは Bayer RAW で撮影できません"))
                return
            }
            device = dev
            rawFormat = raw
            configured = true

            let format = dev.activeFormat
            let isoRange = format.minISO...format.maxISO
            let expoRange = format.minExposureDuration.seconds...format.maxExposureDuration.seconds
            let cfa = Self.cfa(of: raw)
            let desc = "\(dev.localizedName), RAW \(cfa?.name ?? "?"), ISO \(Int(format.minISO))-\(Int(format.maxISO)), "
                + String(format: "露出 %.3f-%.0f ms", expoRange.lowerBound * 1000, expoRange.upperBound * 1000)
            DispatchQueue.main.async {
                self.isoRange = isoRange
                self.exposureRange = expoRange
                self.cfa = cfa
                self.cameraDescription = desc
            }
        }
        if !session.isRunning {
            session.startRunning()
        }
        publish(.ready)
    }

    /// 露出・ISO・ピントを固定する (プレビューにも反映される)
    func apply(iso: Float, exposureSeconds: Double, lensPosition: Float) {
        sessionQueue.async {
            guard let dev = self.device else { return }
            do {
                try dev.lockForConfiguration()
                defer { dev.unlockForConfiguration() }
                let f = dev.activeFormat
                let isoValue = min(max(iso, f.minISO), f.maxISO)
                let seconds = min(max(exposureSeconds, f.minExposureDuration.seconds), f.maxExposureDuration.seconds)
                if dev.isExposureModeSupported(.custom) {
                    dev.setExposureModeCustom(duration: CMTime(seconds: seconds, preferredTimescale: 1_000_000_000),
                                              iso: isoValue, completionHandler: nil)
                }
                if dev.isLockingFocusWithCustomLensPositionSupported {
                    dev.setFocusModeLocked(lensPosition: min(max(lensPosition, 0), 1), completionHandler: nil)
                }
                self.iso = isoValue
                self.exposure = seconds
                self.lensPosition = lensPosition
            } catch {
                self.publish(.failed("カメラの設定を変えられません: \(error.localizedDescription)"))
            }
        }
    }

    /// count 枚撮って積算し, imgs/<name>/ に stacked.tif / stacked.jpg / metadata.csv と各フレームの DNG を保存する
    func capture(name: String, count: Int) {
        sessionQueue.async {
            guard self.configured, self.device != nil else {
                self.publish(.failed("カメラの準備ができていません"))
                return
            }
            if self.capturing { return }
            self.capturing = true
            self.stacker = nil // 1枚目の RAW の大きさで作る
            self.sequenceName = name
            self.total = count
            self.done = 0
            self.failures = 0
            self.publish(.capturing(done: 0, total: count))
            self.captureNext()
        }
    }

    private func captureNext() {
        guard let raw = rawFormat else { return }
        let settings = AVCapturePhotoSettings(rawPixelFormatType: raw)
        // Bayer RAW は速度優先でないと撮れない (複数枚合成をさせない)
        settings.photoQualityPrioritization = .speed
        photoOutput.capturePhoto(with: settings, delegate: self)
    }

    private func finishSequence() {
        capturing = false
        guard let stacker, let mean = stacker.mean() else {
            publish(.failed("積算した画像がありません"))
            return
        }
        let dir = Storage.imageDir(sequenceName)
        let cfa = Self.cfa(of: rawFormat ?? 0) ?? .rggb
        // Android 版と同じ形式 (後ろの cfa はスペクトル出力で Bayer 配列を正しく扱うため)
        let metadata = String(format: "%@, %@,  ISO %ld, fd %f, %ld msec * %ld , cfa %@, device %@",
                              sequenceName, ISO8601DateFormatter().string(from: Date()), Int(iso), Double(lensPosition),
                              Int((exposure * 1000).rounded()), total, cfa.name, DeviceProfileStore.modelIdentifier)
        do {
            try Storage.write(metadata, to: dir.appendingPathComponent("metadata.csv"))
            try Storage.write(TIFF.encode(mean), to: dir.appendingPathComponent("stacked.tif"))
            if let jpeg = ImageUtil.jpeg(from: mean, cfa: cfa) {
                try Storage.write(jpeg, to: dir.appendingPathComponent("stacked.jpg"))
            }
            publish(.finished("保存しました: imgs/\(sequenceName) (\(total) 枚の平均)"))
        } catch {
            publish(.failed("保存できません: \(error.localizedDescription)"))
        }
        self.stacker = nil
    }
}

extension CameraModel: AVCapturePhotoCaptureDelegate {
    func photoOutput(_ output: AVCapturePhotoOutput, didFinishProcessingPhoto photo: AVCapturePhoto, error: Error?) {
        // delegate は sessionQueue 以外から呼ばれるので, 積算は sessionQueue で順番に行う
        let pixelBuffer = photo.pixelBuffer
        let dng = photo.fileDataRepresentation()
        sessionQueue.async {
            if let error {
                self.retryOrFail("撮影に失敗しました: \(error.localizedDescription)")
                return
            }
            guard let buffer = pixelBuffer else {
                self.retryOrFail("RAW 画像がありません")
                return
            }
            do {
                try self.accumulate(buffer)
            } catch {
                self.retryOrFail("積算できません: \(error.localizedDescription)")
                return
            }
            if let dng {
                try? Storage.write(dng, to: Storage.imageDir(self.sequenceName).appendingPathComponent("\(self.done).dng"))
            }
            self.failures = 0
            self.done += 1
            self.publish(.capturing(done: self.done, total: self.total))
            if self.done >= self.total {
                self.finishSequence()
            } else {
                self.captureNext()
            }
        }
    }

    private func accumulate(_ buffer: CVPixelBuffer) throws {
        CVPixelBufferLockBaseAddress(buffer, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(buffer, .readOnly) }
        let w = CVPixelBufferGetWidth(buffer)
        let h = CVPixelBufferGetHeight(buffer)
        let stride = CVPixelBufferGetBytesPerRow(buffer)
        guard let base = CVPixelBufferGetBaseAddress(buffer) else { throw CoreError("RAW 画像を読めません") }
        if stacker == nil {
            stacker = RawStacker(width: w, height: h)
        }
        guard let s = stacker, s.width == w, s.height == h else { throw CoreError("RAW 画像の大きさが変わりました") }
        try s.add(UnsafeRawPointer(base), rowStride: stride, byteCount: stride * h)
    }

    private func retryOrFail(_ message: String) {
        failures += 1
        if failures >= Self.maxConsecutiveFailures {
            stacker = nil
            capturing = false
            publish(.failed(message))
        } else {
            sessionQueue.asyncAfter(deadline: .now() + 0.5) { self.captureNext() }
        }
    }
}
