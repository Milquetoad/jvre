#version 450

// The FULLSCREEN TRIANGLE -- no vertex buffer, no vertex input state at all.
// Three vertices are derived purely from gl_VertexIndex:
//   index 0 -> (0,0) -> NDC (-1,-1)
//   index 1 -> (2,0) -> NDC ( 3,-1)
//   index 2 -> (0,2) -> NDC (-1, 3)
// One huge triangle whose far corners overshoot the screen; the rasterizer
// clips it to exactly the viewport. ONE triangle instead of a two-triangle
// quad on purpose: no diagonal seam, and no double-shaded pixels along it.
//
// This is jvre's OWN shader, so it compiles at BUILD time (glslc task) like
// the cube's -- only the USER's fragment shader goes through runtime shaderc.
void main() {
    vec2 pos = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    gl_Position = vec4(pos * 2.0 - 1.0, 0.0, 1.0);
}
