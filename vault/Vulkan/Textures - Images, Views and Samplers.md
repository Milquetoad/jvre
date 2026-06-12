# Textures -- Images, Views and Samplers

The milestone where a **picture** reaches the screen: a generated checkerboard sampled onto the orbiting quad. Builds directly on [[Uniform Buffers and Descriptor Sets|the descriptor machinery]] (the new descriptor is a second *type*, not a new idea) and reuses [[Pipeline Barriers]]/[[Synchronization2]] (the new work is new *layouts*, not a new mechanism). Done in 6 small beats; verified on the RTX 4090, validation clean but for the known VMA advisory (now including the 256x256 image + its staging buffer).

## Why an image is not a 2D buffer

A [[Vertex Buffers and GPU Memory|Buffer]] is a flat, row-major span of bytes. A `VkImage` differs in three ways, all in service of the GPU's texture-sampling hardware:

1. **Tiling.** Sampling reads little 2D neighborhoods (a bilinear tap touches a 2x2 block). So GPUs store textures in a driver-private *swizzled* layout (`VK_IMAGE_TILING_OPTIMAL`) where 2D-adjacent texels are memory-adjacent -- cache-friendly, but the byte order is secret and **the CPU cannot meaningfully write to it**. That single fact forces the staging upload below.
2. **Layout.** The optimal byte arrangement depends on *use* (being copied into vs. sampled vs. rendered into), so an image carries a **current layout** transitioned with image-memory barriers. A sampled texture's path: `UNDEFINED -> TRANSFER_DST_OPTIMAL -> SHADER_READ_ONLY_OPTIMAL`.
3. **It describes a picture, not a size:** format, extent (w x h x depth), mip levels, array layers, sample count -- none of which a buffer has.

The *memory* half is identical to Buffer (requirements -> find memory type -> allocate -> bind), so the memory-type hunt was lifted to `Device.findMemoryType` and shared.

## Filling it: staging + two layout transitions

Because the CPU can't write OPTIMAL-tiled VRAM, pixels take the same two hops as a device-local buffer, but the second hop **re-tiles**:

```
CPU writes -> staging buffer (HOST_VISIBLE, TRANSFER_SRC, linear)
GPU copies -> image          (DEVICE_LOCAL, TRANSFER_DST|SAMPLED, tiled)
```

recorded as ONE one-shot command: **barrier (UNDEFINED -> TRANSFER_DST) -> `vkCmdCopyBufferToImage` -> barrier (TRANSFER_DST -> SHADER_READ_ONLY)**.

The key idea (same as the swapchain barriers): each barrier's `srcStage/srcAccess -> dstStage/dstAccess` is a **dependency contract**, not just a layout relabel.
- Transition 1: `src = NONE` (nothing ran before) -> `dst = COPY, TRANSFER_WRITE` = *the copy's writes wait until the image is in TRANSFER_DST layout.*
- Transition 2: `src = COPY, TRANSFER_WRITE` -> `dst = FRAGMENT_SHADER, SHADER_READ` = *make the copy finished-and-visible before any fragment shader samples it.* This is what makes the texture safe to sample in every later frame, across submits.

`bufferRowLength/Height = 0` means "tightly packed" -- the driver derives the source stride from `imageExtent`.

## Reading it: view + sampler (two objects a shader samples THROUGH)

A shader can't sample a raw `VkImage`. It samples through:
- **`VkImageView`** -- the same typed "lens" as the [[Image Views|swapchain image views]]: which mip/layer range, how to read the format.
- **`VkSampler`** -- the fixed-function unit that turns a *coordinate* into a *color*. This is where **pixel-art crispness is decided**: `magFilter/minFilter = VK_FILTER_NEAREST` takes the single closest texel (hard pixel edges) vs. `LINEAR` which bilerps the 4 nearest and smears the grid. `addressMode = CLAMP_TO_EDGE` (sprite default; `REPEAT` tiles). Anisotropy left **off** -- it needs a device feature, does nothing for NEAREST, and is meaningless on a flat-facing quad (see [[Game-Engine Capabilities (planned)]] for the "offer it, don't enable speculatively" decision).

A sampler is logically independent of any one image (one sampler serves many textures); `Texture` owns both for now -- a hoist candidate when batching/many-textures arrives.

**sRGB note:** the image format is `R8G8B8A8_SRGB`, so the GPU *linearizes texels on sample* -- correct color math, consistent with the sRGB swapchain. The artist's bytes are sRGB; shading wants linear; the format does the conversion for free.

## Wiring it to the shader: a second descriptor TYPE

