// SPDX-License-Identifier: MIT
// Copyright © 2026 Tsuyoshi Kobayashi(legrs4073)
import FukasisCore
import SwiftUI

/// ダーク減算 (Android 版 DarkActivity). light/stacked.tif - dark/stacked.tif を light/darked.tif に保存する
struct DarkView: View {
    @State private var sequences: [String] = []
    @State private var light = ""
    @State private var dark = ""
    @State private var busy = false
    @State private var message = ""

    var body: some View {
        Form {
            Picker("Light", selection: $light) {
                Text("-").tag("")
                ForEach(sequences, id: \.self) { Text($0).tag($0) }
            }
            Picker("Dark", selection: $dark) {
                Text("-").tag("")
                ForEach(sequences, id: \.self) { Text($0).tag($0) }
            }
            Section {
                Button("PROCESS") { process() }
                    .disabled(light.isEmpty || dark.isEmpty || light == dark || busy)
                if !message.isEmpty {
                    Text(message).font(.footnote)
                }
            }
        }
        .navigationTitle("Dark")
        .onAppear { sequences = Storage.sequences() }
    }

    private func process() {
        busy = true
        message = "処理中…"
        let light = self.light, dark = self.dark
        Task.detached(priority: .userInitiated) {
            let result: String
            do {
                let l = try TIFF.decode(Data(contentsOf: Storage.imageDir(light).appendingPathComponent("stacked.tif")))
                let d = try TIFF.decode(Data(contentsOf: Storage.imageDir(dark).appendingPathComponent("stacked.tif")))
                let out = try SpectrumCore.subtract(l, d)
                try Storage.write(TIFF.encode(out), to: Storage.imageDir(light).appendingPathComponent("darked.tif"))
                result = "imgs/\(light)/darked.tif を保存しました"
            } catch {
                result = "失敗: \(error.localizedDescription)"
            }
            await MainActor.run {
                message = result
                busy = false
            }
        }
    }
}
