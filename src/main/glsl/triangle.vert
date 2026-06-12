#version 450

// Vertex INPUTS -- fed per vertex from the bound vertex buffer, sliced up
// according to the pipeline's binding/attribute descriptions (see Pipeline).
layout(location = 0) in vec2 inPosition;
layout(location = 1) in vec3 inColor;
layout(location = 2) in vec2 inUV;     // texture coordinate (matches Pipeline attribute 2)

// UNIFORM BUFFER -- the data tier above push constants: a real buffer, BOUND
// through a descriptor set rather than pushed into the command stream.
// binding = 0 must match the descriptor set layout's binding number (the
// three-way contract again: shader <-> layout <-> the set that gets bound).
// The block follows std140 layout rules; a single mat4 (4 vec4 columns,
// 64 bytes) is the safe starter -- mixed scalars/vec3s have padding traps.
layout(binding = 0) uniform TransformUbo {
    mat4 transform;   // built CPU-side each frame: aspect * orbit * spin
} ubo;

// Outputs to the fragment shader (interpolated across the triangle).
layout(location = 0) out vec3 fragColor;
layout(location = 1) out vec2 fragUV;

void main() {
    // All motion now lives in the CPU-built matrix -- the shader just applies
    // it. This is the shape real renderers have: transforms are DATA.
    gl_Position = ubo.transform * vec4(inPosition, 0.0, 1.0);
    fragColor = inColor;
    fragUV = inUV;   // hardware interpolates this per fragment
}
