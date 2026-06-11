# Frames in Flight

How many frames the CPU may be *preparing ahead* of the GPU. jvre moved from 1 to **2** on 2026-06-11 (`Renderer.MAX_FRAMES_IN_FLIGHT`), together with the switch to **per-frame command recording**.

## The idea

With **one** frame in flight, CPU and GPU take turns idling: the CPU records and submits a frame, then sits in `vkWaitForFences` until the GPU finishes it. With **two**, the CPU records frame N+1 *while* the GPU draws frame N -- the pipeline stays fed. More than 2 mostly adds input latency (frames queue up) for little extra throughput; 2 is the standard sweet spot.

## What had to multiply (and what didn't)

Everything a frame *holds while the GPU works* needs one copy per slot, or frame N+1 would stomp on frame N's:

| Resource | Count | Why |
|---|---|---|
| command buffer | per **frame in flight** | re-recorded each frame; the fence guards reuse |
| `imageAvailable` semaphore | per **frame in flight** | each in-flight acquire needs its own |
| `inFlightFence` | per **frame in flight** | "is THIS slot's GPU work done?" |
| `renderFinished` semaphore | per **swapchain image** | indexed by *acquired image*, not frame -- a per-frame one races with presentation (the [[Synchronization and the Render Loop|war story]] VUID) |
| swapchain / views / pool | **one** | shared infrastructure, not per-frame state |

`drawFrame` cycles `currentFrame = (currentFrame + 1) % MAX_FRAMES_IN_FLIGHT`, and step 1 waits on *that slot's* fence -- i.e. on the frame submitted `MAX_FRAMES_IN_FLIGHT` frames ago, not the immediately previous one. That wait is what makes reusing the slot's command buffer + semaphore safe.

## Per-frame command recording (the model change that came with it)

The clear used to be **pre-recorded once per swapchain image** and replayed -- fine while the frame never changes, dead the moment anything moves. Now each slot's buffer is **reset + re-recorded every frame** (`recordCommandBuffer(cmd, imageIndex)`; the pool has `RESET_COMMAND_BUFFER_BIT`). This is the modern model, and it's also why [[Swapchain Recreation]] got simpler: nothing pre-recorded bakes in the old extent/images anymore.

The first triangle's draw commands will land inside `recordCommandBuffer`, between `vkCmdBeginRendering` and `vkCmdEndRendering`.

## Gotcha: the early-out path
On `OUT_OF_DATE` at acquire we recreate and `return` **without advancing `currentFrame`**: no submit consumed the slot, its semaphore went unsignaled and its fence stayed signaled, so the retry must reuse the same slot. (Advancing would eventually wait on a fence nothing will signal.) Related: the fence is reset only once a submit is guaranteed -- see [[Swapchain Recreation]].

#vulkan #synchronization #concept
