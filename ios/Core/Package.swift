// swift-tools-version:5.9
import PackageDescription

// Platform-neutral logic. The app compiles these same files directly; this package exists so the
// logic can be unit tested with `swift test` without a simulator or a signed build.
let package = Package(
    name: "ShareFilesCore",
    platforms: [.iOS(.v17), .macOS(.v13)],
    targets: [
        .target(name: "ShareFilesCore"),
        .testTarget(name: "ShareFilesCoreTests", dependencies: ["ShareFilesCore"]),
    ]
)
