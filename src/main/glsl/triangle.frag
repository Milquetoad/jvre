#version 450

// The fragment shader runs once per covered PIXEL (fragment), after the
// rasterizer has carved the triangle into fragments. Its job: decide the color.

// Interpolated from the vertex stage (matched by location, not name).
layout(location = 0) in vec3 fragColor;
layout(location = 1) in vec2 fragUV;

// COMBINED_IMAGE_SAMPLER at binding 1 -- the image VIEW + SAMPLER wired into the
// descriptor set (Renderer). `sampler2D` is GLSL's name for exactly that pairing;
// binding = 1 must match the descriptor set layout (the shader<->layout<->set
// contract, same as the UBO's binding 0 in the vertex stage).
layout(binding = 1) uniform sampler2D tex;

// PUSH CONSTANT, now consumed by the FRAGMENT stage (the range's stageFlags in
// the pipeline layout must say so). Push constants and uniform buffers
// coexist: push = tiny + hot (4 bytes of time), UBO = bigger + structured
// (the transform matrix in the vertex stage).
layout(push_constant) uniform PushConstants {
    float time;   // seconds since the renderer started
} pc;

// location = 0 is COLOR ATTACHMENT 0 -- the swapchain image view.
layout(location = 0) out vec4 outColor;

void main() {
    // Sample the texture at this fragment's interpolated UV. The sampler's
    // NEAREST filter keeps the checker edges crisp (no bilinear smear).
    vec4 texColor = texture(tex, fragUV);

    // Keep the push-constant tier visibly alive: a gentle brightness pulse.
    float pulse = 0.85 + 0.15 * sin(pc.time * 3.0);

    // The grayscale checker texture is tinted by the per-face vertex color, so
    // each cube face reads as its own color. Times the brightness pulse.
    outColor = vec4(texColor.rgb * fragColor * pulse, texColor.a);
}
