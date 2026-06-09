# Windowing — GLFW and the Surface

## Vulkan does NOT make windows
Vulkan is a **pure GPU API** — it knows about devices, queues, memory, images, shaders, and *nothing* about windows, the OS, or input. This is deliberate: windows are OS-specific (Win32 / X11 / Wayland / Cocoa), and Vulkan stays **OS-agnostic**. So windowing is a *separate* job.

## Who makes the window: GLFW → the OS
`glfwCreateWindow(...)` chain:
```
our code → glfwCreateWindow() → Win32 CreateWindowEx() → OS creates window, returns HWND → GLFW wraps it → we get a `long` handle
```
The **OS owns and draws** the window (title bar, borders, buttons, compositing via DWM). **GLFW** is a thin cross-platform layer over the native windowing API — the same role [[LWJGL]] plays for Vulkan, but for windowing. Same `glfwCreateWindow` call speaks X11/Wayland on Linux.

## Division of labor
| Library | Responsibility |
|---|---|
| **GLFW** | create/manage window, receive input + close events (OS side) |
| **Vulkan** | talk to the GPU and render (hardware side) |

## Key subtlety: instance and window are initially STRANGERS
Right after [[Vulkan Instance]] creation, the `VkInstance` and the window have **no connection**. That's why the window is blank — nothing renders into it; Vulkan doesn't know it exists.

They're introduced later via the **surface** (`VkSurfaceKHR`), the one object belonging to both worlds:
```
GLFW window ──[ VkSurfaceKHR ]── Vulkan
```
This is exactly what the instance extensions we enabled are for:
- `VK_KHR_surface` — generic "Vulkan ↔ a surface" capability.
- `VK_KHR_win32_surface` — Windows-specific glue (surface ↔ Win32 `HWND`).

Created with `glfwCreateWindowSurface(...)`. Then the **swapchain** provides the images actually shown in the window. See [[Roadmap - Clear to Color]].

## Implemented ✅ — `createSurface()` in `jvre.Main`
- The handle is a plain `long` (an opaque `VkSurfaceKHR`), held for the program's lifetime — not stack-scoped.
- `glfwCreateWindowSurface(instance, window, null, pSurface)` returns a **Vulkan result code** (int), and *writes the handle* into the `pSurface` out-param — not its return value. Check `== VK_SUCCESS`.
- GLFW picks the platform call for us (`vkCreateWin32SurfaceKHR` here); no per-OS code on our side.
- **Ownership:** the surface belongs to the *instance*, so `vkDestroySurfaceKHR(instance, surface, null)` runs **before** `vkDestroyInstance` (child before parent). Forgetting this = a leak the validation layer catches.
- Clean run printed no validation output — the silent success signal.

## GLFW is NOT a GUI toolkit
GLFW does windows + input + events + contexts only. **No widgets** (no buttons, text fields, menus, layout). It gives the raw *ingredients* (mouse pos, clicks, typed chars) but you build/obtain the GUI elsewhere. See [[GUI Options]].

## The event loop
`glfwPollEvents()` (in `mainLoop`) drains the OS message queue (Win32 message loop under the hood). Clicking the X posts a close event → GLFW sets a flag → `glfwWindowShouldClose()` returns true. Stop polling and the OS marks the app "Not Responding."

#vulkan #concept #windowing
