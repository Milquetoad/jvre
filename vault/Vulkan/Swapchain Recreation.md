# Swapchain Recreation (resize, minimize, out-of-date)

A [[Swapchain]] is built against a snapshot of the surface (extent, transform...). When the window resizes, that snapshot goes stale and the swapchain must be **rebuilt**. jvre added this on 2026-06-11 (`Renderer.recreateSwapchain`), making the window resizable for the first time.

## The three triggers (and why all three)

1. **`VK_ERROR_OUT_OF_DATE_KHR`** from acquire or present: the swapchain can no longer present to the surface *at all*. At acquire -> rebuild and **skip the frame**; at present -> the frame already went out, rebuild after.
2. **`VK_SUBOPTIMAL_KHR`**: still works, just mismatched (e.g. wrong extent). Treated as success at acquire (render the frame), rebuild after present.
3. **The window's own resize flag** (GLFW framebuffer-size callback -> a boolean the loop consumes): some drivers keep presenting happily while the window stretches and never report 1 or 2 -- without the flag we'd render at the old size forever.

The callback only **sets a flag** for the render loop to consume at a safe point -- GLFW may fire it many times mid-drag, and mid-callback is no place to rebuild a swapchain. After a rebuild the stale flag is dropped, so one drag = one rebuild (the driver's OUT_OF_DATE backstops any resize that sneaks in during the rebuild).

## What rebuilds, what survives

Wait for the GPU (`vkDeviceWaitIdle` -- brute-force-but-correct; the no-stall `oldSwapchain` handoff remains a documented seam), then:

- **Rebuilt:** the swapchain + image views (renegotiated from the live surface), and the per-IMAGE `renderFinished` semaphores (the image *count* can change).
- **Survives:** Instance, Surface, Device, the command pool, the per-frame sync objects -- and since [[Frames in Flight|per-frame recording]], the command buffers too (they re-record against the new images automatically; before that, the pre-recorded ones baked in the old extent/views and had to be re-recorded here).

## Two classic gotchas (both hit-proofed in `drawFrame`)

- **The fence-reset deadlock:** reset the fence *before* acquire, then early-return on OUT_OF_DATE -> the fence is left unsignaled, no submit ever signals it, the next frame waits forever. Fix: **wait** the fence at the top, but **reset it only once a submit is guaranteed** (after a successful acquire).
- **Minimize = 0x0 framebuffer**, and a 0x0 swapchain is illegal. Recreation parks in `glfwWaitEvents` until the size is real again -- the app literally sleeps while minimized, which is also the polite thing to do.

## DPI lesson from verification
Asking Windows for a 1200x900 *window* produced a **2672x1946** swapchain -- screen *coordinates* vs framebuffer *pixels* under display scaling. Exactly why `chooseExtent` reads `glfwGetFramebufferSize` (pixels), never the window size.

#vulkan #swapchain #resize #concept
