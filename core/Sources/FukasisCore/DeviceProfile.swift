// SPDX-License-Identifier: MIT
// Copyright © 2026 Tsuyoshi Kobayashi(legrs4073)
//
// 端末ごとの設定 (profiles/device_profiles.json). Android 版 DeviceProfile.java と同じ形式.
// 書かれていない項目は Galaxy S22 で使ってきた値になる.
import Foundation

public struct DeviceProfile: Codable, Equatable, Sendable {
    public var id: String
    public var name: String?
    /// "android" / "ios". 書かなければどちらでも
    public var platform: String?
    /// 機種名の先頭 (Android: Build.MODEL, iPhone: "iPhone15,2" などの機種 ID)
    public var models: [String]?
    /// 実機で観測して確かめたか
    public var verified: Bool?
    public var camera: Camera?
    public var spectrum: Spectrum?
    public var calibration: Calibration?
    public var preview: Preview?

    public struct Camera: Codable, Equatable, Sendable {
        /// Android: カメラ ID ("0" など), iPhone: "wide" / "ultrawide" / "telephoto". 書かなければ自動
        public var id: String?
        /// "RGGB" など. 書かなければカメラが報告する値
        public var cfa: String?

        public init(id: String? = nil, cfa: String? = nil) {
            self.id = id
            self.cfa = cfa
        }
    }

    public struct Spectrum: Codable, Equatable, Sendable {
        public var tMin: Int?
        public var tMax: Int?
        public var bandWidth: Int?
        public var bandCenter: Double?

        public init(tMin: Int? = nil, tMax: Int? = nil, bandWidth: Int? = nil, bandCenter: Double? = nil) {
            self.tMin = tMin
            self.tMax = tMax
            self.bandWidth = bandWidth
            self.bandCenter = bandCenter
        }
    }

    public struct Calibration: Codable, Equatable, Sendable {
        /// 0次光スライダーの範囲 [min, max] (progress = 画像の幅 - x)
        public var folProgress: [Int]?
        /// 輝線スライダーの範囲
        public var peakProgress: [Int]?
        /// 自動校正で許す分散 [min, max] (nm/pixel)
        public var nmPerPx: [Double]?

        public init(folProgress: [Int]? = nil, peakProgress: [Int]? = nil, nmPerPx: [Double]? = nil) {
            self.folProgress = folProgress
            self.peakProgress = peakProgress
            self.nmPerPx = nmPerPx
        }
    }

    /// 撮影画面のプレビューの見せ方 (Android 版のみ使用)
    public struct Preview: Codable, Equatable, Sendable {
        public var keystone: Double?
        public var stretch: Double?
        public var focusZoomCenterY: Double?
        public var guideLineY: Double?
        public var guideLineYPointing: Double?
    }

    public init(id: String, name: String? = nil, platform: String? = nil, models: [String]? = nil) {
        self.id = id
        self.name = name
        self.platform = platform
        self.models = models
    }

    // MARK: - 既定値を補った値

    public static let defaultTRange = (min: 1800, max: 2800)
    public static let defaultFolProgress = (min: 300, max: 600)
    public static let defaultPeakProgress = (min: 1800, max: 2900)

    /// スペクトル計算のパラメータ. CFA が書かれていなければ cameraCFA (カメラが報告する値) を使う
    public func spectrumParams(cameraCFA: CFA? = nil) -> SpectrumParams {
        var p = SpectrumParams.default
        p.tMin = spectrum?.tMin ?? p.tMin
        p.tMax = spectrum?.tMax ?? p.tMax
        p.bandWidth = spectrum?.bandWidth ?? p.bandWidth
        p.bandCenter = spectrum?.bandCenter ?? p.bandCenter
        p.cfa = camera?.cfa.flatMap(CFA.init(name:)) ?? cameraCFA ?? p.cfa
        return p
    }

