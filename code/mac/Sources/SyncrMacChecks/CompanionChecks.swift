import AppKit
import SyncrCore
import SyncrCompanion

@MainActor private func companionWait(_ done: () -> Bool) throws {
    let end = Date().addingTimeInterval(15)
    while !done() {
        guard Date() < end else { throw CheckFailure(description: "companion worker timed out") }
        RunLoop.main.run(mode: .default, before: Date().addingTimeInterval(0.002))
    }
}
@MainActor private func companionEdit(_ model: LibraryModel, _ id: String, _ work: @escaping @Sendable (Note) throws -> Void) throws -> NoteSnapshot {
    var result: Result<NoteSnapshot, Error>?
    model.editNote(id: id, work: work) { result = $0 }
    try companionWait { result != nil }; return try result!.get()
}
@MainActor func companionCoreChecks() throws {
    let dir = FileManager.default.temporaryDirectory.appendingPathComponent("syncr-companion-" + UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: dir) }
    let original = "# Thinking\nCafé 🦉 needs a cache.\nWhat if invalidation fails?\n"
    var id = "", marginID = ""
    do {
        let model = LibraryModel(folder: dir)
        id = try model.createNote(notebook: nil)
        var s = try companionEdit(model,id) { note in
            let sheet = try note.snapshot().sheets[0]
            _ = try note.submit(command: Command(operationId:newId(),dependencies:[sheet.creationOperationId],body:.marks(mutations:[.addText(markId:newId(),sheetId:sheet.id,role:.document,originX:0,originY:0,width:1600,text:original,style:TextStyle(size:.medium,colour:.ink))])))
            try check(CompanionStore.settings(try note.snapshot()).mode == .whenAsked,"new note defaults to When you ask")
            try CompanionStore.saveSettings(CompanionSettings(tell:"Be brutal",mode:.whenAsked,web:true,activeReader:"editor",colour:.green),note:note)
            try CompanionStore.apply([.marginNote(quote:"Café 🦉 needs a cache.",kind:"question",text:"What owns invalidation?",sources:[Source(title:"Evidence",url:"https://example.com/cache")])],note:note,reader:"Editor",expectedText:original,drawings:false)
            try check(try note.snapshot().companionItems.allSatisfy { $0.authorId == note.agentAuthorId() },"agent authorship")
        }
        let margin = s.companionItems.first { if case .marginNote = $0.body { return !$0.tombstone }; return false }!
        marginID = margin.id
        try check(s.textBlocks[0].text == original,"agent margin never rewrites user text")
        let seq = s.localSequence, currentID = marginID
        s = try companionEdit(model,id) { note in
            try CompanionStore.apply([.marginNote(quote:"absent",kind:"flag",text:"must drop",sources:[]),.reply(threadID:"unknown",text:"must drop",sources:[])],note:note,reader:"Editor",expectedText:original,drawings:false)
        }
        try check(s.localSequence == seq,"unmatched passage and unknown thread do not write")
        s = try companionEdit(model,id) { note in
            try CompanionStore.setState(currentID,.pinned,note:note)
            try CompanionStore.userReply(currentID,text:"The owner invalidates.",note:note)
            try CompanionStore.apply([.reply(threadID:currentID,text:"What if it crashes?",sources:[]),.reply(threadID:currentID,text:"How is recovery tested?",sources:[])],note:note,reader:"Editor",expectedText:original,drawings:false)
        }
        try check(s.companionItems.filter { if case .threadMessage = $0.body { return !$0.tombstone }; return false }.count == 3,"thread messages have unique increasing order")
        s = try companionEdit(model,id) { note in
            let block = try note.snapshot().textBlocks[0]
            _ = try note.submit(command: NotebookController.command(block.head,.spliceText(markId:block.id,base:NotebookController.base(block.head),offset:0,delete:0,insert:"Above\n")))
        }
        let anchor = s.companionItems.first { if case .anchor = $0.body { return true }; return false }!
        guard case .anchor(.text(_,let start,let end,_)) = anchor.body else { throw CheckFailure(description:"anchor") }
        try check(start == UInt32("Above\n# Thinking\n".utf8.count),"anchor follows Unicode text insertion above")
        s = try companionEdit(model,id) { note in
            let block = try note.snapshot().textBlocks[0]
            _ = try note.submit(command: NotebookController.command(block.head,.spliceText(markId:block.id,base:NotebookController.base(block.head),offset:start,delete:end-start,insert:"")))
        }
        try check(s.companionItems.contains { if case .anchor(.text(_,_,_,let detached)) = $0.body { return detached }; return false },"passage deletion detaches margin")
        s = try companionEdit(model,id) { note in
            let user = try note.snapshot()
            _ = try note.submit(command:Command(operationId:newId(),dependencies:user.undoDependencies,body:.undo(targetOperationId:user.undoTarget!)))
        }
        try check(s.companionItems.contains { if case .anchor(.text(_,_,_,let detached)) = $0.body { return !detached }; return false },"typing undo restores anchor without removing agent margin")
        let bytes = s.textBlocks[0].text
        s = try companionEdit(model,id) { note in
            let agent = try note.agentSnapshot()
            _ = try note.submitAgent(command:Command(operationId:newId(),dependencies:agent.undoDependencies,body:.undo(targetOperationId:agent.undoTarget!)))
        }
        try check(s.textBlocks[0].text == bytes,"agent undo never undoes user text")
        s = try companionEdit(model,id) { note in
            let agent = try note.agentSnapshot()
            _ = try note.submitAgent(command:Command(operationId:newId(),dependencies:agent.redoDependencies,body:.redo(targetOperationId:agent.redoTarget!)))
            var settings = CompanionStore.settings(try note.snapshot()); settings.mode = .off
            try CompanionStore.saveSettings(settings,note:note)
            try CompanionStore.apply([.marginNote(quote:"Above",kind:"flag",text:"Off must refuse",sources:[])],note:note,reader:"Editor",expectedText:bytes,drawings:false)
        }
        try check(s.companionItems.filter { if case .marginNote = $0.body { return !$0.tombstone }; return false }.count == 1,"Off refuses adapter mutations")
    }
    do {
        let model = LibraryModel(folder:dir)
        let s = try companionEdit(model,id) { _ in }
        try check(CompanionStore.settings(s).tell == "Be brutal" && CompanionStore.settings(s).colour == .green,"per-note settings survive reopen")
        try check(s.companionItems.contains { if case .marginNote(_,_,_,_,.pinned,_) = $0.body { return $0.id == marginID }; return false },"pinned thread survives fresh library")
        try check(s.companionItems.filter { if case .threadMessage = $0.body { return !$0.tombstone }; return false }.count == 3,"whole thread survives reopen")
    }
    print("Companion Mac adapter: OK (settings, Unicode anchors/detachment, sources, threads/reopen, separate history, Off)")
}

