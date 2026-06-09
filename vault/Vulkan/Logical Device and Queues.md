# Logical Device and Queues

The **logical device** (`VkDevice`) is our own configured *connection* to the GPU we [[Physical Device and Queue Families|selected]]. The physical device is read-only ("here's a GPU and its capabilities"); the logical device is what we **create**, **own**, and **destroy**, and through which *all real work* flows — swapchains, pipelines, buffers, command submission. You can even create multiple logical devices from one physical device.

## The create recipe (`VkDeviceCreateInfo`)
Three ingredients:

1. **Queues to create** (`pQueueCreateInfos`). Queues are the typed lanes we submit work into; we don't talk to the GPU directly. Key rule: **queues are born with the device** — you declare them up front (family + how many), you don't create them later. We ask for one queue from the graphics family and one from the present family.
   - **Dedup gotcha:** graphics and present [[Physical Device and Queue Families|may be the same family]] (on the Intel iGPU they are). Vulkan forbids listing the same family index twice, so we collapse them through a `Set<Integer>`.
   - The number of queues per family is inferred from the **length of the `pQueuePriorities`** buffer (one `1.0f` => one queue). Priority is a `[0,1]` scheduling hint, required even when moot.
2. **Features to enable** (`pEnabledFeatures`). Opt-in even if the GPU supports them; we need none yet, so an empty `VkPhysicalDeviceFeatures`.
3. **Device extensions to enable** (`ppEnabledExtensionNames`). Here: **`VK_KHR_swapchain`** — the extension that lets us present images to the [[Windowing - GLFW and the Surface|surface]]. Needed for the next milestone.

After `vkCreateDevice`, queues already exist — we only *retrieve handles* with `vkGetDeviceQueue(device, familyIndex, 0, ...)` (index 0 = the first queue we asked for).

## Device extensions vs instance extensions
Extensions exist at two scopes. Instance extensions (surface, debug-utils) are global; **device extensions live on a specific GPU**, enumerated per physical device with `vkEnumerateDeviceExtensionProperties` (same two-call idiom as [[Validation Layer and Debug Messenger|instance-layer enumeration]]). Because we now *need* swapchain, we made it a **hard requirement during GPU selection** — a device that can't present is unusable — and enable it at device creation.

## War story: device layers are forbidden now
First run threw a validation **error**: `vkCreateDevice(): enabledLayerCount is 1 (not zero)`. Old tutorials say "set device-level validation layers for backwards-compat." That advice is stale: device layers are a Vulkan 1.0 legacy that never worked, the **instance** layer already validates device calls, and current validation requires `enabledLayerCount == 0` (VUID-VkDeviceCreateInfo-enabledLayerCount-12384). Fix: leave `ppEnabledLayerNames` unset. Nice proof the [[Validation Layer and Debug Messenger|safety net]] earns its keep.

## Cleanup order
`vkDestroyDevice` goes **first** in `cleanup()` — it's the most dependent object (everything device-level hangs off it), destroyed before the surface, debug messenger, and instance. Child before parent.

Next: [[Roadmap - Clear to Color|the swapchain]] — the chain of presentable images, built on this device + the surface.

#vulkan #device #queues
