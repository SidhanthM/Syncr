import AppKit
import Foundation
import SyncrCore

/// Unattended driving and inspection of the running app, for the joined test (`code/shared/link-v0/join-test.sh`).
/// Only with the debug flag (`SYNCR_MAC_DEBUG_REPLAY=1`) and `SYNCR_MAC_DEBUG_CONTROL=<file>`: the file is polled for
/// appended command lines, every answer is one greppable `link: ...` line on stderr. Use a disposable library.
///
///   report                   what the Mac holds and shows right now (one line, see below)
///   open <note id>           open the note in the active tab (as clicking it in the sidebar does)
///   view <left> <top> <zoom> set the viewport exactly (no clamping), as scrolling and zooming would
///   newnote                  Cmd-N
///   forget-phones            Forget Phone, for every paired phone
///   link <on|off>            the Phone link switch
///
/// `link: report shown=<id|none> seq=<n|-> loading=<bool> strokes=<n> wet=<n> ink-pixels=<n|-> view=<l>,<t>,<z>
///  last-stroke=<minX>,<minY>,<maxX>,<maxY>|none notes=<id>@<seq>[:mac],… connected=<n>`
/// `strokes` is what the renderer is given for the shown note, `wet` the live layer's strokes (previews not yet
/// replaced by saved ink), `last-stroke` the canvas-space bounds of the last saved stroke.
@MainActor final class DebugControl {
    private let path: String
    private let model: LibraryModel
    private let workspace: Workspace
    private let live: LiveInkCoordinator
    private let link: LinkController
    private let detail: NoteDetailView
    private let notebook: NotebookController
    private let companion: CompanionController
    private let forgetPhones: () -> Void
    private let notebookAction: (String, String) -> Void
    private var offset: UInt64 = 0
    private var partial = ""
    private var timer: Timer?

