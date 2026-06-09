# jvre — Java Vulkan Rendering Engine

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
| GPU + driver | Vulkan-capable | Any vendor; a discrete GPU is preferred automatically |

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
src/main/java/jvre/   Engine + demo source (currently a single linear bootstrap in Main.java)
build.gradle          Build config (LWJGL deps, JDK 21 toolchain, app entry point)
vault/                Obsidian learning vault — concepts, Vulkan notes, progress log
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
- [ ] Render pass
- [ ] Framebuffers
- [ ] Command pool + buffers
- [ ] Synchronization + render loop → **clear to a color**

After that: refactor the linear bootstrap into reusable components, then 2D rendering, text, a self-built GUI, 3D, and eventually ray/path tracing.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Note the contributor license agreement requirement, which keeps dual-licensing options open.

## License

Licensed under the **GNU Affero General Public License v3.0** (AGPL-3.0). See [LICENSE](LICENSE).

In short: you may use, study, modify, and share this software freely, but derivative works — **including software you make available to others over a network** — must also be released under the AGPL. For use under different terms, contact the author about a commercial license.

Copyright © 2026 Peder Godal.
