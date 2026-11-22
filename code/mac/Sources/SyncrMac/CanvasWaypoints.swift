import Foundation
import SyncrCore

/// A canvas's saved views. The list is one entity in the note (`SetWaypoints` holds the whole list), so it syncs; the core
/// refuses more than 100, names over 48 characters and a zoom of zero, and the same name (any case) replaces in place.
enum CanvasWaypoints {
    static let maxCount = 100, maxName = 48

    static func cleanName(_ name: String) -> String {
        String(name.trimmingCharacters(in: .whitespacesAndNewlines).prefix(maxName)).trimmingCharacters(in: .whitespaces)
    }

    /// The list after saving `view` as `name`; nil when the name is empty or the list is full of other names.
    static func saving(_ list: [Waypoint], name: String, camera: CanvasCamera, in view: CGSize) -> [Waypoint]? {
        let name = cleanName(name)
        guard !name.isEmpty else { return nil }
        let c = camera.centre(in: view)
        let waypoint = Waypoint(name: name, cx: Float(c.x), cy: Float(c.y), zoom: Float(camera.zoom))
        var next = list
        if let i = next.firstIndex(where: { $0.name.caseInsensitiveCompare(name) == .orderedSame }) { next[i] = waypoint }
        else if next.count < maxCount { next.append(waypoint) }
        else { return nil }
        return next
    }

    static func removing(_ list: [Waypoint], name: String) -> [Waypoint] {
        list.filter { $0.name.caseInsensitiveCompare(name) != .orderedSame }
    }

    static func camera(_ waypoint: Waypoint, in view: CGSize) -> CanvasCamera {
        .centred(on: CGPoint(x: CGFloat(waypoint.cx), y: CGFloat(waypoint.cy)), zoom: Double(waypoint.zoom), in: view)
    }

    /// "Waypoint 3": the first number not already a name.
    static func nextName(_ list: [Waypoint]) -> String {
        var n = list.count + 1
        while list.contains(where: { $0.name.caseInsensitiveCompare("Waypoint \(n)") == .orderedSame }) { n += 1 }
        return "Waypoint \(n)"
    }
}

/// Canvas-only commands on an open note, on the same base-and-dependency pattern as every metadata command.
enum CanvasCommands {
    private static func base(_ head: EntityHead) -> Base { head.confirmedRevision.map { .confirmed(revision: $0) } ?? .operation(operationId: head.operationId) }

    static func setWaypoints(_ note: Note, _ list: [Waypoint], newId: () -> String) throws {
        let snapshot = try note.snapshot()
        guard snapshot.kind == .canvas, let current = snapshot.waypoints else { throw ViewerError.invalidSource }
        _ = try note.submit(command: Command(operationId: newId(), dependencies: [current.head.operationId],
                                              body: .setWaypoints(waypoints: list, base: base(current.head))))
    }
}

/// What a Canvas snapshot carries beyond a Note's: the saved views, with the head a command needs. The core's
/// `canvas_background` is ignored: a canvas is plain paper in the theme's colour (owner, 2026-11-21).
struct CanvasState: Equatable {
    var waypoints: [Waypoint]
    var waypointsHead: EntityHead

    init?(snapshot: NoteSnapshot) {
        guard snapshot.kind == .canvas, let points = snapshot.waypoints else { return nil }
        waypoints = points.items
        waypointsHead = points.head
    }
}
