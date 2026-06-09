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

**5. Looking ahead — optional, for the curious**
- [[Shaders - GLSL and SPIR-V]], [[GUI Options]] / [[Self-Built GUI (planned)]], [[Ray Tracing and Path Tracing (future)]], [[Device Selection and Cross-Platform (planned)]]

> Prefer a story to an index? [[Progress Log]] is the dated, diary-form version of this same journey.

## Map of Content

### Project
- [[Toolchain Setup]] — what's installed and why
- [[Progress Log]] — dated diary of what we've done
- [[API Vision - Layered Altitudes]] — the engine's north star (high-level + shader-art on one stack)
- [[Device Selection and Cross-Platform (planned)]] — flexible GPU choice + OS-agnostic (post-MVP)

### Foundations (Java ↔ native)
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

## Status
- ✅ Toolchain verified (smoke test passes)
- ✅ Vulkan SDK installed (`glslc` works)
- ✅ [[Vulkan Instance]] created & running
- ✅ [[Validation Layer and Debug Messenger]] (safety net, demonstrated catching a bug)
- ✅ Surface (`VkSurfaceKHR`) created — window connected to Vulkan
- ✅ [[Physical Device and Queue Families]] — picked the RTX 4090 (graphics + present, scored)
- ✅ [[Logical Device and Queues]] — `VkDevice` created, graphics + present queues retrieved, swapchain extension enabled
- ⏭️ Next: **swapchain** — choose format/present-mode/extent, create the presentable images

#jvre #moc
