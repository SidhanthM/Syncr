import AppKit
import CoreGraphics
import ImageIO
import UniformTypeIdentifiers

public enum SketchRenderer {
    public static func image(_ drawing: Drawing, through step: Int, scale: Double = 1) -> CGImage? {
        let width = Int(drawing.width * scale), height = Int(drawing.height * scale)
        guard width > 0, height > 0, width <= 3200, height <= 3200,
              let context = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0, space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return nil }
        context.setFillColor(CGColor(red: 0.985, green: 0.975, blue: 0.95, alpha: 1)); context.fill(CGRect(x: 0, y: 0, width: width, height: height))
        context.translateBy(x: 0, y: CGFloat(height)); context.scaleBy(x: scale, y: -scale)
        context.setStrokeColor(CGColor(red: 0.24, green: 0.28, blue: 0.42, alpha: 1)); context.setFillColor(CGColor(red: 0.24, green: 0.28, blue: 0.42, alpha: 1))
        context.setLineCap(.round); context.setLineJoin(.round)
        var index = 0
        for s in drawing.steps.prefix(max(0, min(step, drawing.steps.count))) {
            for p in s.primitives {
                index += 1; context.setLineWidth(1.8 + Double(index % 4) * 0.12)
                if let points = p.points, let first = points.first {
                    context.beginPath(); context.move(to: CGPoint(x: first.x, y: first.y))
                    for (i, point) in points.dropFirst().enumerated() {
                        let previous = points[i]
                        // Deterministic local wobble, never changing the stored model geometry.
                        context.addQuadCurve(to: CGPoint(x: point.x, y: point.y), control: CGPoint(x: (previous.x + point.x) / 2 + sin(Double(index + i)) * 0.7, y: (previous.y + point.y) / 2 + cos(Double(index + i)) * 0.7))
                    }
                    if p.type == "shaded_region" { context.closePath(); context.saveGState(); context.setAlpha(0.14); context.drawPath(using: .fillStroke); context.restoreGState() }
                    else { context.strokePath() }
                    if p.type == "arrow", let last = points.last {
                        let a = atan2(last.y - first.y, last.x - first.x)
                        context.move(to: CGPoint(x: last.x - 10 * cos(a - 0.4), y: last.y - 10 * sin(a - 0.4)))
                        context.addLine(to: CGPoint(x: last.x, y: last.y)); context.addLine(to: CGPoint(x: last.x - 10 * cos(a + 0.4), y: last.y - 10 * sin(a + 0.4))); context.strokePath()
                    }
                } else if let x = p.x, let y = p.y {
                    let rect = CGRect(x: x, y: y, width: p.width ?? p.size ?? 4, height: p.height ?? p.size ?? 4)
                    switch p.type {
                    case "rectangle": context.stroke(rect)
                    case "ellipse": context.strokeEllipse(in: rect)
                    case "dot": context.fillEllipse(in: CGRect(x: x - (p.size ?? 4) / 2, y: y - (p.size ?? 4) / 2, width: p.size ?? 4, height: p.size ?? 4))
                    case "label":
                        let graphics = NSGraphicsContext(cgContext: context, flipped: true)
                        NSGraphicsContext.saveGraphicsState(); NSGraphicsContext.current = graphics
                        (p.text ?? "").draw(in: CGRect(x: x, y: y, width: max(0, drawing.width - x), height: max(0, drawing.height - y)), withAttributes: [.font: NSFont.systemFont(ofSize: p.size ?? 14), .foregroundColor: NSColor(calibratedRed: 0.24, green: 0.28, blue: 0.42, alpha: 1)])
                        NSGraphicsContext.restoreGraphicsState()
                    default: break
                    }
                }
            }
        }
        return context.makeImage()
    }
    public static func savePNG(_ image: CGImage, to url: URL) throws {
        guard let output = CGImageDestinationCreateWithURL(url as CFURL, UTType.png.identifier as CFString, 1, nil) else { throw CompanionError.invalid("image destination") }
        CGImageDestinationAddImage(output, image, nil)
        guard CGImageDestinationFinalize(output) else { throw CompanionError.invalid("image write") }
    }
    public static func contactSheet(_ drawing: Drawing, at folder: URL) throws {
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let tileW = 400, tileH = 420, columns = 3, rows = (drawing.steps.count + columns - 1) / columns
        guard let ctx = CGContext(data: nil, width: tileW * columns, height: tileH * rows, bitsPerComponent: 8, bytesPerRow: 0, space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { throw CompanionError.invalid("contact sheet") }
        ctx.setFillColor(CGColor(gray: 1, alpha: 1)); ctx.fill(CGRect(x: 0, y: 0, width: tileW * columns, height: tileH * rows))
        for n in 1...drawing.steps.count {
            guard let image = image(drawing, through: n) else { continue }
            try savePNG(image, to: folder.appendingPathComponent("step-\(n).png"))
            let x = (n - 1) % columns * tileW, y = (rows - 1 - (n - 1) / columns) * tileH
            let ratio = min(380 / drawing.width, 280 / drawing.height)
            ctx.draw(image, in: CGRect(x: Double(x + 10), y: Double(y + 130), width: drawing.width * ratio, height: drawing.height * ratio))
            NSGraphicsContext.saveGraphicsState(); NSGraphicsContext.current = NSGraphicsContext(cgContext: ctx, flipped: false)
            "\(n)/\(drawing.steps.count)  \(drawing.steps[n-1].caption)".draw(in: CGRect(x: x + 10, y: y + 5, width: 380, height: 120), withAttributes: [.font: NSFont.systemFont(ofSize: 12), .foregroundColor: NSColor.black])
            NSGraphicsContext.restoreGraphicsState()
        }
        guard let sheet = ctx.makeImage() else { throw CompanionError.invalid("contact sheet") }
        try savePNG(sheet, to: folder.appendingPathComponent("contact-sheet.png"))
    }
}
public enum SketchTrial {
    public static let prompts = [
        "Show N-Queens backtracking on a 4 by 4 board, including a failed branch and a backtrack.",
        "Explain a password hashing flow: random salt, slow password hash, stored record, verification; never encryption.",
        "Draw the Fibonacci call tree for fib(4), showing repeated subproblems.",
        "Explain a cache invalidation race between a writer, a database and a reader.",
        "Draw the water cycle with evaporation, condensation and precipitation.",
        "Explain binary search for 7 in [1,3,5,7,9,11,13].",
        "Draw a two-phase commit with coordinator, two participants, prepare and commit.",
        "Explain a feedback loop with a thermostat, heater and room temperature.",
        "Draw a causal diagram distinguishing correlation from a common cause.",
        "Explain gradient descent on a simple bowl with three successive steps.",
        "Draw a linked-list insertion between two nodes without losing the tail.",
        "Show a decision tree for whether to build or buy a small software service."]
}
