import AppKit
import ImageIO
import UniformTypeIdentifiers

struct PreparedNoteImage: Sendable {
    let data: Data
    let width: Int
    let height: Int
    let mime: String
}

enum NoteImages {
    static let maximumBytes = 8 * 1024 * 1024
    /// ImageIO decodes only a bounded thumbnail, applies orientation, and strips metadata.
    /// Kept separate from storage so importing never needs a main-thread core call.
    static func prepare(_ data: Data, longSide: Int = 2400) throws -> PreparedNoteImage {
        guard data.count <= 64 * 1024 * 1024,
              let source = CGImageSourceCreateWithData(data as CFData, nil),
              let type = CGImageSourceGetType(source) as String?,
              [UTType.png.identifier, UTType.jpeg.identifier, UTType.heic.identifier, UTType.heif.identifier, UTType.tiff.identifier].contains(type),
              let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                kCGImageSourceCreateThumbnailFromImageAlways: true,
                kCGImageSourceCreateThumbnailWithTransform: true,
                kCGImageSourceThumbnailMaxPixelSize: min(2400, max(1, longSide)),
                kCGImageSourceShouldCacheImmediately: true
              ] as CFDictionary) else { throw ImageError.invalid }
        func encode(_ type: UTType, quality: Double?) -> Data? {
            let result = NSMutableData()
            guard let destination = CGImageDestinationCreateWithData(result, type.identifier as CFString, 1, nil) else { return nil }
            let options: CFDictionary? = quality.map { [kCGImageDestinationLossyCompressionQuality: $0] as CFDictionary }
            CGImageDestinationAddImage(destination, image, options)
            return CGImageDestinationFinalize(destination) ? result as Data : nil
        }
        if let png = encode(.png, quality: nil), png.count <= maximumBytes {
            return PreparedNoteImage(data: png, width: image.width, height: image.height, mime: "image/png")
        }
        for quality in [0.9, 0.75, 0.55] {
            if let jpeg = encode(.jpeg, quality: quality), jpeg.count <= maximumBytes {
                return PreparedNoteImage(data: jpeg, width: image.width, height: image.height, mime: "image/jpeg")
            }
        }
        throw ImageError.tooLarge
    }
    enum ImageError: LocalizedError {
        case invalid, tooLarge
        var errorDescription: String? { self == .invalid ? "Cannot read this image (PNG, JPEG or HEIC required)" : "Image is still larger than 8 MB after downscaling" }
    }
    @MainActor static func pasted(_ board: NSPasteboard) -> Data? {
        for type in [NSPasteboard.PasteboardType.png, .tiff, NSPasteboard.PasteboardType(UTType.jpeg.identifier), NSPasteboard.PasteboardType(UTType.heic.identifier)] {
            if let data = board.data(forType: type) { return data }
        }
        if let urls = board.readObjects(forClasses: [NSURL.self], options: [.urlReadingFileURLsOnly: true]) as? [URL],
           let url = urls.first, let size = try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize, size <= 64 * 1024 * 1024 {
            return try? Data(contentsOf: url, options: .mappedIfSafe)
        }
        return nil
    }
}
