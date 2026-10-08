// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "ClaudeWatchKit",
    platforms: [.watchOS(.v10), .iOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "ClaudeWatchKit", targets: ["ClaudeWatchKit"]),
    ],
    dependencies: [
        // Only used on Linux (and other platforms without CryptoKit) for SHA-256.
        .package(url: "https://github.com/apple/swift-crypto.git", "3.0.0" ..< "5.0.0"),
    ],
    targets: [
        .target(
            name: "ClaudeWatchKit",
            dependencies: [
                .product(
                    name: "Crypto",
                    package: "swift-crypto",
                    condition: .when(platforms: [.linux, .windows, .android])
                ),
            ],
            swiftSettings: [.swiftLanguageMode(.v6)]
        ),
        .testTarget(
            name: "ClaudeWatchKitTests",
            dependencies: ["ClaudeWatchKit"],
            swiftSettings: [.swiftLanguageMode(.v6)]
        ),
    ]
)
