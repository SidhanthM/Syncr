import AppKit
import SyncrCore
import SyncrCompanion

/// Core owns every SVG parse/filter. Apps consume only immutable display lists.
struct CorePictureInspector: PictureInspector {
    func filter(svg: String) throws -> String { try filterPicture(svg: svg) }
    func displayList(svg: String) throws -> SyncrCompanion.PictureDisplayList {
        let list = try pictureDisplayList(svg: svg)
        func box(_ b: SyncrCore.PictureBounds) -> SyncrCompanion.PictureBounds {
            SyncrCompanion.PictureBounds(minX: Double(b.minX), minY: Double(b.minY), maxX: Double(b.maxX), maxY: Double(b.maxY))
        }
        func items(_ values: [SyncrCore.PictureItem], prefix: String) -> [PictureDrawItem] {
            values.enumerated().map { index, item in
                switch item {
                case .polyline(let points, let closed, let stroke, let width, let fill, let bounds):
                    var points = points.map { SyncrCompanion.PicturePoint(Double($0.x),Double($0.y)) }
                    if closed, let first = points.first { points.append(first) }
                    return PictureDrawItem(id: prefix+"-\(index)",points: points,stroke: stroke,fill: fill,bounds: box(bounds),strokeWidth: Double(width),closed: closed)
                case .text(let text,let x,let y,let anchor,let baseline,let font,let size,let fill,let transform,_):
                    // The core's text bounds are character-count estimates about twice the real width; the engine's check
                    // wants the glyph box, so measure with the font the renderer will use (the core says callers measure).
                    let layout = PictureFonts.measure(text, token: font, size: Double(size), anchor: anchor, baseline: baseline)
                    let measured = layout.box.applying(CGAffineTransform(a: transform.a,b: transform.b,c: transform.c,d: transform.d,tx: 0,ty: 0)).offsetBy(dx: Double(x),dy: Double(y))
                    return PictureDrawItem(id: prefix+"-\(index)",text: text,x: Double(x),y: Double(y),fontSize: Double(size),anchor: anchor,stroke: "none",fill: fill,
                                           bounds: SyncrCompanion.PictureBounds(minX: measured.minX,minY: measured.minY,maxX: measured.maxX,maxY: measured.maxY),
                                           transform: SyncrCompanion.PictureTransform(a: transform.a,b: transform.b,c: transform.c,d: transform.d),font: font,baseline: baseline)
                }
            }
        }
        return SyncrCompanion.PictureDisplayList(width: Double(list.width),height: Double(list.height),always: items(list.always,prefix: "always"),steps: list.steps.map {
            SyncrCompanion.PictureStep(number: Int($0.number),caption: $0.caption,items: items($0.items,prefix: "step-\($0.number)"),hide: $0.hide.map(Int.init),dim: $0.dim.map(Int.init))
        })
    }
    static func mac(svg: String) throws -> MacPicture {
        let list = try pictureDisplayList(svg: svg)
        func rect(_ b: SyncrCore.PictureBounds) -> MacPicture.Rect { .init(minX: Double(b.minX),minY: Double(b.minY),maxX: Double(b.maxX),maxY: Double(b.maxY)) }
        func items(_ values: [SyncrCore.PictureItem]) -> [MacPicture.Item] {
            values.map { item in
                switch item {
                case .polyline(let points,let closed,let stroke,let width,let fill,let bounds):
                    return .path(points: points.map { MacPicture.Point(x: Double($0.x),y: Double($0.y)) },closed: closed,stroke: stroke,width: Double(width),fill: fill,bounds: rect(bounds))
                case .text(let text,let x,let y,let anchor,let baseline,let font,let size,let fill,let transform,let bounds):
                    return .text(text: text,x: Double(x),y: Double(y),anchor: anchor,font: font,size: Double(size),fill: fill,
                                 transform: MacPicture.Transform(a: transform.a,b: transform.b,c: transform.c,d: transform.d),baseline: baseline,bounds: rect(bounds))
                }
            }
        }
        return MacPicture(width: Double(list.width),height: Double(list.height),always: items(list.always),steps: list.steps.map { MacPicture.Step(caption: $0.caption,hide: $0.hide.map(Int.init),dim: $0.dim.map(Int.init),items: items($0.items)) })
    }
}
