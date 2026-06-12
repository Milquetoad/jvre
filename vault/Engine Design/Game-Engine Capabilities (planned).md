# Game-Engine Capabilities (planned)

Status (2026-06-12): **boundary rule + capability target settled** in conversation. The question: *if someone builds a game engine on top of jvre, what must jvre expose so they never get stuck?* This is the "target silhouette" for L1 — not a build list (you don't build it up front), but the set you must not **preclude**. Scoped to what a game engine needs **from a rendering framework** — jvre is the render + present + input-surface slice, nothing more.

## The boundary rule (the test)

> **jvre owns any capability a higher layer physically cannot build from the outside (a *mechanism*). The game engine owns anything composable from jvre's public surface (*policy*).**

This is the classic **mechanism-vs-policy** split, and it gives a precise test instead of a topic-by-topic judgment call: *can the layer above synthesize this without privileged access to Vulkan/the window?* If no -> jvre. If yes -> game layer.

The subtle consequence: "expose the necessary features" is not passive — it is an **obligation**. Keeping a feature out of jvre commits jvre to exposing the *seams* that feature is built from, because those seams are exactly what the game layer can't add later. The canonical example: `image()` must ship the **sub-rectangle form** (`srcX,srcY,srcW,srcH` -> `dst...`, canvas's 9-arg `drawImage`). Whole-texture-only `image()` makes sprite-sheet animation **impossible** from above, no matter how clever the game layer is. Sampler mode, blend mode, and offscreen targets are the same kind of load-bearing seam.

## Non-goals (jvre is NOT a game engine)

A game engine is rendering **+ audio + input + physics + assets + scene/ECS + scripting + networking**. jvre is only the first slice (+ the window/input *surface*, since it owns the GLFW handle). Hard non-goals — a game engine pulls these from elsewhere: **audio, physics, networking, scripting, ECS/scene graph, animation state machines, asset/file-format parsing** (jvre uploads *bytes/pixels* to the GPU; it does not parse PNG/glTF). Naming these keeps "anything a game engine wants" from ballooning jvre into a game engine.

## Capability tiers

**Tier 0 — already have:** geometry (vertex/index), per-frame UBO + push constants, device-local staging upload, [[Frames in Flight|frames-in-flight]], [[Physical Device and Queue Families|scored GPU selection]], resizable window.

**Tier 1 — on the roadmap:** textures (+ sampler modes, the sub-rect seam), depth/stencil, MSAA, VMA, runtime GPU switching.

**Tier 2 — implied by "any game engine," not yet roadmapped.** All of this is **L1 capability** (the low-level altitude); the game engine *composes* it. None pulls jvre toward being a game engine.

| Capability | Why a game needs it |
|---|---|
| **Present-mode / vsync control** | FIFO vs MAILBOX vs IMMEDIATE = the tearing/latency/power tradeoff every game's settings expose |
| **Fullscreen / borderless** | basic game windowing |
| **Input** (keyboard/mouse/gamepad/text) | no game without it; jvre owns the [[GLFW]] window so it owns the event source |
| **Instanced draw** | 10k sprites / grass / bullets in one call — what makes batching scale |
| **Dynamic / streaming buffers** | the per-frame vertex arena for batching ([[L2 Feature Set - Renderer2D|L2 impl sketch]]); likely the VMA trigger |
| **Storage buffers (SSBO)** | large per-instance data; GPU-driven rendering |
| **Offscreen render targets** (render-to-texture) | **the gateway capability** — pixel-perfect integer upscale, post FX, minimaps, shadow maps |
| **Post-processing / multiple render targets (MRT)** | bloom, color grade, a **CRT/scanline filter for pixel art**, deferred shading |
| **Full pipeline state** — depth test/write, **stencil**, cull/winding, topology, wireframe, blend modes | masking, outlines, 2.5D z-order, debug views |
| **Texture completeness** — arrays, mipmaps, cubemaps, compressed (BC/ASTC), sRGB | tilesheets as array layers, skyboxes, VRAM budget, correct color |
| **Sampler configuration** — filter (nearest/linear), address mode, **anisotropy** | nearest = pixel-art crispness, linear = smooth scaling; anisotropy fixes oblique-angle blur. NB: anisotropy is a sampler mode but **couples down a layer** -- it needs a `samplerAnisotropy` device-feature enable at `Device` creation (+ the `maxSamplerAnisotropy` limit), so the sampler surface and the requested-features set grow together. Inert for nearest/flat sprites; pays off on receding 3D surfaces. Offer it; don't enable speculatively. |
| **Compute shaders** (+ compute queue) | particles, GPU culling, simulation, post FX |
| **Multithreaded command recording** | games record draw work across cores; Vulkan is *designed* for it |
| **Color management / HDR output** | modern displays; correct blending; swapchain format/colorspace |