    public var folProgressRange: ClosedRange<Int> {
        Self.range(calibration?.folProgress, Self.defaultFolProgress)
    }

    public var peakProgressRange: ClosedRange<Int> {
        Self.range(calibration?.peakProgress, Self.defaultPeakProgress)
    }

    public var nmPerPxRange: ClosedRange<Double> {
        if let r = calibration?.nmPerPx, r.count == 2, r[0] > 0, r[0] < r[1] {
            return r[0]...r[1]
        }
        return SpectrumCalibrator.minNmPerPx...SpectrumCalibrator.maxNmPerPx
    }

    private static func range(_ v: [Int]?, _ def: (min: Int, max: Int)) -> ClosedRange<Int> {
        if let v, v.count == 2, v[0] < v[1] {
            return v[0]...v[1]
        }
        return def.min...def.max
    }

    // MARK: - 機種の判定

    /// この端末向けか. 一致すれば一致した機種名の長さ (長いほど具体的), しなければ nil
    public func matchLength(platform: String, model: String) -> Int? {
        if let p = self.platform, p.lowercased() != platform.lowercased() {
            return nil
        }
        let m = model.lowercased()
        return (models ?? []).filter { !$0.isEmpty && m.hasPrefix($0.lowercased()) }.map(\.count).max()
    }

    /// 蛍光灯の写真から推定した値で, スペクトルの範囲とスライダーの範囲を置き換える
    public func applying(_ g: SpectrumCalibrator.GeometryEstimate) -> DeviceProfile {
        var p = self
        var s = p.spectrum ?? Spectrum()
        s.tMin = g.tRange.min
        s.tMax = g.tRange.max
        if let band = g.bandCenter {
            s.bandCenter = band
        }
        p.spectrum = s
        var c = p.calibration ?? Calibration()
        c.folProgress = [g.folProgressRange.min, g.folProgressRange.max]
        c.peakProgress = [g.peakProgressRange.min, g.peakProgressRange.max]
        let nm = g.nmPerPxRange
        c.nmPerPx = [(nm.min * 1000).rounded() / 1000, (nm.max * 1000).rounded() / 1000]
        p.calibration = c
        p.verified = false
        return p
    }

    // MARK: - JSON

    public static func decoder() -> JSONDecoder {
        let d = JSONDecoder()
        d.keyDecodingStrategy = .convertFromSnakeCase
        return d
    }

    public static func encoder() -> JSONEncoder {
        let e = JSONEncoder()
        e.keyEncodingStrategy = .convertToSnakeCase
        e.outputFormatting = [.prettyPrinted, .sortedKeys]
        return e
    }

    public func jsonData() throws -> Data {
        try Self.encoder().encode(self)
    }
}

/// profiles/device_profiles.json の中身
public struct DeviceProfileCatalog: Codable, Equatable, Sendable {
    public var schema: Int?
    public var profiles: [DeviceProfile]

    public init(schema: Int? = 1, profiles: [DeviceProfile]) {
        self.schema = schema
        self.profiles = profiles
    }

    public static func load(_ data: Data) throws -> DeviceProfileCatalog {
        try DeviceProfile.decoder().decode(DeviceProfileCatalog.self, from: data)
    }

    /// この端末向けのプロファイル (一番具体的に一致したもの). なければ nil
    public func match(platform: String, model: String) -> DeviceProfile? {
        var best: (len: Int, profile: DeviceProfile)?
        for p in profiles {
            if let len = p.matchLength(platform: platform, model: model), len > (best?.len ?? 0) {
                best = (len, p)
            }
        }
        return best?.profile
    }

    /// 一致するものがなければ, 既定値だけの (Galaxy S22 と同じ値の) プロファイル
    public func resolve(platform: String, model: String) -> DeviceProfile {
        match(platform: platform, model: model)
            ?? DeviceProfile(id: "generic", name: "未登録の端末 (\(model))", platform: platform, models: [model])
    }
}
