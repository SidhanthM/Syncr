import Foundation

struct IndexedNote: Equatable, Sendable {
    var id: String
    var title: String
    var text: String
    var writable: Bool
}
/// Disposable per-note snapshot cache. Ownership and publication are main-confined; the supplied
/// reader keeps note handles on LibraryModel's worker. At most one read is queued per pass.
@MainActor final class NotebookIndexUpdater {
    struct Revision: Equatable {
        var id: String
        var sequence: UInt64
        var title: String
        var writable: Bool
    }
    struct Source: Equatable { var folder: URL; var notes: [Revision] }
    typealias Reader = (String, @escaping @MainActor (Result<IndexedNote, Error>) -> Void) -> Void
    private let source: () -> Source
    private let read: Reader
    private let publish: ([IndexedNote]) -> Void
    private var cache: [String: (revision: Revision, note: IndexedNote)] = [:]
    private var published: Source?
    private var cacheFolder: URL?
    private var pass: UUID?
    private var recover = false

    init(source: @escaping () -> Source, read: @escaping Reader, publish: @escaping ([IndexedNote]) -> Void) {
        self.source = source; self.read = read; self.publish = publish
    }
    func reset() {
        // Invalidate even an A -> B -> A folder switch while an old read is returning.
        pass = nil; published = nil; cacheFolder = nil; cache = [:]; recover = false
    }
    func refresh(force: Bool = false) {
        if pass != nil { recover = recover || force; return }
        let wanted = source()
        guard force || wanted != published else { return }
        if force || wanted.folder != cacheFolder { cache = [:] }
        cacheFolder = wanted.folder
        let ids = Set(wanted.notes.map(\.id))
        cache = cache.filter { ids.contains($0.key) }
        let token = UUID(); pass = token
        var entries: [IndexedNote] = []
        func next(_ start: Int) {
            guard pass == token else { return }
            var i = start
            while i < wanted.notes.count {
                let revision = wanted.notes[i]
                if let cached = cache[revision.id], cached.revision == revision {
                    entries.append(cached.note); i += 1; continue
                }
                if !revision.writable {
                    let note = IndexedNote(id: revision.id, title: revision.title, text: "", writable: false)
                    cache[revision.id] = (revision, note); entries.append(note); i += 1; continue
                }
                let following = i + 1
                read(revision.id) { [self] result in
                    guard pass == token else { return }
                    // Never carry a previous successful entry across a failed changed-note read.
                    cache[revision.id] = nil
                    if case .success(let note) = result {
                        cache[revision.id] = (revision, note); entries.append(note)
                    }
                    next(following)
                }
                return
            }
            pass = nil
            let forceNext = recover; recover = false
            // Edits/deletes arriving during the pass must not publish stale entries. Reconcile
            // from live metadata even if no refresh notification was delivered during the read.
            guard source() == wanted else { refresh(force: forceNext); return }
            published = wanted; publish(entries)
            if forceNext { refresh(force: true) }
        }
        next(0)
    }
}

