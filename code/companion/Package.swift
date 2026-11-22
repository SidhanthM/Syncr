// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "SyncrCompanion",
    platforms: [.macOS(.v13)],
    products: [.library(name: "SyncrCompanion", targets: ["SyncrCompanion"]),
               .executable(name: "SyncrCompanionChecks", targets: ["SyncrCompanionChecks"]),
               .executable(name: "SyncrSketchTrial", targets: ["SyncrSketchTrial"]),
               .executable(name: "SyncrJudge", targets: ["SyncrJudge"]),
               .executable(name: "SyncrCompanionReplay", targets: ["SyncrCompanionReplay"])],
    targets: [.target(name: "SyncrCompanion", resources: [.copy("Resources/patterns")]),
              .executableTarget(name: "SyncrCompanionChecks", dependencies: ["SyncrCompanion"], resources: [.copy("Fixtures")]),
              .executableTarget(name: "SyncrSketchTrial", dependencies: ["SyncrCompanion"]),
              .executableTarget(name: "SyncrJudge", dependencies: ["SyncrCompanion"]),
              .executableTarget(name: "SyncrCompanionReplay", dependencies: ["SyncrCompanion"], resources: [.copy("Fixtures")])])
