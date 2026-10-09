// SPDX-License-Identifier: MIT
// Copyright © 2026 Tsuyoshi Kobayashi(legrs4073)
import Foundation

/// ファイルの置き場所. Android 版の Documents/FUKASIS-app/ と同じ構成を, アプリの書類フォルダに作る
/// (「ファイル」アプリの「このiPhone内」→「FUKASIS」から見える)
enum Storage {
    static var root: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("FUKASIS-app", isDirectory: true)
    }

    static func imageDir(_ seq: String) -> URL {
        root.appendingPathComponent("imgs", isDirectory: true).appendingPathComponent(seq, isDirectory: true)
    }

    static var calibDir: URL { root.appendingPathComponent("csv/calibdata", isDirectory: true) }
    static var spectrumDir: URL { root.appendingPathComponent("csv/spectrum", isDirectory: true) }
    static var sensitivityDir: URL { root.appendingPathComponent("csv/sensitivity", isDirectory: true) }
    static var deviceDir: URL { root.appendingPathComponent("device", isDirectory: true) }

    /// 名前として使えるか (フォルダを作るので "/" や ".." は不可)
    static func isValidName(_ name: String) -> Bool {
        !name.isEmpty && !name.contains("/") && !name.contains("\\") && name != "." && name != ".."
            && !name.hasPrefix(".")
    }

    static func write(_ data: Data, to url: URL) throws {
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try data.write(to: url, options: .atomic)
    }

    static func write(_ text: String, to url: URL) throws {
        try write(Data(text.utf8), to: url)
    }

    /// dir の中身を新しい順に
    private static func contents(of dir: URL, directories: Bool) -> [URL] {
        let keys: [URLResourceKey] = [.contentModificationDateKey, .isDirectoryKey]
        guard let items = try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: keys,
                                                                       options: [.skipsHiddenFiles]) else {
            return []
        }
        func info(_ u: URL) -> (date: Date, isDir: Bool) {
            let v = try? u.resourceValues(forKeys: Set(keys))
            return (v?.contentModificationDate ?? .distantPast, v?.isDirectory ?? false)
        }
        return items.filter { info($0).isDir == directories }.sorted { info($0).date > info($1).date }
    }

    /// 撮影したシーケンス (新しい順)
    static func sequences() -> [String] {
        contents(of: root.appendingPathComponent("imgs", isDirectory: true), directories: true).map(\.lastPathComponent)
    }

    /// 校正データの名前 (拡張子なし, 新しい順)
    static func calibrations() -> [String] {
        contents(of: calibDir, directories: false).filter { $0.pathExtension == "csv" }
            .map { $0.deletingPathExtension().lastPathComponent }
    }

    static func spectra() -> [URL] {
        contents(of: spectrumDir, directories: false).filter { $0.pathExtension == "csv" }
    }

    static func sensitivityFiles() -> [URL] {
        contents(of: sensitivityDir, directories: false)
    }

    /// darked.tif があればそれ, なければ stacked.tif
    static func analysisImage(_ seq: String) -> URL? {
        for name in ["darked.tif", "stacked.tif"] {
            let u = imageDir(seq).appendingPathComponent(name)
            if FileManager.default.fileExists(atPath: u.path) {
                return u
            }
        }
        return nil
    }
}
