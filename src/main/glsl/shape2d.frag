#version 450

// The L2 Renderer2D shape fragment shader. Emits the interpolated per-vertex
// color, with an analytic edge-COVERAGE term folded into alpha.
//
// Two kinds of shape share this one shader (so they share one batch and keep
// their draw order):
//   - FLAT (tessellated) shapes -- rects, triangles, the fans, the strokes --
//     pass cornerRadius < 0. coverage = 1: color passes straight through, and
//     MSAA handles their silhouettes (as before).
//   - SDF shapes -- circle, rounded-rect, later text -- pass box half-extents +
//     a corner radius + a per-pixel LOCAL coordinate. coverage = the rounded-box
//     signed distance to the edge, ramped over ~1px: a resolution-independent,
//     analytic soft edge. A circle is the special case half = (r,r), radius = r.

layout(location = 0) in vec4 vColor;
layout(location = 1) in vec2 vLocal;
layout(location = 2) in vec2 vHalf;
layout(location = 3) in float vCornerRadius;

layout(location = 0) out vec4 outColor;

void main() {
    float coverage = 1.0;
    if (vCornerRadius >= 0.0) {
        // Rounded-box signed distance (Inigo Quilez): distance from the pixel to
        // the rounded rectangle's edge, in pixels (negative inside). The inner
        // "core" box is shrunk by the corner radius; max(q,0) is the distance
        // out in each axis, and the corner radius is subtracted back to round it.
        vec2 q = abs(vLocal) - vHalf + vCornerRadius;
        float d = min(max(q.x, q.y), 0.0) + length(max(q, vec2(0.0))) - vCornerRadius;
        // ~1px ramp centered on the edge: d=-0.5 -> inside (1), d=+0.5 -> out (0).
        coverage = clamp(0.5 - d, 0.0, 1.0);
    }
    outColor = vec4(vColor.rgb, vColor.a * coverage);
}
