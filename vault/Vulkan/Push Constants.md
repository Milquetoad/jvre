# Push Constants

The cheapest way to get data into a shader, and jvre's first **per-frame** shader input (2026-06-11): a tiny blob written **straight into the command buffer** by `vkCmdPushConstants`. No `VkBuffer`, no descriptors, no synchronization -- the values ride along with the commands themselves. With them, the triangle spins. 🌀

![[spinning-triangle.png]]

## The deal

| | |
|---|---|
| **Size** | tiny -- the spec guarantees only **128 bytes** (`maxPushConstantsSize`); jvre uses 8 |
| **Cost** | nearly free -- no memory allocation, no binding, no hazard to synchronize |
| **Freshness** | per *recording* -- every `vkCmdPushConstants` call snapshots new values into the command stream |
| **Use for** | a time value, a transform matrix, an object index -- small, hot, changes constantly |
| **Not for** | anything big or shared across many draws -- that's uniform buffers + descriptor sets (next) |

## The three places that must agree

1. **The shader** declares one `push_constant` block per pipeline:
   ```glsl
   layout(push_constant) uniform PushConstants {
       float time;    // offset 0
       float aspect;  // offset 4
   } pc;
   ```
2. **The pipeline layout** declares the range: `VkPushConstantRange { stageFlags = VERTEX, offset = 0, size = 8 }` -- the [[Graphics Pipeline|pipeline layout]]'s first real content (it was empty until now).
3. **Record time**: `vkCmdPushConstants(cmd, pipeline.layout(), VK_SHADER_STAGE_VERTEX_BIT, 0, floats(time, aspect))` -- the Java side must write the SAME byte layout the block expects (std430-style packing; two floats = trivially 0 and 4).

Mismatch any pair and the validation layer (or worse, garbage values) will let you know.

## Why this needed [[Frames in Flight|per-frame recording]]

A pushed constant is baked into the recorded commands. The old pre-record-once model would have frozen `time` forever at recording time -- re-recording every frame is exactly what makes "fresh values each frame" possible. This milestone is that architecture visibly paying off.

## What jvre pushes

`[ time, aspect ]`: seconds since renderer start (the spin -- 1 rad/s, clockwise on screen since Vulkan NDC is y-down and GLSL `mat2` is column-major), and framebuffer width/height (dividing x by it keeps the triangle shape-true in any window -- NDC always spans -1..+1 regardless of window shape; a projection matrix will absorb this job later).

## See also
- [[Graphics Pipeline]] -- where the range is declared.
- [[Vertex Buffers and GPU Memory]] -- per-VERTEX data; push constants are per-RECORDING data.
- Next up: uniform buffers + descriptor sets -- the "bigger, shared, still per-frame" tier above push constants.

#vulkan #shaders #concept
