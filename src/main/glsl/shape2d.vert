#version 450

// The L2 Renderer2D shape vertex shader (jvre's own -> build-time glslc, like the
// cube's). Geometry arrives in PIXELS (top-left origin, y down) -- the L2 user
// never sees NDC. This shader does the one coordinate conversion: pixels -> the
// Vulkan clip cube.
//
// Each vertex is [x y | r g b a | localX localY | halfX halfY | cornerRadius |
// u v | mode]:
//   - position in pixels, color already LINEAR (the Color value type decoded it);
//   - local + half + cornerRadius drive the SDF COVERAGE path (used when mode is
//     the SDF box). An SDF shape passes a local pixel offset within the shape,
//     the box half-extents, and the corner radius; the fragment shader computes
//     the rounded-box signed distance and fades alpha across the edge. A CIRCLE
//     is the special case half = (r, r), cornerRadius = r;
//   - uv is the texture coordinate (image/text shapes);
//   - mode SELECTS the fragment behavior: 0 = flat (full coverage, flat color),
//     1 = SDF rounded box, 2 = textured image, 3 = SDF text. (Replaces the old
//     cornerRadius < 0 sentinel with an explicit selector.)
// One batch, one pipeline, draw order preserved across all kinds: the technique
// is invisible at the L2 call site.

layout(location = 0) in vec2 inPos;          // pixels, top-left origin
layout(location = 1) in vec4 inColor;        // linear RGBA
layout(location = 2) in vec2 inLocal;        // SDF: pixel offset within the shape
layout(location = 3) in vec2 inHalf;         // SDF: box half-extents in px
layout(location = 4) in float inCornerRadius;// SDF: corner radius px
layout(location = 5) in vec2 inUv;           // texture coordinate (image/text)
layout(location = 6) in float inMode;        // 0 flat, 1 SDF box, 2 image, 3 text

layout(location = 0) out vec4 vColor;
layout(location = 1) out vec2 vLocal;
layout(location = 2) out vec2 vHalf;
layout(location = 3) out float vCornerRadius;
layout(location = 4) out vec2 vUv;
layout(location = 5) out float vMode;

layout(push_constant) uniform Push {
    vec2 uResolution;   // framebuffer size in pixels
} pc;

void main() {
    // pixels -> NDC. Vulkan's NDC y points DOWN, which is the SAME direction as
    // top-left pixel coordinates -- so, unlike the 3D path, there is no Y flip.
    // pixel (0,0) -> NDC (-1,-1) = the top-left corner. Correct by construction.
    vec2 ndc = inPos / pc.uResolution * 2.0 - 1.0;
    gl_Position = vec4(ndc, 0.0, 1.0);
    vColor = inColor;
    vLocal = inLocal;
    vHalf = inHalf;
    vCornerRadius = inCornerRadius;
    vUv = inUv;
    vMode = inMode;
}
