import AppKit
import Metal
import MetalKit
import SyncrCore

struct Viewport: Equatable, Sendable {
    var left: Float = 0
    var top: Float = 0
    var zoom: Float = 1
    func visibleSize(_ pixels: CGSize) -> SIMD2<Float> {
        let canvas = canvasSize()
        let fit = min(Float(pixels.width) / canvas.width, Float(pixels.height) / canvas.viewHeight)
        return SIMD2(Float(pixels.width), Float(pixels.height)) / max(0.001, fit * zoom)
    }
    /// Convert preference-normalized NSEvent deltas (logical points) into canvas units.
    /// Pass NSView.bounds.size, never MTKView.drawableSize: backing scale must not
    /// change the distance content travels under a scroll gesture.
    func scrollOffset(x: Float, y: Float, viewSize: CGSize) -> SIMD2<Float> {
        let canvas = canvasSize()
        let fit = min(Float(viewSize.width) / canvas.width, Float(viewSize.height) / canvas.viewHeight)
        return SIMD2(x, y) / max(0.001, fit * zoom)
    }
    /// Keep the canvas point under a pinch stationary as the visible area changes.
    func zoomed(to newZoom: Float, around fraction: SIMD2<Float>, pixels: CGSize, limits: ClosedRange<Float> = 1...8) -> Viewport {
        let before = visibleSize(pixels)
        var result = self
        result.zoom = min(limits.upperBound, max(limits.lowerBound, newZoom))
        let after = result.visibleSize(pixels)
        result.left += fraction.x * (before.x - after.x)
        result.top += fraction.y * (before.y - after.y)
        return result
    }
}

struct MeshUniforms {
    var origin: SIMD2<Float>
    var viewport: SIMD2<Float>
    var size: SIMD2<Float>
    var colour: SIMD4<Float>
}

private let shader = """
#include <metal_stdlib>
using namespace metal;
struct Uniforms { float2 origin; float2 viewport; float2 size; float4 colour; };
struct VertexOut { float4 position [[position]]; float4 colour; };
vertex VertexOut stroke_vertex(const device float2 *positions [[buffer(0)]],
                               const device uint *indices [[buffer(1)]],
                               constant Uniforms &u [[buffer(2)]], uint id [[vertex_id]]) {
    float2 point = positions[indices[id]] + u.origin - u.viewport;
    VertexOut out;
    out.position = float4(point.x / u.size.x * 2.0 - 1.0,
                          1.0 - point.y / u.size.y * 2.0, 0.0, 1.0);
    out.colour = u.colour;
    return out;
}
vertex VertexOut reset_vertex(uint id [[vertex_id]]) {
    const float2 positions[3] = { float2(-1,-1), float2(3,-1), float2(-1,3) };
    VertexOut out; out.position = float4(positions[id],0,1); out.colour = float4(0);
    return out;
}
fragment float4 stroke_fragment(VertexOut v [[stage_in]]) { return v.colour; }
fragment float4 dry_fragment(VertexOut v [[stage_in]], texture2d<float, access::read> cache [[texture(0)]]) {
    return cache.read(uint2(v.position.xy));
}
"""

/// Owned by the UI thread. Buffers are immutable after upload and command buffers retain
/// their resources until GPU completion. Only the offscreen checks ever synchronously wait.
final class MeshRenderer {
    let device: MTLDevice
    private let queue: MTLCommandQueue
    private let ordinary: MTLRenderPipelineState
    private let translucent: MTLRenderPipelineState
    private let reset: MTLRenderPipelineState
    private let dryCopy: MTLRenderPipelineState
    private let mask: MTLDepthStencilState
    private let unmasked: MTLDepthStencilState
    private let resetMask: MTLDepthStencilState
    private var colourTarget: MTLTexture?
    private var stencilTarget: MTLTexture?
    /// The attachments of the other size in use (the window and the pad's picture alternate), so neither is rebuilt.
    private var spareTargets: (colour: MTLTexture, stencil: MTLTexture)?
    private var drawingID: UUID?
    private struct Upload { let stroke: DrawStroke; let vertices: MTLBuffer; let indices: MTLBuffer }
    private var uploads: [Upload] = []
    private(set) var bufferBuildCount = 0
    private(set) var attachmentBuildCount = 0
    private(set) var lastPassCount = 0
    private(set) var lastStencilResetCount = 0

