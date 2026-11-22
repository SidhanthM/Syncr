import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

// The pad's Local mode and its pictures (rework-v1 §V, as built 2026-11-16). Pure: no sockets, no views.

/// The pen frame: the rectangle of the note's canvas that the phone's pad covers in Local mode. It is kept as
/// fractions of the Mac's visible view, so it follows the Mac's scrolling and zoom and never leaves the view; the
/// phone asks for a rectangle in canvas units and the Mac answers with the one it settled on.
struct PadFrame: Equatable, Sendable {
    static let minWidth: CGFloat = 400
    static let canvasWidth: CGFloat = 4800
    /// Pad width over height.
    var aspect: CGFloat
    /// Width as a fraction of the view's width (all of it, so panning to or from the paper's edge does not resize it).
    var widthFraction: CGFloat = 0.4
    /// Position as fractions of the room left over: 0 is the left or top edge of the view, 1 the right or bottom.
    var fx: CGFloat = 0.5
    var fy: CGFloat = 0
    /// A canvas has no edges: the frame is limited to the visible view only. A page keeps 0...4800 across and 0 down.
    var unbounded = false

    init(aspect: CGFloat) { self.aspect = Self.cleanAspect(aspect) }

    static func cleanAspect(_ a: CGFloat) -> CGFloat { a.isFinite ? min(4, max(0.25, a)) : 1.6 }

    /// The part of the view that is paper: the canvas is 0...4800 across and starts at 0 down.
    static func usable(_ view: CGRect, unbounded: Bool = false) -> CGRect {
        if unbounded { return CGRect(x: view.minX, y: view.minY, width: max(1, view.width), height: max(1, view.height)) }
        let left = max(0, view.minX), top = max(0, view.minY)
        return CGRect(x: left, y: top, width: max(1, min(view.maxX, canvasWidth) - left), height: max(1, view.maxY - top))
    }

    private func width(in usable: CGRect, wanted: CGFloat) -> CGFloat {
        let most = min(usable.width, usable.height * aspect)
        return max(min(Self.minWidth, most), min(most, wanted))
    }

    /// The frame in canvas units for this visible view.
    func rect(in view: CGRect) -> CGRect {
        let u = Self.usable(view, unbounded: unbounded)
        let w = width(in: u, wanted: widthFraction * view.width), h = w / aspect
        return CGRect(x: u.minX + fx * (u.width - w), y: u.minY + fy * (u.height - h), width: w, height: h)
    }

    /// The size the phone's request gets in this view, keeping its centre.
    func sized(_ wanted: CGRect, view: CGRect) -> CGRect {
        guard wanted.width.isFinite, wanted.midX.isFinite, wanted.midY.isFinite else { return rect(in: view) }
        let w = width(in: Self.usable(view, unbounded: unbounded), wanted: wanted.width), h = w / aspect
        if unbounded { return CGRect(x: wanted.midX - w / 2, y: wanted.midY - h / 2, width: w, height: h) }
        let x = min(max(0, wanted.midX - w / 2), Self.canvasWidth - w)
        return CGRect(x: x, y: max(0, wanted.midY - h / 2), width: w, height: h)
    }

    /// How far the view has to scroll, in canvas units, for `rect` to be inside it.
    static func scroll(toShow rect: CGRect, view: CGRect, unbounded: Bool = false) -> CGVector {
        let u = usable(view, unbounded: unbounded)
        var dx: CGFloat = 0, dy: CGFloat = 0
        if rect.minX < u.minX { dx = rect.minX - u.minX } else if rect.maxX > u.maxX { dx = rect.maxX - u.maxX }
        if rect.minY < u.minY { dy = rect.minY - u.minY } else if rect.maxY > u.maxY { dy = rect.maxY - u.maxY }
        return CGVector(dx: dx, dy: dy)
    }

    /// Adopts `rect` (already sized, the view already scrolled): the fractions that reproduce it, clamped into the view.
    mutating func place(_ rect: CGRect, view: CGRect) {
        let u = Self.usable(view, unbounded: unbounded)
        let w = width(in: u, wanted: rect.width), h = w / aspect
        widthFraction = w / max(1, view.width)
        fx = u.width - w > 0.5 ? min(1, max(0, (rect.minX - u.minX) / (u.width - w))) : 0.5
        fy = u.height - h > 0.5 ? min(1, max(0, (rect.minY - u.minY) / (u.height - h))) : 0
    }
}

/// One ordered run of preview frames arriving twice: on the connection (always, in order) and as datagrams (sooner
/// when the connection stalls on a lost packet, but possibly lost, repeated or out of order). Both sides number the
/// frames the same way, counting from zero at `welcome`. Each frame is handed on once, in order, from whichever path
/// brings it first; nothing is ever dropped, because the connection delivers every frame eventually.
struct PreviewRace {
    static let window: UInt32 = 512
    static let held = 64
    /// The next frame to hand on.
    private(set) var next: UInt32 = 0
    private var streamed: UInt32 = 0
    private var early: [UInt32: Data] = [:]
    private(set) var fromDatagrams = 0

