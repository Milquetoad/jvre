# Graphics Pipeline

The **graphics pipeline** (`VkPipeline`) is THE defining Vulkan object: the [[Shaders - GLSL and SPIR-V|shaders]] plus nearly every piece of fixed-function state -- primitive topology, rasterizer mode, blending, multisampling -- **compiled together into one immutable object, up front**. jvre built its first on 2026-06-11 (`jvre.core.Pipeline`), and with it drew the first triangle. 🔺

## The big bake (why Vulkan does it this way)

OpenGL was a **global state machine**: flip blending on here, change the cull mode there, and the driver must re-validate (sometimes re-compile shaders!) at draw time, unpredictably. Vulkan inverts this: declare *everything* at pipeline **creation**, let the driver optimize once, and at draw time a single `vkCmdBindPipeline` swaps the whole configuration atomically. Predictable cost, paid up front. The flip side: *different state = a different pipeline object* (real engines hash and cache thousands).

**Dynamic state** is the escape hatch -- state listed in `VkPipelineDynamicStateCreateInfo` is excluded from the bake and set per command buffer. jvre makes **viewport + scissor** dynamic, so [[Swapchain Recreation]] never has to rebuild the pipeline (it only bakes the attachment *format*, which is checked on recreation).

## Anatomy (what the create-info bundles)

| Piece | jvre's first triangle |
|---|---|
| **Shader stages** | vertex + fragment modules, entry point `"main"` |
| **Vertex input** | EMPTY -- positions hardcoded in the shader; *the vertex-buffer seam* |
| **Input assembly** | `TRIANGLE_LIST` (every 3 vertices = one triangle) |
| **Viewport state** | counts only (1 + 1) -- values are dynamic |
| **Rasterizer** | `FILL`, **cull `NONE`** (a winding mistake with culling on = silently empty screen, the least debuggable bug in graphics; enable `BACK` once things visibly work) |
| **Multisample** | 1 sample (MSAA later) |
| **Color blend** | no blend, write RGBA |
| **Dynamic state** | `VIEWPORT`, `SCISSOR` |
| **Pipeline layout** | EMPTY -- *the descriptor/push-constant seam* (the shader's external interface; mandatory even when empty) |
| **pNext** | `VkPipelineRenderingCreateInfo` -- see below |

## The [[Dynamic Rendering]] hookup

Classic Vulkan baked a `VkRenderPass` reference into every pipeline -- the coupling dynamic rendering exists to remove. What remains: chain a `VkPipelineRenderingCreateInfo` into `pNext` declaring the **attachment formats** the pipeline will render into (`renderPass = VK_NULL_HANDLE`). Must match what `vkCmdBeginRendering` is given at record time.

## Shader modules are temporary

`vkCreateShaderModule` just wraps the SPIR-V bytes; the *compile-for-this-GPU* happens inside `vkCreateGraphicsPipelines`. Once the pipeline exists the modules are dead weight -- jvre destroys them at the end of the `Pipeline` constructor.

## The draw (in `Renderer.recordCommandBuffer`)

```java
vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.handle());
vkCmdSetViewport(cmd, 0, viewport);   // dynamic: NDC -> pixel mapping
vkCmdSetScissor(cmd, 0, scissor);     // dynamic: hard pixel clip
vkCmdDraw(cmd, 3, 1, 0, 0);           // 3 vertices, 1 instance
```
With no vertex buffer bound, the vertex shader fabricates the corners from `gl_VertexIndex`. The clear (`loadOp = CLEAR`) has already painted the background by the time the triangle lands on it.

## Tier note
Unlike the infrastructure elementaries (Instance, Device, ...), `Pipeline` is **creative-tier** -- per [[API Vision - Layered Altitudes]], Shader/Pipeline/Buffer are the objects an L1 user will actually touch.

## See also
- [[Shaders - GLSL and SPIR-V]] -- what gets baked in, and how it's compiled.
- [[Dynamic Rendering]] / [[Swapchain Recreation]] -- why the pipeline survives a resize.

#vulkan #pipeline #shaders #concept #milestone
