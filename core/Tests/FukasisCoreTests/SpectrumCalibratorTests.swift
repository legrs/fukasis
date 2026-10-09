// SPDX-License-Identifier: MIT
// Android 版 SpectrumCalibratorTest.java と同じ合成データで, Swift 版の自動校正を検証する.
import Foundation
import Testing
@testable import FukasisCore

// 合成データ: 幅 4000, 0次光はスライダー値 450 (x = 3550)
private let width = 4000
private let folProgress = 450
private let fol = width - folProgress

/// 合成の分散: 波長 = 400 + 0.3 * (距離 - 1400)
private func distanceOf(_ wavelength: Double) -> Int {
    Int((1400 + (wavelength - 400) / 0.3).rounded())
}

/// 背景 + 0次光 + 指定波長の輝線 (+ Bayer の1画素ごとの段差)
private func syntheticProfile(_ wavelengths: [Double], _ amplitudes: [Double]) -> [Double] {
    (0..<width).map { x in
        var p = 30.0 + (x % 2 == 0 ? 3 : -3)
        p += 5000 * exp(-pow(Double(x - fol), 2) / (2 * 4.0))
        for (i, wl) in wavelengths.enumerated() {
            let lx = fol - distanceOf(wl)
            p += amplitudes[i] * exp(-pow(Double(x - lx), 2) / (2 * 9.0))
        }
        return p
    }
}

private func image(_ profile: [Double]) -> SpectrumCalibrator.ImageProfile {
    .init(width: profile.count, height: 1000, bandCenterY: 500, profile: profile)
}

@Test func computeDenoIsCorrectForSimpleCase() {
    let deno = SpectrumCalibrator.computeDeno([0, 1, 2, 3])
    #expect(abs(deno[0] - -6) < 1e-9)
    #expect(abs(deno[1] - 2) < 1e-9)
}

@Test func lagrangeInterpolateMatchesLinear() {
    let tRef = [0.0, 10]
    let v = SpectrumCalibrator.lagrangeInterpolate(5, tRef: tRef, cRef: [400, 500], deno: SpectrumCalibrator.computeDeno(tRef))
    #expect(abs(v - 450) < 1e-6)
}

@Test func detectPeaksFindsTwoPeaks() {
    var s = [Double](repeating: 5, count: 100)
    s[20] = 100
    s[21] = 10
    s[70] = 80
    s[19] = 10
    s[69] = 10
    s[71] = 10
    #expect(SpectrumCalibrator.detectPeaks(s, threshold: 0.2, minDistance: 10) == [20, 70])
}

@Test func detectPeaksReturnsEmptyForFlat() {
    #expect(SpectrumCalibrator.detectPeaks([Double](repeating: 10, count: 50), threshold: 0.2, minDistance: 5).isEmpty)
}

@Test func validateCalibration() {
    #expect(!SpectrumCalibrator.validateCalibration(tRef: [100, 100, 200, 300], cRef: [435.8, 546.1, 576.96, 611.6]))
    #expect(!SpectrumCalibrator.validateCalibration(tRef: [100, 200, 100, 300], cRef: [435.8, 546.1, 588.0, 611.6]))
    #expect(SpectrumCalibrator.validateCalibration(tRef: [1800, 2100, 2400, 2700], cRef: [435.8, 546.1, 576.96, 611.6]))
}

@Test func matchCatalogPicksCatalogLinesAmongBrighterDistractors() throws {
    let catalog = SpectrumCalibrator.defaultCatalog
    let wl = [405.4, 435.8, 487.7, 546.1, 588.0, 611.6, 631.0]
    let intens: [Double] = [900, 100, 800, 120, 90, 110, 700]
    let m = try #require(SpectrumCalibrator.matchCatalog(
        distances: wl.map { Double(distanceOf($0)) }, intensities: intens, catalog: catalog,
        minNmPerPx: SpectrumCalibrator.minNmPerPx, maxNmPerPx: SpectrumCalibrator.maxNmPerPx,
        maxRmsNm: SpectrumCalibrator.maxFitRmsNm))
    for (i, c) in catalog.enumerated() {
        #expect(m.distances[i] == Double(distanceOf(c)))
    }
    #expect(abs(m.nmPerPx - 0.3) < 0.01)
    #expect(m.rmsNm < 1)
}

@Test func matchCatalogKeepsOrderOfUnsortedCatalog() throws {
    let catalog = [611.6, 435.8, 588.0, 546.1]
    let d = [435.8, 546.1, 588.0, 611.6].map { Double(distanceOf($0)) }
    let m = try #require(SpectrumCalibrator.matchCatalog(distances: d, intensities: nil, catalog: catalog,
                                                         minNmPerPx: 0.2, maxNmPerPx: 0.45, maxRmsNm: 4))
    #expect(m.distances == catalog.map { Double(distanceOf($0)) })
}

@Test func matchCatalogReturnsNilWhenNotEnoughOrNoFit() {
    let catalog = SpectrumCalibrator.defaultCatalog
    #expect(SpectrumCalibrator.matchCatalog(distances: [1500, 1900, 2000], intensities: nil, catalog: catalog,
                                            minNmPerPx: 0.2, maxNmPerPx: 0.45, maxRmsNm: 4) == nil)
    #expect(SpectrumCalibrator.matchCatalog(distances: [1500, 1700, 1900, 2100], intensities: nil, catalog: catalog,
                                            minNmPerPx: 0.2, maxNmPerPx: 0.45, maxRmsNm: 4) == nil)
}

