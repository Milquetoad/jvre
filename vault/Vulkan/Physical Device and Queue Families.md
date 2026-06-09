# Physical Device & Queue Families

> Status: ✅ **coded & running** — `pickPhysicalDevice()` in `jvre.Main`. Picked the RTX 4090.

## Physical device = a real GPU you *select*, not create
A `VkPhysicalDevice` is a handle to an actual GPU the instance can see. You **enumerate and choose** one — you don't create it, and you don't destroy it (it's owned by the instance). The thing we *create* from it is the **logical device** (next step). This machine exposes two: RTX 4090 (discrete) + AMD integrated.

## Queue families — how you submit work
You never command a GPU directly. You **record** commands into command buffers and **submit** them to a **queue** (the conveyor belt to the GPU). GPUs have multiple internal engines, exposed as **queue families** — each a group of identical queues supporting a set of operations, advertised as capability flags:
- `VK_QUEUE_GRAPHICS_BIT` — draw/render
- `VK_QUEUE_COMPUTE_BIT` — compute shaders
- `VK_QUEUE_TRANSFER_BIT` — memory copies

### Present support is special — *query*, don't read a flag
"Can this family present to **our surface**?" is surface-specific, so it's **not** a queue flag. Ask per family:
```
vkGetPhysicalDeviceSurfaceSupportKHR(device, familyIndex, surface, pSupported)
```
This is the **first real payoff** of the [[Windowing - GLFW and the Surface|surface]].

### Graphics & present may be the same family
The spec doesn't guarantee one family does both, so track two indices (`graphicsFamily`, `presentFamily`) and allow them to coincide. We use `Integer` (nullable) not `int`, because **index 0 is valid** — null = "not found", not -1.

## What we built
1. `vkEnumeratePhysicalDevices` (two-call idiom) → list GPUs.
2. `findQueueFamilies(device)` → `vkGetPhysicalDeviceQueueFamilyProperties` + the present-support query. A GPU missing graphics *or* present is **unsuitable** → skipped.
3. `rateDevice(device)` → **scoring** policy: +1000 if `VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU`, + `maxImageDimension2D` as a gentle tiebreak. Highest suitable score wins.

Scoring (not a hard-coded "first discrete") is deliberate — it's the seam for the future flexible selection (explicit override + runtime switch). See [[Device Selection and Cross-Platform (planned)]].

## Instance-level vs device-level (recurring)
Enumerating/selecting the GPU is still done *through the instance*. But from here on we cross into **device-level** scope: the logical device, queues, and `VK_KHR_swapchain` all belong to the chosen GPU. See [[Vulkan Instance]], [[Roadmap - Clear to Color]].

#vulkan #concept
