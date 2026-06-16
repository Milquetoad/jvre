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

**5. Drawing actual geometry — the creative tier begins**
23. [[Shaders - GLSL and SPIR-V]] — GLSL, the `shaderc` build step, locations, interpolation
24. [[Graphics Pipeline]] — the big bake; dynamic state; the first triangle 🔺
25. [[Vertex Buffers and GPU Memory]] — VkBuffer vs VkDeviceMemory, memory types, staging uploads
26. [[Push Constants]] — per-frame data straight into the command buffer (the spin 🌀)
27. [[Index Buffers]] — unique vertices + indices; `vkCmdDrawIndexed` (the quad)
28. [[Uniform Buffers and Descriptor Sets]] — layouts, pools, sets, per-frame UBOs (the orbit)
29. [[Textures - Images, Views and Samplers]] — VkImage, tiling/layout transitions, samplers (NEAREST), `COMBINED_IMAGE_SAMPLER` (the checkerboard 🖼️)
30. [[3D and the Depth Buffer]] — perspective MVP (JOML), the depth buffer, a solid cube (the 🎲)
31. [[VMA - Vulkan Memory Allocator]] — sub-allocation, intent-over-flags, dedicated attachments (the advisories hit 0 🧹)
32. [[MSAA]] — multisampled color target + built-in resolve; coverage vs texture aliasing (smooth silhouettes ✨)

**6. Looking ahead — optional, for the curious**
- [[Roadmap]] — the prioritized forward plan (start here for "what's next")
- [[GUI Options]] / [[Self-Built GUI (planned)]], [[Ray Tracing and Path Tracing (future)]], [[Device Selection and Cross-Platform (planned)]]

> Prefer a story to an index? [[Progress Log]] is the dated, diary-form version of this same journey.

## Map of Content

### Project
- [[Toolchain Setup]] — what's installed and why
- [[Progress Log]] — dated diary of what we've done
- [[Roadmap]] — the forward-looking plan + the *why* behind its ordering (the current source of truth for "what next")
- [[Definition of Done]] — the tiered quality bar a change must clear to count as done
- [[API Vision - Layered Altitudes]] — the engine's north star (high-level + shader-art on one stack)
- [[Design North Star]] — the sweet spot (smaller than Processing; powerful, flexible, approachable) + how we measure it
- [[ShaderEffect - The Shadertoy Altitude]] — the first realized altitude: runtime-compiled user fragment shaders on a fullscreen triangle (the shader-art path made real)
- [[Diagnostics and the Crash Log]] — the Ring 2 guard: an environment fingerprint tee'd to a per-OS log so a fault on foreign hardware is diagnosable from one file
- [[L2 Feature Set - Renderer2D]] — the formal "just draw" surface: principles, primitives, the no-modes decision (draft)
- [[Game-Engine Capabilities (planned)]] — what L1 must expose so a game engine can be built on jvre: the mechanism/policy rule, the Tier-2 target, text/camera/3D scope, the 3 Renderer-shape constraints
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
- [[Self-Built GUI (planned)]] — our own immediate-mode GUI (✅ built 2026-06-15 as a bounded L2 *demo*, not a shipped feature)
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
- [[Graphics Pipeline]] ✅ — the big bake; dynamic viewport/scissor; dynamic-rendering format hookup (🔺 first triangle)
- [[Vertex Buffers and GPU Memory]] ✅ — Buffer elementary; memory-type hunt; staging -> DEVICE_LOCAL
- [[Push Constants]] ✅ — VkPushConstantRange + vkCmdPushConstants + push_constant block (time/aspect)
- [[Index Buffers]] ✅ — UINT16 indices, vkCmdBindIndexBuffer + vkCmdDrawIndexed (the quad)
- [[Uniform Buffers and Descriptor Sets]] ✅ — layout/pool/set machinery; per-frame mat4 UBO; write-once-rewrite-contents
- [[Textures - Images, Views and Samplers]] ✅ — VkImage + tiling + layout transitions; view + NEAREST sampler; COMBINED_IMAGE_SAMPLER descriptor; UV attribute
- [[3D and the Depth Buffer]] ✅ — perspective MVP (JOML, Vulkan Y-flip + zZeroToOne); depth image in the Swapchain; depth test/write; the cube
- [[VMA - Vulkan Memory Allocator]] ✅ — Device-owned allocator; vmaCreateBuffer/Image; intent over flags; dedicated depth allocation; findMemoryType retired
- [[MSAA]] ✅ — queried 4x; multisampled color + depth; resolveMode in dynamic rendering; the coverage-vs-sampler aliasing split

