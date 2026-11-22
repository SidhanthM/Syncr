import AppKit
import CoreImage

/// A QR code drawn with CoreImage, so nothing is added to the app. Dark modules on white with a quiet zone, whatever the window's
/// appearance, because scanners read dark on light.
enum QRCode {
    /// The code for `text` as an image `side` points square (whole pixels per module, no smoothing), or nil if it cannot be encoded.
    @MainActor static func image(_ text: String, side: CGFloat, scale: CGFloat = 2) -> NSImage? {
        guard let cg = cgImage(text, pixels: Int(side * scale)) else { return nil }
        return NSImage(cgImage: cg, size: NSSize(width: side, height: side))
    }

    static func cgImage(_ text: String, pixels: Int) -> CGImage? {
        guard let filter = CIFilter(name: "CIQRCodeGenerator"), let data = text.data(using: .utf8) else { return nil }
        filter.setValue(data, forKey: "inputMessage")
        filter.setValue("M", forKey: "inputCorrectionLevel")
        guard let raw = filter.outputImage else { return nil }
        let modules = Int(raw.extent.width)
        let quiet = 4
        let per = max(1, pixels / (modules + 2 * quiet))
        let size = (modules + 2 * quiet) * per
        let context = CIContext(options: [.useSoftwareRenderer: true])
        // Whole pixels per module, drawn by hand: no interpolation can blur an edge.
        guard let small = context.createCGImage(raw, from: raw.extent), let bitmap = CGContext(
            data: nil, width: size, height: size, bitsPerComponent: 8, bytesPerRow: 0, space: CGColorSpaceCreateDeviceGray(),
            bitmapInfo: CGImageAlphaInfo.none.rawValue) else { return nil }
        bitmap.setFillColor(gray: 1, alpha: 1)
        bitmap.fill(CGRect(x: 0, y: 0, width: size, height: size))
        bitmap.interpolationQuality = .none
        bitmap.draw(small, in: CGRect(x: quiet * per, y: quiet * per, width: modules * per, height: modules * per))
        return bitmap.makeImage()
    }

    /// What a scanner would read from `image`: every QR code message found in it.
    static func decode(_ image: CGImage) -> [String] {
        guard let detector = CIDetector(ofType: CIDetectorTypeQRCode, context: nil, options: [CIDetectorAccuracy: CIDetectorAccuracyHigh]) else { return [] }
        return detector.features(in: CIImage(cgImage: image)).compactMap { ($0 as? CIQRCodeFeature)?.messageString }
    }
}
