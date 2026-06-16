# Roadmap (forward-looking)

**As of 2026-06-14.** The backward diary is [[Progress Log]]; the original bootstrap checklist is [[Roadmap - Clear to Color]]. This note is the *forward* plan and the *why* behind its ordering.

## Where we are
The full Vulkan substrate (L0/L1 plumbing) and both API altitudes exist:
- **L2 `Renderer2D`** -- v1 surface COMPLETE (fills, strokes, the SDF render path, image + multi-texture batching, text via SDF glyphs) + the transform stack. Rich and coherent. See [[L2 Feature Set - Renderer2D]].
- **`ShaderEffect`** -- the fullscreen Shadertoy altitude, contract-guarded.
- **L1** -- the building blocks (`Pipeline`, `Buffer`, `Texture`, `Font`) are public but *raw*: there is no sanctioned "bring your own geometry + shader" surface yet, and no input/time/capability surface.

Measured against the [[Design North Star]]: the **approachability half (L2) is well-built**; its *approachability is still unproven* (defaults, the "first five minutes", error quality), and the **flexibility half (L1 escape hatch for geometry) is only half-delivered** (fullscreen effects yes, custom meshes no). L2 is also currently **draw-only** -- it cannot react to input.

## The destination: a professional v1.0 on Maven Central
**Decided 2026-06-14.** jvre is a learning project whose curriculum explicitly includes **software engineering, project management, and shipping a real product** -- so the finish line is not "it works on my machine," it is **v1.0 delivered as if it were a professional library**. "Done learning" = jvre shipped to Maven Central with the polish that entails.

**Framework Definition of Done (v1.0)** -- jvre is "done" (1.0) when:
- **Both altitudes deliver the North Star.** L2 is *approachable* (defaults Just Work, the "hello rectangle" five-minutes is real, errors speak L2) AND *interactive* (input + time). L1 is *flexible* (the geometry escape hatch -- mix `fillRect` and a custom shader/mesh in one program).
- **Cross-platform proven** -- Windows + Linux, CI green on both.
- **Usable by a stranger** -- getting-started + examples + an API overview; a consumable published artifact; the LWJGL natives story de-pinned.
- **Honest scope statement** -- what jvre is and isn't (not a game engine, not a GUI toolkit).
- **The per-change [[Definition of Done]] held throughout** -- validation silent, no leaks, tested.
- **API declared stable** -- semver `1.0.0` is a compatibility promise.

**v1.0 = Phases 1-2 + the Release track (below).** Phases 3-4 (polish, the GUI demo, compute, ray tracing) are explicitly **post-1.0** capability growth, not done-ness.

## Prioritization lens (why this order)
1. **On-philosophy first.** The North Star's two explicit debts -- *prove L2 approachability* and *deliver L1 flexibility* -- outrank net-new capability.
2. **Cheap + high-leverage before big + speculative.** Small seams that unlock whole categories (input, time) come first.
3. **Honor forcing functions.** The owner is migrating to Linux; cross-platform is a first-class constraint that has *never been verified* -- validate it before more code piles on top.
4. **~~Refinements are pull-based.~~ SUPERSEDED 2026-06-16 -- jvre will be FULLY-FLEDGED.** The owner has committed to building out the *complete* expected feature set of a serious rendering framework (the bigger of "rendering layer of a game engine" or "rendering software"), as **planned, committed work** -- NOT "ships when a consumer needs it." This lands **post-1.0** (1.0 is still phases 1-2 + the release track), but the catalogued refinements (mipmaps/anisotropy, kerning, MSDF, `Style`, custom fonts, blend modes, ...) and the power axis below are now a committed backlog to schedule, not opportunistic polish. A dedicated post-1.0 feature-plan pass is owed. See [[jvre-fully-fledged-not-pull-based]] (memory).
5. **The power axis is committed, not "if need arises."** Compute / ray tracing / instancing / render-to-texture are genuine planned milestones (post-1.0), part of being fully-fledged -- still sequenced after the standing debts + 1.0, but no longer framed as merely "reachable, not promised."

---

## Phase 1 -- Make L2 interactive, and prove it (near-term)
The cheapest, highest-leverage work, and it pays both North Star debts on the L2 side.

