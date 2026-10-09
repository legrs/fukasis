// SPDX-License-Identifier: MIT
// Copyright © 2026 Tsuyoshi Kobayashi(legrs4073)
import AVFoundation
import FukasisCore
import SwiftUI
import UniformTypeIdentifiers

/// 端末設定 (Android 版 DeviceActivity). 新しい iPhone で使うための準備をする
struct DeviceSetupView: View {
    @EnvironmentObject private var profiles: DeviceProfileStore

    @State private var sequences: [String] = []
    @State private var seq = ""
    @State private var draft: DeviceProfile?
    @State private var estimateText = ""
    @State private var busy = false
    @State private var importing = false
    @State private var message = ""

    private var profile: DeviceProfile { profiles.profile }

    var body: some View {
        Form {
            Section("この端末") {
                info("機種", DeviceProfileStore.modelIdentifier)
                info("プロファイル", "\(profile.id) (\(profile.name ?? "-"))" + (profiles.isOverride ? " [この端末に保存]" : ""))
                if profile.id == "generic" {
                    Text("未登録の端末です. Galaxy S22 の値を使っています. 下の手順でこの端末用の設定を作ってください.")
                        .font(.footnote).foregroundStyle(.orange)
                }
                let p = profile.spectrumParams()
                info("切り出す範囲", "\(p.tMin)-\(p.tMax) px")
                info("積算する帯", String(format: "%ld px @ %.3f", p.bandWidth, p.bandCenter))
                info("スライダー", "0次光 \(profile.folProgressRange.lowerBound)-\(profile.folProgressRange.upperBound), "
                     + "輝線 \(profile.peakProgressRange.lowerBound)-\(profile.peakProgressRange.upperBound)")
                Button("端末情報を書き出す") { exportReport() }
            }
            Section {
                Picker("蛍光灯のシーケンス", selection: $seq) {
                    Text("-").tag("")
                    ForEach(sequences, id: \.self) { Text($0).tag($0) }
                }
                Button("蛍光灯の写真から推定する") { estimate() }.disabled(seq.isEmpty || busy)
                if !estimateText.isEmpty {
                    Text(estimateText).font(.system(.caption, design: .monospaced)).textSelection(.enabled)
                }
                if draft != nil {
                    Button("この端末のプロファイルにする") { saveDraft() }
                }
            } header: {
                Text("新しい端末のセットアップ")
            } footer: {
                Text("三波長型蛍光灯を撮影して (Capture), そのシーケンスを選んでください. 0次光が画像の右側に写るように取り付けます.")
            }
            Section {
                Button("プロファイルの JSON を読み込む") { importing = true }
                Button("同梱のプロファイルに戻す", role: .destructive) {
                    profiles.reset()
                    message = "同梱のプロファイルに戻しました"
                }
                if !message.isEmpty {
                    Text(message).font(.footnote)
                }
            }
        }
        .navigationTitle("Device setup")
        .onAppear { sequences = Storage.sequences() }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.json, .data]) { result in
            importProfile(result)
        }
    }

    private func info(_ title: String, _ value: String) -> some View {
        HStack(alignment: .top) {
            Text(title).foregroundStyle(.secondary)
            Spacer()
            Text(value).multilineTextAlignment(.trailing).textSelection(.enabled)
        }
        .font(.footnote)
    }

    private var fileStem: String {
        DeviceProfileStore.modelIdentifier.replacingOccurrences(of: ",", with: "_")
    }

    /// 端末とカメラの情報 (プロファイルを作る材料. docs/porting.md 参照)
    private func exportReport() {
        var cameras: [[String: Any]] = []
        let discovery = AVCaptureDevice.DiscoverySession(
            deviceTypes: [.builtInWideAngleCamera, .builtInUltraWideCamera, .builtInTelephotoCamera],
            mediaType: .video, position: .back)
        for d in discovery.devices {
            let f = d.activeFormat
            let dims = CMVideoFormatDescriptionGetDimensions(f.formatDescription)
            cameras.append([
                "type": d.deviceType.rawValue,
                "name": d.localizedName,
                "iso_range": [f.minISO, f.maxISO],
                "exposure_ms_range": [f.minExposureDuration.seconds * 1000, f.maxExposureDuration.seconds * 1000],
                "format": "\(dims.width)x\(dims.height)",
                "field_of_view_deg": f.videoFieldOfView,
                "custom_exposure": d.isExposureModeSupported(.custom),
                "custom_lens_position": d.isLockingFocusWithCustomLensPositionSupported,
            ])
        }
        var report: [String: Any] = [
            "model": DeviceProfileStore.modelIdentifier,
            "system": UIDevice.current.systemName + " " + UIDevice.current.systemVersion,
            "cameras": cameras,
            "note": "RAW の形式と色の配列は撮影画面の上に表示されます",
        ]
        if let p = try? profile.jsonData(), let obj = try? JSONSerialization.jsonObject(with: p) {
            report["profile"] = obj
        }
        do {
            let data = try JSONSerialization.data(withJSONObject: report, options: [.prettyPrinted, .sortedKeys])
            let url = Storage.deviceDir.appendingPathComponent("\(fileStem)_report.json")
            try Storage.write(data, to: url)
            message = "device/\(url.lastPathComponent) に保存しました"
        } catch {
            message = "保存できません: \(error.localizedDescription)"
        }
    }

    private func estimate() {
        busy = true
        estimateText = "推定中…"
        let seq = self.seq
        let base = self.profile
        let model = DeviceProfileStore.modelIdentifier
        Task.detached(priority: .userInitiated) {
            var text: String
            var result: DeviceProfile?
            do {
                var params = base.spectrumParams()
                var img = try SequenceImage.load(seq, params: params)
                // スペクトルの帯が積算する帯から外れていたら, 帯の写っている行で解析し直す
                if img.analysis.bandCenterY >= 0,
                   SpectrumCalibrator.bandOffsetWarning(img.analysis, bandWidth: params.bandWidth, bandCenter: params.bandCenter) != nil {
                    params.bandCenter = Double(img.analysis.bandCenterY) / Double(img.analysis.height)
                    img = try SequenceImage.load(seq, params: params)
                }
                let g = try SpectrumCalibrator.estimateGeometry(img.analysis, catalog: SpectrumCalibrator.defaultCatalog)
                var p = base
                if p.id == "generic" {
                    p = DeviceProfile(id: model.lowercased().replacingOccurrences(of: ",", with: "-"),
                                      name: model, platform: DeviceProfileStore.platform, models: [model])
                }
                p = p.applying(g)
                result = p
                let t = g.tRange
                text = String(format: "0次光     : x = %ld (slider %ld)\n分散      : %.3f nm/px (残差 %.2f nm, 輝線 %ld 本)\n400-700nm : 0次光から %.0f-%.0f px\n→ t %ld-%ld px で切り出します\n",
                              g.folX, g.folProgress, g.match.nmPerPx, g.match.rmsNm, g.peakCount,
                              g.distanceAtMin, g.distanceAtMax, t.min, t.max)
                if let w = g.warning {
                    text += "注意: \(w)\n"
                }
                if let json = try? p.jsonData() {
                    text += "\n" + String(decoding: json, as: UTF8.self)
                }
            } catch {
                text = "推定できませんでした: \(error.localizedDescription)"
            }
            let outcome = (text: text, profile: result)
            await MainActor.run {
                busy = false
                estimateText = outcome.text
                draft = outcome.profile
            }
        }
    }

    private func saveDraft() {
        guard let draft else { return }
        do {
            try profiles.save(draft)
            // リポジトリの profiles/device_profiles.json に追加してもらえるよう, 共有できる場所にも書き出す
            let url = Storage.deviceDir.appendingPathComponent("\(fileStem)_profile.json")
            try Storage.write(draft.jsonData(), to: url)
            message = "この端末のプロファイルにしました (device/\(url.lastPathComponent))"
            self.draft = nil
        } catch {
            message = "保存できません: \(error.localizedDescription)"
        }
    }

    private func importProfile(_ result: Result<URL, Error>) {
        do {
            let url = try result.get()
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            let data = try Data(contentsOf: url)
            let p: DeviceProfile
            if let single = try? DeviceProfile.decoder().decode(DeviceProfile.self, from: data) {
                p = single
            } else {
                // device_profiles.json そのものを選んだ場合は, この端末に合うものを使う
                let catalog = try DeviceProfileCatalog.load(data)
                guard let m = catalog.match(platform: DeviceProfileStore.platform, model: DeviceProfileStore.modelIdentifier) else {
                    throw CoreError("この端末 (\(DeviceProfileStore.modelIdentifier)) 向けのプロファイルがありません")
                }
                p = m
            }
            try profiles.save(p)
            message = "\(p.id) を読み込みました"
        } catch {
            message = "読み込めません: \(error.localizedDescription)"
        }
    }
}
