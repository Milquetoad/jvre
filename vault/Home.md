# jvre — Learning Vault

Knowledge base for **jvre** (Java Vulkan Rendering Engine): a from-scratch rendering framework built to *learn* graphics, Vulkan, and the Java↔native ecosystem.

> This vault is maintained continuously alongside the code. Every concept we cover gets (or updates) a note here. Newest activity is in [[Progress Log]].

## Suggested reading order (for newcomers)

New here? The Map of Content below is a reference index, not a path. To actually follow the journey from zero to where we are now, read roughly in this order:

**1. The big picture — why we're doing this**
1. [[Vulkan Overview]] — what Vulkan is, and why it's so explicit/verbose
2. [[API Vision - Layered Altitudes]] — what jvre is ultimately for (the north star)
3. [[Roadmap - Clear to Color]] — the concrete plan to first pixels; the spine everything else hangs on

**2. Foundations — the Java ↔ native ground we stand on**
4. [[Build Tools]] → [[Gradle]] → [[Gradle Wrapper]] — how the project is built (skim if build systems are old hat)
5. [[LWJGL]] → [[JNI]] — how Java reaches the native Vulkan / GLFW C libraries
6. [[Off-Heap Memory]] → [[MemoryStack]] — the memory model used in *every* Vulkan call we write

**3. A convention to read once — it applies everywhere**
7. [[Vulkan Struct Conventions]] — the `sType` / `pNext` pattern you'll see in every struct

**4. The bootstrap, in the order the code builds it** — the heart of what we've done so far
8. [[Vulkan Instance]] — the root handle / loader entry point
9. [[Validation Layer and Debug Messenger]] — the safety net (turned on before everything else)
10. [[Windowing - GLFW and the Surface]] — who actually makes the window, and the window ↔ Vulkan bridge
11. [[Physical Device and Queue Families]] — choosing a GPU; queue families; present support
12. [[Logical Device and Queues]] — opening the connection to the GPU; retrieving queues; requiring swapchain
13. [[Swapchain]] — negotiating and creating the queue of images we present to the screen
14. [[Image Views]] — wrapping each swapchain image so it can be rendered into
15. [[Render Pass]] + [[Framebuffers]] — the *classic* clear path (now **superseded** in jvre, but the concepts carry over)
16. [[Dynamic Rendering]] — the **modern** path jvre actually uses (no render pass / framebuffer; core in Vulkan 1.3)
17. [[Pipeline Barriers]] — the image-layout transitions dynamic rendering makes you own
18. [[Synchronization2]] — the modern (1.3) spelling of barriers + queue submission jvre uses
19. [[Command Buffers]] — recording the clear command (record now, submit later)
20. [[Synchronization and the Render Loop]] — semaphores/fences and the loop that finally shows the color
21. [[Frames in Flight]] — overlapping CPU recording with GPU rendering (and per-frame re-recording)
22. [[Swapchain Recreation]] — resize/minimize/out-of-date handling in the Renderer

**5. Looking ahead — optional, for the curious**
- [[Shaders - GLSL and SPIR-V]], [[GUI Options]] / [[Self-Built GUI (planned)]], [[Ray Tracing and Path Tracing (future)]], [[Device Selection and Cross-Platform (planned)]]

> Prefer a story to an index? [[Progress Log]] is the dated, diary-form version of this same journey.

## Map of Content

### Project
- [[Toolchain Setup]] — what's installed and why
- [[Progress Log]] — dated diary of what we've done
- [[API Vision - Layered Altitudes]] — the engine's north star (high-level + shader-art on one stack)
- [[Design North Star]] — the sweet spot (smaller than Processing; powerful, flexible, approachable) + how we measure it
- [[Device Selection and Cross-Platform (planned)]] — flexible GPU choice + OS-agnostic (post-MVP)

### Foundations (Java ↔ native)
- [[Glossary]] — quick one-line definitions for jargon across the vault
- [[Build Tools]]
- [[Gradle]]
- [[Gradle Wrapper]]
- [[LWJGL]]
- [[GLFW]]
- [[JNI]]
- [[Off-Heap Memory]]
- [[MemoryStack]]
- [[Shaders - GLSL and SPIR-V]]
- [[Consuming and Publishing Libraries]]
- [[Creating a Gradle Project from Scratch]]
- [[Testing and CI-CD]]