- **1a. Input seam.** An L2-friendly per-frame input snapshot: mouse position + buttons, scroll, keyboard, typed chars. Today only `Window.cursorPos` is exposed, and in raw LWJGL form. *Why first:* L2 can draw but not react -- this is the single biggest functional gap, it unlocks every interactive sketch, and it's the prerequisite for the GUI demo. *Size:* small-medium. *Design:* snapshot polled in the frame loop, surfaced through the Renderer/L2 (not raw GLFW callbacks leaking up).
- **1b. Time / delta source.** The Renderer already tracks frame time internally; expose `time()` / `dt()` so animation is first-class at L2 (today a user must reach for `System.nanoTime()`). *Size:* trivial. *Depends:* none.
- **1c. Cross-platform validation + minimal CI.** Flip `lwjglNatives` and actually run on Linux; stand up GitHub Actions to build + run the GPU-free unit tests on Windows **and** Linux. *Why now:* the migration is the forcing function; a broken Linux build is cheapest to catch early, and CI stops it silently rotting. *Size:* small-medium. *Depends:* none (do in parallel).
- **1d. Approachability proof + first user docs. ✅ MOSTLY DONE (2026-06-16).** Wrote the user-facing doc set in `docs/` (getting-started with the literal "hello rectangle", a full 2D-graphics guide, shader-effects + custom-pipelines guides, an overview/map) and refreshed the README (killed "early days", added an honest **scope statement** -- not a game engine / not a GUI toolkit -- a status+capabilities section, and a docs index). Writing the docs *was* the first-user pass: it surfaced the missing **image loading** (queued next) and confirmed the zero-config path (`RendererOptions.defaults()`). **Still open (folded into the release-track readiness):** a dedicated L2-language error-message audit and 1-2 standalone example sketches beyond `Main`/`GuiDemo`.

## Phase 2 -- Deliver L1 flexibility (mid-term)
The most *vision-significant* gap: the North Star promises dropping to custom shaders/geometry "without leaving the engine," but only fullscreen effects honor it today.

- **2a. The geometry escape hatch. ✅ COMPLETE (2026-06-15).** A sanctioned path to render user geometry + shaders + uniforms + textures, mixed with L2 -- the keystone of L1, the North Star's flexibility half delivered.
  - Beat 1: user-defined pipelines via the thin guarded seam (`PipelineSpec`/`createPipeline` + `SceneRenderer`/`FrameRenderer`), additive through `Pipeline.Kind.CUSTOM`.
  - Beat 2: index buffers; bound resources (UBO/texture/push, jvre-owned descriptors); the **Camera (2c done)**; and the **dogfood** -- the engine's textured cube reproduced via the public API, then the hardcoded `SCENE` retired (no built-in geometry left). See [[L1 Escape Hatch]].
- **2b. Capability knobs. ✅ COMPLETE (2026-06-15).** A creation-time `RendererOptions` config carrying: **vsync** (FIFO vs MAILBOX), **MSAA** sample count (1/2/4/8, incl. the no-resolve off path), and **GPU-selection override** (`preferGpu` name substring over the default scoring; runtime switching still future). Clears the hardcoded MAILBOX/MSAA/GPU TODOs. See [[Device Selection and Cross-Platform (planned)]], [[Game-Engine Capabilities (planned)]].
- **2c. A camera helper. ✅ DONE (2026-06-15, with 2a).** An *on-demand* view + projection helper that centralizes the Vulkan-correct projection -- the JOML Y-flip + `zZeroToOne` clip-space lesson already solved once for the cube -- so 3D users don't re-derive it (hide the Vulkan gotcha, not the concept). **Matrix math only in v1** (controllers like orbit/FPS are input-bound -> examples, not the helper). *Open decision:* expose JOML `Matrix4f`, a neutral `float[16]`, or both (JOML is currently implementation-only; a public Camera would surface it). *Why here:* turns "render a mesh" (2a) into "render a scene" -- the API vision lists Mesh/**Camera** together.

## Phase 3 -- Pull-based polish & the first real consumer
Slot opportunistically; let need drive it.

