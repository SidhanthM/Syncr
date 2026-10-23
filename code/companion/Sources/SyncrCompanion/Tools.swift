import Foundation
import CoreFoundation

public struct Point: Codable, Equatable, Sendable { public var x: Double; public var y: Double }
public struct Primitive: Codable, Equatable, Sendable {
    public var type: String
    public var points: [Point]?
    public var x: Double?; public var y: Double?; public var width: Double?; public var height: Double?
    public var text: String?; public var size: Double?
}
public struct DrawingStep: Codable, Equatable, Sendable { public var caption: String; public var primitives: [Primitive] }
public struct Drawing: Codable, Equatable, Sendable {
    public var quote: String; public var title: String; public var width: Double; public var height: Double; public var steps: [DrawingStep]
}
public struct CanvasRegion: Codable, Equatable, Sendable {
    public var x: Double; public var y: Double; public var width: Double; public var height: Double
    public init(x:Double,y:Double,width:Double,height:Double) { self.x = x; self.y = y; self.width = width; self.height = height }
}
public enum ValidatedTool: Equatable, Sendable {
    case stayQuiet
    case marginNote(quote: String, kind: String, text: String, sources: [Source])
    case canvasNote(region: CanvasRegion, kind: String, text: String, sources: [Source])
    case draw(Drawing)
    case reply(threadID: String, text: String, sources: [Source])
}
public enum ToolValidator {
    public static let maxBytes = 65536
    public static func validate(_ data: Data, web: Bool = false) throws -> [ValidatedTool] {
        guard data.count <= maxBytes else { throw CompanionError.invalid("tool byte limit") }
        try StrictJSON.check(data)
        guard let object = try JSONSerialization.jsonObject(with: data) as? [String: Any], Set(object.keys) == ["calls"], let calls = object["calls"] as? [[String: Any]], calls.count <= 4, !calls.isEmpty else { throw CompanionError.invalid("tool envelope") }
        return try calls.map { call in
            try keys(call, ["name", "arguments"])
            guard let name = call["name"] as? String, let args = call["arguments"] as? [String: Any] else { throw CompanionError.invalid("tool") }
            switch name {
            case "stay_quiet": try keys(args, []); return .stayQuiet
            case "margin_note":
                try keys(args, args["region"] == nil ? ["quote", "kind", "text", "sources"] : ["region", "kind", "text", "sources"])
                let kind = try string(args, "kind", 32)
                guard ["question", "explanation", "flag"].contains(kind) else { throw CompanionError.invalid("kind") }
                if let region = args["region"] as? [String:Any] {
                    try keys(region,["x","y","width","height"])
                    let x = try number(region,"x",0,0.999), y = try number(region,"y",0,0.999)
                    return .canvasNote(region:CanvasRegion(x:x,y:y,width:try number(region,"width",0.001,1-x),height:try number(region,"height",0.001,1-y)),kind:kind,text:try string(args,"text",8192),sources:try sources(args,web))
                }
                return .marginNote(quote: try string(args, "quote", 16384), kind: kind, text: try string(args, "text", 8192), sources: try sources(args, web))
            case "reply":
                try keys(args, ["thread_id", "text", "sources"])
                return .reply(threadID: try string(args, "thread_id", 128), text: try string(args, "text", 8192), sources: try sources(args, web))
            case "draw": return .draw(try drawing(args))
            default: throw CompanionError.invalid("unknown tool")
            }
        }
    }
    private static func keys(_ d: [String: Any], _ expected: Set<String>) throws {
        guard Set(d.keys) == expected else { throw CompanionError.invalid("fields") }
    }
    private static func string(_ d: [String: Any], _ key: String, _ limit: Int, empty: Bool = false) throws -> String {
        guard let s = d[key] as? String, s.utf8.count <= limit, !s.contains("\0"), empty || !s.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { throw CompanionError.invalid(key) }; return s
    }
    private static func number(_ d: [String: Any], _ key: String, _ lower: Double, _ upper: Double) throws -> Double {
        guard let n = d[key] as? NSNumber, CFGetTypeID(n) != CFBooleanGetTypeID(), n.doubleValue.isFinite else { throw CompanionError.invalid(key) }
        return min(upper, max(lower, n.doubleValue))
    }
    private static func sources(_ d: [String: Any], _ web: Bool) throws -> [Source] {
        guard let values = d["sources"] as? [[String: Any]], values.count <= 10, web || values.isEmpty else { throw CompanionError.invalid("sources") }
        return try values.map { value in
            try keys(value, ["title", "url"])
            let url = try string(value, "url", 2048)
            guard let u = URLComponents(string: url), u.scheme == "https", u.host != nil, u.user == nil, u.password == nil else { throw CompanionError.invalid("source URL") }
            return Source(title: try string(value, "title", 256), url: url)
        }
    }
    private static func drawing(_ d: [String: Any]) throws -> Drawing {
        try keys(d, ["quote", "title", "width", "height", "steps"])
        let width = try number(d, "width", 100, 1600), height = try number(d, "height", 100, 1600)
        guard let steps = d["steps"] as? [[String: Any]], (1...12).contains(steps.count) else { throw CompanionError.invalid("steps") }
        var count = 0
        let validated = try steps.map { step -> DrawingStep in
            try keys(step, ["caption", "primitives"])
            guard let primitives = step["primitives"] as? [[String: Any]] else { throw CompanionError.invalid("primitives") }
            count += primitives.count
            guard count <= 400 else { throw CompanionError.invalid("primitive limit") }
            let items = try primitives.map { p -> Primitive in
                let type = try string(p, "type", 32)
                switch type {
                case "line", "polyline", "arrow", "shaded_region":
                    try keys(p, ["type", "points"])
                    guard let points = p["points"] as? [[String: Any]], points.count >= (type == "shaded_region" ? 3 : 2), points.count <= 128,
                          (type != "line" && type != "arrow") || points.count == 2 else { throw CompanionError.invalid("points") }
                    let bounded = try points.map { point -> Point in
                        try keys(point, ["x", "y"])
                        return Point(x: try number(point, "x", 0, width), y: try number(point, "y", 0, height))
                    }
                    return Primitive(type: type, points: bounded)
                case "rectangle", "ellipse":
                    try keys(p, ["type", "x", "y", "width", "height"])
                    let x = try number(p, "x", 0, width), y = try number(p, "y", 0, height)
                    return Primitive(type: type, x: x, y: y, width: try number(p, "width", 0, width - x), height: try number(p, "height", 0, height - y))
                case "dot":
                    try keys(p, ["type", "x", "y", "size"])
                    return Primitive(type: type, x: try number(p, "x", 0, width), y: try number(p, "y", 0, height), size: try number(p, "size", 1, 24))
                case "label":
                    try keys(p, ["type", "x", "y", "text", "size"])
                    return Primitive(type: type, x: try number(p, "x", 0, width), y: try number(p, "y", 0, height), text: try string(p, "text", 256), size: try number(p, "size", 10, 40))
                default: throw CompanionError.invalid("primitive type")
                }
            }
            return DrawingStep(caption: try string(step, "caption", 512, empty: true), primitives: items)
        }
        return Drawing(quote: try string(d, "quote", 16384), title: try string(d, "title", 256), width: width, height: height, steps: validated)
    }
    public static func envelope(nativeCalls: [[String: Any]]) throws -> Data {
        try JSONSerialization.data(withJSONObject: ["calls": nativeCalls], options: [.sortedKeys])
    }
}

