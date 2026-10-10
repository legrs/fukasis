// SPDX-License-Identifier: MIT
// Copyright © 2026 Tsuyoshi Kobayashi(legrs4073)
//
// 自動波長校正. Android 版 app/app/src/main/java/com/example/ssa/SpectrumCalibrator.java と同じアルゴリズム
// (片方を直したらもう片方も直すこと. テストも同じ合成データで書いてある).
//
// 座標系: x は画像の列, fol は 0次光の列, 距離 d = fol - x. スライダーの値 (progress) は imgWidth - x.
import Foundation

public enum SpectrumCalibrator {
    /// 三波長型蛍光灯の輝線 (nm). 588.0 はオレンジの輝線2本のうち長波長側
    public static let defaultCatalog: [Double] = [435.8, 546.1, 588.0, 611.6]
    /// 太陽 Fraunhofer 由来の代表線
    public static let solarCatalog: [Double] = [430.8, 486.1, 589.3, 656.3]

    /// 分散 (nm/pixel) の既定の探索範囲 (Galaxy S22)
    public static let minNmPerPx = 0.2
    public static let maxNmPerPx = 0.45
    public static let maxFitRmsNm = 4.0
    public static let peakThreshold = 0.05
    public static let peakMinDistance = 12
    public static let smoothRadius = 2
    public static let maxCandidates = 12

    public struct CalibrationError: Error, LocalizedError, Equatable {
        public let message: String
        public init(_ message: String) { self.message = message }
        public var errorDescription: String? { message }
    }

    // MARK: - 補間

    public static func computeDeno(_ tRef: [Double]) -> [Double] {
        tRef.indices.map { j in
            var d = 1.0
            for k in tRef.indices where k != j {
                d *= tRef[j] - tRef[k]
            }
            return d
        }
    }

    public static func lagrangeInterpolate(_ t: Double, tRef: [Double], cRef: [Double], deno: [Double]) -> Double {
        var tp = 0.0
        for j in tRef.indices {
            var nume = 1.0
            for k in tRef.indices where k != j {
                nume *= t - tRef[k]
            }
            if deno[j] != 0 {
                tp += cRef[j] * nume / deno[j]
            }
        }
        return tp
    }

    // MARK: - ピーク検出

    public static func smooth(_ s: [Double], radius: Int) -> [Double] {
        guard !s.isEmpty else { return [] }
        return s.indices.map { i in
            let lo = max(0, i - radius)
            let hi = min(s.count - 1, i + radius)
            var sum = 0.0
            for j in lo...hi { sum += s[j] }
            return sum / Double(hi - lo + 1)
        }
    }

    /// 極大 + 相対閾値 + 最小距離 (強い方を残す). 昇順のインデックス
    public static func detectPeaks(_ spectrum: [Double], threshold: Double, minDistance: Int) -> [Int] {
        guard spectrum.count >= 3, let maxValue = spectrum.max(), maxValue > 0 else { return [] }
        let absThresh = maxValue * threshold
        var candidates: [Int] = []
        for i in 1..<(spectrum.count - 1) {
            let cur = spectrum[i]
            // 平坦な頂上 (cur == next) でも1点は拾えるよう右側は >=
            if cur > spectrum[i - 1] && cur >= spectrum[i + 1] && cur > absThresh {
                candidates.append(i)
            }
        }
        // 強度の降順 (同じ強度なら左から) に見て, minDistance 以内の近傍を抑制
        let sorted = candidates.sorted { a, b in
            spectrum[a] != spectrum[b] ? spectrum[a] > spectrum[b] : a < b
        }
        var suppressed = [Bool](repeating: false, count: spectrum.count)
        var result: [Int] = []
        for idx in sorted where !suppressed[idx] {
            result.append(idx)
            let lo = max(0, idx - minDistance)
            let hi = min(spectrum.count - 1, idx + minDistance)
            for j in lo...hi where j != idx {
                suppressed[j] = true
            }
        }
        return result.sorted()
    }

    public struct Peaks: Equatable {
        /// fol からの距離 (昇順)
        public let distances: [Double]
        public let intensities: [Double]
    }

    /// fol からの距離 [minDist, maxDist] の範囲だけでピークを探す (範囲内の最小値を背景として引く)
    public static func findPeaksInWindow(_ profile: [Double], fol: Int, minDist: Int, maxDist: Int) -> Peaks {
        let lo = max(1, minDist)
        let hi = min(maxDist, fol)
        guard fol < profile.count, hi - lo >= 3 else { return Peaks(distances: [], intensities: []) }
        var s = (lo...hi).map { profile[fol - $0] }
        s = smooth(s, radius: smoothRadius)
        let minValue = s.min() ?? 0
        s = s.map { $0 - minValue }
        let idx = detectPeaks(s, threshold: peakThreshold, minDistance: peakMinDistance)
        return Peaks(distances: idx.map { Double(lo + $0) }, intensities: idx.map { s[$0] })
    }

