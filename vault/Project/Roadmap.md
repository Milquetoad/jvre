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
4. **Refinements are pull-based.** Polish (kerning, MSDF, `Style`, sampler knobs) ships when a real consumer needs it, not speculatively.
5. **The power axis is real but last.** Compute / ray tracing are "reachable, not promised" -- genuine milestones, but they don't pay down the standing debts.

---

## Phase 1 -- Make L2 interactive, and prove it (near-term)
The cheapest, highest-leverage work, and it pays both North Star debts on the L2 side.

- **1a. Input seam.** An L2-friendly per-frame input snapshot: mouse position + buttons, scroll, keyboard, typed chars. Today only `Window.cursorPos` is exposed, and in raw LWJGL form. *Why first:* L2 can draw but not react -- this is the single biggest functional gap, it unlocks every interactive sketch, and it's the prerequisite for the GUI demo. *Size:* small-medium. *Design:* snapshot polled in the frame loop, surfaced through the Renderer/L2 (not raw GLFW callbacks leaking up).
- **1b. Time / delta source.** The Renderer already tracks frame time internally; expose `time()` / `dt()` so animation is first-class at L2 (today a user must reach for `System.nanoTime()`). *Size:* trivial. *Depends:* none.
- **1c. Cross-platform validation + minimal CI.** Flip `lwjglNatives` and actually run on Linux; stand up GitHub Actions to build + run the GPU-free unit tests on Windows **and** Linux. *Why now:* the migration is the forcing function; a broken Linux build is cheapest to catch early, and CI stops it silently rotting. *Size:* small-medium. *Depends:* none (do in parallel).
- **1d. Approachability proof + first user docs.** A defaults/zero-config audit (`new Window(...)` must Just Work), the literal "hello rectangle" benchmark from the North Star, an L2-language error pass, and 1-2 small example sketches -- one of which can be interactive once 1a/1b land. This is also the right moment for the README **"Getting Started"** snippet (docs written *by being the first user*). *Depends:* 1a, 1b for an interactive example.

## Phase 2 -- Deliver L1 flexibility (mid-term)
The most *vision-significant* gap: the North Star promises dropping to custom shaders/geometry "without leaving the engine," but only fullscreen effects honor it today.

- **2a. The geometry escape hatch.** A sanctioned path to render user geometry with a user vertex format + shaders + (optional) descriptors, mixable with L2 in one program. Requires generalizing the fixed `Pipeline` kinds and lifting the hardcoded cube in `Renderer` into a public mesh/draw path. *Size:* large; the keystone of L1. *Why here:* it's the flexibility half of the whole thesis.
- **2b. Capability knobs.** Creation-time / policy seams that the planned notes already call for: **present-mode / vsync** (hardcoded MAILBOX today), **MSAA sample count** at construction, and **GPU selection override** + groundwork for runtime switching (the scoring *policy* exists in `rateDevice`; the *override* is unexposed). *Size:* medium. See [[Device Selection and Cross-Platform (planned)]], [[Game-Engine Capabilities (planned)]].

## Phase 3 -- Pull-based polish & the first real consumer
Slot opportunistically; let need drive it.

- **3a. L2 refinements.** Kerning + MSDF (text), the combined `Style` fill+stroke (the translucent-overlap *correctness* case), custom-font loading as an L2/Renderer convenience, sampler config (filter/mips/anisotropy), blend modes. Each ships when a consumer makes its absence felt.
- **3b. A tiny immediate-mode GUI demo.** Explicitly a **worked example built ON L2, not a jvre feature** (decision: GUI is a *capability* jvre enables, not a pillar -- see [[Self-Built GUI (planned)]]). Cap it hard: label + button + slider + the hot/active-ID idea (the one genuinely interesting lesson). Doubles as a flagship L2 example and a real API stress test. *Depends:* 1a (input).

## Release track -- toward v1.0 (runs alongside Phases 1-2)
The project-management / delivery half of the learning goal. Climbs a ladder rather than jumping straight to Central.

- **R1. De-pin the natives (blocking prerequisite).** `build.gradle` currently pins `lwjglNatives = 'natives-windows'`; a consumable library must let the *consumer* pick their platform's natives (LWJGL's standard `api`-deps + per-OS classifier pattern). Nothing ships until this is fixed. Pairs naturally with cross-platform (1c).
- **R2. Publishing plumbing.** Gradle `maven-publish`: the main JAR + **sources** + **javadoc** jars, a complete POM (name/description/license/SCM/developer), semver `0.x` while the API churns.
- **R3. Pre-1.0 distribution.** Tagged **GitHub Releases** + **JitPack** (zero-setup consumption from a git tag) so others can try it without Central's bureaucracy. Wire release builds into CI (extends 1c).
- **R4. Maven Central at 1.0.** Verified namespace **`io.github.milquetoad`** (GitHub-verified, free), **GPG-signed** artifacts, the sources/javadoc jars, via the Central Portal. Done once the API is stable -- re-publishing breaking `0.x` to Central is painful and discouraged.
- Cross-cutting: a **getting-started + API overview + examples** doc set (shares work with 1d), and an **honest scope statement** in the README.

## Phase 4 -- The power axis (longer-term)
Net-new capability; "reachable, not promised."

- **4a. Render-to-texture / offscreen targets** -- enables screenshot capture and post-processing/effect chains.
- **4b. Compute shaders** -- compute pipeline, dispatch, storage buffers/images. The big "powerful" milestone the discrete-GPU substrate makes reachable.
- **4c. Ray / path tracing** -- the long-horizon aspiration; reachable on the hardware, not a promise.

---

## Deferred / non-goals (recorded so they don't creep back)
- **A GUI toolkit as a feature pillar** -- out of scope; jvre is a rendering framework (Processing, its upper reference, isn't a GUI toolkit either). A *bounded demo* (3b) is the only sanctioned form; if a real GUI is ever needed for tooling, drop in Dear ImGui / Nuklear ([[GUI Options]]).
- **Beginner/artist friendliness** (hidden `main`, no types) -- a deliberate non-goal per the North Star audience note; chasing it costs the flexibility that is jvre's edge.
- **Retained-mode anything** -- immediate mode is the paradigm throughout.

## The one-line ordering
**Interactivity + proof (1) -> L1 flexibility (2) -> [v1.0: ship to Maven Central] -> pull-based polish + a GUI demo (3) -> the power axis (4).** Cross-platform (1c) and the Release track (R1-R4) run alongside Phases 1-2; **v1.0 = Phases 1-2 + Release track**, and that 1.0-on-Central is the project's finish line.

#roadmap #planning #design
