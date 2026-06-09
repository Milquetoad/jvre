# GLFW

A small, cross-platform **C library for windowing + input + contexts**. Bundled in [[LWJGL]] as the `lwjgl-glfw` module. It's the **OS-facing** half of our stack (Vulkan is the hardware-facing half).

## Scope — what GLFW does
- **Windows** — create/resize/fullscreen, multiple monitors.
- **Input** — keyboard, mouse, scroll, cursor position, gamepads.
- **Events** — close requested, focus, resize callbacks.
- **Contexts** — OpenGL/Vulkan setup hooks.
- Extras — clipboard text, custom cursors, timers.

## What GLFW is NOT
- **Not a GUI toolkit** — no buttons/widgets/layout (see [[GUI Options]], [[Self-Built GUI (planned)]]).
- **Not a renderer** — it never draws pixels; it just gives you a window + an input stream.

## How it works
Thin cross-platform wrapper over the native windowing API — Win32 on Windows, X11/Wayland on Linux, Cocoa on macOS. Same `glfwCreateWindow` call, different OS underneath. The **OS** actually creates/owns/draws the window. (Same role [[LWJGL]] plays for Vulkan, but for windowing.) See [[Windowing - GLFW and the Surface]].

## Calls we use in jvre
| Call | Purpose |
|---|---|
| `glfwInit()` | boot GLFW |
| `glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API)` | no OpenGL context — we attach Vulkan |
| `glfwWindowHint(GLFW_RESIZABLE, GLFW_FALSE)` | no resizing yet (swapchain not built) |
| `glfwCreateWindow(...)` | create window → returns a `long` handle |
| `glfwVulkanSupported()` | is a Vulkan loader present? |
| `glfwGetRequiredInstanceExtensions()` | the surface extensions the instance needs |
| `glfwCreateWindowSurface(...)` | *(upcoming)* make the `VkSurfaceKHR` bridge |
| `glfwWindowShouldClose()` / `glfwPollEvents()` | the event loop |
| `glfwDestroyWindow()` / `glfwTerminate()` | teardown |

## Why a separate library at all
Vulkan is deliberately OS-agnostic and does no windowing. Windows are OS-specific. So you need either per-OS platform code or a library like GLFW (or SDL) to abstract it. LWJGL bundles GLFW, so we get it for free.

#concept #glfw #windowing
