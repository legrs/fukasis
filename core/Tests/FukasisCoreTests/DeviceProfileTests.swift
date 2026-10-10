// SPDX-License-Identifier: MIT
import Foundation
import Testing
@testable import FukasisCore

/// リポジトリの profiles/device_profiles.json
private func repositoryCatalog() throws -> DeviceProfileCatalog {
    let url = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent() // FukasisCoreTests
        .deletingLastPathComponent() // Tests
        .deletingLastPathComponent() // core
        .deletingLastPathComponent() // repository
        .appendingPathComponent("profiles/device_profiles.json")
    return try DeviceProfileCatalog.load(Data(contentsOf: url))
}

@Test func repositoryProfilesAreValid() throws {
    let catalog = try repositoryCatalog()
    #expect(catalog.schema == 1)
    #expect(Set(catalog.profiles.map(\.id)).count == catalog.profiles.count, "id は重複させない")
    for p in catalog.profiles {
        let s = p.spectrumParams()
        #expect(s.tMin < s.tMax, "\(p.id): t_min < t_max")
        #expect(s.bandWidth > 0 && s.bandCenter > 0 && s.bandCenter < 1, "\(p.id): band")
        #expect(p.camera?.cfa.map { CFA(name: $0) != nil } ?? true, "\(p.id): cfa")
        #expect(p.calibration?.folProgress.map { $0.count == 2 && $0[0] < $0[1] } ?? true, "\(p.id): fol_progress")
        #expect(p.calibration?.peakProgress.map { $0.count == 2 && $0[0] < $0[1] } ?? true, "\(p.id): peak_progress")
        #expect(["android", "ios", nil].contains(p.platform), "\(p.id): platform")
    }
}

@Test func galaxyS22KeepsPreviousValues() throws {
    let s22 = try #require(try repositoryCatalog().match(platform: "android", model: "SM-S901Q"))
    #expect(s22.id == "galaxy-s22")
    #expect(s22.spectrumParams(cameraCFA: .rggb) == SpectrumParams.default) // カメラの報告より設定を優先
    #expect(s22.peakProgressRange == 1800...2900)
    #expect(s22.nmPerPxRange == 0.2...0.45)
    #expect(s22.camera?.id == "0")
}

@Test func unknownDeviceFallsBackToDefaults() throws {
    let catalog = try repositoryCatalog()
    #expect(catalog.match(platform: "android", model: "Pixel 8") == nil)
    #expect(catalog.match(platform: "ios", model: "SM-S901Q") == nil) // platform が違う
    let generic = catalog.resolve(platform: "ios", model: "iPhone15,2")
    #expect(generic.id == "generic")
    #expect(generic.spectrumParams(cameraCFA: .rggb).cfa == .rggb) // 未登録ならカメラの報告を使う
    #expect(generic.folProgressRange == 300...600)
}

@Test func mostSpecificModelWins() throws {
    let json = """
    {"profiles": [
      {"id": "a", "models": ["SM-S90"]},
      {"id": "b", "models": ["SM-S901"], "spectrum": {"t_min": 1000, "t_max": 2000}}
    ]}
    """
    let catalog = try DeviceProfileCatalog.load(Data(json.utf8))
    #expect(catalog.match(platform: "android", model: "sm-s901e")?.id == "b")
    #expect(catalog.match(platform: "android", model: "SM-S906")?.id == "a")
    #expect(catalog.match(platform: "android", model: "SM-S901E")?.spectrumParams().tMin == 1000)
}

@Test func applyingGeometryAndJSONRoundTrip() throws {
    let g = SpectrumCalibrator.GeometryEstimate(
        imageWidth: 4000, folX: 3500,
        match: .init(distances: [1500, 1800, 1950, 2050], nmPerPx: 0.25, offsetNm: 60, rmsNm: 0.5),
        peakCount: 6, bandCenter: 0.47, distanceAtMin: 1360, distanceAtMax: 2560)
    let p = DeviceProfile(id: "new", platform: "ios", models: ["iPhone15,2"]).applying(g)
    #expect(p.spectrum?.tMin == 1300 && p.spectrum?.tMax == 2620)
    #expect(p.spectrum?.bandCenter == 0.47)
    #expect(p.calibration?.folProgress == [350, 650])
    #expect(p.calibration?.peakProgress == [1710, 3210])
    #expect(p.verified == false)

    let data = try p.jsonData()
    let text = String(decoding: data, as: UTF8.self)
    #expect(text.contains("\"t_min\" : 1300"))
    #expect(text.contains("\"fol_progress\""))
    let back = try DeviceProfile.decoder().decode(DeviceProfile.self, from: data)
    #expect(back == p)
}
