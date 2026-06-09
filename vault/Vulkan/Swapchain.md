# Swapchain

The **swapchain** (`VkSwapchainKHR`) is the **queue of images that get presented to the [[Windowing - GLFW and the Surface|surface]]** — the machinery behind double/triple buffering. You never draw straight to the screen: you render into an off-screen image while a *different*, finished image is on display, then **present** yours and **acquire** the next free one. That swap is the name.

Built from the [[Logical Device and Queues|logical device]] + the surface. Vulkan automates none of it — you negotiate it across several "**query what's supported, then pick**" decisions.

## The three choices
1. **Surface format** (`VkSurfaceFormatKHR`) — pixel layout + color space. Prefer `VK_FORMAT_B8G8R8A8_SRGB` (= enum `50`) with `SRGB_NONLINEAR` color space (correct-looking colors); else take the first offered.
2. **Present mode** — *how* images are shown:
   - `FIFO` — strict vsync queue; **always supported** (our guaranteed fallback).
   - `MAILBOX` — "triple buffering": newest frame replaces the queued one; low latency, no tearing — but not always available. Prefer it, fall back to FIFO.
   - (On craptop's Intel iGPU only FIFO is exposed; on a discrete GPU MAILBOX usually is.)
3. **Extent** (`VkExtent2D`) — image resolution, in **pixels**. If the surface pins `currentExtent`, use it; if it's the sentinel `0xFFFFFFFF`, we choose — taking the window's **framebuffer** size (pixels, not screen coords; they differ on high-DPI) clamped to the surface's min/max.

## Other create-info fields worth knowing
- **Image count** = `minImageCount + 1` (a spare so we don't stall waiting on the driver), clamped to `maxImageCount` (where `0` means "no max").
- **imageUsage** = `COLOR_ATTACHMENT` — we render directly into these images.
- **Sharing mode** — if graphics and present are **different** families, both queues touch the images: `CONCURRENT` shares them without explicit ownership transfers (simpler, slightly slower). Same family -> `EXCLUSIVE` (faster, nothing to share). craptop's shared-family iGPU takes the EXCLUSIVE path.
- **preTransform** = `currentTransform` (no rotation/flip), **compositeAlpha** = `OPAQUE` (ignore window alpha), **clipped** = true (skip obscured pixels), **oldSwapchain** = null (nothing to recycle — that field is the seam for resize recreation later).

## After creation
Retrieve the image handles with the two-call idiom (the driver may make **more** images than requested). The images are **owned by the swapchain** — never destroyed individually; `vkDestroySwapchainKHR` frees them. We stash the **format** and **extent** as fields because [[Roadmap - Clear to Color|every later step]] (image views, render pass, framebuffers, viewport) needs them.

## Future: surface format / color space as an L1 config (decided 2026-06-09)
`chooseSwapSurfaceFormat` is deliberately a **single choke point** — the seam for making the surface format / color space configurable later, exactly like `rateDevice` is the seam for [[Device Selection and Cross-Platform (planned)|GPU selection]]. Same **"default policy + explicit override"** pattern: ship a sensible default (sRGB BGRA), let an L1 caller override it. Notes for when we expose it:
- **Coupled to format.** Color space is one half of a `VkSurfaceFormatKHR` pair; you can only pick *supported* pairs from the enumerated list. The knob is a *preference with fallback*, not a free choice.
- **L1, not L2.** It belongs on the `Swapchain` elementary as an advanced option; the high-level "just draw" layer hides it.
- **The real payoff is HDR** (HDR10 / BT.2020) — but that's not a lone switch: the format must match *and* the pipeline needs tone mapping, or colors go wrong. Expose with that caveat.
Takeaway: change nothing now — keeping the choice in one method already buys the future extensibility for free. See [[API Vision - Layered Altitudes]].

## Cleanup order
Destroy the swapchain **before** the device (it's created from the device), which is before the instance-level objects. Child before parent.

First run on craptop: `3 images, 800x600, format 50, present mode FIFO` — clean. Next: **image views** (how to interpret each of these images), then the render pass that clears them.

#vulkan #swapchain #presentation
