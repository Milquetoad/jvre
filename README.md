# jvre — Java Vulkan Rendering Engine

[![CI](https://github.com/Milquetoad/jvre/actions/workflows/ci.yml/badge.svg)](https://github.com/Milquetoad/jvre/actions/workflows/ci.yml)

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
| GPU + driver | Vulkan 1.3 | Dynamic rendering (the render path) is core in 1.3; any vendor, a discrete GPU is preferred automatically |
| Vulkan SDK | 1.3+ | **Optional** — only for the validation layers during development. Not needed to *build*: shaders compile via the bundled shaderc, no SDK required. |

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

## Using jvre as a library (early)

> ⚠️ Pre-1.0: the API is unstable and versions may break. Published via [JitPack](https://jitpack.io) for now; Maven Central is planned at 1.0.

```gradle
repositories {
    mavenCentral()
    maven { url 'https://jitpack.io' }
}

dependencies {
    implementation 'com.github.Milquetoad:jvre:v0.1.0'

    // jvre does NOT bundle platform natives -- you choose them for your OS.
    // Add the natives classifier for the LWJGL modules jvre uses:
    def lwjgl = '3.3.4'
    def natives = 'natives-windows' // or natives-linux / natives-macos / -arm64
    runtimeOnly("org.lwjgl:lwjgl:$lwjgl:$natives")
    runtimeOnly("org.lwjgl:lwjgl-glfw:$lwjgl:$natives")
    runtimeOnly("org.lwjgl:lwjgl-vma:$lwjgl:$natives")
    runtimeOnly("org.lwjgl:lwjgl-shaderc:$lwjgl:$natives")
    runtimeOnly("org.lwjgl:lwjgl-spvc:$lwjgl:$natives")
    runtimeOnly("org.lwjgl:lwjgl-stb:$lwjgl:$natives")
}
```

The LWJGL libraries themselves arrive transitively through jvre's POM; only the per-OS **natives** are yours to pick (the standard LWJGL consumer pattern). A future convenience may bundle these.

## Cross-platform

The code is OS-agnostic by design: windowing and surface creation go through GLFW, which picks the right platform call on each OS. The LWJGL **natives** classifier is auto-detected from the build host (`build.gradle`), so no edit is needed to build on Windows, Linux, or macOS. Windows and Linux are first-class (both run in CI); macOS additionally needs MoltenVK and the `-XstartOnFirstThread` JVM flag.

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
- [x] Textures — images + samplers + `COMBINED_IMAGE_SAMPLER` descriptors
- [x] 3D + depth buffer — perspective MVP, depth test/write, back-face culling
- [x] **MSAA** 🧊 — a tumbling, depth-tested, 4×-antialiased cube (multisampled color + depth, resolve built into dynamic rendering)
- [x] **VMA** — VMA-managed memory (intent-over-flags); standing allocation advisories closed

🧊 **Milestone reached: textures → 3D + depth → MSAA complete.**

Then the two altitudes of the [layered API](#) took shape:

- [x] **`ShaderEffect`** ✨ — the Shadertoy altitude: runtime-compiled (shaderc) user fragment shaders over a fullscreen triangle, named builtin uniforms, a SPIRV-Cross effect-contract guard
- [x] **L2 `Renderer2D`** 🎨 — the "just draw" altitude (v1 surface complete): a per-frame vertex arena + shape pipeline; **fills** (rect, circle, ellipse, triangle, quad, rounded-rect), **strokes** (line, rect, circle/ellipse, triangle/quad with miter joins — CPU-triangulated, no `wideLines`), an **SDF render path** (analytic circle + rounded-box, one batch, draw order preserved), **`image`** (textured quads + multi-texture flush-on-switch batching), and **`text`** (single-channel **SDF glyphs** via stb_truetype, built-in DejaVu Sans, crisp at any size from one bake)

After that: a self-built GUI, more 3D, and eventually ray/path tracing.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Note the contributor license agreement requirement, which keeps dual-licensing options open.

## License

Licensed under the **GNU Affero General Public License v3.0** (AGPL-3.0). See [LICENSE](LICENSE).

In short: you may use, study, modify, and share this software freely, but derivative works — **including software you make available to others over a network** — must also be released under the AGPL. For use under different terms, contact the author about a commercial license.

Copyright © 2026 Peder Godal.
