// SPDX-License-Identifier: MIT
// Copyright © 2026 Tsuyoshi Kobayashi(legrs4073)
import Charts
import SwiftUI

/// 出力したスペクトルの一覧 (Android 版 ViewActivity)
struct SpectrumListView: View {
    @State private var files: [URL] = []

    var body: some View {
        List(files, id: \.self) { url in
            NavigationLink(url.deletingPathExtension().lastPathComponent) {
                SpectrumChartView(url: url)
            }
        }
        .overlay {
            if files.isEmpty {
                Text("csv/spectrum にスペクトルがありません").foregroundStyle(.secondary)
            }
        }
        .navigationTitle("Spectra")
        .onAppear { files = Storage.spectra() }
    }
}

struct SpectrumChartView: View {
    let url: URL

    struct Point: Identifiable {
        let wavelength: Double
        let intensity: Double
        var id: Double { wavelength }
    }

    @State private var header = ""
    @State private var points: [Point] = []

    var body: some View {
        VStack(alignment: .leading) {
            Text(header).font(.caption).foregroundStyle(.secondary)
            Chart(points) {
                LineMark(x: .value("wavelength (nm)", $0.wavelength), y: .value("relative intensity", $0.intensity))
                    .interpolationMethod(.linear)
            }
            .chartXScale(domain: (points.first?.wavelength ?? 400)...(points.last?.wavelength ?? 700))
            .chartXAxisLabel("wavelength / nm")
        }
        .padding()
        .navigationTitle(url.deletingPathExtension().lastPathComponent)
        .toolbar {
            ShareLink(item: url)
        }
        .onAppear(perform: load)
    }

    /// 1行目はメタデータ, 2行目は列名, 以降 "波長,強度"
    private func load() {
        guard let text = try? String(contentsOf: url, encoding: .utf8) else { return }
        let lines = text.split(whereSeparator: \.isNewline)
        header = lines.first.map(String.init) ?? ""
        points = lines.compactMap { line -> Point? in
            let cols = line.split(separator: ",")
            guard cols.count >= 2, let x = Double(cols[0].trimmingCharacters(in: .whitespaces)),
                  let y = Double(cols[1].trimmingCharacters(in: .whitespaces)) else { return nil }
            return Point(wavelength: x, intensity: y)
        }
        .sorted { $0.wavelength < $1.wavelength }
    }
}
