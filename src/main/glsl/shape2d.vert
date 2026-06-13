#version 450

// The L2 Renderer2D shape vertex shader (jvre's own -> build-time glslc, like the
// cube's). Geometry arrives in PIXELS (top-left origin, y down) -- the L2 user
// never sees NDC. This shader does the one coordinate conversion: pixels -> the
// Vulkan clip cube.
//
// Each vertex is [x y | r g b a | localX localY | sdfRadius]:
//   - position in pixels, color already LINEAR (the Color value type decoded it);
//   - local + sdfRadius drive the SDF COVERAGE path. A tessellated (flat) shape
//     passes sdfRadius < 0, meaning "not an SDF shape -> full coverage". An SDF
//     shape (e.g. the circle) passes a local pixel offset within the shape and
//     the shape's radius, and the fragment shader fades alpha across the edge.
//     One batch, one pipeline, draw order preserved -- the SDF technique is
//     invisible at the L2 call site.

layout(location = 0) in vec2 inPos;        // pixels, top-left origin
layout(location = 1) in vec4 inColor;      // linear RGBA
layout(location = 2) in vec2 inLocal;      // SDF: pixel offset within the shape
layout(location = 3) in float inSdfRadius; // SDF: shape radius in px; < 0 = flat shape

layout(location = 0) out vec4 vColor;
layout(location = 1) out vec2 vLocal;
layout(location = 2) out float vSdfRadius;

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
    vSdfRadius = inSdfRadius;
}
