// SPDX-License-Identifier: MIT
// Copyright © 2026 Tsuyoshi Kobayashi(legrs4073)
import SwiftUI

@main
struct FukasisApp: App {
    @StateObject private var profiles = DeviceProfileStore()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(profiles)
        }
    }
}

/// Android 版の MainActivity と同じ並び
struct ContentView: View {
    @EnvironmentObject private var profiles: DeviceProfileStore

    var body: some View {
        NavigationStack {
            List {
                Section {
                    NavigationLink("Capture") { CaptureView() }
                    NavigationLink("Dark") { DarkView() }
                    NavigationLink("CSV (spectrum)") { SpectrumExportView() }
                    NavigationLink("Calibration") { CalibrationView() }
                    NavigationLink("View spectrum graph") { SpectrumListView() }
                }
                Section {
                    NavigationLink("Device setup") { DeviceSetupView() }
                } footer: {
                    Text("端末設定: \(profiles.profile.name ?? profiles.profile.id)"
                         + (profiles.profile.id == "generic" ? "\n未登録の端末です. 新しい端末のセットアップをしてください" : ""))
                }
            }
            .navigationTitle("FUKASIS")
        }
    }
}