@Test func calibrateFindsFolAndCatalogLines() throws {
    let catalog = SpectrumCalibrator.defaultCatalog
    let p = syntheticProfile([405.4, 435.8, 487.7, 546.1, 588.0, 611.6], [400, 300, 500, 600, 200, 900])
    let r = try SpectrumCalibrator.calibrate(image(p), imgWidth: width, folProgMin: 350, folProgMax: 600,
                                             peakProgMin: 1800, peakProgMax: 2900, catalog: catalog)
    #expect(r.folProgress == folProgress)
    for (i, c) in catalog.enumerated() {
        #expect(abs(r.peakProgress[i] - (folProgress + distanceOf(c))) <= 1)
    }
}

@Test func calibrateFailsWhenLinesCannotBeIdentified() {
    #expect(throws: SpectrumCalibrator.CalibrationError.self) {
        try SpectrumCalibrator.calibrate(image(syntheticProfile([500, 520], [500, 500])), imgWidth: width,
                                         folProgMin: 350, folProgMax: 600, peakProgMin: 1800, peakProgMax: 2900,
                                         catalog: SpectrumCalibrator.defaultCatalog)
    }
}

@Test func detectFolProgressRejectsWidthMismatchAndDarkImage() {
    #expect(throws: SpectrumCalibrator.CalibrationError.self) {
        try SpectrumCalibrator.detectFolProgress(image([Double](repeating: 30, count: width)), imgWidth: width, progMin: 350, progMax: 600)
    }
    #expect(throws: SpectrumCalibrator.CalibrationError.self) {
        try SpectrumCalibrator.detectFolProgress(image(syntheticProfile([], [])), imgWidth: width + 2, progMin: 350, progMax: 600)
    }
}

@Test func detectFolProgressSearchesOnlySliderRange() throws {
    var p = syntheticProfile([], [])
    p[width - 10] = 1e6
    #expect(try SpectrumCalibrator.detectFolProgress(image(p), imgWidth: width, progMin: 350, progMax: 600) == folProgress)
}

@Test func findPeaksInWindowIgnoresZerothOrderOutsideWindow() {
    let peaks = SpectrumCalibrator.findPeaksInWindow(syntheticProfile([546.1], [50]), fol: fol, minDist: 1350, maxDist: 2450)
    #expect(peaks.distances.count == 1)
    #expect(abs(peaks.distances[0] - Double(distanceOf(546.1))) <= 1)
}

@Test func bandOffsetWarningOnlyWhenBandLeavesStrip() throws {
    let p = [Double](repeating: 0, count: 10)
    #expect(SpectrumCalibrator.bandOffsetWarning(.init(width: 10, height: 1000, bandCenterY: 520, profile: p)) == nil)
    #expect(SpectrumCalibrator.bandOffsetWarning(.init(width: 10, height: 1000, bandCenterY: -1, profile: p)) == nil)
    let below = try #require(SpectrumCalibrator.bandOffsetWarning(.init(width: 10, height: 1000, bandCenterY: 560, profile: p)))
    #expect(below.contains("下") && below.contains("60"))
    let above = try #require(SpectrumCalibrator.bandOffsetWarning(.init(width: 10, height: 1000, bandCenterY: 400, profile: p)))
    #expect(above.contains("上") && above.contains("100"))
    // 帯の位置を変えた端末では, その位置を基準にする
    #expect(SpectrumCalibrator.bandOffsetWarning(.init(width: 10, height: 1000, bandCenterY: 700, profile: p), bandWidth: 80, bandCenter: 0.7) == nil)
}

@Test func estimateGeometryFromFluorescentLamp() throws {
    let p = syntheticProfile([405.4, 435.8, 487.7, 546.1, 588.0, 611.6, 631.0], [400, 300, 500, 600, 200, 900, 300])
    let g = try SpectrumCalibrator.estimateGeometry(image(p), catalog: SpectrumCalibrator.defaultCatalog)
    #expect(g.folX == fol)
    #expect(g.folProgress == folProgress)
    #expect(abs(g.match.nmPerPx - 0.3) < 0.005)
    #expect(abs(g.distanceAtMin - 1400) < 5)
    #expect(abs(g.distanceAtMax - 2400) < 5)
    #expect(abs(g.tRange.min - 1350) <= 5 && abs(g.tRange.max - 2450) <= 5)
    #expect(g.folProgressRange == (300, 600))
    #expect(abs(g.peakProgressRange.min - 1700) <= 5 && abs(g.peakProgressRange.max - 3000) <= 5)
    #expect(abs(g.nmPerPxRange.min - 0.2) < 0.01 && abs(g.nmPerPxRange.max - 0.45) < 0.01)
    #expect(g.bandCenter == 0.5)
    #expect(g.warning == nil)

    // 推定した範囲で, いつもの自動校正が通る
    let r = try SpectrumCalibrator.calibrate(image(p), imgWidth: width, folProgMin: g.folProgressRange.min,
                                             folProgMax: g.folProgressRange.max, peakProgMin: g.peakProgressRange.min,
                                             peakProgMax: g.peakProgressRange.max, catalog: SpectrumCalibrator.defaultCatalog,
                                             minNmPerPx: g.nmPerPxRange.min, maxNmPerPx: g.nmPerPxRange.max)
    #expect(r.folProgress == folProgress)
}

@Test func estimateGeometryFailsWithoutZerothOrder() {
    #expect(throws: SpectrumCalibrator.CalibrationError.self) {
        try SpectrumCalibrator.estimateGeometry(image([Double](repeating: 30, count: width)), catalog: SpectrumCalibrator.defaultCatalog)
    }
}
