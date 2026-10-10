// SPDX-License-Identifier: MIT
// Copyright © 2026 Tsuyoshi Kobayashi(legrs4073)
//
// C++ コア (fukasis_core.h) の Swift ラッパ.
import FukasisCoreC
import Foundation

/// カラーフィルタ配列 (左上 2x2 の読み順). rawValue は FK_CFA_* と同じ
public enum CFA: Int32, CaseIterable, Codable, Sendable {
    case rggb = 0
    case grbg = 1
    case gbrg = 2
    case bggr = 3
    case mono = 4

    public init?(name: String) {
        let v = fk_cfa_parse(name)
        guard v >= 0, let cfa = CFA(rawValue: v) else { return nil }
        self = cfa
    }

    public var name: String { String(cString: fk_cfa_name(rawValue)) }
}

/// 1ch の float 画像 (行優先)
public struct FloatImage: Sendable {
    public let width: Int
    public let height: Int
    public var pixels: [Float]

    public init(width: Int, height: Int, pixels: [Float]) {
        precondition(pixels.count == width * height, "pixels.count must be width * height")
        self.width = width
        self.height = height
        self.pixels = pixels
    }
}

/// スペクトル切り出しのパラメータ (機種ごとに変わりうる)
public struct SpectrumParams: Equatable, Sendable {
    public var tMin: Int
    public var tMax: Int
    public var bandWidth: Int
    public var bandCenter: Double
    public var cfa: CFA
    public var wlMin: Double
    public var wlMax: Double

    /// Galaxy S22 で使ってきた既定値
    public static let `default`: SpectrumParams = {
        let p = fk_default_spectrum_params()
        return SpectrumParams(tMin: Int(p.t_min), tMax: Int(p.t_max), bandWidth: Int(p.band_width),
                              bandCenter: p.band_center, cfa: CFA(rawValue: p.cfa) ?? .gbrg,
                              wlMin: p.wl_min, wlMax: p.wl_max)
    }()

    public init(tMin: Int, tMax: Int, bandWidth: Int, bandCenter: Double, cfa: CFA, wlMin: Double, wlMax: Double) {
        self.tMin = tMin
        self.tMax = tMax
        self.bandWidth = bandWidth
        self.bandCenter = bandCenter
        self.cfa = cfa
        self.wlMin = wlMin
        self.wlMax = wlMax
    }

    var c: fk_spectrum_params {
        fk_spectrum_params(t_min: Int32(tMin), t_max: Int32(tMax), band_width: Int32(bandWidth),
                           band_center: bandCenter, cfa: cfa.rawValue, wl_min: wlMin, wl_max: wlMax)
    }
}

/// コアが返したエラーメッセージ
public struct CoreError: Error, LocalizedError, Equatable {
    public let message: String
    public init(_ message: String) { self.message = message }
    public var errorDescription: String? { message }
}

/// C の文字列 (fk_free で解放するもの) を String にして解放する
private func takeString(_ p: UnsafeMutablePointer<CChar>?) -> String? {
    guard let p else { return nil }
    defer { fk_free(p) }
    return String(cString: p)
}

public enum SpectrumCore {
    /// スペクトル CSV を作る (Android の makecsv と同じ計算)
    public static func makeSpectrum(image: FloatImage, fol: Int, calibration: String, metadata: String,
                                    sensitivity: String, params: SpectrumParams = .default) throws -> String {
        var p = params.c
        var csv: UnsafeMutablePointer<CChar>?
        let err = image.pixels.withUnsafeBufferPointer { px in
            fk_make_spectrum(px.baseAddress, Int32(image.width), Int32(image.height), Int32(fol),
                             calibration, metadata, sensitivity, &p, &csv)
        }
        if let message = takeString(err) {
            throw CoreError(message)
        }
        guard let text = takeString(csv) else { throw CoreError("スペクトルを作れませんでした") }
        return text
    }

    /// 列ごとの (B+G+R) と, スペクトルの帯が写っている行 (不明なら -1)
    public static func columnProfile(image: FloatImage, params: SpectrumParams = .default) -> (profile: [Double], bandCenterY: Int) {
        var p = params.c
        var out = [Double](repeating: 0, count: image.width)
        let band = image.pixels.withUnsafeBufferPointer { px in
            out.withUnsafeMutableBufferPointer { o in
                fk_column_profile(px.baseAddress, Int32(image.width), Int32(image.height), &p, o.baseAddress)
            }
        }
        return (out, Int(band))
    }

