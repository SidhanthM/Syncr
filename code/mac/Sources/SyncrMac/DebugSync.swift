import Foundation
import SyncrCore

/// Mac-side edits and inspection for the joined sync test (`code/shared/link-v0/join-test.sh`), behind the same debug flag
/// as `DebugControl`. Every edit is one ordinary core command through `LibraryModel.editNote`, so it is committed (and
/// announced to a v1 phone) exactly like a typed or drawn change.
///
///   sync-close-tabs                 close every tab (no note open)
///   sync-stroke <note> <x>          one stroke at x (a short curve), first sheet
///   sync-move <note> first|last <tx>  move the first or last live stroke right by tx
///   sync-text <note> <word>         a text block holding the word
///   sync-margin <note>              a margin note on the note's first text block (adds the anchor)
///   sync-bulk <note> <count>        count long strokes (8,000 samples each): a big note, for cutting a transfer
///   sync-count <note>               operations in the note: `link: sync-count note=<id> ops=<n>`
///   sync-fixture <note> <count>     count short strokes (60 samples) spread over 8,000 by 6,000 units, 500 to a command:
///                                   the latency harness's canvas fixture (`code/shared/latency/run.sh pan`)
@MainActor final class DebugSync {
    private let model: LibraryModel
    private let say: (String) -> Void
    private let closeTabs: () -> Void
    init(model: LibraryModel, closeTabs: @escaping () -> Void, say: @escaping (String) -> Void) { self.model = model; self.closeTabs = closeTabs; self.say = say }

    static let commands: Set<String> = ["sync-close-tabs", "sync-stroke", "sync-move", "sync-text", "sync-margin", "sync-bulk", "sync-count", "sync-fixture"]

    nonisolated private static func channels(x: Float, count: Int = 60, row: Float = 0, length: Float = 600) throws -> Data {
        var c = SampleChannels(x: [], y: [], pressure: [], elapsedMs: [], tilt: nil, orientation: nil)
        for i in 0..<count {
            let u = Float(i) / Float(count - 1)
            c.x.append(x + length * u); c.y.append(1200 + row + 100 * sin(u * 6)); c.pressure.append(0.5); c.elapsedMs.append(UInt32(i * 4))
        }
        return try encodeSamples(channels: c)
    }

    func handle(_ words: [String]) {
        if words[0] == "sync-close-tabs" { closeTabs(); say("ack sync-close-tabs"); return }
        guard words.count >= 2 else { say("sync-usage"); return }
        let note = words[1]
        let name = words[0]
        let run: (@Sendable (Note) throws -> Void)
        switch name {
        case "sync-stroke":
            let x = Float(words.count > 2 ? words[2] : "100") ?? 100
            run = { h in
                let sheet = try h.snapshot().sheets[0]
                _ = try h.submit(command: Command(operationId: newId(), dependencies: [sheet.creationOperationId], body: .marks(mutations: [
                    .addStroke(markId: newId(), sheetId: sheet.id, samples: try Self.channels(x: x), brush: Brush(family: .pen, size: .medium, colour: .ink))])))
            }
        case "sync-bulk":
            let n = Int(words.count > 2 ? words[2] : "6") ?? 6
            run = { h in
                let sheet = try h.snapshot().sheets[0]
                for k in 0..<n {
                    _ = try h.submit(command: Command(operationId: newId(), dependencies: [sheet.creationOperationId], body: .marks(mutations: [
                        .addStroke(markId: newId(), sheetId: sheet.id, samples: try Self.channels(x: 100, count: 8000, row: Float(k) * 30), brush: Brush(family: .pen, size: .medium, colour: .ink))])))
                }
            }
        case "sync-fixture":
            let n = Int(words.count > 2 ? words[2] : "5000") ?? 5000
            run = { h in
                let sheet = try h.snapshot().sheets[0]
                for start in stride(from: 0, to: n, by: 500) {
                    let marks = try (start..<min(n, start + 500)).map { k -> MarkMutation in
                        .addStroke(markId: newId(), sheetId: sheet.id, samples: try Self.channels(x: Float((k * 977) % 8000), row: Float((k * 613) % 6000) - 1200, length: 160),
                                   brush: Brush(family: k % 11 == 10 ? .highlighter : .pen, size: .medium, colour: [.ink, .blue, .red, .green][k % 4]))
                    }
                    _ = try h.submit(command: Command(operationId: newId(), dependencies: [sheet.creationOperationId], body: .marks(mutations: marks)))
                }
            }
        case "sync-move":
            let which = words.count > 2 ? words[2] : "first"
            let tx = Double(words.count > 3 ? words[3] : "100") ?? 100
            run = { h in
                let live = try h.snapshot().strokes.filter { !$0.tombstone }
                guard let s = which == "last" ? live.last : live.first else { throw ViewerError.invalidSource }
                _ = try h.submit(command: Command(operationId: newId(), dependencies: [s.head.operationId], body: .marks(mutations: [
                    .transformStroke(markId: s.id, base: .operation(operationId: s.head.operationId), transform: Affine(a: 1, b: 0, c: 0, d: 1, tx: tx, ty: 0))])))
            }
        case "sync-text":
            let word = words.count > 2 ? words[2] : "hello"
            run = { h in
                let sheet = try h.snapshot().sheets[0]
                _ = try h.submit(command: Command(operationId: newId(), dependencies: [sheet.creationOperationId], body: .marks(mutations: [
                    .addText(markId: newId(), sheetId: sheet.id, role: .free, originX: 100, originY: 100, width: 800, text: word, style: TextStyle(size: .medium, colour: .ink))])))
            }
        case "sync-margin":
            run = { h in
                let s = try h.snapshot()
                guard let block = s.textBlocks.first(where: { !$0.tombstone }) else { throw ViewerError.invalidSource }
                let anchor = newId(), margin = newId()
                _ = try h.submit(command: Command(operationId: newId(), dependencies: [block.head.operationId], body: .companionMarks(mutations: [
                    CompanionMutation(id: anchor, base: .absent, tombstone: false, body: .anchor(target: .text(blockId: block.id, start: 0, end: 2, detached: false))),
                    CompanionMutation(id: margin, base: .absent, tombstone: false, body: .marginNote(anchorId: anchor, kind: .flag, text: "Why?", reader: "Reader", state: .pinned, sources: []))])))
            }
        case "sync-count":
            model.editNote(id: note, work: { _ in }) { [say] result in
                switch result {
                case .success: break
                case .failure(let error): say("sync-count failed=\(error.localizedDescription)")
                }
            }
            model.syncDigests([note]) { [say] digests in say("sync-count note=\(note) ops=\(digests[note]?.operationCount ?? 0)") }
            return
        default: return
        }
        model.editNote(id: note, work: run) { [say] result in
            switch result {
            case .success: say("ack \(name) \(note)")
            case .failure(let error): say("\(name) failed=\(error.localizedDescription)")
            }
        }
    }
}
