#version 450

// An effect that SAMPLES an input channel -- the iChannel0..3 convention from
// Shadertoy, now supported by jvre. The renderer feeds iChannel0 a texture via
// renderer.setEffectChannel(0, tex); here we display it with a time-driven ripple
// (UV warp) and a mouse-driven tint, so it's obviously live AND obviously sampling
// the bound image. Demonstrates the multi-channel binding (this one uses channel 0).

layout(location = 0) out vec4 outColor;

// iChannel0 = the texture bound to effect channel 0. set 0, binding 0 is the
// jvre effect-channel convention (iChannelN -> binding N).
layout(set = 0, binding = 0) uniform sampler2D iChannel0;

layout(push_constant) uniform Push {
    vec2 uResolution;   // framebuffer size in px
    vec2 uMouse;        // cursor px, top-left origin
    float uTime;        // seconds
} pc;

void main() {
    // gl_FragCoord is top-left/y-down, and texture row 0 is the image's top, so
    // uv maps straight through -- no flip (an earlier 1.0 - uv.y was upside down).
    vec2 uv = gl_FragCoord.xy / pc.uResolution;

    // A gentle ripple: warp the sample coordinates by a time-animated sine.
    float wave = 0.012 * sin(uv.y * 28.0 + pc.uTime * 2.5);
    vec3 rgb = texture(iChannel0, uv + vec2(wave, 0.0)).rgb;

    // Mouse X scrubs a warm tint across the image, so it's clearly interactive.
    float tint = clamp(pc.uMouse.x / max(pc.uResolution.x, 1.0), 0.0, 1.0);
    rgb = mix(rgb, rgb * vec3(1.2, 0.9, 0.7), tint);

    outColor = vec4(rgb, 1.0);
}