    // MARK: - カタログとの対応付け

    public struct CatalogMatch: Equatable {
        /// catalog[i] の輝線の fol からの距離 (catalog と同じ順)
        public let distances: [Double]
        /// 波長 = offsetNm + nmPerPx * 距離
        public let nmPerPx: Double
        public let offsetNm: Double
        public let rmsNm: Double

        /// 直線フィットで波長 nm が写る距離
        public func distance(atNm nm: Double) -> Double { (nm - offsetNm) / nmPerPx }
    }

    /// 検出ピークをカタログ波長に対応付ける (輝線の間隔の比で決まるので余分な線が混じっていてもよい)
    public static func matchCatalog(distances peakDistances: [Double], intensities peakIntensities: [Double]?,
                                    catalog: [Double], minNmPerPx: Double, maxNmPerPx: Double,
                                    maxRmsNm: Double) -> CatalogMatch? {
        let n = catalog.count
        guard n >= 2, peakDistances.count >= n else { return nil }
        let catOrder = catalog.indices.sorted { catalog[$0] < catalog[$1] }
        let wl = catOrder.map { catalog[$0] }
        for i in 1..<n where wl[i] == wl[i - 1] {
            return nil
        }

        let useIntensity = peakIntensities?.count == peakDistances.count
        var order = Array(peakDistances.indices)
        if useIntensity, let inten = peakIntensities {
            // 強度の降順 (同じなら元の順). Java の安定ソートと同じ結果にする
            order = order.enumerated().sorted { a, b in
                inten[a.element] != inten[b.element] ? inten[a.element] > inten[b.element] : a.offset < b.offset
            }.map(\.element)
        }
        let m = min(order.count, max(maxCandidates, n))
        let byDistance = Array(order.prefix(m)).enumerated().sorted { a, b in
            peakDistances[a.element] != peakDistances[b.element]
                ? peakDistances[a.element] < peakDistances[b.element] : a.offset < b.offset
        }.map(\.element)
        let cand = byDistance.map { peakDistances[$0] }
        let candIntensity = byDistance.map { useIntensity ? peakIntensities![$0] : 0 }

        var pick = Array(0..<n)
        var best: (rms: Double, slope: Double, offset: Double, intensity: Double)?
        var bestPick: [Int]?
        while true {
            if let fit = fitLine(cand, pick, wl), fit.slope >= minNmPerPx, fit.slope <= maxNmPerPx, fit.rms <= maxRmsNm {
                let intensitySum = pick.reduce(0.0) { $0 + candIntensity[$1] }
                let better: Bool
                if let b = best {
                    better = fit.rms < b.rms - 1e-9 || (abs(fit.rms - b.rms) <= 1e-9 && intensitySum > b.intensity)
                } else {
                    better = true
                }
                if better {
                    best = (fit.rms, fit.slope, fit.offset, intensitySum)
                    bestPick = pick
                }
            }
            // 次の組合せ
            var k = n - 1
            while k >= 0 && pick[k] == m - n + k {
                k -= 1
            }
            if k < 0 { break }
            pick[k] += 1
            for j in (k + 1)..<n {
                pick[j] = pick[j - 1] + 1
            }
        }
        guard let b = best, let bp = bestPick else { return nil }
        var distances = [Double](repeating: 0, count: n)
        for i in 0..<n {
            distances[catOrder[i]] = cand[bp[i]]
        }
        return CatalogMatch(distances: distances, nmPerPx: b.slope, offsetNm: b.offset, rmsNm: b.rms)
    }

    private static func fitLine(_ x: [Double], _ pick: [Int], _ wl: [Double]) -> (rms: Double, slope: Double, offset: Double)? {
        let n = Double(pick.count)
        var sx = 0.0, sy = 0.0, sxx = 0.0, sxy = 0.0
        for (i, p) in pick.enumerated() {
            let xi = x[p]
            sx += xi
            sy += wl[i]
            sxx += xi * xi
            sxy += xi * wl[i]
        }
        let den = n * sxx - sx * sx
        guard den != 0 else { return nil }
        let slope = (n * sxy - sx * sy) / den
        let offset = (sy - slope * sx) / n
        var ss = 0.0
        for (i, p) in pick.enumerated() {
            let r = wl[i] - (offset + slope * x[p])
            ss += r * r
        }
        return ((ss / n).squareRoot(), slope, offset)
    }

