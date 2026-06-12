# MSAA -- Multisample Anti-Aliasing

The last item on the long-standing roadmap list (textures -> 3D + depth -> MSAA): smooth geometry silhouettes via 4x multisampling + resolve. Verified on the RTX 4090 (cube edges smooth, validation fully silent).

## The idea

Geometry edges alias because each pixel makes ONE binary covered-or-not decision. MSAA rasterizes **N coverage samples per pixel** while the fragment shader still runs ~once per pixel -- that is the multi- vs super-sampling distinction and why it's affordable: edge pixels get fractional coverage, interior pixels cost the same as before. At the end a **resolve** averages the N samples into the presentable single-sample image.

Structural consequence: the scene stops rendering into the swapchain image. It renders into an offscreen **multisampled color image**; the swapchain image demotes to *resolve destination*.

## The pieces (all machinery this project already had)

- **Sample count is QUERIED** ([[Swapchain]]), like the depth format: `framebufferColorSampleCounts & framebufferDepthSampleCounts`, take the best <= 4. (4x = most of 8x's visual win at half the bandwidth, universal on desktop.)
- **MSAA color image**: swapchain format, N samples, `COLOR_ATTACHMENT` usage, [[VMA - Vulkan Memory Allocator|VMA]] `DEDICATED_MEMORY` -- the second textbook dedicated attachment. Extent-sized, recreated with the chain. Multisampled images never have mips (spec rule).
- **The depth buffer went multisampled too** -- depth testing happens per SAMPLE, so a 1x depth buffer against a 4x color target is meaningless (and illegal). Same count, enforced by construction in [[3D and the Depth Buffer|Swapchain's depth creation]].
- **Pipeline**: `rasterizationSamples = N`, BAKED state -- the concrete reason the [[L2 Feature Set - Renderer2D|L2 spec]] pinned "AA is a creation-time option, never a runtime toggle" before this code existed.
- **The resolve is built into dynamic rendering**: the color attachment gets `resolveMode = AVERAGE`, `resolveImageView = ` the acquired swapchain view. It happens at `vkCmdEndRendering` -- no extra pass, no explicit `vkCmdResolveImage`.
- **storeOp = DONT_CARE on the MSAA image** (and depth): once resolved, per-sample data is garbage; storing is wasted bandwidth.

## The barrier (the depth lesson, applied proactively)

The MSAA color image is the SECOND one-image-shared-across-frames-in-flight resource (after the [[3D and the Depth Buffer|depth buffer]]) -- not per-frame, not rotating with the swapchain. So its per-frame `UNDEFINED -> COLOR_ATTACHMENT_OPTIMAL` transition must gate on the *previous* frame's color writes (`src = COLOR_ATTACHMENT_OUTPUT + COLOR_ATTACHMENT_WRITE`). Last time sync-validation caught this class of bug (the depth WAW hazard); this time it was written correctly up front and validation stayed silent -- the lesson generalized.

Gotcha logged: the recording reuses one barrier struct, mutated between calls. Barrier 2 (present transition) used to inherit the swapchain image from barrier 1; with barrier 1b retargeting to the MSAA image in between, barrier 2 now re-sets `.image()` explicitly. Implicit struct state across reuses is fragile -- watch it whenever a barrier is inserted mid-sequence.

## Observed and EXPECTED: the checker edges still alias

Eyeball verification caught exactly the right thing: silhouettes smooth, but the checker-pattern boundaries INSIDE faces still stair-step. That is correct behavior -- **two different aliasing phenomena with two different owners**:

- **MSAA owns silhouettes** (coverage). Interior pixels are 100% covered; all samples agree; the shader runs once and samples the texture once. MSAA never sees the checker pattern. (Sample-rate shading exists to force per-sample shading -- supersampling cost; not the tool for this.)
- **The sampler owns texel edges.** The checker boundaries are hard BECAUSE of NEAREST -- the pixel-art seam, kept deliberately. The fix-ladder, all sampler-side and all in the [[Game-Engine Capabilities (planned)|capabilities catalogue]]: LINEAR mag filter (smooth up close), mipmaps + linear min (minification shimmer -- currently no mip chain, maxLod 0), anisotropic filtering (obliquely viewed surfaces -- the tumbling cube faces are literally the case the earlier anisotropy discussion said it pays off on).

This is the [[L2 Feature Set - Renderer2D|L2 two-track AA decision]] confirmed by eyeball: MSAA for geometry, filtering/SDF for everything inside.

## Framerate note (asked alongside)

No frame limiting exists: present mode is MAILBOX on the 4090 (uncapped, no tearing, frames replaced in the queue -- thousands of fps, most never shown). It *looks* right at any rate because all animation is TIME-based (`System.nanoTime`), never per-frame increments -- the quiet payoff of a decision made at the spin milestone. `choosePresentMode` (MAILBOX > FIFO) is the policy seam for the capabilities table's present-mode/vsync knob; you-own-the-loop means apps can also throttle themselves. Planned, not built.

#vulkan #msaa #antialiasing #resolve