struct NotebookMatch: Equatable {
    var id: String
    var title: String
    var line: String
    var offset: Int
}
struct OutlineHeading: Equatable { var title: String; var level: Int; var offset: Int }
struct NotebookIndex {
    var notes: [IndexedNote] = []
    func resolve(_ title: String) -> IndexedNote? {
        // Duplicate titles resolve deterministically, never by index enumeration order.
        notes.sorted { $0.id < $1.id }.first { $0.title.caseInsensitiveCompare(title) == .orderedSame }
    }
    func completions(_ prefix: String) -> [IndexedNote] {
        notes.filter { $0.title.lowercased().hasPrefix(prefix.lowercased()) }.sorted { $0.title.localizedCaseInsensitiveCompare($1.title) == .orderedAscending }
    }
    func backlinks(to title: String) -> [NotebookMatch] {
        notes.flatMap { note in Markdown.lines(note.text).compactMap { line in
            line.spans.contains { if case .wiki(let t) = $0.kind { return t.caseInsensitiveCompare(title) == .orderedSame }; return false }
                ? NotebookMatch(id: note.id, title: note.title, line: line.text, offset: line.range.location) : nil
        } }
    }
    var tags: [(String, Int)] {
        var counts: [String: Int] = [:]
        for note in notes { for tag in Set(tags(in: note.text)) { counts[tag, default: 0] += 1 } }
        return counts.sorted { $0.key < $1.key }.map { ($0.key, $0.value) }
    }
    func tags(in text: String) -> [String] {
        Markdown.lines(text).flatMap(\.spans).compactMap { if case .tag(let t) = $0.kind { return t }; return nil }
    }
    func tagged(_ tag: String) -> [IndexedNote] { notes.filter { tags(in: $0.text).contains(tag) } }
    func search(_ query: String) -> [NotebookMatch] {
        guard !query.isEmpty else { return [] }
        return notes.flatMap { note -> [NotebookMatch] in
            let lines = Markdown.lines(note.writable ? note.text : "").filter { $0.text.localizedCaseInsensitiveContains(query) }
            if lines.isEmpty && note.title.localizedCaseInsensitiveContains(query) { return [NotebookMatch(id: note.id, title: note.title, line: note.title, offset: 0)] }
            return lines.map { NotebookMatch(id: note.id, title: note.title, line: $0.text, offset: $0.range.location) }
        }
    }
    static func outline(_ text: String) -> [OutlineHeading] {
        Markdown.lines(text).compactMap { line in
            guard case .heading(let level) = line.kind else { return nil }
            return OutlineHeading(title: String(line.text.dropFirst(line.prefix.length)), level: level, offset: line.range.location)
        }
    }
    static func dailyTitle(_ date: Date = Date(), timeZone: TimeZone = .current) -> String {
        let f = DateFormatter(); f.locale = Locale(identifier: "en_US_POSIX"); f.timeZone = timeZone; f.dateFormat = "yyyy-MM-dd"
        return f.string(from: date)
    }
}

struct MarkdownImport {
    struct Entry: Equatable { var file: URL; var title: String; var notebook: String?; var text: String }
    var entries: [Entry] = []
    var skipped: [String] = []
    static func map(_ source: URL) throws -> MarkdownImport {
        let fm = FileManager.default
        let values = try source.resourceValues(forKeys: [.isDirectoryKey, .isSymbolicLinkKey])
        guard values.isSymbolicLink != true else { throw CocoaError(.fileReadNoPermission) }
        let directory = values.isDirectory == true
        let files: [URL]
        if directory {
            files = (fm.enumerator(at: source, includingPropertiesForKeys: [.isRegularFileKey, .isSymbolicLinkKey], options: [])?.allObjects as? [URL] ?? []).sorted { $0.path < $1.path }
        } else { files = [source] }
        var result = MarkdownImport()
        for file in files {
            let v = try file.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey, .isDirectoryKey])
            let rootPath = source.resolvingSymlinksInPath().path
            let filePath = file.deletingLastPathComponent().resolvingSymlinksInPath().appendingPathComponent(file.lastPathComponent).path
            let relative = directory ? String(filePath.dropFirst(rootPath.count + 1)) : file.lastPathComponent
            if v.isSymbolicLink == true { result.skipped.append(relative); continue }
            if v.isDirectory == true { continue }
            guard v.isRegularFile == true, v.isSymbolicLink != true, file.pathExtension.lowercased() == "md" else { result.skipped.append(relative); continue }
            let data = try Data(contentsOf: file)
            guard String(data: data, encoding: .utf8) != nil, data.count <= 1_048_576 else { result.skipped.append(relative); continue }
            // The standard-library decoder retains an initial U+FEFF; Foundation's decoder may
            // consume it as a BOM. Stored Markdown must round-trip its actual UTF-8 bytes.
            let text = String(decoding: data, as: UTF8.self)
            guard !text.contains("\u{0}") else { result.skipped.append(relative); continue }
            let path = (relative as NSString).deletingLastPathComponent
            let notebook = directory ? ([source.lastPathComponent] + (path.isEmpty ? [] : path.components(separatedBy: "/"))).joined(separator: " › ") : nil
            // Core stores LF only. LF documents round-trip byte-for-byte; CRLF input is normalized once.
            result.entries.append(Entry(file: file, title: file.deletingPathExtension().lastPathComponent, notebook: notebook,
                                        text: text.replacingOccurrences(of: "\r\n", with: "\n").replacingOccurrences(of: "\r", with: "\n")))
        }
        return result
    }
    static func export(_ text: String, to file: URL) throws { try Data(text.utf8).write(to: file, options: .atomic) }
}
