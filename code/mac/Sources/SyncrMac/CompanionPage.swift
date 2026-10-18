import AppKit
import ImageIO
import UniformTypeIdentifiers
import SyncrCore
import SyncrCompanion

/// Bounded context for this note only. TextKit is main-confined; triangle rasterization/PNG encoding
/// run on a utility task using immutable meshes, without waiting on the live renderer or its GPU.
enum CompanionPage {
    @MainActor static func region(snapshot:NoteSnapshot,selection:ByteSpan?) -> CanvasRegion {
        let width = CGFloat(snapshot.textBlocks.first { $0.role == .document && !$0.tombstone }?.width ?? 1600)
        let page = DocumentLayout.page(note:snapshot,width:width)
        let utf16 = selection.map { String(decoding:Array((page?.text ?? "").utf8).prefix($0.start),as:UTF8.self).utf16.count } ?? 0
        let y = page.flatMap { page in DocumentLayout.lines(note:page,width:page.width).first { NSLocationInRange(utf16,$0.range) || $0.range.location == utf16 }?.rect.minY } ?? 260
        return CanvasRegion(x:0,y:max(0,y-500),width:max(3200,width+1600),height:1800)
    }
    @MainActor static func image(snapshot:NoteSnapshot,selection:ByteSpan?,strokes:[DrawStroke],region:CanvasRegion? = nil) async -> Data? {
        let width = CGFloat(snapshot.textBlocks.first { $0.role == .document && !$0.tombstone }?.width ?? 1600)
        let page = DocumentLayout.page(note:snapshot,width:width) ?? DocumentPage(title:snapshot.title,text:"")
        let region = region ?? Self.region(snapshot:snapshot,selection:selection)
        let crop = CGRect(x:region.x,y:region.y,width:region.width,height:region.height), scale = min(0.4,1280/region.width)
        let base = DocumentLayout.image(note:page,width:page.width,scale:scale,region:crop)
        guard let cg = base.cgImage(forProposedRect:nil,context:nil,hints:nil) else { return nil }
        return await Task.detached(priority:.utility) { raster(base:cg,strokes:strokes,crop:crop,scale:scale) }.value
    }
    private static func raster(base:CGImage,strokes:[DrawStroke],crop:CGRect,scale:CGFloat) -> Data? {
        let width = max(1,Int(crop.width*scale)), height = max(1,Int(crop.height*scale))
        guard let context = CGContext(data:nil,width:width,height:height,bitsPerComponent:8,bytesPerRow:0,space:CGColorSpaceCreateDeviceRGB(),bitmapInfo:CGImageAlphaInfo.premultipliedLast.rawValue) else { return nil }
        context.draw(base,in:CGRect(x:0,y:0,width:width,height:height))
        context.translateBy(x:0,y:CGFloat(height)); context.scaleBy(x:scale,y:-scale); context.translateBy(x:-crop.minX,y:-crop.minY)
        var triangles = 0
        for stroke in strokes {
            let mesh = stroke.mesh
            guard crop.intersects(CGRect(x:CGFloat(mesh.minX+stroke.originX),y:CGFloat(mesh.minY+stroke.originY),width:CGFloat(mesh.maxX-mesh.minX),height:CGFloat(mesh.maxY-mesh.minY))) else { continue }
            let path = CGMutablePath()
            mesh.vertices.withUnsafeBytes { vertices in mesh.indices.withUnsafeBytes { indices in
                func point(_ offset:Int) -> CGPoint? {
                    let i = Int(UInt32(littleEndian:indices.loadUnaligned(fromByteOffset:offset,as:UInt32.self))) * 8
                    guard i+8 <= vertices.count else { return nil }
                    return CGPoint(x:CGFloat(Float(bitPattern:UInt32(littleEndian:vertices.loadUnaligned(fromByteOffset:i,as:UInt32.self)))+stroke.originX),y:CGFloat(Float(bitPattern:UInt32(littleEndian:vertices.loadUnaligned(fromByteOffset:i+4,as:UInt32.self)))+stroke.originY))
                }
                for offset in stride(from:0,to:indices.count-11,by:12) {
                    guard triangles < 200000, let a = point(offset), let b = point(offset+4), let c = point(offset+8) else { break }
                    path.move(to:a); path.addLine(to:b); path.addLine(to:c); path.closeSubpath(); triangles += 1
                }
            }}
            let colour = NotePalette.colour(stroke.colour,dark:false,highlighter:stroke.highlighter)
            context.setFillColor(CGColor(red:CGFloat(colour.x),green:CGFloat(colour.y),blue:CGFloat(colour.z),alpha:CGFloat(colour.w))); context.addPath(path); context.fillPath()
            if triangles >= 200000 { break }
        }
        guard let image = context.makeImage() else { return nil }
        let data = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(data,UTType.png.identifier as CFString,1,nil) else { return nil }
        CGImageDestinationAddImage(destination,image,nil)
        return CGImageDestinationFinalize(destination) ? data as Data : nil
    }
}
