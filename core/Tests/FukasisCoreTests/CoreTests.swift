// SPDX-License-Identifier: MIT
import Foundation
import Testing
@testable import FukasisCore

private let calib = "1900,2100,2300,2500\n430.000000,490.000000,550.000000,610.000000"
private let meta = "test, 2026-10-01T00:00:00Z,  ISO 3200, fd 1.000000, 100 msec * 1 "

private func sensitivity() -> String {
    var s = "sensitivity\nwavelength,b,g,r\n"
    for l in stride(from: 350, through: 800, by: 10) {
        s += "\(l),0.2,0.3,0.5\n"
    }
    return s
}

/// 距離 2200 (520nm) に輝線がある画像 (core/cpptest と同じ)
private func peakImage(cfaShift: Bool = false) -> FloatImage {
    let w = 3300, h = 200, fol = 3200
    var px = [Float](repeating: 0, count: w * h)
    for y in 0..<h {
        for x in 0..<w {
            let i = Double(fol - x)
            px[y * w + x] = Float(10 + 1000 * exp(-pow(i - 2200, 2) / 18))
        }
    }
    return FloatImage(width: w, height: h, pixels: px)
}

@Test func cfaNames() {
    #expect(CFA(name: "gbrg") == .gbrg)
    #expect(CFA(name: "xyz") == nil)
    #expect(CFA.rggb.name == "RGGB")
    #expect(SpectrumParams.default.tMin == 1800 && SpectrumParams.default.cfa == .gbrg)
}

@Test func makeSpectrumFindsPeak() throws {
    let csv = try SpectrumCore.makeSpectrum(image: peakImage(), fol: 3200, calibration: calib, metadata: meta,
                                            sensitivity: sensitivity())
    let lines = csv.split(separator: "\n").map(String.init)
    #expect(lines[0] == meta)
    #expect(lines.count == 2 + 999)
    let rows = lines.dropFirst(2).map { $0.split(separator: ",").map { Double($0)! } }
    let peak = try #require(rows.max { $0[1] < $1[1] })
    #expect(abs(peak[0] - 520) < 0.5)
    #expect(abs(peak[1] - 1) < 1e-6)
}

@Test func makeSpectrumReportsErrors() {
    #expect(throws: CoreError.self) {
        try SpectrumCore.makeSpectrum(image: peakImage(), fol: 0, calibration: calib, metadata: meta, sensitivity: sensitivity())
    }
    #expect(throws: CoreError.self) {
        try SpectrumCore.makeSpectrum(image: peakImage(), fol: 3200, calibration: "1,2", metadata: meta, sensitivity: sensitivity())
    }
}

@Test func columnProfileAndAnalyze() {
    let img = peakImage()
    let a = SpectrumCore.analyze(image: img)
    #expect(a.width == img.width && a.profile.count == img.width)
    let maxX = a.profile.indices.max { a.profile[$0] < a.profile[$1] }
    #expect(maxX == 3200 - 2200)
}

@Test func tiffRoundTrip() throws {
    let img = FloatImage(width: 5, height: 3, pixels: (0..<15).map { Float($0) * 0.5 - 2 })
    let data = TIFF.encode(img)
    #expect(data.count > 15 * 4)
    let back = try TIFF.decode(data)
    #expect(back.width == 5 && back.height == 3 && back.pixels == img.pixels)
    #expect(throws: CoreError.self) { try TIFF.decode(Data([1, 2, 3])) }
}

@Test func stackerAveragesFrames() throws {
    let s = RawStacker(width: 2, height: 2)
    #expect(s.mean() == nil)
    for v: UInt16 in [100, 300] {
        let raw = [UInt16](repeating: v, count: 4)
        try raw.withUnsafeBytes { try s.add($0.baseAddress!, rowStride: 4, byteCount: $0.count) }
    }
    #expect(s.count == 2)
    #expect(s.mean()?.pixels == [200, 200, 200, 200])
    let small = [UInt16](repeating: 1, count: 1)
    #expect(throws: CoreError.self) {
        try small.withUnsafeBytes { try s.add($0.baseAddress!, rowStride: 4, byteCount: $0.count) }
    }
}

@Test func subtractAndPreview() throws {
    let a = FloatImage(width: 2, height: 2, pixels: [5, 6, 7, 8])
    let b = FloatImage(width: 2, height: 2, pixels: [1, 1, 1, 1])
    #expect(try SpectrumCore.subtract(a, b).pixels == [4, 5, 6, 7])
    #expect(throws: CoreError.self) { try SpectrumCore.subtract(a, FloatImage(width: 1, height: 1, pixels: [0])) }
    let rgb = SpectrumCore.previewRGB(image: a, cfa: .mono)
    #expect(rgb.count == 12 && rgb[0] == 0 && rgb[9] == 255)
}
