import AppKit
import AVFoundation
import Speech

struct VoicePreferences: Codable {
    var identifier = ""
    var rate: Float = AVSpeechUtteranceDefaultSpeechRate
}

/// Speech is local UI work; it never gets a core handle or a provider credential.
@MainActor final class CompanionVoice: NSObject {
    private let speaker = AVSpeechSynthesizer()
    private let audio = AVAudioEngine()
    private var request: SFSpeechAudioBufferRecognitionRequest?
    private var recognition: SFSpeechRecognitionTask?
    private var monitor: Any?
    private var optionDown: TimeInterval?
    private var lastTap = -TimeInterval.infinity
    private var handsFree = false
    private var generation = 0
    private weak var destination: NSTextView?
    private var insertion = NSRange(location: 0, length: 0)
    var target: (() -> NSTextView?)?
    var status: ((String) -> Void)?
    var voiceID = ""
    var rate: Float = AVSpeechUtteranceDefaultSpeechRate
    private(set) var listening = false

    static var dictationAvailability: String {
        // Embedded __info_plist metadata is readable with CLT, but does not supply an app identity
        // for TCC. Never probe a microphone from a bare process or pretend permission was granted.
        guard Bundle.main.bundleURL.pathExtension == "app" else { return "Dictation needs the bundled app (run bundle.sh)" }
        guard Bundle.main.object(forInfoDictionaryKey: "NSSpeechRecognitionUsageDescription") != nil,
              Bundle.main.object(forInfoDictionaryKey: "NSMicrophoneUsageDescription") != nil else { return "Dictation needs microphone and speech usage descriptions" }
        return "Hold Option to dictate; double-tap for hands-free"
    }
    static var voices: [AVSpeechSynthesisVoice] { AVSpeechSynthesisVoice.speechVoices().sorted { ($0.language, $0.name) < ($1.language, $1.name) } }

    func speak(_ text: String) {
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        let utterance = AVSpeechUtterance(string: text)
        if !voiceID.isEmpty { utterance.voice = AVSpeechSynthesisVoice(identifier: voiceID) }
        utterance.rate = max(AVSpeechUtteranceMinimumSpeechRate, min(AVSpeechUtteranceMaximumSpeechRate, rate))
        speaker.speak(utterance)
    }
    func silence() { speaker.stopSpeaking(at: .immediate) }
    func install() {
        guard monitor == nil else { return }
        monitor = NSEvent.addLocalMonitorForEvents(matching: [.flagsChanged, .keyDown]) { [weak self] event in
            let consumed = MainActor.assumeIsolated {
                guard let self else { return false }
                if event.type == .keyDown {
                    if event.keyCode == 53, self.listening { self.stop(); return true }
                    // Option used in a shortcut is not dictation.
                    if self.optionDown != nil { self.optionDown = nil; self.stop() }
                    return false
                }
                self.option(event.modifierFlags.contains(.option), at: event.timestamp)
                return false
            }
            return consumed ? nil : event
        }
    }
    private func option(_ down: Bool, at time: TimeInterval) {
        if down {
            guard optionDown == nil, target?()?.isEditable == true else { return }
            optionDown = time
            if time - lastTap < 0.35 { handsFree = true; if !listening { start() } }
            else {
                let stamp = time
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.22) { [weak self] in
                    guard let self, self.optionDown == stamp else { return }; self.start()
                }
            }
        } else if let began = optionDown {
            optionDown = nil
            if time - began < 0.22 { lastTap = time }
            if !handsFree { stop() }
        }
    }
    func start() {
        guard !listening else { return }
        guard Bundle.main.bundleURL.pathExtension == "app" else { status?(Self.dictationAvailability); return }
        guard let view = target?(), view.isEditable else { return }
        generation += 1
        let token = generation
        Task { [weak self, weak view] in
            let speech = await withCheckedContinuation { continuation in SFSpeechRecognizer.requestAuthorization { continuation.resume(returning: $0) } }
            let microphone = await AVCaptureDevice.requestAccess(for: .audio)
            guard let self, let view, self.generation == token else { return }
            guard speech == .authorized, microphone else { self.status?("Dictation permission denied — enable Speech Recognition and Microphone in System Settings"); return }
            self.begin(view)
        }
    }
    private func begin(_ view: NSTextView) {
        guard let recognizer = SFSpeechRecognizer(), recognizer.isAvailable else { status?("Speech recognition is unavailable"); return }
        // Do not send microphone audio to an unchosen speech service.
        guard recognizer.supportsOnDeviceRecognition else { status?("On-device dictation is unavailable for this language"); return }
        let request = SFSpeechAudioBufferRecognitionRequest()
        request.requiresOnDeviceRecognition = true
        request.shouldReportPartialResults = true
        self.request = request; destination = view; insertion = view.selectedRange()
        let node = audio.inputNode
        node.installTap(onBus: 0, bufferSize: 1024, format: node.outputFormat(forBus: 0)) { buffer, _ in request.append(buffer) }
        do { audio.prepare(); try audio.start() }
        catch { node.removeTap(onBus: 0); self.request = nil; status?("Could not start microphone: \(error.localizedDescription)"); return }
        listening = true; status?("Listening locally — Escape stops dictation")
        let token = generation
        recognition = recognizer.recognitionTask(with: request) { [weak self] result, error in
            let text = result?.bestTranscription.formattedString, final = result?.isFinal ?? false
            Task { @MainActor [weak self] in
                guard let self, self.generation == token, self.listening else { return }
                if let text, let destination = self.destination, NSMaxRange(self.insertion) <= destination.string.utf16.count {
                    destination.insertText(text, replacementRange: self.insertion)
                    self.insertion.length = text.utf16.count
                }
                if final || error != nil { self.stop() }
            }
        }
    }
    func stop() {
        generation += 1; handsFree = false
        if listening { audio.stop(); audio.inputNode.removeTap(onBus: 0) }
        request?.endAudio(); recognition?.cancel(); recognition = nil; request = nil
        destination = nil; listening = false
    }
}
