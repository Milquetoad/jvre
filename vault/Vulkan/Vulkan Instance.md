# Vulkan Instance

> Status: ✅ **coded & running**, now **with validation** ([[Validation Layer and Debug Messenger]]). In `jvre.Main` (`createInstance()`).

## Implementation notes (what we actually wrote)
- App skeleton: `initWindow() -> initVulkan() -> mainLoop() -> cleanup()` — kept stable for all future steps.
- `initWindow`: `glfwInit`; hint `GLFW_CLIENT_API = GLFW_NO_API` (no OpenGL — we attach Vulkan ourselves); `GLFW_RESIZABLE = FALSE` for now.
- Structs built with **`calloc`** (zeroes memory → safe defaults for `pNext`/`flags`) on the [[MemoryStack]].
- Output pattern: `vkCreateInstance(createInfo, null, pInstance)` writes the handle into `pInstance`; the middle `null` = default CPU allocator. Then `new VkInstance(pInstance.get(0), createInfo)`.
- `cleanup`: `vkDestroyInstance` (Vulkan has no GC) — reverse order of creation.
- ⚠️ Required `Configuration.STACK_SIZE.set(512)` at startup — see the gotcha in [[MemoryStack]].

The **root** Vulkan object (`VkInstance`) — the connection between our application and the Vulkan library itself. **Before the instance there is no Vulkan**: you can't enumerate GPUs or create a device without it. It's the trunk the whole [[Roadmap - Clear to Color]] hangs off.

## The loader (why the instance exists)
`vulkan-1.dll` is **not** a driver — it's the **loader**, a thin Khronos shim. The real implementation is the GPU vendor's **ICD** (Installable Client Driver); this machine has NVIDIA's + AMD's. The loader routes calls to the right driver, with enabled **layers** stacked in between:

```
your code → LWJGL/JNI → loader (vulkan-1.dll) → [validation layer] → NVIDIA ICD → RTX 4090
```

Creating the instance configures that entry point. It's GPU-agnostic on purpose: it's the thing you use to *discover* the GPUs, so it must come first.

## What you configure (3 kinds of info)
1. **`VkApplicationInfo`** — app name/version, **engine** name/version (`"jvre"` lives here), and the **Vulkan API version** targeted. (Drivers sometimes read these names for per-app optimization profiles.)
2. **Instance-level extensions** — opt-in; core Vulkan is tiny. We need `VK_KHR_surface` + `VK_KHR_win32_surface` (present to a window) and `VK_EXT_debug_utils` (deliver validation messages).
3. **Layers** — interceptors in the call chain. `VK_LAYER_KHRONOS_validation` = the validation layer (the reason we installed the SDK first). See [[Vulkan Overview]].

## Instance-level vs device-level scope (important, recurring)
| Scope | Belongs to | Examples |
|---|---|---|
| **Instance-level** | global Vulkan session | enumerate GPUs, surface extensions, debug utils, validation layer |
| **Device-level** | a specific GPU | rendering, queues, `VK_KHR_swapchain` |

Enable instance extensions/layers at instance creation; device extensions at logical-device creation. (Classic bug: trying to enable `VK_KHR_swapchain` — a *device* extension — on the instance.)

## `sType` / `pNext` (in every Vulkan struct)
See [[Vulkan Struct Conventions]].

## Build steps (preview)
1. On the [[MemoryStack]], fill `VkApplicationInfo` (`sType`, `"jvre"` strings, API version).
2. Build extension-name list (GLFW's + debug utils) and layer-name list (validation).
3. Fill `VkInstanceCreateInfo` (`sType`, pointer to app info, the two lists).
4. `vkCreateInstance(...)` → receive the `VkInstance` handle.
5. Handle is **long-lived** → not on the stack; held for the program's lifetime, `vkDestroyInstance` last at shutdown (see stack-vs-heap in [[MemoryStack]]).

The canonical [[LWJGL]] pattern: *fill two native structs, pass to one C function, keep the handle.*

#vulkan
