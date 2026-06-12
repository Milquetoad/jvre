#version 450

// The fragment shader runs once per covered PIXEL (fragment), after the
// rasterizer has carved the triangle into fragments. Its job: decide the color.

// Interpolated from the vertex stage (matched by location, not name).
layout(location = 0) in vec3 fragColor;

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
    // A gentle brightness pulse so the push-constant path stays visibly alive.
    float pulse = 0.85 + 0.15 * sin(pc.time * 3.0);
    outColor = vec4(fragColor * pulse, 1.0);
}
