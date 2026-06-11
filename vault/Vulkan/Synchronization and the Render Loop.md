# Synchronization and the Render Loop

The setup steps ran once; the **render loop** runs every frame. Vulkan's defining trait dominates here: the **CPU, the GPU's rendering, and the GPU's presentation all run asynchronously**, and *you* order them by hand. This is the step that finally puts pixels on screen.

## Two kinds of sync primitive
- **Semaphore** — orders **GPU <-> GPU** work (no CPU visibility). Binary: signaled by one operation, waited by another.
- **Fence** — orders **GPU -> CPU**. The CPU can *wait* on it to know the GPU finished. Created **SIGNALED** here so the very first frame's wait doesn't deadlock on a frame that never ran.

We use: `imageAvailable` (semaphore -- acquire done), `renderFinished` (semaphore -- render done, safe to present), `inFlightFence` (fence -- this frame's GPU work done). Originally **one frame in flight** (simplest correct form); since 2026-06-11 there are **two** -- see [[Frames in Flight]] for how the primitives multiplied, and [[Synchronization2]] for the modern API spelling the submit uses now.

## drawFrame() -- the per-frame dance
1. **Wait** `inFlightFence` (previous frame done) -> **reset** it.
2. **Acquire** an image: `vkAcquireNextImageKHR` (signals `imageAvailable` when the image is genuinely ready), returns an image index.
3. **Submit** `commandBuffers[imageIndex]`: *wait* on `imageAvailable` at the `COLOR_ATTACHMENT_OUTPUT` stage, *signal* `renderFinished`, *signal* `inFlightFence`. (The [[Render Pass|render pass]]'s subpass dependency makes the wait -> color-write ordering correct.)
4. **Present** `vkQueuePresentKHR`: *wait* on `renderFinished`, then display the image.

After the loop: **`vkDeviceWaitIdle`** before [[Roadmap - Clear to Color|cleanup]], so we don't free objects the GPU is still using.

## War story: the renderFinished semaphore reuse race
The first version used **one shared** `renderFinished` semaphore. The [[Validation Layer and Debug Messenger|validation layer]] immediately flagged it (`VUID-vkQueueSubmit-pSignalSemaphores-00067`): the next frame's submit re-signals the semaphore before the previous frame's **present** has consumed it -- and presentation can't signal our fence, so we can't know when it's free. Orange still drew, but it was a real async hazard.

**Fix (the layer literally suggested it):** use **one `renderFinished` semaphore per swapchain image**, indexed by the acquired image. Reuse-safe because an image isn't re-acquired until its prior present released its semaphore. `imageAvailable` and the fence stay single (one frame in flight). Clean run after that.

## Formerly deferred -- both landed 2026-06-11
- **Frames in flight (>1)** -- now 2; see [[Frames in Flight]].
- **Swapchain recreation on resize / out-of-date** -- now handled in the `Renderer`; see [[Swapchain Recreation]].

## Milestone
**Clear to color achieved** (2026-06-09): bright orange `(1.0, 0.4, 0.0)`, FIFO, on the Intel UHD 620, zero validation output. The full bootstrap (instance -> ... -> render loop) works end to end. Next phase: refactor the linear `Main.java` into the reusable "elementaries" (see [[API Vision - Layered Altitudes]]), then a first triangle / shader.

#vulkan #synchronization #render-loop #milestone
