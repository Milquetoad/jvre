# jvre — Java Vulkan Rendering Engine

![The first triangle: RGB-interpolated, on an orange clear](vault/assets/first-triangle.png)

A general-purpose rendering framework written **from scratch in Java on top of [Vulkan](https://www.vulkan.org/)** (via [LWJGL](https://www.lwjgl.org/)). The goal is twofold:

1. **Learn graphics from first principles** — understand every layer, from the Vulkan instance up to a full render loop, with no engine hiding the details.
2. **Grow into a reusable framework** — windowing, shaders, 2D and 3D rendering, and eventually the rendering backbone for a small game engine and for algorithm/CS visualizations.

> ⚠️ **Early days.** This is a learning project under active construction. The API is not stable. See the [roadmap](#roadmap).

## Vision

Two parallel use-cases on one engine:

- **High-level** — define a window and a set of things to render, and play with them *without writing shaders*.
- **Low-level** — Shadertoy-style shader illustration for when you *do* want to write shaders.

Longer-term: a self-built immediate-mode GUI, 3D rendering, and (reachable, not promised) compute and hardware-accelerated ray/path tracing.

## Requirements

| Tool | Version | Notes |
|---|---|---|
| JDK | 21 | Gradle toolchain will enforce this |
| Vulkan SDK | 1.3+ | Needed for the validation layers and `glslc`; install from [LunarG](https://vulkan.lunarg.com/) |
| GPU + driver | Vulkan 1.3 | Dynamic rendering (the render path) is core in 1.3; any vendor, a discrete GPU is preferred automatically |

LWJGL (Vulkan + GLFW bindings) is pulled in by Gradle — no manual install. The Vulkan **loader** itself (`vulkan-1.dll` / `libvulkan.so`) comes from your system / GPU driver, not from LWJGL.

## Build & run

The project ships a Gradle **wrapper**, so you don't need Gradle installed — just a JDK 21.

```bash
# Windows
./gradlew.bat run

# Linux / macOS
./gradlew run
```

You should see a window open and console output reporting each Vulkan bootstrap step (instance, surface, chosen GPU, ...). Close the window to exit.

## Cross-platform

The code is OS-agnostic by design: windowing and surface creation go through GLFW, which picks the right platform call on each OS. The only per-platform difference is the LWJGL **natives** classifier in `build.gradle` (`natives-windows` / `natives-linux` / `natives-macos`). Windows and Linux are first-class; macOS additionally needs MoltenVK and the `-XstartOnFirstThread` JVM flag.

## Project layout

```
src/main/java/jvre/        Demo entry point (Main.java -- wiring only)
src/main/java/jvre/core/   Engine elementaries: Window, Instance, Surface, Device, Swapchain, Buffer, Pipeline, Renderer
src/main/glsl/             GLSL shader sources (compiled to SPIR-V at build time by glslc)
build.gradle               Build config (LWJGL deps, JDK 21 toolchain, shader compilation)
vault/                     Obsidian learning vault — concepts, Vulkan notes, progress log
```

The [`vault/`](vault/) directory is a companion **Obsidian knowledge base** maintained alongside the code: every concept we cover gets a note, and `vault/Project/Progress Log.md` is a dated diary of progress. Start at `vault/Home.md`.

## Roadmap

Path to first pixels ("clear to color"):

- [x] Vulkan instance
- [x] Validation layer + debug messenger
- [x] Window surface
- [x] Physical device selection (queue families, present support, scoring)
- [x] Logical device + queues
- [x] Swapchain
- [x] Image views
- [x] Render pass + framebuffers *(since replaced — see below)*
- [x] Command pool + buffers
- [x] Synchronization + render loop → **clear to a color** ✅

🟠 **Milestone reached: the window clears to a solid color.** The full Vulkan bootstrap — instance through the render loop — runs end to end with the validation layers clean.

Current phase — refactor into reusable components + modernize:

- [x] Stable layer extracted: `Window`, `Instance`, `Surface`
- [x] Device context extracted: `Device` (selection + logical device + queues), `Swapchain` (+ image views)
- [x] Migrated to **dynamic rendering** (Vulkan 1.3) — render pass + framebuffers deleted; explicit pipeline barriers drive the image layout transitions
- [x] Migrated to **synchronization2** (Vulkan 1.3) — `vkQueueSubmit2` / `vkCmdPipelineBarrier2`; device selection verifies API version + feature support; sync-validation + best-practices layer checks enabled
- [x] `Renderer` coordinator — owns the device context; **resizable window** with swapchain recreation (incl. minimize); **2 frames in flight** with per-frame command recording
- [x] **First triangle** 🔺 — graphics `Pipeline` (dynamic viewport/scissor, dynamic-rendering format hookup) + GLSL shaders compiled to SPIR-V at build time (`glslc` Gradle task)
- [x] Vertex buffers — `Buffer` elementary (`VkBuffer` + `VkDeviceMemory`, memory-type selection); geometry as data, staged into device-local memory
- [x] Push constants 🌀 — the triangle **spins**: per-frame time + aspect pushed straight into the command buffer
- [x] Index buffers — the quad: unique vertices + UINT16 indices, `vkCmdDrawIndexed`
- [x] Uniform buffers + descriptor sets 🛰️ — the quad **orbits**: per-frame mat4 UBO through the layout/pool/set machinery; push constants move to the fragment stage (both tiers side by side)
- [ ] Textures — images + samplers + `COMBINED_IMAGE_SAMPLER` descriptors; then 3D + depth, then MSAA

After that: 2D rendering, text, a self-built GUI, 3D, and eventually ray/path tracing.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Note the contributor license agreement requirement, which keeps dual-licensing options open.

## License

Licensed under the **GNU Affero General Public License v3.0** (AGPL-3.0). See [LICENSE](LICENSE).

In short: you may use, study, modify, and share this software freely, but derivative works — **including software you make available to others over a network** — must also be released under the AGPL. For use under different terms, contact the author about a commercial license.

Copyright © 2026 Peder Godal.