    /// The committed ink, rendered once and resolved, so a wet frame only has to copy it and draw the live strokes
    /// (`render(... cacheDry:)`). `SYNCR_MAC_DRY_CACHE=0` turns it off and every frame draws as it always did.
    private struct DryKey: Equatable { var drawing: UUID?; var strokes: Int; var width: Int; var height: Int; var viewport: Viewport; var dark: Bool }
    let dryCacheEnabled: Bool
    private var dryTexture: MTLTexture?
    private var dryKey: DryKey?
    private(set) var dryCacheBuilds = 0
    private(set) var dryCacheHits = 0
    /// Per frame, off the main thread at GPU completion: CPU encode time, GPU time (both ms), whether the frame rebuilt the cache.
    var frameTimes: (@Sendable (Double, Double, Bool) -> Void)?

    init(device: MTLDevice, dryCache: Bool? = nil) throws {
        self.device = device
        dryCacheEnabled = dryCache ?? (ProcessInfo.processInfo.environment["SYNCR_MAC_DRY_CACHE"] != "0")
        guard device.supportsTextureSampleCount(4), let queue = device.makeCommandQueue() else {
            throw RendererError.unavailable
        }
        self.queue = queue
        let source = try device.makeLibrary(source: shader, options: nil)
        func pipeline(_ highlighter: Bool, resetting: Bool = false, copying: Bool = false) throws -> MTLRenderPipelineState {
            let descriptor = MTLRenderPipelineDescriptor()
            descriptor.vertexFunction = source.makeFunction(name: resetting || copying ? "reset_vertex" : "stroke_vertex")
            descriptor.fragmentFunction = source.makeFunction(name: copying ? "dry_fragment" : "stroke_fragment")
            descriptor.rasterSampleCount = 4
            descriptor.colorAttachments[0].pixelFormat = .bgra8Unorm
            descriptor.stencilAttachmentPixelFormat = .stencil8
            let attachment = descriptor.colorAttachments[0]!
            if resetting { attachment.writeMask = [] }
            attachment.isBlendingEnabled = highlighter
            attachment.sourceRGBBlendFactor = .sourceAlpha
            attachment.destinationRGBBlendFactor = .oneMinusSourceAlpha
            attachment.sourceAlphaBlendFactor = .one
            attachment.destinationAlphaBlendFactor = .oneMinusSourceAlpha
            return try device.makeRenderPipelineState(descriptor: descriptor)
        }
        ordinary = try pipeline(false)
        translucent = try pipeline(true)
        reset = try pipeline(false, resetting: true)
        dryCopy = try pipeline(false, copying: true)
        func state(_ comparison: MTLCompareFunction, _ operation: MTLStencilOperation) throws -> MTLDepthStencilState {
            let descriptor = MTLDepthStencilDescriptor()
            let face = MTLStencilDescriptor()
            face.stencilCompareFunction = comparison
            face.stencilFailureOperation = .keep
            face.depthFailureOperation = .keep
            face.depthStencilPassOperation = operation
            descriptor.frontFaceStencil = face; descriptor.backFaceStencil = face
            guard let state = device.makeDepthStencilState(descriptor: descriptor) else { throw RendererError.unavailable }
            return state
        }
        mask = try state(.notEqual, .replace)
        unmasked = try state(.always, .keep)
        resetMask = try state(.always, .replace)
    }

