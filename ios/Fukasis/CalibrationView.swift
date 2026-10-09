// SPDX-License-Identifier: MIT
// Copyright © 2026 Tsuyoshi Kobayashi(legrs4073)
import FukasisCore
import SwiftUI

/// 波長校正 (Android 版 CalibActivity). 0次光と4本の輝線の位置を合わせて, csv/calibdata/<name>.csv に保存する
struct CalibrationView: View {
    @EnvironmentObject private var profiles: DeviceProfileStore

    @State private var sequences: [String] = []
    @State private var seq = ""
    @State private var image: SequenceImage?
    @State private var folProgress: Double = 450
    @State private var lineProgress: [Double] = [2000, 2200, 2300, 2400]
    @State private var wavelengths: [String] = SpectrumCalibrator.defaultCatalog.map { String($0) }
    @State private var name = ""
    @State private var busy = false
    @State private var message = ""

    private static let colors: [Color] = [.blue, .green, .yellow, .red]

    private var profile: DeviceProfile { profiles.profile }

    var body: some View {
        Form {
            Section {
                Picker("Sequence", selection: $seq) {
                    Text("-").tag("")
                    ForEach(sequences, id: \.self) { Text($0).tag($0) }
                }
                HStack {
                    Button("OPEN") { open(auto: false) }.disabled(seq.isEmpty || busy)
                    Spacer()
                    Button("AUTO") { open(auto: true) }.disabled(seq.isEmpty || busy)
                }
                .buttonStyle(.borderless)
            }
            if let image {
                Section("0次光") {
                    let range = profile.folProgressRange
                    ImageStrip(image: image.preview, x0: image.width - range.upperBound, x1: image.width - range.lowerBound,
                               bandCenter: profile.spectrumParams().bandCenter,
                               lines: [(image.width - Int(folProgress), .white)])
                    Slider(value: $folProgress, in: Double(range.lowerBound)...Double(range.upperBound), step: 1)
                    Text("fol: \(Int(folProgress))").font(.caption)
                }
                Section("輝線") {
                    let range = profile.peakProgressRange
                    ImageStrip(image: image.preview, x0: image.width - range.upperBound, x1: image.width - range.lowerBound,
                               bandCenter: profile.spectrumParams().bandCenter,
                               lines: lineProgress.indices.map { (image.width - Int(lineProgress[$0]), Self.colors[$0]) })
                    ForEach(0..<4, id: \.self) { i in
                        HStack {
                            TextField("nm", text: $wavelengths[i])
                                .keyboardType(.decimalPad)
                                .frame(width: 70)
                            Slider(value: $lineProgress[i], in: Double(range.lowerBound)...Double(range.upperBound), step: 1)
                                .tint(Self.colors[i])
                            Text("\(Int(lineProgress[i]))").font(.caption).frame(width: 44)
                        }
                    }
                }
            }
            Section {
                TextField("Calibration Data Name", text: $name)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                Button("EXPORT CSV") { export() }.disabled(image == nil || busy)
                if !message.isEmpty {
                    Text(message).font(.footnote)
                }
            }
        }
        .navigationTitle("Calibration")
        .onAppear { sequences = Storage.sequences() }
    }

    private func catalog() -> [Double]? {
        let values = wavelengths.map { Double($0.trimmingCharacters(in: .whitespaces)) }
        guard values.allSatisfy({ $0 != nil }) else { return nil }
        return values.compactMap { $0 }
    }

    /// 画像を開く. auto なら 0次光と輝線を自動で合わせる (解析はバックグラウンド)
    private func open(auto: Bool) {
        guard let catalog = catalog() else {
            message = "波長が数値ではありません"
            return
        }
        busy = true
        message = auto ? "自動検出中…" : "読み込み中…"
        let seq = self.seq
        let profile = self.profile
        Task.detached(priority: .userInitiated) {
            var loaded: SequenceImage?
            var result: SpectrumCalibrator.CalibrationResult?
            var failure: String?
            do {
                let img = try SequenceImage.load(seq, params: profile.spectrumParams())
                loaded = img
                if auto {
                    let fol = profile.folProgressRange
                    let peak = profile.peakProgressRange
                    let nm = profile.nmPerPxRange
                    result = try SpectrumCalibrator.calibrate(img.analysis, imgWidth: img.width,
                                                              folProgMin: fol.lowerBound, folProgMax: fol.upperBound,
                                                              peakProgMin: peak.lowerBound, peakProgMax: peak.upperBound,
                                                              catalog: catalog, minNmPerPx: nm.lowerBound, maxNmPerPx: nm.upperBound)
                }
            } catch {
                failure = error.localizedDescription
            }
            let outcome = (image: loaded, result: result, failure: failure)
            await MainActor.run {
                apply(outcome, auto: auto, profile: profile)
            }
        }
    }

    private func apply(_ outcome: (image: SequenceImage?, result: SpectrumCalibrator.CalibrationResult?, failure: String?),
                       auto: Bool, profile: DeviceProfile) {
        busy = false
        if let loaded = outcome.image {
            image = loaded
        }
        if let failure = outcome.failure {
            message = (auto ? "自動検出に失敗しました: " : "") + failure
            return
        }
        guard let r = outcome.result, let img = outcome.image else {
            message = ""
            return
        }
        folProgress = Double(r.folProgress)
        lineProgress = r.peakProgress.map(Double.init)
        if name.isEmpty {
            name = img.seq
        }
        var text = String(format: "自動検出完了 (%@): 輝線 %ld 本中 4 本を対応付け, 直線からのずれ %.2f nm. 確認して EXPORT CSV を押してください",
                          img.fileName, r.peakCount, r.match.rmsNm)
        let band = profile.spectrumParams()
        if let w = SpectrumCalibrator.bandOffsetWarning(img.analysis, bandWidth: band.bandWidth, bandCenter: band.bandCenter) {
            text += "\n注意: " + w
        }
        message = text
    }

    private func export() {
        let name = self.name.trimmingCharacters(in: .whitespaces)
        guard Storage.isValidName(name) else {
            message = "Calibration Data Name を入力してください"
            return
        }
        guard let c = catalog() else {
            message = "波長が数値ではありません"
            return
        }
        // fol との相対距離 (= スライダーの値の差)
        let rel = lineProgress.map { Int($0) - Int(folProgress) }
        guard Set(rel).count == rel.count else {
            message = "同じ位置に2本以上の線があります. 4本を別々の輝線に合わせてください"
            return
        }
        let text = rel.map(String.init).joined(separator: ",") + "\n"
            + c.map { String(format: "%f", $0) }.joined(separator: ",")
        do {
            try Storage.write(text, to: Storage.calibDir.appendingPathComponent(name + ".csv"))
            message = "校正データを保存しました (csv/calibdata/\(name).csv)"
        } catch {
            message = "保存に失敗しました: \(error.localizedDescription)"
        }
    }
}
