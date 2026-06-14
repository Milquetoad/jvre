#version 450

// The L2 Renderer2D shape fragment shader. Emits the interpolated per-vertex
// color, with an analytic edge-COVERAGE term folded into alpha.
//
// Several kinds of shape share this one shader (so they share one batch and keep
// their draw order). A per-vertex MODE selector picks the behavior:
//   - mode 0 FLAT (tessellated) shapes -- rects, triangles, the fans, the
//     strokes -- coverage = 1: color passes straight through, and MSAA handles
//     their silhouettes.
//   - mode 1 SDF shapes -- circle, rounded-rect -- pass box half-extents + a
//     corner radius + a per-pixel LOCAL coordinate. coverage = the rounded-box
//     signed distance to the edge, ramped over ~1px: a resolution-independent,
//     analytic soft edge. A circle is the special case half = (r,r), radius = r.
//   - mode 2 textured IMAGE -- sample the bound texture at vUv and TINT by
//     vColor (white = pass through). Coverage is the texture's own alpha.
//   - mode 3 SDF TEXT -- sample the bound glyph atlas (R8, the distance in .r) at
//     vUv and threshold it with a smoothstep whose width tracks the on-screen
//     scale (fwidth), for a crisp anti-aliased edge at any text size. Colored by
//     vColor.

layout(location = 0) in vec4 vColor;
layout(location = 1) in vec2 vLocal;
layout(location = 2) in vec2 vHalf;
layout(location = 3) in float vCornerRadius;
layout(location = 4) in vec2 vUv;
layout(location = 5) in float vMode;

// The one bound resource: the texture image (mode 2) / glyph atlas (mode 3)
// sample through. Flat/SDF shapes never read it, but it is always validly bound
// (the Renderer binds a 1x1 white default when no image is drawn).
layout(set = 0, binding = 0) uniform sampler2D uTex;

layout(location = 0) out vec4 outColor;

void main() {
    if (vMode > 1.5 && vMode < 2.5) {
        // mode 2: textured image. The texture is sRGB, so the sample is already
        // linearized; multiply by the (linear) tint. vColor = white passes the
        // texture through unchanged.
        outColor = texture(uTex, vUv) * vColor;
        return;
    }
    if (vMode > 2.5) {
        // mode 3: SDF text. The atlas stores signed distance in .r (UNORM, so the
        // glyph edge baked at value 128 reads as ~0.5; inside is larger). fwidth
        // gives the per-pixel change in distance -> a smoothstep band ~1px wide on
        // screen, so the edge stays crisp whether the text is tiny or huge.
        float dist = texture(uTex, vUv).r;
        float aa = fwidth(dist);
        float coverage = smoothstep(0.5 - aa, 0.5 + aa, dist);
        outColor = vec4(vColor.rgb, vColor.a * coverage);
        return;
    }
    float coverage = 1.0;
    if (vMode > 0.5 && vMode < 1.5) {
        // mode 1: rounded-box signed distance (Inigo Quilez): distance from the
        // pixel to the rounded rectangle's edge, in pixels (negative inside). The
        // inner "core" box is shrunk by the corner radius; max(q,0) is the
        // distance out in each axis, and the corner radius is subtracted back to
        // round it.
        vec2 q = abs(vLocal) - vHalf + vCornerRadius;
        float d = min(max(q.x, q.y), 0.0) + length(max(q, vec2(0.0))) - vCornerRadius;
        // Anti-alias over ~1 SCREEN pixel using the distance field's screen-space
        // gradient: w = |grad d| is ~1 at identity (a true distance field) and
        // scales with any transform, so a rotated/scaled SDF shape keeps a clean
        // ~1px edge. (At identity this reduces to the old clamp(0.5 - d).)
        float w = max(length(vec2(dFdx(d), dFdy(d))), 1e-6);
        coverage = clamp(0.5 - d / w, 0.0, 1.0);
    }
    outColor = vec4(vColor.rgb, vColor.a * coverage);
}
