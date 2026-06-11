#version 450

// The fragment shader runs once per covered PIXEL (fragment), after the
// rasterizer has carved the triangle into fragments. Its job: decide the color.

// Interpolated from the three vertices' fragColor outputs -- the rasterizer
// blends them by how close this fragment is to each corner (barycentric
// interpolation). Matched to the vertex shader by location, not name.
layout(location = 0) in vec3 fragColor;

// Where the color goes: location = 0 is COLOR ATTACHMENT 0 -- the same slot
// recordCommandBuffer points at the swapchain image view. (The swapchain
// format is sRGB, so this linear value gets sRGB-encoded on write.)
layout(location = 0) out vec4 outColor;

void main() {
    outColor = vec4(fragColor, 1.0);
}