    /// tRef に重複がなく, cRef が 350-750nm 内であること
    public static func validateCalibration(tRef: [Double], cRef: [Double]) -> Bool {
        guard tRef.count == cRef.count, tRef.count >= 2 else { return false }
        for i in tRef.indices {
            for k in (i + 1)..<tRef.count where tRef[i] == tRef[k] {
                return false
            }
        }
        guard cRef.allSatisfy({ $0 >= 350 && $0 <= 750 }) else { return false }
        return !computeDeno(tRef).contains(0)
    }

    // MARK: - 0次光

    /// x ∈ [xMin, xMax] で値が最大の列. 範囲が空なら -1
    public static func estimateFolInRange(_ colSum: [Double], xMin: Int, xMax: Int) -> Int {
        let lo = max(0, xMin)
        let hi = min(colSum.count - 1, xMax)
        guard lo <= hi else { return -1 }
        var best = lo
        var x = lo + 1
        while x <= hi {
            if colSum[x] > colSum[best] {
                best = x
            }
            x += 1
        }
        return best
    }

    /// 画像の列プロファイル
    public struct ImageProfile: Equatable {
        public let width: Int
        public let height: Int
        /// スペクトルの帯が写っている行. 不明なら -1
        public let bandCenterY: Int
        public let profile: [Double]

        public init(width: Int, height: Int, bandCenterY: Int, profile: [Double]) {
            self.width = width
            self.height = height
            self.bandCenterY = bandCenterY
            self.profile = profile
        }
    }

    /// スペクトルの帯が積算する帯から外れていれば警告文. 問題なければ nil
    public static func bandOffsetWarning(_ img: ImageProfile, bandWidth: Int = 80, bandCenter: Double = 0.5) -> String? {
        guard img.bandCenterY >= 0 else { return nil }
        let half = bandWidth / 2
        let offset = img.bandCenterY - Int((Double(img.height) * bandCenter).rounded(.down))
        // 帯の中心が積算範囲の 3/4 より外にあれば, 帯の大部分が積算から漏れている
        if abs(offset) <= half * 3 / 4 { return nil }
        return "スペクトルの帯が積算する位置から\(offset > 0 ? "下" : "上")に \(abs(offset)) px ずれています"
            + " (スペクトル出力は \(2 * half) px の帯を使います). 分光器の取り付けか端末設定の帯の位置を確認してください"
    }

    private static func median(_ v: [Double]) -> Double {
        let sorted = v.sorted()
        return sorted.isEmpty ? 0 : sorted[sorted.count / 2]
    }

    /// 0次光の位置をスライダーで表せる範囲 (progress ∈ [progMin, progMax]) から探す. スライダーの値を返す
    public static func detectFolProgress(_ img: ImageProfile, imgWidth: Int, progMin: Int, progMax: Int) throws -> Int {
        guard img.width == imgWidth else {
            throw CalibrationError("tif (幅 \(img.width)) と表示中の画像 (幅 \(imgWidth)) の大きさが違います")
        }
        let fol = estimateFolInRange(img.profile, xMin: imgWidth - progMax, xMax: imgWidth - progMin)
        guard fol >= 0 else { throw CalibrationError("0次光を探す範囲が画像の外です") }
        let peak = img.profile[fol]
        guard peak > 0, peak > 2 * max(median(img.profile), 0) else {
            throw CalibrationError("0次光が見つかりません (スライダーの範囲内に明るい点がありません)")
        }
        return imgWidth - fol
    }

    // MARK: - 校正

    public struct CalibrationResult: Equatable {
        public let folProgress: Int
        /// catalog[i] の輝線のスライダー値 (catalog と同じ順)
        public let peakProgress: [Int]
        public let match: CatalogMatch
        public let peakCount: Int
    }

    public static func calibrate(_ img: ImageProfile, imgWidth: Int, folProgMin: Int, folProgMax: Int,
                                 peakProgMin: Int, peakProgMax: Int, catalog: [Double],
                                 minNmPerPx: Double = minNmPerPx, maxNmPerPx: Double = maxNmPerPx) throws -> CalibrationResult {
        let folProgress = try detectFolProgress(img, imgWidth: imgWidth, progMin: folProgMin, progMax: folProgMax)
        let fol = imgWidth - folProgress
        let peaks = findPeaksInWindow(img.profile, fol: fol, minDist: peakProgMin - folProgress, maxDist: peakProgMax - folProgress)
        guard let match = matchCatalog(distances: peaks.distances, intensities: peaks.intensities, catalog: catalog,
                                       minNmPerPx: minNmPerPx, maxNmPerPx: maxNmPerPx, maxRmsNm: maxFitRmsNm) else {
            throw CalibrationError("輝線をカタログの波長に対応付けられません (検出 \(peaks.distances.count) 本). 波長の値を確認するか, 手動で合わせてください")
        }
        guard validateCalibration(tRef: match.distances, cRef: catalog) else {
            throw CalibrationError("校正データが不正です (波長は 350-750nm で, 重複のないように)")
        }
        let progress = match.distances.map { folProgress + Int($0.rounded()) }
        return CalibrationResult(folProgress: folProgress, peakProgress: progress, match: match, peakCount: peaks.distances.count)
    }

