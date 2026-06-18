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
//   - mode 4 ELLIPSE FILL -- an approximate ellipse signed distance (half = the
//     radii), AA'd like mode 1. Crisp at any size, MSAA-independent.
//   - mode 5 ELLIPSE RING (stroke) -- abs(ellipse distance) - halfThickness (the
//     half-thickness rides in cornerRadius); an annulus around the centerline
//     ellipse. Circles are the rx==ry case (exact).

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

// --- MSDF text (mode 6) helpers ---------------------------------------------
// The MSDF distance range in atlas pixels -- MUST match Font.PX_RANGE (the bake
// constant). If they disagree, the anti-aliasing band mis-scales.
const float MSDF_PX_RANGE = 4.0;

// The classic MSDF reconstruction: the true signed distance is the MEDIAN of the
// three channels (each channel tracks a different subset of edges; the median
// recovers the actual nearest edge -- including at sharp corners single-channel
// SDF rounds off).
float median(float r, float g, float b) {
    return max(min(r, g), min(max(r, g), b));
}

// The distance range expressed in SCREEN pixels at this fragment: the bake range
// (atlas px) scaled by how many screen px each atlas texel currently covers
// (1/fwidth(uv) in texels). Clamped to >= 1 so the edge never goes sub-pixel-hard.
float screenPxRange(vec2 uv) {
    vec2 unitRange = vec2(MSDF_PX_RANGE) / vec2(textureSize(uTex, 0));
    vec2 screenTexSize = vec2(1.0) / fwidth(uv);
    return max(0.5 * dot(unitRange, screenTexSize), 1.0);
}

// Approximate signed distance to an ellipse with radii ab (Inigo Quilez's
// gradient-corrected form). EXACT for circles (ab.x == ab.y); near the edge it's
// accurate enough for a ~1px AA ramp. Negative inside.
float ellipseSDF(vec2 p, vec2 ab) {
    ab = max(ab, vec2(1e-4));
    float k0 = length(p / ab);
    float k1 = length(p / (ab * ab));
    if (k1 < 1e-8) return -min(ab.x, ab.y);   // at the center: deep inside
    return k0 * (k0 - 1.0) / k1;
}

// Coverage from a signed distance (px, negative inside): a ~1 SCREEN-pixel ramp
// using the field's screen-space gradient, so the edge stays crisp under any
// transform/zoom. Shared by every SDF mode.
float coverageFromSdf(float d) {
    float w = max(length(vec2(dFdx(d), dFdy(d))), 1e-6);
    return clamp(0.5 - d / w, 0.0, 1.0);
}

void main() {
    if (vMode > 1.5 && vMode < 2.5) {
        // mode 2: textured image. The texture is sRGB, so the sample is already
        // linearized; multiply by the (linear) tint. vColor = white passes the
        // texture through unchanged.
        outColor = texture(uTex, vUv) * vColor;
        return;
    }
    if (vMode > 2.5 && vMode < 3.5) {
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
    if (vMode > 5.5) {
        // mode 6: MSDF text. The atlas holds three distance fields (RGB, UNORM,
        // linear). median() reconstructs the true signed distance -- preserving
        // sharp corners single-channel SDF rounds. screenPxRange() converts the
        // bake range to screen px so the 1px AA band tracks the on-screen size.
        vec3 msd = texture(uTex, vUv).rgb;
        float sd = median(msd.r, msd.g, msd.b);
        float screenPxDistance = screenPxRange(vUv) * (sd - 0.5);
        float coverage = clamp(screenPxDistance + 0.5, 0.0, 1.0);
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
        coverage = coverageFromSdf(d);
    } else if (vMode > 3.5 && vMode < 4.5) {
        // mode 4: ellipse FILL -- half = the radii, vLocal = pixel offset from centre.
        coverage = coverageFromSdf(ellipseSDF(vLocal, vHalf));
    } else if (vMode > 4.5) {
        // mode 5: ellipse RING (stroke) -- annulus around the centerline ellipse;
        // vCornerRadius carries the half-thickness.
        float d = abs(ellipseSDF(vLocal, vHalf)) - vCornerRadius;
        coverage = coverageFromSdf(d);
    }
    outColor = vec4(vColor.rgb, vColor.a * coverage);
}
