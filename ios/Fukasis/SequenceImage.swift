// SPDX-License-Identifier: MIT
// Copyright © 2026 Tsuyoshi Kobayashi(legrs4073)
import FukasisCore
import SwiftUI

/// 校正・スペクトル出力で使う, シーケンスの画像 (表示用の stacked.jpg と解析用の tif)
struct SequenceImage {
    let seq: String
    let preview: CGImage
    let analysis: SpectrumCalibrator.ImageProfile
    let fileName: String

    var width: Int { analysis.width }

    /// 読み込みは重いので UI スレッドから呼ばないこと
    static func load(_ seq: String, params: SpectrumParams) throws -> SequenceImage {
        guard let url = Storage.analysisImage(seq) else {
            throw CoreError("\(seq) に darked.tif / stacked.tif がありません")
        }
        let image = try TIFF.decode(Data(contentsOf: url))
        let jpgURL = Storage.imageDir(seq).appendingPathComponent("stacked.jpg")
        let shown = UIImage(contentsOfFile: jpgURL.path)?.cgImage ?? ImageUtil.cgImage(from: image, cfa: params.cfa)
        guard let preview = shown else { throw CoreError("画像を表示できません") }
        return SequenceImage(seq: seq, preview: preview, analysis: SpectrumCore.analyze(image: image, params: params),
                             fileName: url.lastPathComponent)
    }
}

/// 画像の一部 (x ∈ [x0, x1)) を横いっぱいに表示し, 指定した列に縦線を引く
struct ImageStrip: View {
    let image: CGImage
    let x0: Int
    let x1: Int
    let bandCenter: Double
    /// (列, 色)
    let lines: [(x: Int, color: Color)]

    var body: some View {
        let cropped = ImageUtil.crop(image, x0: x0, x1: x1, bandCenter: bandCenter, height: max(40, (x1 - x0) / 4))
        GeometryReader { geo in
            ZStack(alignment: .topLeading) {
                if let cropped {
                    Image(decorative: cropped, scale: 1)
                        .resizable()
                        .interpolation(.none)
                        .frame(width: geo.size.width, height: geo.size.height)
                }
                ForEach(Array(lines.enumerated()), id: \.offset) { _, line in
                    let fx = (CGFloat(line.x - x0) + 0.5) / CGFloat(max(1, x1 - x0)) * geo.size.width
                    Rectangle()
                        .fill(line.color)
                        .frame(width: 1, height: geo.size.height)
                        .offset(x: fx)
                }
            }
        }
        .frame(height: 90)
        .background(Color.black)
        .clipped()
    }
}