- **3a. L2 refinements.** **SDF curves ✅ DONE (2026-06-15).** `fillEllipse`/`strokeCircle`/`strokeEllipse` moved from tessellation to the SDF path (crisp at any size, MSAA-independent): `strokeCircle` is an exact annulus SDF, the ellipses use iq's *approximate* ellipse distance (good enough for AA). One 6-vertex quad each (modes 4/5), `sdfBox` generalized to `sdfQuad`, the tessellation helper (`circleSegments`) retired. **Image loading ✅ DONE (2026-06-16)** -- `renderer.loadImage(path)` decodes PNG/JPEG/... via stb_image (the gap the docs surfaced). **Sampler filter ✅ DONE (2026-06-16)** -- a `Filter` enum (NEAREST/LINEAR) per texture; defaults by intent (createImage->NEAREST, loadImage->LINEAR). (Filtering changes artifact *character*, not detail -- no super-resolution.) Remaining refinements (now **committed**, not pull-based -- see the prioritization lens): the rest of sampler config (**mipmaps + anisotropy**, the minification case), kerning + MSDF (text), the combined `Style` fill+stroke (the translucent-overlap *correctness* case), blend modes. **Custom-font (TTF) loading is scheduled at [4b](#phase-4----the-power-axis-longer-term)** (right after render-to-texture, per owner request).
- **3b. A tiny immediate-mode GUI demo. ✅ DONE (2026-06-15).** A worked example built ON L2, **not** a jvre feature -- it lives in `jvre.demo` (excluded from the published jar), like `jvre.tools`. `Gui` (label + button + slider) over the **hot/active-ID** core; `GuiDemo` drives a bouncing ball (`gradlew runGuiDemo`). As a real API stress test it surfaced two missing L2 methods -- **`textWidth` + `lineHeight`** (text measurement for layout) -- now added to `Renderer2D` (the pull-based-polish loop working as designed). See [[Self-Built GUI (planned)]].

## Release track -- toward v1.0 (runs alongside Phases 1-2)
The project-management / delivery half of the learning goal. Climbs a ladder rather than jumping straight to Central.

- **R1. De-pin the natives (blocking prerequisite).** `build.gradle` currently pins `lwjglNatives = 'natives-windows'`; a consumable library must let the *consumer* pick their platform's natives (LWJGL's standard `api`-deps + per-OS classifier pattern). Nothing ships until this is fixed. Pairs naturally with cross-platform (1c).
- **R2. Publishing plumbing.** Gradle `maven-publish`: the main JAR + **sources** + **javadoc** jars, a complete POM (name/description/license/SCM/developer), semver `0.x` while the API churns.
- **R3. Pre-1.0 distribution.** Tagged **GitHub Releases** + **JitPack** (zero-setup consumption from a git tag) so others can try it without Central's bureaucracy. Wire release builds into CI (extends 1c).
- **R4. Maven Central at 1.0.** Verified namespace **`io.github.milquetoad`** (GitHub-verified, free), **GPG-signed** artifacts, the sources/javadoc jars, via the Central Portal. Done once the API is stable -- re-publishing breaking `0.x` to Central is painful and discouraged.
  - **API surface audit ✅ DONE (2026-06-16)** -- the "API declared stable" precondition: pruned `jvre.core` 29 -> 26 public types (hid `Vk`/`Commands`/`Swapchain`, trimmed `Buffer`/`Texture`/`Pipeline`/`Font` to opaque handles, kept `Device` as the raw escape). Principle: tighten now (reversible), widen later (additive). Contract written down in `docs/api-surface.md`.
  - **Namespace verified + GPG key created ✅ (owner, 2026-06-16).**
  - **Build plumbing ✅ DONE (2026-06-16):** `signing` plugin (opt-in `-PsignArtifacts`, `useGpgCmd`), a local staging repo + `centralBundle` Zip task producing `build/central-bundle-<version>.zip` (the manual-bundle path -- transparent, no 3rd-party publish plugin). Unsigned dry-run verified the layout + checksums.
  - **Remaining R4 (owner):** add `signing.gnupg.keyName` + the Central Portal token to `~/.gradle/gradle.properties`, run `gradlew centralBundle -PsignArtifacts`, upload the bundle (Portal UI / Publisher API), validate. **Then the version call: 1.0.0 now vs a 0.2.0 Central shakedown first.**
- Cross-cutting: a **getting-started + API overview + examples** doc set (shares work with 1d), and an **honest scope statement** in the README.

## Phase 4 -- The power axis (longer-term)
Net-new capability, post-1.0. Per the fully-fledged direction these are **committed**, not "reachable, not promised." (Several spitballed 2026-06-15; sequenced here.)

