# Command Pool & Command Buffers

Core Vulkan idea: you don't fire commands at the GPU one at a time. You **record** a batch of commands into a **command buffer**, then **submit** the whole buffer to a [[Physical Device and Queue Families|queue]]. **Recording and executing are separate** — recording just writes the instruction list; the GPU runs it later on submit. This batching (record once, submit many; record across threads) is a big reason Vulkan is fast.

## Command pool (`VkCommandPool`)
The allocator command buffers come from, **bound to one queue family** (graphics here). Buffers from a pool can only be submitted to queues of that family. We pass no flags: each buffer is recorded **once** and never reset. (A loop that re-records every frame would set `RESET_COMMAND_BUFFER_BIT`.) Destroying the pool frees all its buffers — we never free them individually.

## Command buffers (`VkCommandBuffer`)
We allocate **one PRIMARY buffer per [[Framebuffers|framebuffer]]** (primary = submittable directly; secondary buffers are called *from* primaries). Then pre-record the clear into each:

```
vkBeginCommandBuffer
  vkCmdBeginRenderPass(framebuffer i, clearValue = ORANGE, SUBPASS_CONTENTS_INLINE)
  // nothing here -- loadOp=CLEAR does the work; no geometry/pipeline for a clear
  vkCmdEndRenderPass
vkEndCommandBuffer
```

- **The clear color** is a `VkClearValue` (one per attachment) passed to `vkCmdBeginRenderPass`. Ours is **bright orange** `(1.0, 0.4, 0.0, 1.0)`. Because the swapchain is an **sRGB** format, these linear values are sRGB-encoded on write, so on screen it reads as a vivid orange (brighter than the raw numbers suggest).
- **renderArea** = the whole image (offset 0, extent = swapchain size).
- **`SUBPASS_CONTENTS_INLINE`** = the commands are right here in this primary buffer (vs. deferred to secondary buffers).
- We point `renderPassInfo.framebuffer` at framebuffer `i` and record the same clear into buffer `i` — so each swapchain image has its own ready-to-go clear.

## Honest expectation
After this step the window is **still blank** — the buffers are recorded but never **submitted**. Orange appears only once the [[Roadmap - Clear to Color|render loop]] submits them. That's the next and final step: **synchronization + the render loop** (acquire image -> submit its command buffer -> present), where the subpass dependency in the [[Render Pass|render pass]] finally matters.

First run on craptop: `Command pool created` + `Recorded 3 command buffers (clear to orange)`, clean.

#vulkan #command-buffers #clear