    // MARK: - 新しい端末のセットアップ

    /// 蛍光灯の写真から求めた, その端末 (と筐体) のスペクトルの写り方
    public struct GeometryEstimate: Equatable {
        public let imageWidth: Int
        public let folX: Int
        public let match: CatalogMatch
        public let peakCount: Int
        /// スペクトルの帯が写っている行 (画像の高さに対する割合). 不明なら nil
        public let bandCenter: Double?
        /// wlMin / wlMax nm が写る 0次光からの距離 (直線フィットによる)
        public let distanceAtMin: Double
        public let distanceAtMax: Double

        public var folProgress: Int { imageWidth - folX }

        /// スペクトルを切り出す範囲 (少し余裕をもたせる)
        public var tRange: (min: Int, max: Int) {
            let margin = max(50.0, 0.05 * (distanceAtMax - distanceAtMin))
            let lo = max(1, Int((distanceAtMin - margin).rounded(.down)))
            let hi = min(folX - 1, Int((distanceAtMax + margin).rounded(.up)))
            return (lo, max(lo + 1, hi))
        }

        /// 0次光スライダーの範囲 (筐体の組み立て誤差を見込んで ±150px)
        public var folProgressRange: (min: Int, max: Int) {
            (max(1, folProgress - 150), min(imageWidth - 1, folProgress + 150))
        }

        /// 輝線スライダーの範囲 (0次光がスライダーの範囲のどこにあっても, 出力する波長域の線を合わせられる)
        public var peakProgressRange: (min: Int, max: Int) {
            let f = folProgressRange
            return (max(1, f.min + Int(distanceAtMin.rounded(.down))), min(imageWidth - 1, f.max + Int(distanceAtMax.rounded(.up))))
        }

        /// 自動校正で許す分散の範囲
        public var nmPerPxRange: (min: Double, max: Double) {
            (match.nmPerPx / 1.5, match.nmPerPx * 1.5)
        }

        /// 出力したい波長域が画像からはみ出していれば警告
        public var warning: String? {
            if distanceAtMax >= Double(folX) {
                return "長波長側が画像の左端からはみ出しています (分光器の向きか 0次光の位置を確認してください)"
            }
            if distanceAtMin <= 0 {
                return "短波長側が 0次光に重なっています"
            }
            return nil
        }
    }

    /// 0次光をこの割合より右から探す (0次光は画像の右側に写るように組み立てる)
    public static let folSearchFromFraction = 0.6

    /// 蛍光灯などの輝線が写った画像から, 0次光の位置・分散・スペクトルの範囲を推定する.
    /// 新しい端末のプロファイルを作るときに使う (Galaxy S22 用の範囲を前提にしない).
    public static func estimateGeometry(_ img: ImageProfile, catalog: [Double], wlMin: Double = 400, wlMax: Double = 700) throws -> GeometryEstimate {
        let w = img.width
        guard w >= 100, img.profile.count == w else { throw CalibrationError("画像が小さすぎます") }
        let fol = estimateFolInRange(img.profile, xMin: Int(Double(w) * folSearchFromFraction), xMax: w - 1)
        guard fol >= 0, img.profile[fol] > 2 * max(median(img.profile), 0) else {
            throw CalibrationError("0次光が見つかりません (画像の右側 \(Int((1 - folSearchFromFraction) * 100))% に明るい点がありません)")
        }
        // 0次光のすその広がりを避けて, 画像の左端まで探す
        let minDist = max(20, w / 20)
        let peaks = findPeaksInWindow(img.profile, fol: fol, minDist: minDist, maxDist: fol)
        guard let match = matchCatalog(distances: peaks.distances, intensities: peaks.intensities, catalog: catalog,
                                       minNmPerPx: 0.02, maxNmPerPx: 2.0, maxRmsNm: maxFitRmsNm) else {
            throw CalibrationError("輝線をカタログの波長に対応付けられません (検出 \(peaks.distances.count) 本)")
        }
        let band: Double? = img.bandCenterY >= 0 && img.height > 0
            ? (Double(img.bandCenterY) / Double(img.height) * 1000).rounded() / 1000 : nil
        return GeometryEstimate(imageWidth: w, folX: fol, match: match, peakCount: peaks.distances.count,
                                bandCenter: band, distanceAtMin: match.distance(atNm: wlMin),
                                distanceAtMax: match.distance(atNm: wlMax))
    }
}
