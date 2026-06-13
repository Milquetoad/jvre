#version 450

// jvre ShaderEffect demo -- compiled at RUNTIME by jvre itself (shaderc), not
// by the build. This file is a plain text resource: it deliberately lives
// OUTSIDE src/main/glsl so the Gradle glslc task never touches it. Editing it
// and re-running requires no Vulkan SDK and no build-time shader step -- that
// is the whole point of the ShaderEffect altitude.
//
// The contract every effect shader follows (v1):
//   - declare this push-constant block; jvre fills it automatically each frame
//   - gl_FragCoord.xy = pixel coordinates, TOP-LEFT origin (Vulkan's y-down --
//     conveniently the same direction uMouse uses)
//   - write the final color to outColor

layout(location = 0) out vec4 outColor;

layout(push_constant) uniform Push {
    vec2 uResolution;   // framebuffer size in pixels
    vec2 uMouse;        // cursor position in pixels, top-left origin
    float uTime;        // seconds since the renderer started
} pc;

void main() {
    // Centered, aspect-correct coordinates: y spans [-1,1], x scaled to match.
    vec2 uv = (gl_FragCoord.xy * 2.0 - pc.uResolution) / pc.uResolution.y;
    vec2 mouse = (pc.uMouse * 2.0 - pc.uResolution) / pc.uResolution.y;

    // Two ripple sources: one anchored at the center, one following the mouse.
    // Their waves interfere -- move the cursor and watch the pattern shift.
    float d1 = length(uv);
    float d2 = length(uv - mouse);
    float wave = sin(d1 * 24.0 - pc.uTime * 3.0) * 0.5
               + sin(d2 * 24.0 - pc.uTime * 4.0) * 0.5;

    // Map the interference into a deep-water / crest palette.
    vec3 deep  = vec3(0.02, 0.09, 0.20);
    vec3 crest = vec3(0.35, 0.85, 1.00);
    vec3 color = mix(deep, crest, wave * 0.5 + 0.5);

    // Soft vignette so the edges breathe.
    color *= 1.0 - 0.35 * smoothstep(0.8, 1.6, d1);

    outColor = vec4(color, 1.0);
}
