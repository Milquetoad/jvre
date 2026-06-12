# Uniform Buffers and Descriptor Sets

The data tier above [[Push Constants]] (2026-06-12): a **uniform buffer** (UBO) is a real [[Vertex Buffers and GPU Memory|Buffer]] the shader reads as a structured block -- bigger, persistent, *bound* rather than pushed. The binding goes through Vulkan's **descriptor machinery**, the biggest remaining concept cluster -- and the one textures ride on too. Demo: the quad now **orbits** (UBO matrix) while **pulsing** (fragment push constant) -- both tiers in one draw.

![[orbiting-quad.png]]

## The four objects (and which is which)

| Object | What it is | Lives in jvre |
|---|---|---|
| `VkDescriptorSetLayout` | the **shape**: "binding 0 = one uniform buffer, vertex stage" -- a schema, no resources | `Pipeline` (referenced by the pipeline layout) |
| `VkDescriptorPool` | arena allocator for sets (destroying it frees them all -- same pattern as the command pool) | `Renderer` |
| `VkDescriptorSet` | an **instance** of the shape: an actual pointer table, written to point at real buffers | `Renderer`, one per frame in flight |
| the UBO itself | a plain `Buffer` with `UNIFORM_BUFFER` usage | `Renderer`, one per frame in flight |

The contract has the same three-way shape as push constants, one level up: **shader** `layout(binding = 0) uniform ...` <-> **set layout** binding 0 <-> **the set** bound at record time. Mismatch any pair -> validation (or garbage).

## The pattern that makes it cheap: write once, rewrite contents

The sets are pointed at their buffers **once**, at startup (`vkUpdateDescriptorSets`). Per frame, only two cheap things happen: the slot's UBO **contents** are rewritten (host-visible + coherent -- staging would just add a copy for data that changes every frame), and `vkCmdBindDescriptorSets` selects the slot's set. The pointer tables themselves never change. This "descriptors are static, data flows through them" shape is the norm in real renderers.

## Why one UBO + set per frame in flight

Same reasoning as every per-slot resource ([[Frames in Flight]]): frame N+1's CPU write must not stomp the UBO frame N's GPU is still reading. The slot's fence guards the handoff -- `drawFrame` waits it *before* rewriting the slot's UBO.

## std140 -- the classic footgun (noted, not yet hit)

Uniform blocks follow **std140 layout rules**: a `vec3` pads to 16 bytes, arrays stride in 16-byte units, etc. -- the CPU-side byte layout must match or fields silently read garbage. jvre's block is a single `mat4` (4 vec4 columns, 64 bytes, no traps) on purpose. The matrix is **column-major** (GLSL's convention) -- the hand-derived `transformMatrix` in `Renderer` documents its columns.

## Push constants vs uniform buffers (the decision table)

| | push constants | uniform buffer |
|---|---|---|
| size | >= 128 bytes guaranteed | 64 KB+ guaranteed (`maxUniformBufferRange`) |
| plumbing | none (in the command stream) | layout + pool + set + buffer |
| update | per recording, free | rewrite buffer contents (per-slot) |
| shared across draws | re-pushed per recording | bind once, many draws read it |
| use for | object index, time, tiny per-draw data | transforms, camera/view data, material params, anything structured |

jvre keeps both alive side by side: matrix in the UBO (vertex stage), time in a 4-byte push constant (**fragment** stage -- note `stageFlags` moved with it; ranges must say where the shader consumes them).

## What this unlocks
**Textures** are "just" another descriptor type (`COMBINED_IMAGE_SAMPLER` instead of `UNIFORM_BUFFER`) in the same layout/pool/set machinery -- plus the image-side work (VkImage, samplers, layout transitions we know from [[Pipeline Barriers]]). The descriptor half of that milestone is now learned. Later shortcut to evaluate: `VK_KHR_push_descriptor` (core in 1.4, available as an extension on our 1.3 floor) skips pools/sets for simple cases.

#vulkan #descriptors #uniforms #concept
