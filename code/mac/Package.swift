// swift-tools-version: 6.0
import PackageDescription
import Foundation

let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().path
let package = Package(
    name: "SyncrMac",
    platforms: [.macOS(.v14)],
    products: [.executable(name: "SyncrMac", targets: ["SyncrMac"])],
    dependencies: [.package(path: "../companion")],
    targets: [
        .target(name: "syncr_coreFFI", path: "Native", sources: ["shim.c"], publicHeadersPath: "include"),
        .target(name: "SyncrCore", dependencies: ["syncr_coreFFI"], path: "Sources/SyncrCore",
                linkerSettings: [.unsafeFlags(["-L\(root)/Native", "-lsyncr_core", "-Xlinker", "-rpath", "-Xlinker", "\(root)/Native"])]),
        .executableTarget(name: "SyncrMac", dependencies: ["SyncrCore", .product(name: "SyncrCompanion", package: "companion")], path: "Sources/SyncrMac"),
        // CLT does not ship XCTest or TestingMacros. Compile the viewer model and renderer
        // alongside standalone checks instead; these source symlinks have a single owner.
        .executableTarget(name: "SyncrMacChecks", dependencies: ["SyncrCore", .product(name: "SyncrCompanion", package: "companion")], path: "Sources/SyncrMacChecks"),
        .executableTarget(name: "SyncrPictureTrial", dependencies: ["SyncrCore", .product(name: "SyncrCompanion", package: "companion")], path: "Sources/SyncrPictureTrial")
    ]
)
