#version 450

// The L2 Renderer2D shape fragment shader. v1 is the simplest possible: emit the
// interpolated per-vertex color, straight. (Alpha blending happens in the fixed-
// function blend stage, configured on the pipeline.) The planned SDF edge-AA for
// curves/strokes will add work HERE later -- smoothstepping alpha over ~1px of
// signed distance -- which is why color is the only output now.

layout(location = 0) in vec4 vColor;

layout(location = 0) out vec4 outColor;

void main() {
    outColor = vColor;
}
