# Framebuffers

A **framebuffer** (`VkFramebuffer`) binds **concrete [[Image Views|image views]] into a [[Render Pass|render pass]]'s attachment slots**, at a specific size. The render pass is the *blueprint* ("slot 0 = a color attachment of this format"); the framebuffer is the *filled-in* version ("slot 0 = *this actual image view*, 800x600").

## One per swapchain image
Each [[Swapchain|swapchain]] image is a separate render target, so we make **one framebuffer per swapchain image view**. They all share the same render pass and size; they differ only in which view they wrap. (When the render loop renders frame N, it picks the framebuffer for the image it acquired.)

## Create-info (`VkFramebufferCreateInfo`) — tiny
- **renderPass** — the pass these attachments must be *compatible* with (same attachment count/formats).
- **pAttachments** — the view(s) filling the slots, in order. Here one: the color view. The order/index must match the render pass's attachment indices.
- **width / height** — the [[Swapchain|swapchain]] extent.
- **layers** — 1 (more for layered/stereoscopic rendering).

## Cleanup order
Framebuffers reference both the image views and the render pass, so destroy **all framebuffers first**, then the render pass, then the image views, then the swapchain. (On window resize, framebuffers + swapchain + views are exactly the set that gets recreated — see the device-context seam in [[Device Selection and Cross-Platform (planned)]].)

First run on craptop: `Created 3 framebuffers` (matching the 3 images/views), clean. Next: the **command pool + command buffers** — record "begin render pass (clear), end" into a buffer the GPU can execute.

#vulkan #framebuffers #clear
