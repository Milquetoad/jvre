#version 450

// The first triangle, HARDCODED in the vertex shader -- no vertex buffer yet.
// The vertex shader runs once per vertex; vkCmdDraw(.., 3, ..) asks for 3
// vertices, and gl_VertexIndex (0, 1, 2) tells each invocation which one it is,
// so we just index into these arrays. (The real input -- vertex buffers fed
// through pipeline vertex-input state -- is the NEXT step; this isolates the
// pipeline machinery first.)
//
// Coordinates are Vulkan NDC (normalized device coordinates):
//   x: -1 (left) .. +1 (right)
//   y: -1 (TOP)  .. +1 (bottom)   <-- y points DOWN, unlike OpenGL!
//   z:  0 (near) .. 1 (far)
vec2 positions[3] = vec2[](
    vec2( 0.0, -0.5),   // top center
    vec2( 0.5,  0.5),   // bottom right
    vec2(-0.5,  0.5)    // bottom left
);

// One color per corner; the rasterizer INTERPOLATES them across the triangle
// (that is why the middle blends -- nothing in the fragment shader does it).
vec3 colors[3] = vec3[](
    vec3(1.0, 0.0, 0.0),    // red
    vec3(0.0, 1.0, 0.0),    // green
    vec3(0.0, 0.0, 1.0)     // blue
);

// Output to the fragment shader. location = 0 must match the fragment
// shader's input at location 0 -- they are wired BY LOCATION, not by name.
layout(location = 0) out vec3 fragColor;

void main() {
    // gl_Position is the one MANDATORY vertex-shader output: the vertex's
    // clip-space position. w = 1.0 -> no perspective (2D for now).
    gl_Position = vec4(positions[gl_VertexIndex], 0.0, 1.0);
    fragColor = colors[gl_VertexIndex];
}
