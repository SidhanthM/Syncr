// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "SyncrCompanion",
    platforms: [.macOS(.v13)],
    products: [.library(name: "SyncrCompanion", targets: ["SyncrCompanion"]),
               .executable(name: "SyncrCompanionChecks", targets: ["SyncrCompanionChecks"]),
               .executable(name: "SyncrSketchTrial", targets: ["SyncrSketchTrial"])],
    targets: [.target(name: "SyncrCompanion"),
              .executableTarget(name: "SyncrCompanionChecks", dependencies: ["SyncrCompanion"], resources: [.copy("Fixtures")]),
              .executableTarget(name: "SyncrSketchTrial", dependencies: ["SyncrCompanion"])])
