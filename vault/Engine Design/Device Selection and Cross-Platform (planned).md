# Device Selection & Cross-Platform (planned)

Two standing architectural constraints, noted early so the elementaries are shaped to absorb them. **MVP first**, but don't build anything that forecloses these.

## 1. Flexible GPU selection
Not the tutorial's hard-coded "first discrete GPU." Three capabilities:

1. **Default policy** — a *scoring* function (prefer discrete > integrated; **require** graphics + present support; optionally weigh VRAM/features). Best score wins.
2. **Explicit override** — caller forces a GPU by name or index, bypassing the score. (Dual-GPU dev machine, headless test rigs, power users.)
3. **Runtime switching** — change GPU while running, like a game's graphics-options menu.

### Why #3 drives architecture
Almost everything in Vulkan hangs off the chosen GPU: logical device, queues, swapchain, render pass, pipelines, command buffers. Switching GPUs = tear all of that down and rebuild it.

**Seam:** keep the GPU-agnostic part (instance + [[Windowing - GLFW and the Surface|surface]]) **stable and long-lived**; put everything device-dependent inside ONE recreatable unit — a **"device context" / renderer** — that can be disposed and rebuilt. This is the *same* machinery needed for **window-resize swapchain recreation**, so building the seam once buys both features. Structure the elementaries around a disposable device-and-below layer. See [[API Vision - Layered Altitudes]], [[Roadmap - Clear to Color]].

## 2. OS-agnostic (Windows + Linux required; macOS nice-to-have)
The chosen stack is cross-platform **by construction**, and the code so far is **already portable**:
- `glfwGetRequiredInstanceExtensions()` returns the right surface extensions per OS (`VK_KHR_win32_surface` on Windows, `VK_KHR_xcb_surface`/`VK_KHR_wayland_surface` on Linux). We never hard-code the Windows one.
- `glfwCreateWindowSurface()` calls the correct platform surface function internally — *the reason we used it* over `vkCreateWin32SurfaceKHR`.

Per-OS differences are **build config + launch flags, not branching logic**:

| Concern | Windows | Linux | macOS |
|---|---|---|---|
| LWJGL natives | `natives-windows` | `natives-linux` | `natives-macos` / `-arm64` |
| Vulkan driver | vendor ICD | vendor ICD (Mesa/NVIDIA) | **no native Vulkan** → MoltenVK (Vulkan→Metal) |
| Threading | — | — | GLFW on main thread → `-XstartOnFirstThread` |
| Display server | — | X11 / Wayland (GLFW handles) | — |

**Takeaway:** Windows + Linux is essentially free — auto-detect the host OS in `build.gradle` to pick the LWJGL natives classifier. macOS is the only one with a real catch (MoltenVK + main-thread flag) → matches the user's priority (defer it).

#engine-design #planned #cross-platform #gpu
