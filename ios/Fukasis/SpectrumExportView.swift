// SPDX-License-Identifier: MIT
// Copyright © 2026 Tsuyoshi Kobayashi(legrs4073)
import FukasisCore
import SwiftUI
import UniformTypeIdentifiers

/// スペクトル出力 (Android 版 CsvActivity). darked.tif (なければ stacked.tif) から csv/spectrum/<seq>.csv を作る
struct SpectrumExportView: View {
    @EnvironmentObject private var profiles: DeviceProfileStore

    @State private var sequences: [String] = []
    @State private var calibrations: [String] = []
    @State private var sensitivities: [URL] = []
    @State private var seq = ""
    @State private var calibration = ""
    @AppStorage("sensitivityFile") private var sensitivityName = ""
    @State private var image: SequenceImage?
    @State private var folProgress: Double = 450
    @State private var importing = false
    @State private var busy = false
    @State private var message = ""

    private var profile: DeviceProfile { profiles.profile }

    var body: some View {
        Form {
            Section {
                Picker("Sequence", selection: $seq) {
                    Text("-").tag("")
                    ForEach(sequences, id: \.self) { Text($0).tag($0) }
                }
                Picker("Calibration", selection: $calibration) {
                    Text("-").tag("")
                    ForEach(calibrations, id: \.self) { Text($0).tag($0) }
                }
                Picker("Sensitivity", selection: $sensitivityName) {
                    Text("-").tag("")
                    ForEach(sensitivities, id: \.lastPathComponent) { Text($0.lastPathComponent).tag($0.lastPathComponent) }
                }
                Button("感度データを追加…") { importing = true }
            }
            Section {
                HStack {
                    Button("OPEN") { open(auto: false) }.disabled(seq.isEmpty || busy)
                    Spacer()
                    Button("AUTO (0次光)") { open(auto: true) }.disabled(seq.isEmpty || busy)
                }
                .buttonStyle(.borderless)
                if let image {
                    let range = profile.folProgressRange
                    ImageStrip(image: image.preview, x0: image.width - range.upperBound - 400,
                               x1: image.width - range.lowerBound + 100,
                               bandCenter: profile.spectrumParams().bandCenter,
                               lines: [(image.width - Int(folProgress), .white)])
                    Slider(value: $folProgress, in: Double(range.lowerBound)...Double(range.upperBound), step: 1)
                    Text("fol: \(Int(folProgress))").font(.caption)
                }
            }
            Section {
                Button("EXPORT CSV") { export() }
                    .disabled(image == nil || calibration.isEmpty || sensitivityName.isEmpty || busy)
                if !message.isEmpty {
                    Text(message).font(.footnote)
                }
            }
        }
        .navigationTitle("CSV")
        .onAppear(perform: reload)
        .fileImporter(isPresented: $importing, allowedContentTypes: [.commaSeparatedText, .plainText, .data]) { result in
            importSensitivity(result)
        }
    }

    private func reload() {
        sequences = Storage.sequences()
        calibrations = Storage.calibrations()
        sensitivities = Storage.sensitivityFiles()
        // 一番最近の校正データを選んでおく
        if calibration.isEmpty, let latest = calibrations.first {
            calibration = latest
        }
        if !sensitivityName.isEmpty && !sensitivities.contains(where: { $0.lastPathComponent == sensitivityName }) {
            sensitivityName = ""
        }
    }

    /// 選んだ感度データをアプリの中にコピーしておく (次回からも選べる)
    private func importSensitivity(_ result: Result<URL, Error>) {
        do {
            let url = try result.get()
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            let data = try Data(contentsOf: url)
            try Storage.write(data, to: Storage.sensitivityDir.appendingPathComponent(url.lastPathComponent))
            sensitivities = Storage.sensitivityFiles()
            sensitivityName = url.lastPathComponent
        } catch {
            message = "感度データを読み込めません: \(error.localizedDescription)"
        }
    }

    private func open(auto: Bool) {
        busy = true
        message = auto ? "自動検出中…" : "読み込み中…"
        let seq = self.seq
        let profile = self.profile
        Task.detached(priority: .userInitiated) {
            var loaded: SequenceImage?
            var progress: Int?
            var failure: String?
            do {
                let img = try SequenceImage.load(seq, params: profile.spectrumParams())
                loaded = img
                if auto {
                    let r = profile.folProgressRange
                    progress = try SpectrumCalibrator.detectFolProgress(img.analysis, imgWidth: img.width,
                                                                        progMin: r.lowerBound, progMax: r.upperBound)
                }
            } catch {
                failure = error.localizedDescription
            }
            let outcome = (image: loaded, progress: progress, failure: failure)
            await MainActor.run {
                busy = false
                if let img = outcome.image {
                    image = img
                }
                if let failure = outcome.failure {
                    message = (auto ? "自動検出に失敗しました: " : "") + failure
                    return
                }
                guard let p = outcome.progress, let img = outcome.image else {
                    message = ""
                    return
                }
                folProgress = Double(p)
                var text = "0次光を検出しました (\(img.fileName)): \(p)"
                let band = profile.spectrumParams()
                if let w = SpectrumCalibrator.bandOffsetWarning(img.analysis, bandWidth: band.bandWidth, bandCenter: band.bandCenter) {
                    text += "\n注意: " + w
                }
                message = text
            }
        }
    }

    private func export() {
        guard let image else { return }
        busy = true
        message = "出力中…"
        let seq = self.seq
        let calibURL = Storage.calibDir.appendingPathComponent(calibration + ".csv")
        let sensitURL = Storage.sensitivityDir.appendingPathComponent(sensitivityName)
        let fol = image.width - Int(folProgress)
        // Bayer 配列は metadata.csv に記録があればそちらが優先される (コアが読む)
        let params = profile.spectrumParams()
        Task.detached(priority: .userInitiated) {
            let result: String
            do {
                guard let imgURL = Storage.analysisImage(seq) else { throw CoreError("画像がありません") }
                let img = try TIFF.decode(Data(contentsOf: imgURL))
                let csv = try SpectrumCore.makeSpectrum(
                    image: img, fol: fol,
                    calibration: String(contentsOf: calibURL, encoding: .utf8),
                    metadata: String(contentsOf: Storage.imageDir(seq).appendingPathComponent("metadata.csv"), encoding: .utf8),
                    sensitivity: String(contentsOf: sensitURL, encoding: .utf8),
                    params: params)
                try Storage.write(csv, to: Storage.spectrumDir.appendingPathComponent(seq + ".csv"))
                result = "スペクトルを保存しました (csv/spectrum/\(seq).csv)"
            } catch {
                result = "失敗: \(error.localizedDescription)"
            }
            await MainActor.run {
                busy = false
                message = result
            }
        }
    }
}
