// swift-tools-version: 5.10
import PackageDescription
import Foundation

let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
let vlc = root.appendingPathComponent("build/vlc-sdk")
let imports: [SwiftSetting] = [.unsafeFlags(["-Xcc", "-I\(vlc.path)/include"])]
let links: [LinkerSetting] = [
    .unsafeFlags(["-L\(vlc.path)/lib", "-Xlinker", "-rpath", "-Xlinker",
                  "@executable_path/../Frameworks/VLC/lib"])
]

let package = Package(
    name: "SubPlayerMac",
    platforms: [.macOS(.v12)],
    products: [.executable(name: "SubPlayerMac", targets: ["SubPlayerMac"])],
    targets: [
        .systemLibrary(name: "CVLC", path: "Sources/CVLC"),
        .target(name: "PlaybackCore", dependencies: ["CVLC"], swiftSettings: imports),
        .executableTarget(name: "SubPlayerMac", dependencies: ["PlaybackCore"],
                          swiftSettings: imports, linkerSettings: links),
        .executableTarget(name: "PlaybackCheck", dependencies: ["PlaybackCore"],
                          path: "Tests/PlaybackCheck", swiftSettings: imports, linkerSettings: links),
        .executableTarget(name: "SubtitleCheck", dependencies: ["PlaybackCore"],
                          path: "Tests/SubtitleCheck", swiftSettings: imports, linkerSettings: links)
    ]
)