    private mutating func drain(into out: inout [Data]) {
        while let frame = early.removeValue(forKey: next) { out.append(frame); next &+= 1; fromDatagrams += 1 }
    }

    /// A frame read from the connection.
    mutating func stream(_ frame: Data) -> [Data] {
        let seq = streamed
        streamed &+= 1
        guard seq >= next else { return [] }  // a datagram brought it first
        var out = [frame]
        next = seq &+ 1
        early = early.filter { $0.key >= next }
        drain(into: &out)
        return out
    }

    /// Frames from one datagram: `first` is the number of the first of them.
    mutating func datagram(first: UInt32, frames: [Data]) -> [Data] {
        var out: [Data] = []
        for (i, frame) in frames.enumerated() {
            let seq = first &+ UInt32(i)
            if seq < next { continue }
            if seq == next { out.append(frame); next &+= 1; fromDatagrams += 1; drain(into: &out) }
            else if seq - next <= Self.window, early.count < Self.held { early[seq] = frame }
        }
        return out
    }

    /// Splits a datagram's payload (after its key): first number (u32 BE), then length-prefixed (u16 BE) frames.
    static func parse(_ payload: Data) -> (first: UInt32, frames: [Data])? {
        let b = [UInt8](payload)
        guard b.count >= 6 else { return nil }
        let first = UInt32(b[0]) << 24 | UInt32(b[1]) << 16 | UInt32(b[2]) << 8 | UInt32(b[3])
        var at = 4, frames: [Data] = []
        while at < b.count {
            guard at + 2 <= b.count else { return nil }
            let n = Int(b[at]) << 8 | Int(b[at + 1])
            at += 2
            guard n >= 1, at + n <= b.count, frames.count < 16 else { return nil }
            frames.append(Data(b[at..<(at + n)]))
            at += n
        }
        return frames.isEmpty ? nil : (first, frames)
    }
}

/// A picture of what the pad covers, for the phone (message kind 0x53): kind, the target `rev` it was drawn at, how
/// many pad strokes were saved by then (the phone drops its own copy of those), the pictured region in canvas units,
/// then a JPEG. Never split: an image that would not fit is encoded smaller.
enum PadPicture {
    static let kind: UInt8 = 0x53
    /// Picture kinds: 1 what the pen frame covers (the snippet), 2 the page map (not sent: the phone draws it from `target`), 3 the Mac's whole visible view (Read).
    static let region: UInt8 = 1
    static let view: UInt8 = 3
    static let maxImage = 65_000

    /// The whole message with its length prefix, or nil when the image is too large.
    static func framed(rev: UInt32, saved: UInt32, region: CGRect, image: Data, kind: UInt8 = PadPicture.region) -> Data? {
        guard !image.isEmpty, image.count <= maxImage else { return nil }
        var body = Data([Self.kind, kind])
        for v in [rev, saved] { body.append(contentsOf: [UInt8(v >> 24 & 255), UInt8(v >> 16 & 255), UInt8(v >> 8 & 255), UInt8(v & 255)]) }
        for v in [region.minX, region.minY, region.width, region.height] {
            let bits = Float(v).bitPattern
            body.append(contentsOf: [UInt8(bits >> 24 & 255), UInt8(bits >> 16 & 255), UInt8(bits >> 8 & 255), UInt8(bits & 255)])
        }
        body.append(image)
        let n = UInt32(body.count)
        var out = Data([UInt8(n >> 24 & 255), UInt8(n >> 16 & 255), UInt8(n >> 8 & 255), UInt8(n & 255)])
        out.append(body)
        return out
    }

    /// JPEG bytes that fit one message: quality drops first, then the size halves.
    static func jpeg(_ image: CGImage) -> Data? {
        var source = image
        for _ in 0..<3 {
            for quality in [0.6, 0.4, 0.25] {
                let data = NSMutableData()
                guard let dest = CGImageDestinationCreateWithData(data, UTType.jpeg.identifier as CFString, 1, nil) else { return nil }
                CGImageDestinationAddImage(dest, source, [kCGImageDestinationLossyCompressionQuality: quality] as CFDictionary)
                guard CGImageDestinationFinalize(dest) else { return nil }
                if data.length <= maxImage { return data as Data }
            }
            let w = source.width / 2, h = source.height / 2
            guard w >= 32, h >= 32, let ctx = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: 0,
                space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipFirst.rawValue) else { return nil }
            ctx.interpolationQuality = .high
            ctx.draw(source, in: CGRect(x: 0, y: 0, width: w, height: h))
            guard let smaller = ctx.makeImage() else { return nil }
            source = smaller
        }
        return nil
    }
}