The [[Uniform Buffers and Descriptor Sets|three-way contract]] (shader binding <-> set-layout binding <-> the bound set's write) now spans both tiers:
- **Set layout** (in `Pipeline`) gains `binding 1 = COMBINED_IMAGE_SAMPLER, fragment stage`. That type bundles an image view + a sampler into one descriptor -- GLSL's `sampler2D`. (Vulkan also offers separate sampled-image + sampler descriptors; combined is the common case.)
- **Pool** (in `Renderer`) must size **each descriptor type** it hands out: a `UNIFORM_BUFFER` slot *and* a `COMBINED_IMAGE_SAMPLER` slot per frame in flight. `maxSets` stays one set per frame (each set now holds both bindings).
- **Writes:** each set writes binding 0 (its per-frame UBO) and binding 1 (the texture's `view()` + `sampler()`, with `imageLayout = SHADER_READ_ONLY_OPTIMAL`). The UBO is per-frame because its *contents* change; the texture is the **same image for every set** (one picture), so all sets point at it.

## Sampling it: UV attribute + `texture()`

The quad's vertex layout grew from `[x y | r g b]` (5 floats) to `[x y | r g b | u v]` (7 floats): stride 28, a third attribute (`location 2`, vec2 UV at offset 20). UVs use Vulkan's **top-left texture origin** (0,0 = top-left texel), tracking the corners directly. The vertex shader passes `inUV -> fragUV` (hardware-interpolated); the fragment shader declares `layout(binding = 1) uniform sampler2D tex;` and does `texture(tex, fragUV)`. All four data paths are now alive in one draw: UBO matrix (orbit/spin), push constant (pulse), vertex color (interpolated, available for tint), and the sampled texture.

## Engineering notes

- **`Commands.oneShot(device, pool, lambda)`** -- the allocate/record/submit/wait/free scaffolding was lifted out of `Buffer.copy` once `Texture` needed the identical pattern. Both callers now share it.
- **`Device.findMemoryType`** -- the memory-type hunt moved to where the physical device (which advertises the types) lives; `Buffer` and `Texture` both call it.

## Alpha blending (the transparency seam)

Sampling alone draws every texel opaquely; **transparency** needs blending turned on in the pipeline (it was `REPLACE` through the first 5 beats). After the fragment shader, the hardware combines its output with the framebuffer per channel: `result = src*srcFactor <op> dst*dstFactor`. The classic *source-over-destination* picks `srcFactor = SRC_ALPHA`, `dstFactor = ONE_MINUS_SRC_ALPHA`, `op = ADD`:

```
color = src.rgb*src.a + dst.rgb*(1 - src.a)
```

so `alpha 0` leaves the background untouched and `alpha 1` replaces it -- a sprite's transparent background. Demonstrated by making half the checkerboard cells `alpha = 0`: magenta squares float over the clear color.

Freebie from the sRGB choice: an sRGB attachment blends in **linear** space and re-encodes on store (gamma-correct for free); the alpha channel of an `_SRGB` format is itself linear, so `src.a` is raw.

**Straight vs premultiplied:** we use straight alpha (`SRC_ALPHA`). Fine here because NEAREST + binary texture alpha means no partial-alpha edge texels to fringe. Filtered/mipmapped sprites get dark halos with straight alpha and want **premultiplied** (texture rgb pre-scaled by a, blend `srcFactor = ONE`). A knob for the eventual sprite layer -- noted, not built.

## Seams for the layers above (the point, per the capabilities note)

Textures is where the [[Game-Engine Capabilities (planned)|mechanism/policy seams]] start mattering. Status after this milestone:
- **NEAREST sampling** -- done (hardcoded; becomes a `Texture.create(..., filter)` param when generalized).
- **UV sub-rectangle** (the seam that makes sprite-sheet animation possible from above) -- the UV *machinery* exists, but UVs are currently the whole texture [0,1]. Addressing part of an atlas is a thin change on top.
- **Alpha blending** (transparency seam) -- **done** (beat 6, section above): src-over-dst alpha; half the checker cells made transparent to prove it. Straight alpha for now; premultiplied is the filtered-sprite knob.

## Next

**3D + depth** (the substrate is already 3D-capable -- a `z` + a perspective matrix + a depth buffer; see [[Game-Engine Capabilities (planned)]]), then **MSAA** (needs the own-`VkImage` machinery this milestone just built, plus a multisampled depth buffer). The VMA milestone looms larger now -- every image is another single allocation the best-practices layer flags.

#vulkan #textures #images #samplers #descriptors
