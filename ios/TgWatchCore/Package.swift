// swift-tools-version: 5.9
import PackageDescription
let package = Package(name: "TgWatchCore", platforms: [.iOS(.v17), .macOS(.v13)],
    products: [.library(name: "TgWatchCore", targets: ["TgWatchCore"])],
    targets: [.target(name: "TgWatchCore"),
              .testTarget(name: "TgWatchCoreTests", dependencies: ["TgWatchCore"], resources: [.copy("Fixtures")])])
