import AppKit
import Metal
import MetalKit
import SyncrCore

struct Viewport {
    var left: Float = 0
    var top: Float = 0
    var zoom: Float = 1

    func visibleSize(_ pixels: CGSize) -> SIMD2<Float> {
        let canvas = canvasSize()
        let fit = min(Float(pixels.width) / canvas.width, Float(pixels.height) / canvas.viewHeight)
        return SIMD2(Float(pixels.width), Float(pixels.height)) / max(0.001, fit * zoom)
    }

    mutating func clamp(pixels: CGSize, bottom: Float) {
        let size = visibleSize(pixels)
        left = min(max(0, left), max(0, canvasSize().width - size.x))
        top = min(max(0, top), max(0, bottom))
    }
}

struct MeshUniforms {
    var origin: SIMD2<Float>
    var viewport: SIMD2<Float>
    var size: SIMD2<Float>
    var colour: SIMD4<Float>
}

/// Source-compiled shaders work with Command Line Tools (no metal command or Xcode needed).
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
fragment float4 stroke_fragment(VertexOut v [[stage_in]]) { return v.colour; }
"""

final class MeshRenderer {
    let device: MTLDevice
    private let queue: MTLCommandQueue
    private let ordinary: MTLRenderPipelineState
    private let translucent: MTLRenderPipelineState
    private let stencil: MTLDepthStencilState

    init(device: MTLDevice) throws {
        self.device = device
        guard let queue = device.makeCommandQueue() else { throw RendererError.unavailable }
        self.queue = queue
        let source = try device.makeLibrary(source: shader, options: nil)
        func pipeline(_ highlighter: Bool) throws -> MTLRenderPipelineState {
            let descriptor = MTLRenderPipelineDescriptor()
            descriptor.vertexFunction = source.makeFunction(name: "stroke_vertex")
            descriptor.fragmentFunction = source.makeFunction(name: "stroke_fragment")
            descriptor.colorAttachments[0].pixelFormat = .bgra8Unorm
            descriptor.stencilAttachmentPixelFormat = .stencil8
            let attachment = descriptor.colorAttachments[0]!
            attachment.isBlendingEnabled = highlighter
            attachment.rgbBlendOperation = .add
            attachment.alphaBlendOperation = .add
            attachment.sourceRGBBlendFactor = .sourceAlpha
            attachment.destinationRGBBlendFactor = .oneMinusSourceAlpha
            attachment.sourceAlphaBlendFactor = .one
            attachment.destinationAlphaBlendFactor = .oneMinusSourceAlpha
            return try device.makeRenderPipelineState(descriptor: descriptor)
        }
        ordinary = try pipeline(false)
        translucent = try pipeline(true)
        let depth = MTLDepthStencilDescriptor()
        let face = MTLStencilDescriptor()
        face.stencilCompareFunction = .equal
        face.stencilFailureOperation = .keep
        face.depthFailureOperation = .keep
        face.depthStencilPassOperation = .incrementClamp
        depth.frontFaceStencil = face
        depth.backFaceStencil = face
        stencil = device.makeDepthStencilState(descriptor: depth)!
    }

    /// Same function drives MTKView and an offscreen texture in the tests.
    func render(_ drawing: Drawing?, viewport: Viewport, dark: Bool, target: MTLTexture) throws {
        guard let buffer = queue.makeCommandBuffer() else { throw RendererError.unavailable }
        let pixelSize = CGSize(width: target.width, height: target.height)
        let visible = viewport.visibleSize(pixelSize)
        let stencilDesc = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: .stencil8,
                                          width: target.width, height: target.height, mipmapped: false)
        stencilDesc.usage = .renderTarget
        stencilDesc.storageMode = .private
        guard let stencilTexture = device.makeTexture(descriptor: stencilDesc) else { throw RendererError.unavailable }
        let clear = MTLRenderPassDescriptor()
        clear.colorAttachments[0].texture = target
        clear.colorAttachments[0].loadAction = .clear
        clear.colorAttachments[0].storeAction = .store
        clear.colorAttachments[0].clearColor = dark
            ? MTLClearColor(red: 0.10, green: 0.11, blue: 0.13, alpha: 1)
            : MTLClearColor(red: 0.98, green: 0.97, blue: 0.94, alpha: 1)
        guard let first = buffer.makeRenderCommandEncoder(descriptor: clear) else { throw RendererError.unavailable }
        first.endEncoding()
        for stroke in drawing?.strokes ?? [] {
            let mesh = stroke.mesh
            let count = mesh.indices.count / 4
            guard count > 0, mesh.vertices.count >= 8 else { continue }
            let minX = mesh.minX + stroke.originX, maxX = mesh.maxX + stroke.originX
            let minY = mesh.minY + stroke.originY, maxY = mesh.maxY + stroke.originY
            if maxX < viewport.left || minX > viewport.left + visible.x ||
                maxY < viewport.top || minY > viewport.top + visible.y { continue }
            guard let vertices = device.makeBuffer(bytes: [UInt8](mesh.vertices), length: mesh.vertices.count),
                  let indices = device.makeBuffer(bytes: [UInt8](mesh.indices), length: mesh.indices.count) else {
                throw RendererError.unavailable
            }
            let pass = MTLRenderPassDescriptor()
            pass.colorAttachments[0].texture = target
            pass.colorAttachments[0].loadAction = .load
            pass.colorAttachments[0].storeAction = .store
            pass.stencilAttachment.texture = stencilTexture
            pass.stencilAttachment.loadAction = .clear // one stencil mask per translucent stroke
            pass.stencilAttachment.storeAction = .dontCare
            pass.stencilAttachment.clearStencil = 0
            guard let encoder = buffer.makeRenderCommandEncoder(descriptor: pass) else { throw RendererError.unavailable }
            encoder.setRenderPipelineState(stroke.highlighter ? translucent : ordinary)
            if stroke.highlighter { encoder.setDepthStencilState(stencil); encoder.setStencilReferenceValue(0) }
            var uniforms = MeshUniforms(origin: SIMD2(stroke.originX, stroke.originY),
                        viewport: SIMD2(viewport.left, viewport.top), size: visible,
                        colour: Self.colour(stroke.colour, dark: dark, highlighter: stroke.highlighter))
            encoder.setVertexBuffer(vertices, offset: 0, index: 0)
            encoder.setVertexBuffer(indices, offset: 0, index: 1)
            encoder.setVertexBytes(&uniforms, length: MemoryLayout<MeshUniforms>.stride, index: 2)
            encoder.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: count)
            encoder.endEncoding()
        }
        buffer.commit()
        buffer.waitUntilCompleted()
        if buffer.status == .error { throw buffer.error ?? RendererError.unavailable }
    }

    private static func colour(_ value: InkColour, dark: Bool, highlighter: Bool) -> SIMD4<Float> {
        let alpha: Float = highlighter ? 0.35 : 1
        switch value {
        case .ink: return SIMD4(dark ? 0.93 : 0.12, dark ? 0.93 : 0.14, dark ? 0.91 : 0.17, alpha)
        case .red: return SIMD4(0.82, 0.19, 0.22, alpha)
        case .green: return SIMD4(0.12, 0.62, 0.31, alpha)
        case .blue: return SIMD4(0.13, 0.35, 0.83, alpha)
        case .orange: return SIMD4(0.92, 0.48, 0.10, alpha)
        }
    }
}

enum RendererError: Error { case unavailable }