    /// 自動校正用の解析結果
    public static func analyze(image: FloatImage, params: SpectrumParams = .default) -> SpectrumCalibrator.ImageProfile {
        let r = columnProfile(image: image, params: params)
        return SpectrumCalibrator.ImageProfile(width: image.width, height: image.height,
                                               bandCenterY: r.bandCenterY, profile: r.profile)
    }

    /// a - b
    public static func subtract(_ a: FloatImage, _ b: FloatImage) throws -> FloatImage {
        guard a.width == b.width, a.height == b.height else {
            throw CoreError("観測画像とダーク画像のサイズが違います")
        }
        var out = [Float](repeating: 0, count: a.pixels.count)
        a.pixels.withUnsafeBufferPointer { pa in
            b.pixels.withUnsafeBufferPointer { pb in
                out.withUnsafeMutableBufferPointer { po in
                    fk_subtract(pa.baseAddress, pb.baseAddress, po.baseAddress, pa.count)
                }
            }
        }
        return FloatImage(width: a.width, height: a.height, pixels: out)
    }

    /// 確認用の RGB プレビュー (width*height*3)
    public static func previewRGB(image: FloatImage, cfa: CFA) -> [UInt8] {
        var out = [UInt8](repeating: 0, count: image.width * image.height * 3)
        image.pixels.withUnsafeBufferPointer { px in
            out.withUnsafeMutableBufferPointer { o in
                fk_preview_rgb8(px.baseAddress, Int32(image.width), Int32(image.height), cfa.rawValue, o.baseAddress)
            }
        }
        return out
    }
}

public enum TIFF {
    /// 1ch float32 の無圧縮 TIFF
    public static func encode(_ image: FloatImage) -> Data {
        var out: UnsafeMutablePointer<UInt8>?
        var size = 0
        image.pixels.withUnsafeBufferPointer { px in
            fk_tiff_encode_f32(px.baseAddress, Int32(image.width), Int32(image.height), &out, &size)
        }
        guard let out else { return Data() }
        defer { fk_free(out) }
        return Data(bytes: out, count: size)
    }

    /// 1ch の無圧縮 TIFF (stacked.tif / darked.tif. Android 版で撮ったものも読める)
    public static func decode(_ data: Data) throws -> FloatImage {
        var px: UnsafeMutablePointer<Float>?
        var w: Int32 = 0
        var h: Int32 = 0
        let err = data.withUnsafeBytes { raw in
            fk_tiff_decode(raw.bindMemory(to: UInt8.self).baseAddress, raw.count, &px, &w, &h)
        }
        if let message = takeString(err) {
            throw CoreError(message)
        }
        guard let px else { throw CoreError("TIFF を読めません") }
        defer { fk_free(px) }
        let count = Int(w) * Int(h)
        return FloatImage(width: Int(w), height: Int(h), pixels: Array(UnsafeBufferPointer(start: px, count: count)))
    }
}

/// RAW (16bit) を足し合わせて平均を作る
public final class RawStacker {
    private let handle: OpaquePointer
    public let width: Int
    public let height: Int

    public init(width: Int, height: Int) {
        self.width = width
        self.height = height
        // 確保に失敗するのはメモリが足りないときだけ
        handle = fk_stacker_new(Int32(width), Int32(height))!
    }

    deinit {
        fk_stacker_free(handle)
    }

    /// 16bit の RAW を1枚足す. rowStride は1行のバイト数
    public func add(_ base: UnsafeRawPointer, rowStride: Int, byteCount: Int) throws {
        if let message = takeString(fk_stacker_add_u16(handle, base.assumingMemoryBound(to: UInt8.self), rowStride, byteCount)) {
            throw CoreError(message)
        }
    }

    public var count: Int { Int(fk_stacker_count(handle)) }

    /// 平均画像. 1枚も足していなければ nil
    public func mean() -> FloatImage? {
        var out = [Float](repeating: 0, count: width * height)
        let ok = out.withUnsafeMutableBufferPointer { fk_stacker_mean(handle, $0.baseAddress) }
        return ok != 0 ? FloatImage(width: width, height: height, pixels: out) : nil
    }
}
