import Foundation
import SyncrCompanion

struct CompanionPreferences: Codable {
    var provider = "codex"
    /// The gate model (reading along) and the answer model (asking, replying, pictures).
    var readingModel = ""
    var askingModel = ""
    /// Pictures: on by default (owner, 2026-11-16, after the second sketch trial). Stored as `pictures`, so a `drawings`
    /// false saved while they were an off-by-default experiment does not keep them off.
    var drawings = true
    var tokenLimit = 100000
    var requestLimit = 100
    /// Hand-drawn pen style for pictures (Excalidraw-like wobble); clean strokes when off.
    var handDrawn = true
    enum CodingKeys: String, CodingKey {
        case provider, readingModel, askingModel, tokenLimit, requestLimit, handDrawn
        case drawings = "pictures"
    }
    init() {}
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        provider = try c.decodeIfPresent(String.self, forKey: .provider) ?? provider
        readingModel = try c.decodeIfPresent(String.self, forKey: .readingModel) ?? ""
        askingModel = try c.decodeIfPresent(String.self, forKey: .askingModel) ?? ""
        drawings = try c.decodeIfPresent(Bool.self, forKey: .drawings) ?? true
        tokenLimit = try c.decodeIfPresent(Int.self, forKey: .tokenLimit) ?? tokenLimit
        requestLimit = try c.decodeIfPresent(Int.self, forKey: .requestLimit) ?? requestLimit
        handDrawn = try c.decodeIfPresent(Bool.self, forKey: .handDrawn) ?? true
    }
}

/// The form owns presentation and drafts; the controller keeps library/engine persistence.
@MainActor protocol CompanionSettingsSource: AnyObject {
    var preferences: CompanionPreferences { get }
    var readers: [Reader] { get }
    var voicePreferences: VoicePreferences { get }
    var settingsModels: [ProviderModel] { get }
    var settingsProviderState: String { get }
    var settingsMeter: BudgetMeter { get }
    func refreshModels()
    func savePreferences(_ value: CompanionPreferences)
    func saveReaders(_ value: [Reader]) throws
    func saveVoice(_ value: VoicePreferences)
}