public enum ToolSchemas {
    static func object(_ properties: [String: Any]) -> [String: Any] { ["type": "object", "properties": properties, "required": properties.keys.sorted(), "additionalProperties": false] }
    static func string(_ max: Int) -> [String: Any] { ["type": "string", "minLength": 1, "maxLength": max] }
    public static var tools: [[String: Any]] {
        let coordinate: [String: Any] = ["type": "number", "minimum": 0, "maximum": 1600]
        let point = object(["x": coordinate, "y": coordinate])
        let sources: [String: Any] = ["type": "array", "maxItems": 10, "items": object(["title": string(256), "url": string(2048)])]
        var primitiveOptions: [[String: Any]] = []
        for type in ["line", "polyline", "arrow", "shaded_region"] {
            primitiveOptions.append(object(["type": ["const": type], "points": ["type": "array", "minItems": type == "shaded_region" ? 3 : 2, "maxItems": (type == "line" || type == "arrow") ? 2 : 128, "items": point]]))
        }
        for type in ["rectangle", "ellipse"] { primitiveOptions.append(object(["type": ["const": type], "x": coordinate, "y": coordinate, "width": coordinate, "height": coordinate])) }
        primitiveOptions.append(object(["type": ["const": "dot"], "x": coordinate, "y": coordinate, "size": ["type": "number", "minimum": 1, "maximum": 24]]))
        primitiveOptions.append(object(["type": ["const": "label"], "x": coordinate, "y": coordinate, "text": string(256), "size": ["type": "number", "minimum": 10, "maximum": 40]]))
        let fraction:[String:Any] = ["type":"number","minimum":0,"maximum":1]
        var margin = object(["quote":string(16384),"region":object(["x":fraction,"y":fraction,"width":fraction,"height":fraction]),"kind":["type":"string","enum":["question","explanation","flag"]],"text":string(8192),"sources":sources])
        margin["required"] = ["kind","text","sources"]; margin["oneOf"] = [["required":["quote"]],["required":["region"]]]
        let definitions: [(String, String, [String: Any])] = [
            ("stay_quiet", "Usually choose this: nothing needs saying.", object([:])),
            ("margin_note", "Ask, explain, or flag beside a quoted passage. For ink with no text nearby, use region instead of quote: a rectangle in normalized 0–1 coordinates of the attached page image. Never supply both.", margin),
            ("reply", "Reply only to an existing thread ID.", object(["thread_id": string(128), "text": string(8192), "sources": sources])),
            ("draw", "Draw incrementally under a quoted passage; labels are typeset. At most 400 primitives total, 64 KiB.", object(["quote": string(16384), "title": string(256), "width": ["type": "number", "minimum": 100, "maximum": 1600], "height": ["type": "number", "minimum": 100, "maximum": 1600], "steps": ["type": "array", "minItems": 1, "maxItems": 12, "items": object(["caption": ["type": "string", "maxLength": 512], "primitives": ["type": "array", "maxItems": 400, "items": ["oneOf": primitiveOptions]]])]]))]
        return definitions.map { ["name": $0.0, "description": $0.1, "input_schema": $0.2] }
    }
    public static var strictJSONInstruction: String {
        let choices = tools.map { tool in object(["name": ["const": tool["name"]!], "arguments": tool["input_schema"]!]) }
        let schema = object(["calls": ["type": "array", "minItems": 1, "maxItems": 4, "items": ["oneOf": choices]]])
        let bytes = try! JSONSerialization.data(withJSONObject: schema, options: [.sortedKeys])
        return "Return ONLY a JSON object matching this schema, without Markdown fences or extra text: " + String(decoding: bytes, as: UTF8.self)
    }
}
