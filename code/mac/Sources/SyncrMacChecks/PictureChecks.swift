import AppKit
import CryptoKit

@MainActor func pictureSurfaceChecks() throws {
    let picture = MacPicture.fixture
    try check(PictureRenderer.bounds(picture).width > 270, "fit contains actual label and stroke bounds")
    var hashes = Set<Data>()
    for step in 1...3 {
        guard let image = PictureRenderer.image(picture, through: step), let png = image.representation(using: .png, properties: [:]) else { throw CheckFailure(description: "picture render failed") }
        hashes.insert(Data(SHA256.hash(data: png)))
    }
    try check(hashes.count == 3, "hide/undo changes rendered pixels")
    let light = PictureRenderer.image(picture, through: 3, dark: false)!.representation(using: .png, properties: [:])!
    let dark = PictureRenderer.image(picture, through: 3, dark: true)!.representation(using: .png, properties: [:])!
    try check(light != dark, "picture respects light/dark tokens")
    let canvas = NSImage(size: CGSize(width: 3000,height: 1000), flipped: false) { rect in NSColor.systemBlue.setFill(); rect.fill(); return true }
    let source = NSBitmapImageRep(data: canvas.tiffRepresentation!)!.representation(using: .png, properties: [:])!
    let originalHash = Data(SHA256.hash(data: source))
    let image = try NoteImages.prepare(source)
    try check(image.width == 2400 && image.height == 800 && image.data.count <= NoteImages.maximumBytes, "image long side and bytes bounded")
    let small = try NoteImages.prepare(image.data, longSide: 1280)
    try check(small.width == 1280 && small.height <= 1280, "provider copy scaled independently")
    try check(Data(SHA256.hash(data: source)) == originalHash && source != image.data, "conversion leaves original bytes untouched")
    do { _ = try NoteImages.prepare(Data("not an image".utf8)); throw CheckFailure(description: "invalid image accepted") } catch is NoteImages.ImageError {}
    let voice = VoicePreferences(identifier: "system",rate: 0.4)
    let decoded = try JSONDecoder().decode(VoicePreferences.self,from: JSONEncoder().encode(voice))
    try check(decoded.identifier == voice.identifier && decoded.rate == voice.rate, "voice selection round trips")
    print("Picture/image/voice surface: OK (3 distinct undo states, theme, 2400px/8MB import, 1280px provider copy, invalid bytes, voice persistence)")
}
