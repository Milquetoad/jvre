# VMA -- Vulkan Memory Allocator

The milestone that retired jvre's deliberate learning debt: one `vkAllocateMemory` per resource. The standing best-practices advisories -- accruing since the first vertex buffer, six by the cube -- went **6 -> 1 -> 0**, beat by beat. Verified on the RTX 4090: stderr fully silent, clean shutdown.

## Why one-allocation-per-resource had to die

It was the right call *for learning* (the [[Vertex Buffers and GPU Memory|memory-type hunt]] was hand-rolled twice and understood), but wrong for an engine, for hard reasons:

1. **Drivers cap total allocations.** `maxMemoryAllocationCount` may be as low as **4096**. A real scene has tens of thousands of buffers/textures -- one-each doesn't just warn, it eventually *fails*.
2. **`vkAllocateMemory` is slow** (a driver round trip); engines amortize it.
3. The real pattern is **sub-allocation**: few big `VkDeviceMemory` blocks, many resources placed inside at offsets. Doing that well -- alignment, the buffer-image granularity rule, free lists, defragmentation -- is a library-sized problem.

**VMA** (AMD's Vulkan Memory Allocator) is the industry-standard answer; LWJGL ships bindings (`lwjgl-vma` -- a real native library, unlike `lwjgl-vulkan`).

## The integration

- **[[Logical Device and Queues|Device]] owns the `VmaAllocator`** -- device-scoped (its blocks come from this `VkDevice`), created after the queues, destroyed *before* `vkDestroyDevice`. Fits the [[Device Selection and Cross-Platform (planned)|recreatable-context seam]]: GPU switch tears down allocator + device as one unit.
- **The function-pointer handshake** (`VmaVulkanFunctions.set(instance, device)`): VMA is C++ that calls Vulkan *itself*, and LWJGL loads Vulkan dynamically -- so we hand VMA the resolved pointers. `vulkanApiVersion = 1.3` (honest: device selection verifies it).
- **`vmaDestroyAllocator` asserts if any VMA resource is still alive** -- every clean shutdown is a free leak check.

## The conceptual shift: intent over flags

The manual path was *create -> query requirements -> hunt a memory type by property flags -> allocate -> bind*. With VMA that collapses to one call (`vmaCreateBuffer` / `vmaCreateImage`), and callers stop naming memory-property flags and start declaring **intent**:

| Caller says | VMA hears | Used by |
|---|---|---|
| `AUTO` + `HOST_ACCESS_SEQUENTIAL_WRITE` | "CPU streams data in, never reads back" -> host-visible, write-combined OK | staging buffers, per-frame UBOs (`Buffer`'s `hostVisible = true`) |
| `AUTO` (no host flags) | GPU-only residency, prefer VRAM | vertex/index buffers, texture images |
| `AUTO` + `DEDICATED_MEMORY` | own allocation, not a block slot | the depth image (below) |

`Device.findMemoryType` retired with zero callers -- the *concept* (types x heaps, the typeFilter-AND-properties test, why HOST_VISIBLE and DEVICE_LOCAL split on discrete GPUs) stays load-bearing in [[Vertex Buffers and GPU Memory]] and git history; VMA just runs the same hunt internally, better.

## Mapping and the flush idiom

Uploads go `vmaMapMemory` -> write -> `vmaUnmapMemory` -> `vmaFlushAllocation`. The flush is a **no-op on coherent memory** (the desktop norm) and the required flush anywhere else -- correctness by default. The old approach dodged the bug class by *requiring* HOST_COHERENT; the new one is correct on any memory VMA picks. (LWJGL binds `vmaFlushAllocation` as `void` -- nothing to `Vk.check`.)

## Dedicated allocations: the attachment exception

Sub-allocation is for the many-small-resources case. **Full-screen attachments are the textbook opposite**: large (the depth image is ~1.9 MB at 800x600, growing with resolution), long-lived, recreated on resize, and drivers can optimize dedicated attachment memory. So the [[3D and the Depth Buffer|depth image]] passes `VMA_ALLOCATION_CREATE_DEDICATED_MEMORY_BIT`; small textures stay pooled. The future MSAA color target is the same case.

## What did NOT change

The resources' *Vulkan* halves are untouched: `VkBufferCreateInfo`/`VkImageCreateInfo`, usage flags, sharing mode, staging uploads, layout transitions, views, samplers, descriptors. VMA replaces only the memory half -- create/place/bind. The cube renders pixel-identically; the migration is invisible by design.

## Empirical arc (the satisfying part)

| Step | startup advisories |
|---|---|
| before (manual allocations) | 6 |
| Buffer migrated | 1 (only the texture image left) |
| Texture + depth migrated | **0 -- stderr silent** |

#vulkan #memory #vma #allocation
