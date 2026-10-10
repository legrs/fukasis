// swift-tools-version:5.9
// FUKASIS の機種によらない処理 (スペクトル計算・TIFF・自動校正・端末プロファイル).
// iPhone 版 (ios/) はこのパッケージを使う. Android 版も C++ 部分 (Sources/FukasisCoreC) を共有している.
import PackageDescription

let package = Package(
    name: "FukasisCore",
    platforms: [.iOS(.v16), .macOS(.v14)],
    products: [
        .library(name: "FukasisCore", targets: ["FukasisCore"]),
    ],
    targets: [
        // C++ 本体と, Swift から呼ぶための C インターフェース (include/fukasis_core.h)
        .target(
            name: "FukasisCoreC",
            path: "Sources/FukasisCoreC",
            publicHeadersPath: "include"
        ),
        .target(
            name: "FukasisCore",
            dependencies: ["FukasisCoreC"],
            path: "Sources/FukasisCore"
        ),
        .testTarget(
            name: "FukasisCoreTests",
            dependencies: ["FukasisCore"],
            path: "Tests/FukasisCoreTests"
        ),
    ],
    cxxLanguageStandard: .cxx17
)