    private func prepare(_ drawing: Drawing?, width: Int, height: Int) throws {
        if colourTarget?.width != width || colourTarget?.height != height, let spare = spareTargets,
           spare.colour.width == width, spare.colour.height == height {
            spareTargets = colourTarget.flatMap { c in stencilTarget.map { (c, $0) } }
            colourTarget = spare.colour; stencilTarget = spare.stencil
        }
        if colourTarget?.width != width || colourTarget?.height != height {
            spareTargets = colourTarget.flatMap { c in stencilTarget.map { (c, $0) } }
            func texture(_ format: MTLPixelFormat) throws -> MTLTexture {
                let desc = MTLTextureDescriptor()
                desc.textureType = .type2DMultisample
                desc.pixelFormat = format; desc.width = width; desc.height = height
                desc.sampleCount = 4; desc.storageMode = .private; desc.usage = .renderTarget
                guard let texture = device.makeTexture(descriptor: desc) else { throw RendererError.unavailable }
                return texture
            }
            colourTarget = try texture(.bgra8Unorm)
            stencilTarget = try texture(.stencil8)
            attachmentBuildCount += 1
        }
        if drawingID != drawing?.id {
            uploads.removeAll(); drawingID = drawing?.id
        }
        // Progressive loading only appends strokes. A new note/reload has a new Drawing ID.
        for stroke in (drawing?.strokes ?? []).dropFirst(uploads.count) {
            let mesh = stroke.mesh
            guard !mesh.vertices.isEmpty, !mesh.indices.isEmpty else { throw RendererError.unavailable }
            let vertices = mesh.vertices.withUnsafeBytes { device.makeBuffer(bytes: $0.baseAddress!, length: $0.count) }
            let indices = mesh.indices.withUnsafeBytes { device.makeBuffer(bytes: $0.baseAddress!, length: $0.count) }
            guard let vertices, let indices else { throw RendererError.unavailable }
            uploads.append(Upload(stroke: stroke, vertices: vertices, indices: indices))
            bufferBuildCount += 2
        }
    }

