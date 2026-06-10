# Progress Log

Reverse-chronological diary. Newest at top.

## 2026-06-10 — Switched to dynamic rendering 🟠 (render passes out) ✅
- Owner read that render passes are a criticized early-Vulkan design; we confirmed and **migrated to [[Dynamic Rendering]]** (`VK_KHR_dynamic_rendering`, core in **Vulkan 1.3**). Render passes aren't deprecated/removed (and subpasses earn their keep on *tiled mobile* GPUs), but on desktop they're verbose for little gain and tightly couple pipeline<->pass -- so 1.3's dynamic rendering is the modern default. See [[Dynamic Rendering]].
- Changes: `Instance` now requests **Vulkan 1.3** (was 1.0); `Device` enables the **`dynamicRendering`** feature (chained via `pNext` with `VkPhysicalDeviceDynamicRenderingFeatures`); `Swapchain` gained `image(i)` (barriers act on images, not views); `Main` **deleted `createRenderPass()` + `createFramebuffers()`** and now records each command buffer as **[[Pipeline Barriers|barrier]] -> `vkCmdBeginRendering` (orange clear into the image view) -> `vkCmdEndRendering` -> barrier**.
- **New concept: [[Pipeline Barriers]] + image layouts.** The render pass used to transition the image's layout for free (`initialLayout`/`finalLayout`); now we do it by hand -- `UNDEFINED -> COLOR_ATTACHMENT_OPTIMAL` before, `COLOR_ATTACHMENT_OPTIMAL -> PRESENT_SRC_KHR` after. `oldLayout = UNDEFINED` each frame is safe (we pre-record once and re-clear). Barriers operate on **images**, gated at `COLOR_ATTACHMENT_OUTPUT` to line up with the acquire-semaphore wait.
- **Verified on the Intel UHD 620** (Vulkan 1.3, FIFO): still clears to bright orange, **validation-clean** (the layer was active and silent -- including about the two barriers). Committed `6d5311a`. Render Pass / Framebuffers notes kept but flagged superseded.
- **Next:** a `Renderer` coordinator that owns the device-context objects (+ swapchain recreation on resize), then a first **triangle / shader**.

## 2026-06-10 — Refactor: `Swapchain` extracted (+ dynamic-rendering plan) ✅
- Pulled swapchain negotiation/creation **and** the per-image `VkImageView`s out of `Main` into **`jvre.core.Swapchain`** (the format/present-mode/extent choosers came along). It owns the chosen `imageFormat`/`width`/`height` that later steps build against. Ownership split kept intact: the **images** belong to the swapchain (freed on destroy), the **views** are ours. `Main` now reads `swapchain.imageFormat()/width()/height()/imageCount()/imageView(i)/handle()`; `cleanup()` calls `swapchain.close()` (views, then chain). Behavior-preserving; compiles clean. Committed `1fd2a9a`.
- **Deliberately did NOT extract `RenderPass`/`Framebuffers`:** the next step replaces them. Owner read that render passes are a criticized early-Vulkan design and asked if we're current. Verdict: render passes aren't *deprecated/removed* (and on tiled mobile GPUs subpasses can be a real win), but on desktop they're verbose for little gain, so **Vulkan 1.3 (2022) promoted `VK_KHR_dynamic_rendering` to core** as the simpler default -- no `VkRenderPass`, no `VkFramebuffer`, no pipeline<->renderpass coupling. `Swapchain` + its image views survive that change untouched (you render straight into a view), so extracting it was non-throwaway.
- **Next:** migrate to **dynamic rendering** -- bump the instance API to 1.3, enable the `dynamicRendering` feature, delete render pass + framebuffers, swap `vkCmdBeginRenderPass`/`End` for `vkCmdBeginRendering`/`End`, and take over the image **layout transitions** ourselves with explicit pipeline barriers (UNDEFINED -> COLOR_ATTACHMENT_OPTIMAL before, -> PRESENT_SRC_KHR after). New concept to learn: [[Pipeline Barriers]].

