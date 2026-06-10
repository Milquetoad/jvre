# Render Pass

> [!note] Superseded in jvre by [[Dynamic Rendering]]
> jvre no longer uses render passes -- as of 2026-06-10 it renders via
> [[Dynamic Rendering]] (core in Vulkan 1.3), which drops `VkRenderPass`,
> `VkFramebuffer`, and subpasses. This note is kept because the *concepts*
> (attachments, load/store ops, image layouts) carry straight over, and render
> passes are still valid Vulkan -- they're just not our path. The layout
> transitions this object did automatically are now explicit [[Pipeline Barriers]].

A **render pass** (`VkRenderPass`) is the **blueprint of a rendering operation** — *not* the rendering itself and *not* the images. It declares which **attachments** (images) are involved, what to do with each at the **start/end**, and which **subpasses** (phases) use them. Framebuffers bind the real [[Image Views|image views]] to it later; the graphics pipeline is built *against* it.

For "clear to color" no shaders/pipeline are needed — **the render pass does the clearing**, via `loadOp = CLEAR`.

## The three pieces
1. **Attachment description** (`VkAttachmentDescription`) — one color attachment matching the [[Swapchain|swapchain]] format:
   - `samples = 1` (no MSAA).
   - **`loadOp = CLEAR`** — fill with a constant at the pass start. **This is the line that clears the screen.**
   - `storeOp = STORE` — keep the result (so it can be presented).
   - stencil load/store = `DONT_CARE` (no stencil).
   - `initialLayout = UNDEFINED` (don't care about prior contents -- we're clearing) -> `finalLayout = PRESENT_SRC_KHR` (leave it ready to hand to the swapchain).
2. **Subpass** (`VkSubpassDescription`) — one phase, bound to graphics, writing the attachment as its color target. The attachment **reference** gives the index (`0`) + the layout to use *during* the subpass (`COLOR_ATTACHMENT_OPTIMAL`). That index `0` is exactly what a fragment shader later writes as `layout(location = 0)`.
3. **Subpass dependency** (`VkSubpassDependency`) — synchronization from the implicit pre-subpass (`VK_SUBPASS_EXTERNAL`) to subpass 0 on the `COLOR_ATTACHMENT_OUTPUT` stage. Inert right now; it's what makes the **render loop** correct (so we don't write color before the swapchain image is available). Added here so the render pass is complete, not revisited.

## Layouts, briefly
Images live in **layouts** optimized for a use (color-attachment, present-src, transfer...). The render pass moves the attachment `UNDEFINED -> (COLOR_ATTACHMENT_OPTIMAL during the subpass) -> PRESENT_SRC_KHR`, automatically. We'll meet layouts again with textures/transitions.

## Honest expectation
Creating the render pass still shows **no color** — it only *defines* the clear. The [[Roadmap - Clear to Color|command buffer]] records it and the render loop runs it. Steps left to pixels: **framebuffers -> command pool/buffers -> synchronization -> render loop**.

First run on craptop: `Render pass created`, clean. Next: **framebuffers** — bind each image view to this render pass (one framebuffer per swapchain image).

#vulkan #render-pass #clear