    init(path: String, model: LibraryModel, workspace: Workspace, live: LiveInkCoordinator, link: LinkController,
         detail: NoteDetailView, notebook: NotebookController, companion: CompanionController, notebookAction: @escaping (String, String) -> Void, forgetPhones: @escaping () -> Void) {
        self.path = path; self.model = model; self.workspace = workspace; self.live = live
        self.link = link; self.detail = detail; self.notebook = notebook; self.companion = companion; self.notebookAction = notebookAction; self.forgetPhones = forgetPhones
        timer = Timer.scheduledTimer(withTimeInterval: 0.05, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.poll() }
        }
        say("control ready")
    }

    private func say(_ line: String) { FileHandle.standardError.write(Data(("link: " + line + "\n").utf8)) }

    private func poll() {
        guard let handle = FileHandle(forReadingAtPath: path) else { return }
        defer { try? handle.close() }
        do {
            try handle.seek(toOffset: offset)
            guard let data = try handle.readToEnd(), !data.isEmpty else { return }
            offset += UInt64(data.count)
            partial += String(decoding: data, as: UTF8.self)
        } catch { return }
        while let newline = partial.firstIndex(of: "\n") {
            let line = String(partial[..<newline]).trimmingCharacters(in: .whitespaces)
            partial = String(partial[partial.index(after: newline)...])
            if !line.isEmpty { run(line) }
        }
    }

    private func run(_ line: String) {
        let words = line.split(separator: " ").map(String.init)
        switch words.first {
        case "ai-mode": if words.count == 2, let mode = Int(words[1]), (0...2).contains(mode) { companion.debugMode(mode); say("ack ai-mode") }
        case "ai-fixture", "ai-record", "ai-all": if words.count == 2, let data = try? Data(contentsOf: URL(fileURLWithPath: words[1])) { companion.debugFixture(data,askNow:words[0] != "ai-record",all:words[0] == "ai-all"); say("ack \(words[0])") }
        case "ai-web": companion.debugStrip(web:words.last == "on"); say("ack ai-web")
        case "ai-preset": if words.count == 2, let index = Int(words[1]) { companion.debugStrip(preset:index); say("ack ai-preset") }
        case "ai-reader": if words.count == 2, let index = Int(words[1]) { companion.debugStrip(reader:index); say("ack ai-reader") }
        case "ai-reply": if words.count >= 3 { companion.reply(words[1],text:words.dropFirst(2).joined(separator:" ")); say("ack ai-reply") }
        case "ai-request-file": if words.count == 2 { Task { await companion.debugRecord(at:URL(fileURLWithPath:words[1])); self.say("ack ai-request-file") } }
        case "ai-drawing": if words.count == 2 { detail.debugDrawing(words[1]); say("ack ai-drawing") }
        case "ai-report": Task { self.say("ai-report " + (await companion.debugReport())) }
        case "ai-pin", "ai-dismiss", "ai-thread": if words.count == 2 { if words[0] == "ai-thread" { companion.openThread(words[1]) } else { companion.setState(words[1], words[0] == "ai-pin" ? .pinned : .dismissed) }; say("ack \(words[0])") }
        case "ai-undo": companion.undoAgent(); say("ack ai-undo")
        case "ai-redo": companion.undoAgent(redo: true); say("ack ai-redo")
        case "ai-settings": companion.showSettings(); say("ack ai-settings")
        case "ai-last": companion.lastRequest(); say("ack ai-last")
        case "ai-drawings": var p = companion.preferences; p.drawings = words.last == "on"; companion.savePreferences(p); say("ack ai-drawings")
        case "command", "palette", "accept", "tag-open": notebookAction(words[0], words.dropFirst().joined(separator: " ")); say("ack \(words[0])")
        case "windows":
            for window in NSApplication.shared.windows where window.isVisible { say("visible-window id=\(window.windowNumber) title=\(window.title)") }
        case "outline":
            if words.count == 2, let index = Int(words[1]) {
                let headings = NotebookIndex.outline(detail.documentEditor.textView.string)
                if headings.indices.contains(index) { detail.revealText(headings[index].offset); say("ack outline") }
            }
        case "front": NSApplication.shared.unhide(nil); detail.window?.makeKeyAndOrderFront(nil); NSApplication.shared.activate(ignoringOtherApps: true); say("ack front")
        case "window": say("window id=\(detail.window?.windowNumber ?? -1)")
        case "typefile":
            if words.count == 2, let text = try? String(contentsOfFile: words[1], encoding: .utf8) { detail.documentEditor.debugSetText(text); say("ack typefile bytes=\(text.utf8.count)") }
        case "insert":
            let raw = String(line.dropFirst(7))
            if let data = raw.data(using: .utf8), let text = try? JSONDecoder().decode(String.self, from: data) { detail.documentEditor.textView.insertText(text, replacementRange: detail.documentEditor.textView.selectedRange()); say("ack insert") }
        case "click-link": if words.count >= 2, let offset = Int(words[1]) { detail.documentEditor.textView.debugClick(offset, newTab: words.count > 2 && words[2] == "newtab"); say("ack click-link") }
        case "caret":
            if words.count == 2, let offset = Int(words[1]) { detail.revealText(offset); say("ack caret") }
        case "key":
            if words.count == 2 {
                let editor = detail.documentEditor
                switch words[1] {
                case "enter": _ = editor.textView(editor.textView, doCommandBy: #selector(NSResponder.insertNewline(_:)))
                case "tab": _ = editor.textView(editor.textView, doCommandBy: #selector(NSResponder.insertTab(_:)))
                case "backtab": _ = editor.textView(editor.textView, doCommandBy: #selector(NSResponder.insertBacktab(_:)))
                case "undo", "redo": editor.flush(); if let id = workspace.active { notebook.document(note: id).history(redo: words[1] == "redo") }
                default: editor.shortcut(words[1])
                }
                say("ack key \(words[1])")
            }
        case "save": detail.flushText(); notebook.flushAll { ok in self.say("saved ok=\(ok)") }
        case "drag-width": if words.count >= 2, let width = Double(words[1]) { detail.documentEditor.debugDragWidth(width, left: words.count > 2 && words[2] == "left"); say("ack drag-width") }
        case "drag-free": if words.count == 4, let index = Int(words[1]), let dx = Double(words[2]), let dy = Double(words[3]) { detail.debugDragFree(index, dx: dx, dy: dy); say("ack drag-free") }
        case "native-export":
            if let id = workspace.active, words.count == 2 {
                detail.flushText()
                model.editNote(id: id, work: { handle in _ = try handle.exportCopy(destinationPath: words[1]) }, completion: { result in
                    switch result { case .success: self.say("native-export ok"); case .failure(let error): self.say("native-export failed=\(error.localizedDescription)") }
                })
            }
        case "width": if let id = workspace.active, words.count == 2, let width = Double(words[1]) { notebook.setWidth(note: id, width: width); say("ack width") }
        case "free":
            if let id = workspace.active, words.count >= 3, let x = Double(words[1]), let y = Double(words[2]) {
                let text = words.count == 4 ? (try? String(contentsOfFile: words[3], encoding: .utf8)) ?? "" : ""
                notebook.addFree(note: id, x: x, y: y, text: text); say("ack free")
            }
        case "free-edit":
            if words.count == 3, let index = Int(words[1]), let text = try? String(contentsOfFile: words[2], encoding: .utf8) { detail.debugEditFree(index, text: text); say("ack free-edit") }
        case "free-move":
            if words.count == 4, let index = Int(words[1]), let x = Double(words[2]), let y = Double(words[3]) { detail.onFreeMove?(index, x, y); say("ack free-move") }
        case "page-image":
            if let id = workspace.active, words.count == 2 {
                model.readSnapshot(id: id) { result in
                    guard case .success(let snapshot) = result, let block = snapshot.textBlocks.first(where: { $0.role == .document && !$0.tombstone }),
                          let image = DocumentLayout.image(note: snapshot, width: CGFloat(block.width)), let tiff = image.tiffRepresentation,
                          let png = NSBitmapImageRep(data: tiff)?.representation(using: .png, properties: [:]) else { self.say("page-image failed"); return }
                    do { try png.write(to: URL(fileURLWithPath: words[1])); self.say("page-image ok") } catch { self.say("page-image failed") }
                }
            }
        case "rename":
            if let id = workspace.active { notebook.rename(id, to: String(line.dropFirst(7))) { ok in self.say("renamed ok=\(ok)") } }
        case "wikilink": do { try notebook.openLink(String(line.dropFirst(9)), newTab: true); say("ack wikilink") } catch { say("error \(error.localizedDescription)") }
        case "daily": do { try notebook.daily(); say("ack daily") } catch { say("error \(error.localizedDescription)") }
        case "backlinks": detail.toggleNotebookSidebar(); say("ack backlinks")
        case "search": say("search results=" + model.notebookIndex.search(String(line.dropFirst(7))).map { $0.title + ":" + $0.line }.joined(separator: "|"))
        case "tag": say("tag results=" + model.notebookIndex.tagged(String(line.dropFirst(4))).map(\.title).joined(separator: "|"))
        case "text-report":
            if let id = workspace.active {
                say("text-report editor-bytes=\(detail.documentEditor.textView.string.utf8.count) status=\(notebook.status(note: id)) outline=\(NotebookIndex.outline(detail.documentEditor.textView.string).count) tags=\(model.notebookIndex.tags.map { $0.0 + ":" + String($0.1) }.joined(separator: ",")) backlinks=\(model.notebookIndex.backlinks(to: workspace.title(of: id)).count)")
                model.readSnapshot(id: id) { result in
                    if case .success(let snapshot) = result { self.say("text-disk seq=\(snapshot.localSequence) blocks=\(snapshot.textBlocks.filter { !$0.tombstone }.count) document-bytes=\(snapshot.textBlocks.first { !$0.tombstone && $0.role == .document }?.text.utf8.count ?? 0) width=\(snapshot.textBlocks.first { !$0.tombstone && $0.role == .document }?.width ?? 0)") }
                }
            }
        case "mdimport": notebook.importMarkdown(URL(fileURLWithPath: String(line.dropFirst(9)))) { result in
            switch result { case .success(let mapped): self.workspace.libraryChanged(); if let last = self.model.notes.last { self.workspace.open(last.id) }; self.say("mdimport imported=\(mapped.entries.count) skipped=\(mapped.skipped.joined(separator: "|"))")
            case .failure(let error): self.say("mdimport failed=\(error.localizedDescription)") }
        }
        case "mdexport": if let id = workspace.active { notebook.exportMarkdown(note: id, to: URL(fileURLWithPath: String(line.dropFirst(9)))) { result in
            switch result { case .success: self.say("mdexport ok"); case .failure(let error): self.say("mdexport failed=\(error.localizedDescription)") }
        } }
        case "document": detail.documentView(); say("ack document")
        case "replay": live.replay(.pen); say("ack replay")
        case "quit": NSApplication.shared.terminate(nil)
        case "report": report()
        case "open": if words.count == 2 { workspace.open(words[1]); say("ack open \(words[1])") }
        case "view":
            if words.count == 4, let l = Float(words[1]), let t = Float(words[2]), let z = Float(words[3]) {
                detail.debugViewport = Viewport(left: l, top: t, zoom: z); say("ack view \(l),\(t),\(z)")
            }
        case "newnote": do { let id = try workspace.newNote(); say("ack newnote \(id)") } catch { say("newnote-failed \(error.localizedDescription)") }
        case "forget-phones": forgetPhones(); say("ack forget-phones")
        case "link": if words.count == 2 { link.setEnabled(words[1] == "on"); say("ack link \(words[1])") }
        default: say("unknown-command \(line)")
        }
    }

    private func report() {
        live.engine.waitIdle()
        let shown = model.shownNote
        let drawing = shown != nil ? model.drawing : nil
        let seq = shown.flatMap { id in model.notes.first { $0.id == id }?.localSequence }
        let v = detail.debugViewport
        var last = "none"
        if let s = drawing?.strokes.last {
            last = String(format: "%.1f,%.1f,%.1f,%.1f", s.originX + s.mesh.minX, s.originY + s.mesh.minY, s.originX + s.mesh.maxX, s.originY + s.mesh.maxY)
        }
        let pixels = detail.debugInkPixels().map(String.init) ?? "-"
        let notes = model.notes.map { "\($0.id)@\($0.localSequence)" + (model.owned.contains($0.id) ? ":mac" : "") }.joined(separator: ",")
        say("report shown=\(shown ?? "none") seq=\(seq.map(String.init) ?? "-") loading=\(model.loading) strokes=\(drawing?.strokes.count ?? 0) "
            + "wet=\(live.engine.currentFrame.strokes.count) ink-pixels=\(pixels) view=\(v.left),\(v.top),\(v.zoom) last-stroke=\(last) "
            + "notes=\(notes.isEmpty ? "-" : notes) connected=\(link.connectedPhones.count)")
    }
}