Most of Tier 2 is purely **additive** — bolt on when a need appears, no regret. The exceptions are the three constraints below.

## Boundary-case decisions (2026-06-12)

These three sit on the mechanism/policy line and were decided deliberately:

- **Text — IN (jvre).** Technically policy (composable from textures + batching), but absorbed anyway because it is universal and the font-atlas pipeline is real work no one should redo. Already claimed by [[L2 Feature Set - Renderer2D|L2's `text()`]]. A *chosen* exception to the rule, eyes open.
- **Camera — OUT (game layer); primitives IN.** A 2D camera is **pure policy**: pan = `translate`, zoom = `scale`, rotate = `rotate`. A camera is just "apply one transform at the top of the frame." L2 already lists "2D camera" as deliberately-absent for this reason — the transform stack *is* a camera if you push it first. Clean split: **jvre owns the projection** (world/pixel space -> framebuffer; the ortho matrix, pixels/top-left/y-down — touches coordinate systems + the swapchain, so a game can't synthesize it); **the game layer owns the *view*** (where it looks, follow-the-player, deadzones, screen shake, smoothing). A `Camera2D` class belongs above jvre. Nothing new to build — the transform stack + projection are already planned.
- **3D — split into two opposite answers:**
  1. **L1 substrate: 3D-capable — leave it.** Vulkan has no 2D/3D concept; it's vertices + shaders + matrices either way. The orbiting quad already pushes a `mat4`; depth is roadmapped. A vertex with `z` + a perspective matrix instead of ortho *is* 3D — there is no separate "3D feature." Restricting L1 to 2D would mean *removing* generality you get for free, and contradicts the "modern public L1" thesis. **You'd have to work to *prevent* 3D.**
  2. **A friendly 3D *API* (meshes/materials/lights/camera): deferred, and NOT at L2.** That is "write a 3D engine" (PBR, shadow maps, skeletal anim, scene graph), it explodes scope, and the asset-y half is game-layer policy anyway. Bolting it onto **L2 would wreck L2's coherence** — L2 is coherent *because* it's flat (Processing's 3D mode is the famous awkward bolt-on). A friendly 3D layer is a **separate, optional, much-later third altitude**; defer indefinitely at zero cost.

  Reinforcing detail: L2 already declines to bundle vector math (users bring JOML). That extends here — for 3D the *game* brings the perspective/`lookAt` matrices via JOML and hands them to jvre; jvre owns the **plumbing that consumes them** (a UBO matrix slot, the depth buffer, a perspective-capable pipeline), not the camera math. Same mechanism/policy line, held consistently.

| Altitude | 3D? |
|---|---|
| **L1** (low-level substrate) | **Yes — 3D-capable, nearly free, partly there.** Don't restrict. |
| **L2** (Processing-style draw) | **No — stays flat by design.** That's what makes it coherent. |
| **Friendly 3D layer** (meshes/lights/camera) | **Separate, optional, much-later altitude.** Asset parts are game-layer. |

## Architectural constraints — decisions with deadlines

Tier 2 is mostly "build later, no regret." **Three exceptions constrain code being written now** — they impose shape on the [[Frames in Flight|Renderer]] / [[Device Selection and Cross-Platform (planned)|device context]], and retrofitting them is surgery, not addition. Don't build them yet; just keep them **expressible**, never assumed-away:

1. **Multithreaded command recording** (biggest). If the Renderer hard-assumes single-threaded recording into one command buffer, parallel recording is a rewrite. The *ownership model* — who allocates command pools (per-thread vs per-frame) — must not bake in single-threaded.
2. **Offscreen render targets.** `drawFrame` currently renders straight to the swapchain. The moment you want pixel-perfect upscaling *or* any post FX, you need "render to an image, then present that image." Structure the loop as *render to a target (which today happens to be the swapchain)* — not as one hardcoded pass to the swapchain.
3. **Queue-set abstraction.** Queue selection currently hunts graphics+present. The day compute matters, the device/queue layer should already think "a set of queues by capability," not "the graphics queue." (Overlaps [[Device Selection and Cross-Platform (planned)|the recreatable-context seam]].)

## Where this fits

Everything above is **L1 capability**; the game engine (or a future jvre L2/L3) composes it. Consistent with [[API Vision - Layered Altitudes|the two-altitude thesis]] and [[Design North Star|the north star]] (powerful + flexible via a public L1; approachable via L2). The near-term pixel-art / 2D work needs *none* of Tier 2 — but the architecture must not preclude it, and (good news) inherently doesn't.

#design #engine-design #planned #api #capabilities
