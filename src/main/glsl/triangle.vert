#version 450

// Vertex INPUTS -- fed per vertex from the bound vertex buffer, sliced up
// according to the pipeline's binding/attribute descriptions (see Pipeline):
// location 0 reads 2 floats (the position), location 1 reads 3 floats (the
// color), both from the same interleaved buffer. The hardcoded arrays from the
// first triangle are gone -- geometry is DATA now, owned by the application.
layout(location = 0) in vec2 inPosition;
layout(location = 1) in vec3 inColor;

// PUSH CONSTANTS -- tiny per-recording data (here: 8 bytes) pushed straight
// into the command buffer by vkCmdPushConstants. No buffer, no descriptor, no
// sync; the cheapest way to get fresh values to a shader every frame. One
// push_constant block per pipeline; members are laid out std430-style (two
// packed floats here), and the Java side must write the SAME layout.
layout(push_constant) uniform PushConstants {
    float time;    // seconds since the renderer started
    float aspect;  // framebuffer width / height
} pc;

// Output to the fragment shader. Wired BY LOCATION, not by name; the
// rasterizer interpolates it across the triangle.
layout(location = 0) out vec3 fragColor;

void main() {
    // Spin at 1 radian/second: a 2D rotation matrix from the pushed time.
    // GLSL mat2 is COLUMN-major: columns (c, s) and (-s, c). With Vulkan's
    // y-DOWN NDC this reads as a clockwise spin on screen.
    float c = cos(pc.time);
    float s = sin(pc.time);
    vec2 rotated = mat2(c, s, -s, c) * inPosition;

    // NDC always spans -1..+1 regardless of window shape, so a non-square
    // window would stretch the spin into a wobble. Dividing x by the aspect
    // ratio makes the triangle shape-true at any window size.
    rotated.x /= pc.aspect;

    // w = 1.0 -> no perspective. A projection matrix will land here later
    // (and will absorb the aspect correction when it does).
    gl_Position = vec4(rotated, 0.0, 1.0);
    fragColor = inColor;
}