### Vulkan
- [[Vulkan Overview]]
- [[Roadmap - Clear to Color]]
- [[Vulkan Struct Conventions]] — the `sType`/`pNext` pattern
- [[Windowing - GLFW and the Surface]] — who makes the window (not Vulkan!)
- [[GUI Options]] — ImGui / Nuklear (GLFW has no widgets)
- [[Self-Built GUI (planned)]] — our own immediate-mode GUI (later milestone)
- [[Ray Tracing and Path Tracing (future)]] — yes, reachable (compute + hardware RT on the 4090)
- [[Vulkan Instance]] ✅
- [[Validation Layer and Debug Messenger]] ✅ — the safety net
- [[Physical Device and Queue Families]] ✅ — picking a GPU, queues, present support
- [[Logical Device and Queues]] ✅ — VkDevice, queue handles, swapchain extension
- [[Swapchain]] ✅ — the presented-image queue (format, present mode, extent)
- [[Image Views]] ✅ — how to interpret each swapchain image (the render-pass prerequisite)
- [[Dynamic Rendering]] ✅ — jvre's render path (Vulkan 1.3; no render pass / framebuffer)
- [[Pipeline Barriers]] ✅ — image-layout transitions (what dynamic rendering makes us own)
- [[Synchronization2]] ✅ — per-barrier stage masks, VkDependencyInfo, vkQueueSubmit2 (core 1.3)
- [[Render Pass]] ⚠️ superseded — the classic clear-to-color blueprint (kept for concepts)
- [[Framebuffers]] ⚠️ superseded — bind image views into a render pass (kept for concepts)
- [[Command Buffers]] ✅ — record "barrier / clear (dynamic rendering) / barrier"
- [[Synchronization and the Render Loop]] ✅ — semaphores/fences + the per-frame loop (🟠 first pixels)
- [[Frames in Flight]] ✅ — 2 in flight; per-frame command recording (the modern model)
- [[Swapchain Recreation]] ✅ — resizable window; OUT_OF_DATE/SUBOPTIMAL/flag triggers; minimize handling

## Status
- ✅ Toolchain verified (smoke test passes)
- ✅ Vulkan SDK installed (`glslc` works)
- ✅ [[Vulkan Instance]] created & running
- ✅ [[Validation Layer and Debug Messenger]] (safety net, demonstrated catching a bug)
- ✅ Surface (`VkSurfaceKHR`) created — window connected to Vulkan
- ✅ [[Physical Device and Queue Families]] — picked the RTX 4090 (graphics + present, scored)
- ✅ [[Logical Device and Queues]] — `VkDevice` created, graphics + present queues retrieved, swapchain extension enabled
- ✅ [[Swapchain]] — created (3 images, 800x600, sRGB BGRA, FIFO on the Intel iGPU)
- ✅ [[Image Views]] — one view per swapchain image (3 created)
- ✅ [[Render Pass]] — clear-to-color blueprint created (one color attachment, `loadOp=CLEAR`)
- ✅ [[Framebuffers]] — one per swapchain image (3 created)
- ✅ [[Command Buffers]] — pool + 3 buffers recorded (clear to bright orange)
- ✅ [[Synchronization and the Render Loop]] — 🟠 **clear to color achieved!** (orange, FIFO, clean validation)
- ✅ **Refactor underway** — extracted `Window`/`Instance`/`Surface` (stable layer) + `Device` + `Swapchain` into `jvre.core`
- ✅ [[Dynamic Rendering]] — switched off render passes/framebuffers (Vulkan 1.3); orange still clean on the Intel UHD 620
- ✅ [[Synchronization2]] — barriers + submit migrated to the 1.3 sync API; 1.3/feature support now *verified* during device selection; sync-validation + best-practices checks on (all clean on the 4090)
- ✅ **`Renderer` extracted** — owns the whole device context (Device, Swapchain, commands, sync); `Main` is wiring only
- ✅ [[Swapchain Recreation]] — window is resizable (+ minimize); verified by driving the window via user32
- ✅ [[Frames in Flight]] — 2 in flight, per-frame command recording
- ⏭️ Next: the first **triangle / shader** — graphics pipeline, GLSL -> SPIR-V, the draw goes inside `recordCommandBuffer`

#jvre #moc
