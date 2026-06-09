# Progress Log

Reverse-chronological diary. Newest at top.

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
