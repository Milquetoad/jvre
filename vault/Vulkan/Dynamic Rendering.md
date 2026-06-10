# Dynamic Rendering

**Dynamic rendering** (`VK_KHR_dynamic_rendering`, **core since Vulkan 1.3**, 2022) is the modern way to render in Vulkan without a [[Render Pass|render pass]] or [[Framebuffers|framebuffer]] object. jvre switched to it on 2026-06-10. It's the path most 2024+ tutorials teach first.

## Why it exists (the honest version)

The original [[Render Pass|render pass]] + **subpass** model was designed largely for **tiled mobile GPUs**: subpasses let those GPUs keep an image in fast on-chip *tile memory* across several steps without round-tripping to main memory. On a tiled GPU that can be a real win.

On **desktop / immediate-mode GPUs** (RTX 4090, Intel UHD 620, ...) subpasses bought almost nothing but cost:
- a lot of **verbosity** (attachment descriptions, subpass descriptions, dependencies, framebuffers), and
- a **tight coupling**: a graphics pipeline had to be created against a *specific* `VkRenderPass`, so pipelines and passes were awkward to mix.

So 1.3 added dynamic rendering as a simpler default. Render passes are **not deprecated or removed** -- still valid, still useful on mobile -- they're just no longer the recommended starting point on desktop. See [[Progress Log]] 2026-06-10.

## What you give up / take on

The render pass used to do **image layout transitions automatically** via `initialLayout`/`finalLayout` + a subpass dependency. Dynamic rendering does **not**, so *you* issue them by hand with [[Pipeline Barriers]]. Net trade: two whole objects (`VkRenderPass`, `VkFramebuffer`) disappear, in exchange for ~15 lines of explicit barriers you write once. That's a *good* trade for learning -- barriers are the heart of Vulkan sync, and the render pass had been hiding them.

## How jvre uses it

1. **Enable it.** Request **Vulkan 1.3** at instance creation (`appInfo.apiVersion`), and enable the **`dynamicRendering`** device feature -- chained into `VkDeviceCreateInfo.pNext` via `VkPhysicalDeviceDynamicRenderingFeatures` (post-1.0 features are opted into through `pNext`, not `pEnabledFeatures`). A portable build would first verify support with `vkGetPhysicalDeviceFeatures2`.
2. **Record each command buffer** (`Main.createCommandBuffers`), per [[Swapchain]] image:
   - [[Pipeline Barriers|barrier]]: `UNDEFINED -> COLOR_ATTACHMENT_OPTIMAL` (now renderable).
   - `VkRenderingAttachmentInfo`: point `.imageView()` at this image's [[Image Views|view]], `loadOp = CLEAR` (the orange), `storeOp = STORE`, `imageLayout = COLOR_ATTACHMENT_OPTIMAL`.
   - `VkRenderingInfo`: render area + `layerCount = 1` + that one color attachment.
   - `vkCmdBeginRendering` -> (nothing to draw for clear-to-color) -> `vkCmdEndRendering`.
   - [[Pipeline Barriers|barrier]]: `COLOR_ATTACHMENT_OPTIMAL -> PRESENT_SRC_KHR` (now presentable).

Because we **pre-record once and replay**, `oldLayout = UNDEFINED` on the first barrier is exactly right every frame: it means "discard prior contents", and we re-clear anyway.

## What stayed the same
The [[Swapchain]] (and its [[Image Views]]), [[Command Buffers]], and the [[Synchronization and the Render Loop|sync + render loop]] are unchanged -- dynamic rendering only replaced the render-pass/framebuffer middle. `drawFrame` still does wait-fence -> acquire -> submit -> present.

## See also
- [[Pipeline Barriers]] -- the layout transitions we now own.
- [[Render Pass]] / [[Framebuffers]] -- what this replaced (kept for concepts).