@MainActor func companionDrawingChecks() throws {
    let dir = FileManager.default.temporaryDirectory.appendingPathComponent("syncr-companion-drawing-" + UUID().uuidString)
    defer { try? FileManager.default.removeItem(at:dir) }
    let model = LibraryModel(folder:dir), id = try model.createNote(notebook:nil)
    let text = "Explain this flow.\nNext paragraph.\n"
    let args:[String:Any] = ["quote":"Explain this flow.","title":"A bounded flow","width":500,"height":300,"steps":[["caption":"Connect","primitives":[["type":"arrow","points":[["x":20,"y":100],["x":400,"y":100]]],["type":"label","x":30,"y":40,"text":"A to B","size":24]]]]]
    let tools = try ToolValidator.validate(ToolValidator.envelope(nativeCalls:[["name":"draw","arguments":args]]))
    var s = try companionEdit(model,id) { note in
        let sheet = try note.snapshot().sheets[0]
        _ = try note.submit(command:Command(operationId:newId(),dependencies:[sheet.creationOperationId],body:.marks(mutations:[.addText(markId:newId(),sheetId:sheet.id,role:.document,originX:0,originY:0,width:1600,text:text,style:TextStyle(size:.medium,colour:.ink))])))
        try CompanionStore.apply(tools,note:note,reader:"Professor",expectedText:text,drawings:false)
    }
    try check(s.textBlocks[0].text == text && s.companionItems.isEmpty,"experimental drawing opt-out makes no note change")
    s = try companionEdit(model,id) { try CompanionStore.apply(tools,note:$0,reader:"Professor",expectedText:text,drawings:true) }
    let drawing = s.companionItems.first { if case .drawing = $0.body { return !$0.tombstone }; return false }!
    let embed = "![[syncr-drawing:\(drawing.id)]]"
    try check(s.textBlocks[0].text == "Explain this flow.\n" + embed + "\n\nNext paragraph.\n","drawing inserts only its embed beneath passage")
    try check(CompanionStore.drawing(drawing.body)?.steps.count == 1,"stored drawing revalidates through common primitive boundary")
    var page = DocumentPage(title:"",text:s.textBlocks[0].text)
    page.drawingHeights = [drawing.id:480]
    let lines = DocumentLayout.lines(note:page,width:500), embedRange = (page.text as NSString).range(of:embed)
    let embedLines = lines.filter { NSIntersectionRange($0.range,embedRange).length > 0 }
    try check(embedLines.count == 1 && embedLines[0].rect.height >= 480,"narrow document reserves inline drawing height without wrapping embed: \(embedLines.map(\.rect))")
    s = try companionEdit(model,id) { note in
        let agent = try note.agentSnapshot()
        _ = try note.submitAgent(command:Command(operationId:newId(),dependencies:agent.undoDependencies,body:.undo(targetOperationId:agent.undoTarget!)))
    }
    try check(s.textBlocks[0].text == text,"agent undo removes only drawing embed")
    s = try companionEdit(model,id) { note in
        let agent = try note.agentSnapshot()
        _ = try note.submitAgent(command:Command(operationId:newId(),dependencies:agent.redoDependencies,body:.redo(targetOperationId:agent.redoTarget!)))
    }
    try check(s.textBlocks[0].text.contains(embed),"drawing embed redo")
    // Context image uses this note's committed ink, cropped around the passage, never another note.
    try model.open(id:id)
    let channels = SampleChannels(x:[200,400],y:[350,350],pressure:[0.5,0.8],elapsedMs:[0,8],tilt:nil,orientation:nil)
    let mesh = try tessellateStroke(samples:encodeSamples(channels:channels),style:defaultStrokeStyle(brush:Brush(family:.pen,size:.medium,colour:.red)),transform:Affine(a:1,b:0,c:0,d:1,tx:0,ty:0))
    let captured = s
    var pageData:Data?, finished = false
    Task { pageData = await CompanionPage.image(snapshot:captured,selection:ByteSpan(start:0,end:18),strokes:[DrawStroke(mesh:mesh,originX:0,originY:0,colour:.red,highlighter:false)]); finished = true }
    try companionWait { finished }
    let image = pageData.flatMap { NSBitmapImageRep(data:$0) }
    try check(image != nil && image!.pixelsWide <= 1280 && image!.pixelsHigh <= 720,"ink request image is bounded PNG")
    try check((pageData?.count ?? 0) < 4*1024*1024,"ink image below request limit")
    print("Companion drawings/context: OK (experimental gate, inline embed/undo/redo, narrow layout, bounded ink PNG)")
}
