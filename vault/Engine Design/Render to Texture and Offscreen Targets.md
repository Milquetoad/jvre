# Render to Texture and Offscreen Targets

Status: **shipped 2026-06-16** (Roadmap [[Roadmap#Phase 4 -- The power axis longer-term|4a]], the first Phase-4 power-axis milestone). The L1 path (`drawToTarget`) and the L2 path (`createCanvas`) both land. This note is the *why*; the dated story is in [[Progress Log]].

## What it is (the one idea)

> **An offscreen target is the swapchain, minus the surface.**

Everything we draw into is a color-attachment `VkImage`. The swapchain's images are special only in that the *surface* owns them and we PRESENT them (`vkQueuePresentKHR`). An offscreen target is the same kind of image, except:
- **WE** create it (via VMA, like the depth buffer), and
- it carries **two** usages -- `COLOR_ATTACHMENT` (render into it) **and** `SAMPLED` (read it back as a `sampler2D`), and
- it ends each pass in `SHADER_READ_ONLY_OPTIMAL` (sampleable), not `PRESENT_SRC_KHR`.

That dual usage IS render-to-texture: the same image is a draw destination in one pass and a texture source in the next. `RenderTarget` is, structurally, "a [[Textures - Images, Views and Samplers|Texture]] you render into instead of upload pixels into" -- so `target.texture()` drops into `g.image(...)` and custom-pipeline texture slots unchanged.

## Why it's the gateway capability

Per [[Game-Engine Capabilities (planned)]] (line 33), offscreen targets are *the* gateway: once content can land in an image instead of the screen you unlock pixel-perfect integer upscaling, **post-processing / effect chains**, minimaps, shadow maps, and -- the strategic one -- **frame readback for automated visual-regression testing** ([[Roadmap#Phase 4 -- The power axis longer-term|4d]] builds on this), the one thing CI structurally can't do today. The capabilities note flagged the architectural debt early (line 67): *structure the loop as "render to a target (which today happens to be the swapchain)", not as one hardcoded pass.* This milestone paid that debt.

## The polish decision: a target renders exactly like the screen

jvre's pipelines BAKE the color format, depth format, and sample count. So the cleanest, most polished design is: **a target matches the swapchain's color format, depth format, AND sample count.** Then *every already-baked pipeline* (L2 shapes, the cube scene, an effect) renders into it for free, and sRGB color-spaces match so there are no gamma surprises when you sample it back.

The deliberate consequence: **a target inherits the renderer's MSAA.** When the screen is 4x, targets are 4x -- render into a multisampled image and RESOLVE into the single-sample *sampleable* image (the same resolve dance the swapchain already does). No awkward independent sample-count knob, no per-target pipeline re-bakes. So a target's image shape mirrors the swapchain's:

| MSAA on (Nx) | MSAA off (1x) |
|---|---|
| **color** (1-sample, `COLOR_ATTACHMENT\|SAMPLED`) -- resolve dest + what you sample | **color** (1-sample) -- direct target + what you sample |
| **msaaColor** (N-sample, `COLOR_ATTACHMENT`) -- rendered into, resolved into `color` | *(none)* |
| **depth** (N-sample) | **depth** (1-sample) |

(A *deliberately* 1x target while the screen is 4x -- for crisp pixel-art upscaling -- needs target-specific pipeline variants; catalogued, not v1.)

## The render loop, made target-agnostic

A frame's single command buffer now records: **each queued target pass first, then the swapchain pass** -- the swapchain is just the *last* render target. Each target pass is the swapchain pass with two differences:

1. **The hand-off barrier** (the one new load-bearing piece): after rendering, transition `color` `COLOR_ATTACHMENT_OPTIMAL -> SHADER_READ_ONLY_OPTIMAL` (`COLOR_ATTACHMENT_OUTPUT/WRITE -> FRAGMENT_SHADER/READ`). THIS is what makes "render this pass, sample the next pass" safe.
2. **The entry barriers wait on the PREVIOUS frame.** A target's color/depth images are OURS and shared across both [[Frames in Flight|frames in flight]] (not rotating like swapchain images, not per-frame like the UBOs). So -- exactly the [[3D and the Depth Buffer|depth-buffer cross-frame WAW/WAR lesson]] -- the entry barrier on `color` must wait on the prior frame's color WRITE *and* its shader-sample READ. First frame has no prior use; harmless.

Two content kinds plug into a target pass (`recordTargetPass` owns the attachments + barriers; the content owns the draws):
- **L1** -- `renderer.drawToTarget(target, frame -> {...})`, reusing the [[L1 Escape Hatch|FrameRenderer]] seam.
- **L2** -- `renderer.createCanvas(target)` returns a `Renderer2D` whose batch is recorded into the target. The Processing `createGraphics` analog (Processing being L2's [[API Vision - Layered Altitudes|north star]]).

## Two aliasing lessons (both about "the GPU reads at execution, not at record")

These are the subtle correctness traps, and both are the same root cause: host-visible per-frame data is written by the CPU *now* but read by the GPU when the command buffer *executes* -- so two writes before execution means both reads see the LAST write.

- **Per-pipeline UBO aliasing across passes.** A pipeline has one per-frame UBO. Draw it in *two* passes per frame with different uniforms and the second `uniform()` clobbers the first -- both draws render with the second matrix. That's why the demo routes the cube into a target *only* (drawn once). To render the same pipeline into a target AND the main pass, you'd need two UBO slots (or two pipelines).
- **Per-batch shape arena.** The main surface and a canvas are both live in one command buffer (different passes), so they cannot share a vertex arena. Hence the **`ShapeBatch`** refactor: the shape GPU resources (per-frame arena + transient descriptor pool) became a reusable bundle, one per `Renderer2D`. The shape *pipeline* + white default stay shared (stateless).

## "Low quality" is resolution, not samples

A target is a fixed-size image. If its on-screen footprint (in real pixels, after the demo's responsive scale and the display's DPI) exceeds the target's resolution, it's MAGNIFIED and softens -- MSAA (silhouette AA) doesn't help interior detail. The fix is resolution: size the target to its display footprint (`target px >= display px`). This is the same lever pixel-art upscaling pulls the *other* way (deliberately low-res target -> NEAREST -> integer upscale). Verified: 256^2 looked soft on a maximized 4K window; 1024^2 was crisp.

## Dynamic resolution = recreate (you can't resize a `VkImage`)

Changing a target's resolution always means **destroy the old images, create new ones** (dimensions are baked at creation, like a pipeline's formats) -- same hazard as [[Swapchain Recreation]]: don't free images the GPU is still reading (guard with `waitIdle` or fences). jvre owns the *mechanism* (create/recreate a target at any size, safely); the *policy* (when -- a perf heuristic, or "follow the window") is the user's, per the mechanism/policy rule. A `target.resize(w,h)` convenience that defers the free until the slot's fence clears (no full stall) is a catalogued refinement.

## Deferred (catalogued, not v1)

- **Target-specific sample count** (a 1x target while the screen is Nx) -- needs target-baked pipeline variants.
- **`target.resize(w,h)`** convenience (fence-deferred recreate).
- **Format-change-on-resize** rebuild for caller-owned targets (shares the custom-pipeline limitation).
- Built ON this: **[[Roadmap#Phase 4 -- The power axis longer-term|4d]] headless + frame readback** (copy the target to CPU -> golden-PNG diffs), **4e shape/mask clip** (stencil or alpha mask), post-processing / MRT.

See also: [[MSAA]] (the resolve mechanism), [[Pipeline Barriers]] / [[Synchronization2]] (the barriers), [[L2 Feature Set - Renderer2D]] (the canvas content), [[Dynamic Rendering]] (no render pass / framebuffer).

#design #engine-design #render-to-texture #api #capabilities