## Status
- ✅ Toolchain verified (smoke test passes)
- ✅ Vulkan SDK installed (validation layers; the build no longer needs it -- shaders compile via the bundled `shaderc`)
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
- ✅ 🔺 **FIRST TRIANGLE** — [[Graphics Pipeline]] + [[Shaders - GLSL and SPIR-V|shaders]] (RGB-interpolated, on the orange clear; screenshot in [[Progress Log]])
- ✅ [[Vertex Buffers and GPU Memory]] — geometry is data now: `Buffer` elementary, host-visible first, then staging to DEVICE_LOCAL
- ✅ 🌀 [[Push Constants]] — **the triangle spins** (time + aspect pushed per frame; the per-frame-recording payoff)
- ✅ [[Index Buffers]] — **the quad**: 4 unique vertices + 6 indices, `vkCmdDrawIndexed` (best-practices layer now files the VMA ticket at startup — known advisory)
- ✅ [[Uniform Buffers and Descriptor Sets]] — **the quad orbits**: CPU-built mat4 through a per-frame UBO + descriptor set; push constant moved to the fragment stage (pulse) — both tiers side by side
- ✅ 🖼️ [[Textures - Images, Views and Samplers]] — **a picture on the quad**: checkerboard sampled via VkImage + layout transitions + NEAREST sampler + COMBINED_IMAGE_SAMPLER descriptor (verified on the 4090; validation clean but the known VMA advisory)
- ✅ 🪟 **Alpha blending** — REPLACE → src-over-dst alpha; half the checker cells transparent so sprites can have transparent backgrounds (verified on the 4090; see [[Textures - Images, Views and Samplers]])
- ✅ 🎲 [[3D and the Depth Buffer]] — **a solid spinning cube**: perspective MVP (JOML), a depth buffer in the Swapchain, depth test/write occluding far faces (verified on the 4090)
- ✅ 🔄 **Back-face culling** — BACK + CCW front faces; the two-mirror winding lesson (first attempt rendered inside-out; see [[3D and the Depth Buffer]])
- ✅ 🧹 [[VMA - Vulkan Memory Allocator]] — **advisories 6 → 0, stderr silent**: sub-allocation via a Device-owned VmaAllocator; intent over flags; the hand-rolled memory hunt retired
- ✅ ✨ [[MSAA]] — **smooth silhouettes**: 4x multisampled color + depth, resolve built into dynamic rendering, swapchain demoted to resolve target (verified on the 4090; checker-edge aliasing observed + expected — the sampler's domain, not MSAA's)
- 🏁 **The long-standing roadmap list (textures → 3D + depth → MSAA) is COMPLETE.**
- ✅ 🎨 [[ShaderEffect - The Shadertoy Altitude]] — **the first realized altitude**: a runtime-compiled (shaderc) user fragment shader on a fullscreen triangle; auto-filled `uResolution`/`uMouse`/`uTime`; the renderer's first content seam (verified on the 4090 — the ripple demo). jvre's FIRST unit tests arrive with it ([[Testing and CI-CD]]).
- ✅ 🩺 [[Diagnostics and the Crash Log]] — **the Ring 2 guard**: an environment fingerprint (GPU/driver/loader/OS, the EXCLUSIVE-vs-CONCURRENT queue line, formats) tee'd to a per-OS app-data log a user can attach to a bug report; eager+flushed; frictionless manual send (verified on the 4090, fault path included).
- ✅ 🛡️ **Ring 3 guard** — `ShaderReflection` enforces the [[ShaderEffect - The Shadertoy Altitude#The contract, now ENFORCED (Ring 3 guard) ✅|effect contract]] at creation via SPIRV-Cross (`lwjgl-spvc`): no bound resources, push block ≤ 20 bytes; fails fast in the user's terms. Also the foundation for `set()`. (The "what breaks jvre" plan — all three rings — is now fully executed.)
- ✅ 🟦 [[L2 Feature Set - Renderer2D|L2 `Renderer2D`]] — **the "just draw" altitude, complete**: fills, strokes, the SDF render path (incl. crisp SDF curves), `image` + multi-texture batching + `loadImage` (PNG/JPEG via stb_image) + per-texture `Filter`, `text` (SDF glyphs) + measurement, the transform stack.
- ✅ 🎮 **Interactivity** — a per-frame `Input` snapshot (mouse/keys/scroll/typed, level+edge) and `time()`/`dt()`; plus creation-time `RendererOptions` (vsync/MSAA/GPU override).
- ✅ 🔧 **L1 escape hatch** — user-defined pipelines (`PipelineSpec`/`VertexLayout`/`FrameRenderer`/`SceneRenderer`) + `Camera`; the cube dogfooded via the public API, the hardcoded SCENE retired.
- ✅ 🖱️ **Immediate-mode GUI demo** (`jvre.demo`, built ON L2 -- a worked example, not a feature; the hot/active-ID lesson).
- ✅ 📖 **Docs + API audit** — user guides in `docs/` (+ the [[Public API surface]] contract); the public `jvre.core` surface tightened for the 1.0 freeze.
- ✅ 🏁 **jvre 1.0.0 RELEASED on Maven Central** (`io.github.milquetoad:jvre:1.0.0`) — GPG-signed, cross-platform CI, the project's stated finish line. **Beyond 1.0:** built out toward fully-fledged ([[Roadmap]] Phases 3-4).

#jvre #moc