## 2026-06-10 — Refactor: `Device` extracted ✅
- Pulled physical-device **selection** (the scoring policy), logical **`VkDevice`** creation, and **graphics/present queue** retrieval out of `Main` into **`jvre.core.Device`** -- the head of the recreatable "device context" (Instance + Surface stay put above it; everything GPU-dependent will hang below it). `Main` now does `device = new Device(instance, surface)` and reads `device.handle()` / `device.physicalDevice()` / `device.graphicsQueue()` / `device.presentQueue()` / `device.graphicsFamily()` / `device.presentFamily()`. Pruned 13 now-dead imports.
- **Behavior-preserving:** identical scoring (discrete +1000, `maxImageDimension2D` tiebreak), same swapchain-extension hard requirement, same queue-family dedup. Small cleanup: `createSwapchain`/`createCommandPool` used to *re-run* `findQueueFamilies` each time they needed the indices; they now read the values `Device` captured once at construction. `rateDevice` stays the seam for future flexible GPU selection. Compiles clean; committed `8c29195`.
- Was caught mid-extraction by a context cutoff (two half-applied edits + stray imports); resumed, finished, reviewed the diff as a pure mechanical move, and verified the build.
- **Next:** extract `Swapchain` / `RenderPass` / `Framebuffers` into the device context, then a `Renderer` coordinator (+ resize/swapchain recreation), then frames-in-flight (>1).

## 2026-06-09 — Refactor begins: stable trio extracted ✅
- Started turning the linear `Main.java` into reusable classes. New package **`jvre.core`** with the **stable layer**: `Window` (GLFW), `Instance` (instance + validation + debug messenger), `Surface` (VkSurfaceKHR). `Main` now constructs them (`new Window(...)`, `new Instance("jvre demo", ENABLE_VALIDATION)`, `new Surface(instance, window)`) and calls `.handle()` where the raw Vulkan object is needed; pruned the now-dead imports. **Behavior-preserving** -- still clears to orange, validation-clean.
- Design decisions logged in [[API Vision - Layered Altitudes]]: **naming** is Vulkan-faithful at L1 (its audience is Vulkan-literate), intuitive at L2; **L1 visibility** hides infrastructure and exposes only the creative tier (`Shader`/`Pipeline`/`Buffer`) -- a Shadertoy shader touches ~1 artifact, not a dozen objects. Plus [[Design North Star]] (the smaller-than-Processing-but-flexible sweet spot).
- **Next:** extract `Device` (physical selection + logical device + queues), then `Swapchain`/`RenderPass`/`Framebuffers`, then a `Renderer` coordinator (+ resize recreation).

## 2026-06-09 — Headless validation on Hal-9000's RTX 4090 ✅
- Ran the latest jvre on Hal-9000 (RTX 4090) over SSH/Tailscale (GLFW window is invisible there, but bootstrap runs fine; captured output, then killed the process). Same code, validation-clean. Cross-hardware confirmation of the flexible design:
  - **Scoring** picks the **RTX 4090 (score 33768)** over the AMD iGPU — vs craptop's Intel UHD 620 (16384). The discrete-GPU preference works on real dual-GPU hardware.
  - **Present-mode preference** picks **MAILBOX** on the 4090 vs the **FIFO** fallback on the Intel — exactly our "prefer MAILBOX, else FIFO" logic, validated across two GPUs.
  - Caveat: both machines report a **shared** graphics+present family, so the swapchain's `CONCURRENT` sharing-mode branch is still unexercised on real hardware.
- **Next:** refactor the linear `Main.java` into reusable "elementaries" (start of the framework). See [[API Vision - Layered Altitudes]].

