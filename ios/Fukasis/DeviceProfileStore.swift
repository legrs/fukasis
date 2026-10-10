// SPDX-License-Identifier: MIT
// Copyright © 2026 Tsuyoshi Kobayashi(legrs4073)
import FukasisCore
import Foundation

/// この iPhone で使う端末プロファイル (Android 版 DeviceProfiles.java と同じ優先順位)
///  1. 端末設定で保存した (読み込んだ) プロファイル
///  2. 同梱の device_profiles.json のうち機種 ID が一致するもの
///  3. 既定値 (Galaxy S22 と同じ値)
final class DeviceProfileStore: ObservableObject {
    static let platform = "ios"

    @Published private(set) var profile: DeviceProfile
    @Published private(set) var isOverride = false

    /// "iPhone15,2" など (シミュレータではシミュレートしている機種)
    static let modelIdentifier: String = {
        if let sim = ProcessInfo.processInfo.environment["SIMULATOR_MODEL_IDENTIFIER"] {
            return sim
        }
        var info = utsname()
        uname(&info)
        return withUnsafeBytes(of: &info.machine) { raw in
            String(decoding: raw.prefix { $0 != 0 }, as: UTF8.self)
        }
    }()

    private static var overrideURL: URL {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return dir.appendingPathComponent("device_profile.json")
    }

    init() {
        let loaded = Self.load()
        profile = loaded.profile
        isOverride = loaded.isOverride
    }

    static func bundledCatalog() -> DeviceProfileCatalog? {
        guard let url = Bundle.main.url(forResource: "device_profiles", withExtension: "json"),
              let data = try? Data(contentsOf: url) else { return nil }
        return try? DeviceProfileCatalog.load(data)
    }

    private static func load() -> (profile: DeviceProfile, isOverride: Bool) {
        if let data = try? Data(contentsOf: overrideURL),
           let p = try? DeviceProfile.decoder().decode(DeviceProfile.self, from: data) {
            return (p, true)
        }
        let catalog = bundledCatalog() ?? DeviceProfileCatalog(profiles: [])
        return (catalog.resolve(platform: platform, model: modelIdentifier), false)
    }

    func save(_ p: DeviceProfile) throws {
        try Storage.write(p.jsonData(), to: Self.overrideURL)
        profile = p
        isOverride = true
    }

    func reset() {
        try? FileManager.default.removeItem(at: Self.overrideURL)
        let loaded = Self.load()
        profile = loaded.profile
        isOverride = loaded.isOverride
    }
}
