# Pipeline Barriers (and Image Layouts)

A **pipeline barrier** (`vkCmdPipelineBarrier`) is a command you record into a [[Command Buffers|command buffer]] that does two bundled jobs: (1) **synchronize** GPU work (order stages, make memory writes visible), and (2) **transition an image's layout**. jvre needs them since switching to [[Dynamic Rendering]] -- the [[Render Pass|render pass]] used to do the layout transitions for us.

## Image layouts -- the core idea

A Vulkan image isn't just pixels; at any moment it's in a **layout** -- a GPU-internal memory arrangement optimized for one use. The *same* image is physically stored differently (tiling, compression) depending on what you're doing with it. You must **transition** between layouts at the right moments. The ones we use:

| Layout | Meaning / use |
|---|---|
| `VK_IMAGE_LAYOUT_UNDEFINED` | "contents don't matter / may be discarded" -- a valid *source* layout any time you're about to overwrite. |
| `..._COLOR_ATTACHMENT_OPTIMAL` | arranged for the GPU to **render into** as a color target. |
| `..._PRESENT_SRC_KHR` | arranged for the **display engine** to scan out (present). |

You can't render into a `PRESENT_SRC` image, nor present a `COLOR_ATTACHMENT` image -- hence the transitions.

## The two halves of a barrier

1. **Execution + memory dependency** -- `srcStageMask`/`dstStageMask` (which pipeline **stages** must finish vs. wait) and `srcAccessMask`/`dstAccessMask` (which memory **accesses** to flush/make-visible). "Before any *dst* work at stage X, finish *src* work at stage Y and publish its writes."
2. **Layout transition** (an **image memory barrier**, `VkImageMemoryBarrier`) -- `oldLayout -> newLayout` for one image + a **subresource range** (which mips/layers/aspect). Barriers act on **images, not [[Image Views|views]]** -- that's why [[Swapchain]] exposes `image(i)` as well as `imageView(i)`.

`srcQueueFamilyIndex = dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED` = no queue-family ownership transfer.

## jvre's two transitions per frame

Recorded around `vkCmdBeginRendering`/`End` in `Main.createCommandBuffers`:

**Barrier 1 -- make it renderable** (`UNDEFINED -> COLOR_ATTACHMENT_OPTIMAL`)
- src stage `COLOR_ATTACHMENT_OUTPUT`, src access `0`; dst stage `COLOR_ATTACHMENT_OUTPUT`, dst access `COLOR_ATTACHMENT_WRITE`.
- Gating at `COLOR_ATTACHMENT_OUTPUT` matches the submit's `pWaitDstStageMask` (where it waits on the `imageAvailable` semaphore), so the transition can't run before the image is actually acquired.

**Barrier 2 -- make it presentable** (`COLOR_ATTACHMENT_OPTIMAL -> PRESENT_SRC_KHR`)
- src stage `COLOR_ATTACHMENT_OUTPUT`, src access `COLOR_ATTACHMENT_WRITE`; dst stage `BOTTOM_OF_PIPE`, dst access `0`.

`UNDEFINED` as `oldLayout` each frame is safe because we pre-record once and re-clear every frame (we never need the old contents). See [[Dynamic Rendering]].

## Gotcha
Barriers are easy to get subtly wrong (wrong stage/access -> validation warnings or real races). The [[Validation Layer and Debug Messenger|validation layer]] is the safety net here: it flags missing/incorrect barriers, which is how you learn them. jvre's two ran validation-clean on the Intel UHD 620.

## Now in the synchronization2 spelling
As of 2026-06-11 jvre records these barriers through **[[Synchronization2]]** (`vkCmdPipelineBarrier2` + `VkImageMemoryBarrier2` via a `VkDependencyInfo` bundle): each barrier carries its **own** stage masks instead of inheriting them from the call, and barrier 2's `dst = BOTTOM_OF_PIPE` became the honest `dstStageMask = NONE`. Everything above -- layouts, the two transitions, the access masks, the gating rationale -- is unchanged; only the API shape moved.