- **4a. Render-to-texture / offscreen targets.** Render into an image instead of the swapchain -- the FOUNDATION for readback and post-processing / effect chains.
- **4b. Public font loading (TTF).** A sanctioned custom-font API -- `renderer.loadFont(resourcePath, sizePx)` returning a `Font` -- so users aren't limited to the built-in DejaVu. Today `Font.load` is internal (package-private after the API audit) and `Font` is an opaque handle from `renderer.font()`; this gives it a public creation path, mirroring `loadImage`. Promotes the catalogued "custom-font loading" L2 refinement to committed, scheduled work. (Slotted right after 4a by owner request, 2026-06-16.)
- **4c. Rect / scissor clip (L2).** Restrict drawing to a rectangle -- scroll views, UI panels, masked regions. A clip stack mirroring the transform stack (`g.pushClip(x,y,w,h)` / `g.popClip()`), driven by the **dynamic scissor** the pipeline already enables (`vkCmdSetScissor`). Implementation wrinkle: L2 batches into one draw, so a clip change flushes the batch at that boundary (same mechanism as flush-on-texture-switch). Cheap + high-value; nearly an L2-completeness item. (Slotted right after 4b by owner request, 2026-06-16.)
- **4d. Headless + frame readback.** Render with no window (no swapchain) and copy the result image back to CPU memory. Beyond screenshots, the strategic payoff is **automated visual-regression testing** (render a frame -> diff a golden PNG) -- the one thing CI structurally can't do today. Needs a GPU/driver: runs on Hal, a GPU CI runner, or a SOFTWARE Vulkan impl (lavapipe/SwiftShader) headless in CI. *Builds on 4a.*
- **4e. Shape / mask clip (L2).** Clip to a non-rectangular region (circle, polygon, another shape) -- Canvas/Skia `clip(path)`. Via a **stencil mask** (draw the mask to the stencil buffer, then stencil-test the content) or render-to-texture + alpha mask. *Builds on 4a + stencil support.* The richer 2D follow-on to the rect clip (4c).
- **4f. Instanced rendering.** One draw call -> N copies with per-instance data (particles, tiles, many meshes). A real Vulkan lesson (per-instance input rate, instance buffers, `vkCmdDraw` instanceCount). Presupposes the 2a geometry path; jvre's L2 already covers "many shapes" via the vertex arena, so this is the 3D/scene side.
- **4g. GPU timestamp queries.** `vkCmdWriteTimestamp` + query pools to measure GPU-side pass timing -- profiling + a perf HUD. Extends the [[Diagnostics and the Crash Log|Diagnostics]] story from "what crashed" to "what's slow."
- **4h. 3D user clip planes.** Arbitrary clip planes in 3D (`shaderClipDistance` / `gl_ClipDistance`) -- cross-sections, "clip below the water plane" reflections, CAD cutaways. Specialized 3D/L1 item, gated behind enabling the device feature. (Frustum near/far clipping is already inherent in the projection -- not a feature.)
- **4i. Compute shaders** -- compute pipeline, dispatch, storage buffers/images. The big "powerful" milestone the discrete-GPU substrate makes reachable.
- **4j. Ray / path tracing** -- the long-horizon aspiration; reachable on the hardware.

---

## Deferred / non-goals (recorded so they don't creep back)
- **A GUI toolkit as a feature pillar** -- out of scope; jvre is a rendering framework (Processing, its upper reference, isn't a GUI toolkit either). A *bounded demo* (3b) is the only sanctioned form; if a real GUI is ever needed for tooling, drop in Dear ImGui / Nuklear ([[GUI Options]]).
- **Beginner/artist friendliness** (hidden `main`, no types) -- a deliberate non-goal per the North Star audience note; chasing it costs the flexibility that is jvre's edge.
- **Retained-mode anything** -- immediate mode is the paradigm throughout.

## The one-line ordering
**Interactivity + proof (1) -> L1 flexibility (2) -> [v1.0: ship to Maven Central] -> pull-based polish + a GUI demo (3) -> the power axis (4).** Cross-platform (1c) and the Release track (R1-R4) run alongside Phases 1-2; **v1.0 = Phases 1-2 + Release track**, and that 1.0-on-Central is the project's finish line.

#roadmap #planning #design
