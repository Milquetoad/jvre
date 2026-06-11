# Vertex Buffers and GPU Memory

jvre's first GPU memory (2026-06-11, `jvre.core.Buffer`): the triangle's geometry left the shader's hardcoded arrays and became **data** -- 60 bytes of interleaved floats in VRAM, streamed into the vertex shader per the [[Graphics Pipeline]]'s vertex-input state.

## Buffer != memory (the two-object split)

Vulkan splits "a buffer" into two objects you wire together yourself:

1. **`VkBuffer`** -- a typed handle: size + usage flags (`VERTEX_BUFFER`, `TRANSFER_SRC/DST`, ...). Owns **no storage**.
2. **`VkDeviceMemory`** -- a raw allocation from one of the GPU's memory **heaps**, chosen by memory-**type** index.

`vkBindBufferMemory` marries them. The split exists so real engines can allocate a few big memory blocks and sub-allocate many buffers into them -- drivers cap total allocations (`maxMemoryAllocationCount` can be as low as 4096). jvre does one allocation per buffer *while learning*; that job eventually goes to **VMA** (Vulkan Memory Allocator -- LWJGL ships bindings).

## The memory-type hunt

`vkGetPhysicalDeviceMemoryProperties` exposes a table of types; a type fits when **both**:
- the buffer allows it: bit `i` set in `vkGetBufferMemoryRequirements(...).memoryTypeBits`, and
- it has every **property** we need:

| Property | Meaning |
|---|---|
| `HOST_VISIBLE` | the CPU can `vkMapMemory` it |
| `HOST_COHERENT` | CPU writes reach the GPU without manual `vkFlushMappedMemoryRanges` (we always pair it with HOST_VISIBLE: slightly slower traffic, zero forgotten-flush bugs) |
| `DEVICE_LOCAL` | lives in VRAM -- the memory the GPU reads fastest |

On **discrete** GPUs, HOST_VISIBLE and DEVICE_LOCAL are usually **different types** -- the CPU cannot map VRAM. That split is exactly why staging exists. (On iGPUs one type is often both.)

## The staging upload (Buffer.deviceLocal)

```
CPU writes  ->  staging buffer  (HOST_VISIBLE | HOST_COHERENT, TRANSFER_SRC)
GPU copies  ->  result buffer   (DEVICE_LOCAL, TRANSFER_DST | VERTEX_BUFFER)
```
The copy is a GPU command like any other: a throwaway **one-shot command buffer** (`ONE_TIME_SUBMIT`) records `vkCmdCopyBuffer`, gets submitted ([[Synchronization2|vkQueueSubmit2]], no semaphores) and `vkQueueWaitIdle` blocks until it lands (also a full memory barrier, so later vertex reads are safe). Crude but right for startup uploads; mid-game streaming would use a fence and overlap. The staging buffer dies immediately after. Every graphics queue implicitly supports transfer -- no separate transfer queue needed (yet).

## Feeding the pipeline

- **Binding description** = the buffer slot: binding 0, stride 20 bytes (5 floats), `RATE_VERTEX` (advance per vertex; `RATE_INSTANCE` is for instancing).
- **Attribute descriptions** = what each shader `location` reads: location 0 `R32G32_SFLOAT` (vec2 position) at offset 0; location 1 `R32G32B32_SFLOAT` (vec3 color) at offset 8. Image formats double as "data shapes" here -- quirky but consistent.
- Record time: `vkCmdBindVertexBuffers(cmd, 0, buffer, offset)` -> the shader's `layout(location = N) in` variables stream from the buffer.

Interleaved layout (`[x y | r g b]` per vertex) keeps one vertex's data together in memory -- good cache behavior; the alternative (separate position/color buffers) uses multiple bindings.

## Gotcha collection
- `uploadFloats` guards against writing past the buffer's size -- map/copy/unmap has no bounds checking of its own.
- `allocationSize` comes from `vkGetBufferMemoryRequirements`, NOT from your byte count -- the driver may demand more (alignment).
- Cleanup order: destroy the buffer, then free its memory (child before parent, as ever).

## See also
- [[Graphics Pipeline]] -- where the vertex-input state lives.
- [[Shaders - GLSL and SPIR-V]] -- the `location` wiring on the shader side.

#vulkan #memory #buffers #concept
