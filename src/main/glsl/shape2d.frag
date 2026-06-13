#version 450

// The L2 Renderer2D shape fragment shader. Emits the interpolated per-vertex
// color, with an analytic edge-COVERAGE term folded into alpha.
//
// Two kinds of shape share this one shader (so they share one batch and keep
// their draw order):
//   - FLAT (tessellated) shapes -- rects, triangles, the fans, the strokes --
//     pass sdfRadius < 0. coverage = 1: the color passes straight through, and
//     MSAA handles their silhouettes (as before).
//   - SDF shapes -- the circle, later rounded-rects/text -- pass a radius >= 0
//     and a per-pixel LOCAL coordinate. coverage = the signed distance to the
//     edge, ramped over ~1px: a resolution-independent, analytic soft edge.

layout(location = 0) in vec4 vColor;
layout(location = 1) in vec2 vLocal;
layout(location = 2) in float vSdfRadius;

layout(location = 0) out vec4 outColor;

void main() {
    float coverage = 1.0;
    if (vSdfRadius >= 0.0) {
        // Signed distance from this pixel to the rim, in pixels (negative inside).
        float d = length(vLocal) - vSdfRadius;
        // A ~1px ramp centered on the edge: d=-0.5 -> fully inside (1),
        // d=+0.5 -> fully outside (0), d=0 -> half coverage.
        coverage = clamp(0.5 - d, 0.0, 1.0);
    }
    outColor = vec4(vColor.rgb, vColor.a * coverage);
}
