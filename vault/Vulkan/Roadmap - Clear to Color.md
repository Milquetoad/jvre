# Roadmap — Clear to Color

A window cleared to a solid color requires ~a dozen Vulkan objects, created in order. (No shaders/pipeline needed — the **render pass** does the clearing. See [[Shaders - GLSL and SPIR-V]].)

## Phase A — get a GPU we can talk to
1. **[[Vulkan Instance]]** — root handle; declare app + required extensions (`VK_KHR_surface`, `VK_KHR_win32_surface`).
2. **Surface** — wrap our GLFW window so Vulkan can present to it.
3. **Physical device** — enumerate GPUs and pick one (prefer the **RTX 4090** over integrated AMD).
4. **Queue families** — find queues supporting graphics + present.
5. **Logical device** — our connection to the GPU + queue handles; enable `VK_KHR_swapchain`.

## Phase B — images we draw into
6. **Swapchain** — the chain of images shown on screen (double/triple buffering); choose format, present mode, size.
7. **Image views** — "how to interpret" each swapchain image.
8. **Render pass** — `loadOp = CLEAR` → **this is what clears the screen.**
9. **Framebuffers** — bind render-pass attachments to image views (one per image).

## Phase C — record and run
10. **Command pool + buffers** — record "begin render pass (clear), end".
11. **Synchronization** — semaphores + fences coordinate the GPU's async work.
12. **Render loop** — acquire → submit → present; recreate swapchain on resize.

Then **cleanup** in reverse order.

After it works as one linear file → refactor into framework classes ("the elementaries"). See [[Vulkan Overview]].

#vulkan #roadmap
