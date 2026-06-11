#version 450

// Vertex INPUTS -- fed per vertex from the bound vertex buffer, sliced up
// according to the pipeline's binding/attribute descriptions (see Pipeline):
// location 0 reads 2 floats (the position), location 1 reads 3 floats (the
// color), both from the same interleaved buffer. The hardcoded arrays from the
// first triangle are gone -- geometry is DATA now, owned by the application.
layout(location = 0) in vec2 inPosition;
layout(location = 1) in vec3 inColor;

// Output to the fragment shader. Wired BY LOCATION, not by name; the
// rasterizer interpolates it across the triangle.
layout(location = 0) out vec3 fragColor;

void main() {
    // Coordinates arrive already in Vulkan NDC (x right, y DOWN, z in [0,1]);
    // w = 1.0 -> no perspective. A projection matrix will land here later.
    gl_Position = vec4(inPosition, 0.0, 1.0);
    fragColor = inColor;
}