    /// A single 4x MSAA pass for either output. Pass a drawable to present without blocking;
    /// without one, wait for offscreen pixel inspection. No per-stroke render passes.
    /// `wet` is the live-ink layer: drawn after the committed strokes in this same pass, continuing
    /// the same stencil references, so a live highlighter stroke is one coverage like a committed one.
    /// `stage` gets (name, monotonic ns) as the frame goes through: "encoded", "scheduled", "gpu done",
    /// "presented" (the drawable's presented time; "presented (handler time)" when the system reports none;
    /// offscreen, GPU completion).  `afterScheduled`: present only once the GPU work is scheduled, for
    /// layers with `presentsWithTransaction`. `cacheDry`: the view is still, so the committed ink may come from the
    /// dry cache (two passes in one command buffer: rebuild if stale, then cache + wet); leave it false while the view moves.
    func render(_ drawing: Drawing?, wet: WetFrame? = nil, viewport: Viewport, dark: Bool, target: MTLTexture,
                presenting drawable: MTLDrawable? = nil,
                completion: (@Sendable (String?) -> Void)? = nil,
                stage: (@Sendable (String, UInt64) -> Void)? = nil,
                afterScheduled: Bool = false, cacheDry: Bool = false) throws {
        let started = monotonicNs()
        try prepare(drawing, width: target.width, height: target.height)
        guard let buffer = queue.makeCommandBuffer() else { throw RendererError.unavailable }
        let visible = viewport.visibleSize(CGSize(width: target.width, height: target.height))
        let paper = NotePalette.rgba(ThemeTokens.page(dark))
        func passDescriptor(resolving resolve: MTLTexture, clearing: Bool) -> MTLRenderPassDescriptor {
            let pass = MTLRenderPassDescriptor()
            pass.colorAttachments[0].texture = colourTarget
            pass.colorAttachments[0].resolveTexture = resolve
            // A pass that starts from the cached ink covers every pixel with it, so its colour needs no clear.
            pass.colorAttachments[0].loadAction = clearing ? .clear : .dontCare
            pass.colorAttachments[0].storeAction = .multisampleResolve
            pass.colorAttachments[0].clearColor = MTLClearColor(red: Double(paper.x), green: Double(paper.y), blue: Double(paper.z), alpha: 1)
            pass.stencilAttachment.texture = stencilTarget
            pass.stencilAttachment.loadAction = .clear
            pass.stencilAttachment.storeAction = .dontCare
            pass.stencilAttachment.clearStencil = 0
            return pass
        }
        func visibleBounds(_ minX: Float, _ minY: Float, _ maxX: Float, _ maxY: Float) -> Bool {
            !(maxX < viewport.left || minX > viewport.left + visible.x || maxY < viewport.top || minY > viewport.top + visible.y)
        }
        /// Encodes one pass: the committed strokes, the wet layer, or both. Stencil references start at zero in each pass.
        func encode(_ encoder: MTLRenderCommandEncoder, committed: Bool, wetLayer: Bool) {
            var reference: UInt32 = 0
            /// Binds pipeline and stencil state for one stroke. Highlighters take the next stencil reference.
            func bind(_ highlighter: Bool) {
                if highlighter {
                    if reference == 255 {
                        // Hardware stencil clear is a load action. On wrap, emulate it with
                        // a colour-masked fullscreen triangle *inside this same pass*.
                        encoder.setRenderPipelineState(reset)
                        encoder.setDepthStencilState(resetMask)
                        encoder.setStencilReferenceValue(0)
                        encoder.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 3)
                        reference = 0; lastStencilResetCount += 1
                    }
                    reference += 1
                    encoder.setDepthStencilState(mask)
                    encoder.setStencilReferenceValue(reference)
                } else { encoder.setDepthStencilState(unmasked) }
                encoder.setRenderPipelineState(highlighter ? translucent : ordinary)
            }
            func draw(_ vertices: MTLBuffer, _ indices: MTLBuffer, count: Int, origin: SIMD2<Float>, colour: SIMD4<Float>) {
                var uniforms = MeshUniforms(origin: origin, viewport: SIMD2(viewport.left, viewport.top), size: visible, colour: colour)
                encoder.setVertexBuffer(vertices, offset: 0, index: 0)
                encoder.setVertexBuffer(indices, offset: 0, index: 1)
                encoder.setVertexBytes(&uniforms, length: MemoryLayout<MeshUniforms>.stride, index: 2)
                encoder.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: count)
            }
            if committed {
                for upload in uploads {
                    let stroke = upload.stroke, mesh = stroke.mesh
                    guard visibleBounds(mesh.minX + stroke.originX, mesh.minY + stroke.originY,
                                        mesh.maxX + stroke.originX, mesh.maxY + stroke.originY) else { continue }
                    bind(stroke.highlighter)
                    draw(upload.vertices, upload.indices, count: mesh.indices.count / 4, origin: SIMD2(stroke.originX, stroke.originY),
                         colour: MeshRenderer.colour(stroke.colour, custom: stroke.custom, dark: dark, highlighter: stroke.highlighter))
                }
            }
            guard wetLayer else { return }
            // Wet layer. Sheet origin is zero: only the first sheet of a Mac note takes live ink.
            for stroke in wet?.strokes ?? [] {
                var bound = false
                for piece in stroke.pieces where visibleBounds(piece.minX, piece.minY, piece.maxX, piece.maxY) {
                    if !bound { bind(stroke.highlighter); bound = true }
                    draw(piece.vertices, piece.indices, count: piece.indexCount, origin: .zero,
                         colour: MeshRenderer.colour(stroke.colour, custom: stroke.custom, dark: dark, highlighter: stroke.highlighter))
                }
            }
            if let hover = wet?.hover {
                // A fixed on-screen size whatever the zoom: 5 px radius.
                let radius = 5 * visible.x / Float(target.width)
                var points: [SIMD2<Float>] = [SIMD2(hover.x, hover.y)]
                for i in 0..<16 { let a = Float(i) * .pi / 8; points.append(SIMD2(hover.x + radius * cos(a), hover.y + radius * sin(a))) }
                var triangles: [UInt32] = []
                for i in 0..<16 { triangles += [0, UInt32(1 + i), UInt32(1 + (i + 1) % 16)] }
                if let v = device.makeBuffer(bytes: points, length: points.count * MemoryLayout<SIMD2<Float>>.stride),
                   let i = device.makeBuffer(bytes: triangles, length: triangles.count * 4) {
                    bind(false)
                    draw(v, i, count: triangles.count, origin: .zero, colour: MeshRenderer.colour(.ink, dark: dark, highlighter: false))
                }
            }
        }
        lastPassCount = 1; lastStencilResetCount = 0
        var rebuilt = false
        if cacheDry && dryCacheEnabled {
            // Still view: the committed ink comes from the cache, rebuilt (in this same command buffer) only when the
            // drawing, viewport, size or theme changed. The wet pass starts by copying the cache into its 4x target.
            let key = DryKey(drawing: drawingID, strokes: uploads.count, width: target.width, height: target.height, viewport: viewport, dark: dark)
            if dryKey != key || dryTexture == nil {
                if dryTexture?.width != target.width || dryTexture?.height != target.height {
                    let desc = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: .bgra8Unorm, width: target.width, height: target.height, mipmapped: false)
                    desc.usage = [.renderTarget, .shaderRead]; desc.storageMode = .private
                    guard let texture = device.makeTexture(descriptor: desc) else { throw RendererError.unavailable }
                    dryTexture = texture
                }
                guard let cache = dryTexture,
                      let build = buffer.makeRenderCommandEncoder(descriptor: passDescriptor(resolving: cache, clearing: true)) else { throw RendererError.unavailable }
                encode(build, committed: true, wetLayer: false)
                build.endEncoding()
                dryKey = key; dryCacheBuilds += 1; rebuilt = true
            } else { dryCacheHits += 1 }
            guard let cache = dryTexture,
                  let encoder = buffer.makeRenderCommandEncoder(descriptor: passDescriptor(resolving: target, clearing: false)) else { throw RendererError.unavailable }
            encoder.setRenderPipelineState(dryCopy)
            encoder.setDepthStencilState(unmasked)
            encoder.setFragmentTexture(cache, index: 0)
            encoder.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 3)
            encode(encoder, committed: false, wetLayer: true)
            encoder.endEncoding()
            lastPassCount = 2
        } else {
            dryKey = nil  // the view is moving (or the cache is off): the cache is stale from here until a still frame rebuilds it
            guard let encoder = buffer.makeRenderCommandEncoder(descriptor: passDescriptor(resolving: target, clearing: true)) else { throw RendererError.unavailable }
            encode(encoder, committed: true, wetLayer: true)
            encoder.endEncoding()
        }
        let encodeMs = Double(monotonicNs() - started) / 1e6
        if let frameTimes {
            buffer.addCompletedHandler { b in frameTimes(encodeMs, (b.gpuEndTime - b.gpuStartTime) * 1000, rebuilt) }
        }
        stage?("encoded", monotonicNs())
        if let stage {
            buffer.addScheduledHandler { _ in stage("scheduled", monotonicNs()) }
            buffer.addCompletedHandler { _ in stage("gpu done", monotonicNs()) }
        }
        if let drawable {
            if let stage {
                drawable.addPresentedHandler { d in
                    if d.presentedTime > 0 { stage("presented", UInt64(d.presentedTime * 1e9)) }
                    else { stage("presented (handler time)", monotonicNs()) }
                }
            }
            if let completion { buffer.addCompletedHandler { completion($0.error?.localizedDescription) } }
            if afterScheduled {
                buffer.commit(); buffer.waitUntilScheduled(); drawable.present()
            } else {
                buffer.present(drawable); buffer.commit()
            }
        } else {
            buffer.commit(); buffer.waitUntilCompleted()
            if buffer.status == .error { throw buffer.error ?? RendererError.unavailable }
            stage?("presented", monotonicNs())
        }
    }
}

enum RendererError: Error { case unavailable }

extension MeshRenderer {
    /// Pen colours on the Mac's page. Plain ink is the window's Ink token so handwriting matches typed text
    /// at full strength; the other pen colours are the shared ones.
    static func colour(_ colour: InkColour, custom: Oklch? = nil, dark: Bool, highlighter: Bool) -> SIMD4<Float> {
        if let custom {
            // The writer's own ink, gamut-mapped by the core and drawn as chosen in both themes; near-black and
            // near-white greys take the window's ink so writing stays readable.
            let resolved = resolveCustomInk(ink: custom)
            let rgb = resolved.themeInk ? ThemeTokens.ink(dark) : resolved.argb
            return NotePalette.rgba(highlighter ? (rgb & 0x00ffffff) | (NotePalette.highlighterAlpha << 24) : rgb)
        }
        guard colour == .ink else { return NotePalette.colour(colour, dark: dark, highlighter: highlighter) }
        let rgb = ThemeTokens.ink(dark)
        return NotePalette.rgba(highlighter ? (rgb & 0x00ffffff) | (NotePalette.highlighterAlpha << 24) : rgb)
    }
}