## 2026-06-09 — 🟠 CLEAR TO COLOR — first pixels! ✅
- Added `createSyncObjects()` + `drawFrame()` + a real render loop to `jvre.Main`. **The window now fills with bright orange** `(1.0, 0.4, 0.0)`, vsync (FIFO), on the Intel UHD 620 — zero validation output. **The entire "clear to color" roadmap is complete** (instance -> ... -> render loop). See [[Synchronization and the Render Loop]].
- Sync model (one frame in flight): `imageAvailable` + `renderFinished` semaphores (GPU<->GPU), one `inFlightFence` (GPU->CPU, created SIGNALED). `drawFrame`: wait fence -> acquire -> submit (wait imageAvailable, signal renderFinished + fence) -> present. `vkDeviceWaitIdle` before cleanup.
- **War story:** a single shared `renderFinished` semaphore tripped validation `VUID-vkQueueSubmit-pSignalSemaphores-00067` — the next frame re-signals it before the previous PRESENT consumed it (present can't signal a fence). Fix (the layer suggested it): **one renderFinished semaphore per swapchain image**, indexed by acquired image. Clean after. Another win for the safety net.
- Deferred on purpose: frames-in-flight (>1) and swapchain recreation on resize (window is fixed-size).
- **Next phase:** refactor the linear `Main.java` into reusable "elementaries" (stable instance+surface vs. one recreatable device context — see [[API Vision - Layered Altitudes]]), then a first **triangle / shader**.

## 2026-06-09 — Command pool + buffers ✅
- Added `createCommandPool()` + `createCommandBuffers()` to `jvre.Main`: a graphics-family pool, one primary command buffer per framebuffer, each pre-recording begin -> `vkCmdBeginRenderPass` (clear = **bright orange** `(1.0, 0.4, 0.0)`) -> end render pass -> end. Output: `Recorded 3 command buffers (clear to orange)`, clean. See [[Command Buffers]].
- Learned: recording != executing (record now, submit later); command pool is tied to a queue family and frees its buffers on destroy; `loadOp=CLEAR` means there's nothing to record between begin/end render pass; the clear color is a `VkClearValue`, and sRGB encoding makes the linear values read brighter on screen; `SUBPASS_CONTENTS_INLINE`.
- Committed + pushed render pass/framebuffers (`da8ab56`) and the [[Glossary]] (`330487d`).
- **Next (the finale):** **synchronization + render loop** — semaphores/fences to order acquire -> submit -> present, then the loop that submits these buffers. **This is where the orange actually appears.** See [[Roadmap - Clear to Color]].

## 2026-06-09 — Framebuffers ✅
- Added `createFramebuffers()` to `jvre.Main`: one `VkFramebuffer` per swapchain image, each binding that image's [[Image Views|view]] into the [[Render Pass|render pass]]'s color slot at the swapchain size. Output: `Created 3 framebuffers`, clean. See [[Framebuffers]].
- Learned: render pass = blueprint (attachment *slots*), framebuffer = the *filled-in* binding (slot -> concrete view) at a size; attachment order must match the render pass; framebuffers/views/swapchain are the set recreated on resize.
- **Next:** **command pool + command buffers** — record "begin render pass (clear) / end" into a buffer the GPU executes. See [[Roadmap - Clear to Color]].

## 2026-06-09 — Render pass ✅
- Added `createRenderPass()` to `jvre.Main`: one color attachment (swapchain format, `loadOp=CLEAR` -> `storeOp=STORE`, `UNDEFINED` -> `PRESENT_SRC_KHR`), a single graphics subpass referencing it, and a forward-looking subpass dependency for the render loop's sync. Output: `Render pass created`, clean. See [[Render Pass]].
- Learned: the render pass is a **blueprint** (attachments + load/store ops + subpasses), separate from the images/pipeline; **`loadOp=CLEAR` is literally what clears the screen**; image **layouts** and automatic transitions; the attachment index = the fragment shader's `layout(location=0)`; subpass dependencies as sync seams.
- Added a vault [[Glossary]] (smoke test, boilerplate, two-call idiom, etc.). Committed + pushed swapchain/image-views/design-notes (`ba4c401`, `7b4c3a9`).
- **Next:** **framebuffers** — one per swapchain image, binding its [[Image Views|view]] to this render pass. See [[Roadmap - Clear to Color]].

## 2026-06-09 — Image views ✅
- Added `createImageViews()` to `jvre.Main`: one `VkImageView` per swapchain image (2D, swapchain format, color aspect, no mips, single array layer, identity swizzle). Output: `Created 3 image views`, clean. See [[Image Views]].
- Learned: you almost never use a `VkImage` directly — a view says *how to interpret* it; render pass/framebuffer reference **views, not images**; the `subresourceRange` (aspect + mip/array range) and component swizzle; we own the views (destroy before the swapchain).
- **Design discussion logged this session:** [[Design North Star]] (the smaller-than-Processing-but-flexible sweet spot; approachability is unbuilt and must be designed at L2, not bolted on), plus the elementaries-refactor *timing/seam* and surface-format-as-L1-policy decisions (in [[API Vision - Layered Altitudes]] / [[Swapchain]]).
- **Next:** the **render pass** — describe the color attachment (these views) with `loadOp = CLEAR`. This is the object that actually **clears the screen**. See [[Roadmap - Clear to Color]].

## 2026-06-09 — Swapchain ✅
- Added `createSwapchain()` to `jvre.Main`: queried surface capabilities / formats / present modes, chose format (prefer `B8G8R8A8_SRGB`), present mode (prefer `MAILBOX`, fall back to `FIFO`), and extent (window framebuffer size, clamped), created the `VkSwapchainKHR`, and retrieved the image handles. See [[Swapchain]].
- Output on craptop's Intel UHD 620: `Swapchain created: 3 images, 800x600, format 50, present mode FIFO` — clean. `minImageCount`(2)+1 = triple buffering; the iGPU exposes only FIFO (no MAILBOX); surface pinned the 800x600 extent; format 50 = `B8G8R8A8_SRGB`.
- Learned: the present/acquire swap model; sharing mode (`CONCURRENT` vs `EXCLUSIVE`) depends on whether graphics/present families differ (shared here -> EXCLUSIVE); extent is in **pixels** (framebuffer size, not screen coords); swapchain images are **owned by the swapchain** (freed by destroying it, not individually); `oldSwapchain` is the future seam for resize recreation.
- **Next:** **image views** — a `VkImageView` per swapchain image describing how to interpret it (2D, color, mip/array range), the prerequisite for framebuffers. See [[Roadmap - Clear to Color]].

## 2026-06-09 — Logical device + queues ✅
- Added `createLogicalDevice()` to `jvre.Main`: deduped graphics+present families via a `Set`, requested one queue each, enabled the `VK_KHR_swapchain` device extension, created the `VkDevice`, and retrieved the graphics & present `VkQueue` handles. Also made **swapchain support a hard requirement** during GPU selection (`checkDeviceExtensionSupport`). See [[Logical Device and Queues]].
- On craptop's Intel UHD 620, graphics and present are the **same family** → output: `Logical device created; ... (shared family)`.
- **War story:** first run hit a validation **ERROR** — `vkCreateDevice enabledLayerCount is 1 (not zero)`. Old "set device layers for backwards-compat" advice is now wrong: device layers are 1.0 legacy, the instance layer covers device calls, and the spec requires count 0. Removed them → clean run. The [[Validation Layer and Debug Messenger|safety net]] earning its keep.
- Learned: physical (read-only, selected) vs logical (created/owned/destroyed) device; queues are born with the device and only *retrieved*; queue count is inferred from the priorities buffer length; device vs instance extension scope; destroy the device first in cleanup.
- **Setup milestone:** this was the first feature built on **craptop** after migrating from Hal-9000 (Tailscale/SSH to the 4090 box wired up; Vulkan SDK installed locally). See [[Toolchain Setup]].
- **Next:** the **swapchain** — choose format/present-mode/extent, create the chain of presentable images. See [[Roadmap - Clear to Color]].

## 2026-06-09 — Open-sourced: pushed to GitHub ✅
- Published to **https://github.com/Milquetoad/jvre** (public). Added docs: README, CONTRIBUTING (with a CLA), `.gitignore`, `.gitattributes` (LF normalization for the coming Linux move), AGPL-3.0 LICENSE.
- **License = AGPL-3.0**, chosen via the one-way-ratchet principle (start restrictive; can always relax to MIT, never re-tighten). Keeps dual-licensing/monetization open — relevant if jvre ever powers a hosted/streamed rendering service (headless Vulkan!). See [[Device Selection and Cross-Platform (planned)]] neighbors / engine vision.
- Privacy scrub before going public: no secrets; excluded `.claude/settings.local.json` and Obsidian `workspace.json`; genericized a local path; committed with a GitHub **noreply** email so the real address stays out of history.
- The whole **vault is committed** — the learning notes travel with the code.
- **Next (resumes the build):** **logical device + queues**.

## 2026-06-09 — Physical device: picked the RTX 4090 ✅
- Added `pickPhysicalDevice()` to `jvre.Main`: enumerate GPUs, find queue families (graphics + present), discard unsuitable, **score** the rest (discrete +1000, +maxImageDimension2D tiebreak), keep the best. See [[Physical Device and Queue Families]].
- Output: `Picked GPU: NVIDIA GeForce RTX 4090 (score 33768)`.
- Learned: **queue families** (you submit work to typed queues, not the GPU directly); present support is **queried** per-family via `vkGetPhysicalDeviceSurfaceSupportKHR` (first payoff of the surface), not a flag; graphics/present may be the same family; `Integer` sentinel because index 0 is valid; physical device is *selected*, never created/destroyed.
- Built scoring (not hard-coded) on purpose — the seam for [[Device Selection and Cross-Platform (planned)|flexible selection]].
- **Next:** **logical device + queues** — create a `VkDevice` from the chosen GPU and retrieve our graphics & present queue handles. See [[Roadmap - Clear to Color]].

## 2026-06-09 — Surface: window meets Vulkan ✅
- Added `createSurface()` to `jvre.Main`: `glfwCreateWindowSurface` builds a `VkSurfaceKHR` from the window + instance. See [[Windowing - GLFW and the Surface]].
- Finally *used* the `VK_KHR_surface` / `VK_KHR_win32_surface` extensions GLFW has demanded since day one.
- Learned: surface is an **instance-owned** opaque `long` handle; the call returns a Vulkan result code and writes the handle into an out-param; GLFW hides the per-OS surface call. Destroy it **before** the instance (child before parent).
- Clean run, zero validation output = success.
- **Next:** **physical device selection** — enumerate GPUs, ask each "can you present to this surface?", prefer the RTX 4090. See [[Roadmap - Clear to Color]].

## 2026-06-09 — Safety net: validation layer + debug messenger ✅
- Added [[Validation Layer and Debug Messenger]] to `jvre.Main`: enabled `VK_LAYER_KHRONOS_validation`, added `VK_EXT_debug_utils`, registered a callback, chained a messenger into the instance `pNext`.
- Clean run = silent (WARNING|ERROR only). Then **deliberately broke it** (skipped destroying the messenger) → validation caught the leaked object with a spec-cited error + VUID. Printed twice = both standalone + pNext messengers fired (confirms the pNext trick). Reverted the bug.
- Learned: the **two-call enumeration idiom**, a **Java method as a native callback** (JNI in reverse, must `.free()`), severity/type filters.
- **Next:** the **surface** (`VkSurfaceKHR`) — introduce the window to Vulkan (`glfwCreateWindowSurface`). See [[Windowing - GLFW and the Surface]].

## 2026-06-09 — First Vulkan code: instance created ✅
- Wrote `jvre.Main` with the `initWindow/initVulkan/mainLoop/cleanup` skeleton; created a real [[Vulkan Instance]] (bare, no validation yet). Blank window opens + closes cleanly; prints "Vulkan instance created successfully."
- **War story:** first run crashed with `OutOfMemoryError: Out of stack space` — `new VkInstance` enumerates all GPU extensions on the 64 KB [[MemoryStack]], and the RTX 4090 + AMD combo overflowed it. Fixed with `Configuration.STACK_SIZE.set(512)`. (Logged in [[MemoryStack]].)
- **Next:** add the validation layer + `VK_EXT_debug_utils` debug messenger; then deliberately trigger a validation error to see the safety net work.

## 2026-06-09 — Vulkan instance (concept)
- Explained the [[Vulkan Instance]] conceptually: it's the root object / the loader-configured entry point; GPU-agnostic; carries app info, instance extensions, and layers.
- Covered the **instance-level vs device-level** scope split, and the [[Vulkan Struct Conventions|`sType`/`pNext`]] pattern common to all Vulkan structs.
- **Next:** actually write `vkCreateInstance` (validation layer + debug messenger included).

## 2026-06-08 — Kickoff & foundations
- **Landscape research:** no existing Java+Vulkan *general framework* matching our goal. [[LWJGL]] is the right low-level foundation; decided to build the framework ourselves for learning.
- **[[Toolchain Setup]]:** JDK 21 (already present), Gradle 8.10.2 (via [[Gradle Wrapper|wrapper]]), LWJGL 3.3.4, Vulkan SDK 1.4.350.0. GPUs: RTX 4090 (discrete) + AMD integrated.
- **Smoke test** (`jvre.Main`): confirmed GLFW inits, the Vulkan loader is reachable, and required instance extensions = `VK_KHR_surface`, `VK_KHR_win32_surface`. ✅
- **Concepts learned:** [[Build Tools]], [[Gradle]], [[Gradle Wrapper]], [[LWJGL]], [[JNI]], [[Off-Heap Memory]], [[MemoryStack]].
- **Planned** the path to first pixels: [[Roadmap - Clear to Color]].
- **Next:** create the [[Vulkan Instance]].

#log
