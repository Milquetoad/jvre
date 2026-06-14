# Definition of Done

What "done" means for a *change* in jvre. Derived from the working agreement (CLAUDE.md), the [[Design North Star]], and how the project already works in practice. **Tiered**: every change meets the baseline; a behavior-changing beat adds the "shipped" bar; a landing feature/milestone adds the rest. Earlier tiers are implied by later ones.

> For when the **whole framework** is done -- the **v1.0 line** (a professional release to Maven Central) -- see the *Framework Definition of Done* in [[Roadmap]]. This note is the per-change bar; that one is the finish line.

## Baseline -- every change
- [ ] **Builds green.** `./gradlew build` passes (compile + shader compile + tests).
- [ ] **No regressions.** The existing suite still passes.
- [ ] **Tested where testable without a GPU.** New CPU-side logic (math, state machines, vertex/layout generation) gets unit tests. GPU-coupled code (a real `Texture`/`Font`, actual drawing) is verified on hardware instead -- not mocked into a false green.
- [ ] **House style.** Heavily commented, explaining *why* (not just what), matching the surrounding density + idiom. ASCII-only in source and console output.
- [ ] **No layering leaks.** No L1/Vulkan concept bleeds upward into L2; L2 stays in pixel space. Errors speak the user's altitude -- an L2 misuse throws a plain message, never a Vulkan VUID.
- [ ] **Intentional surface.** Anything newly `public` is meant to be public; internals stay package-private.

## Shipped -- any beat that changes behavior
Baseline, plus:
- [ ] **Verified on real hardware** (currently the 4090): the intended behavior is *observed*, not assumed.
- [ ] **Validation silent.** Sync + best-practices layers produce no output; clean exit; no leaks (VMA's destroy-asserts would fire on a leak).
- [ ] **Resources balanced.** Everything created is torn down, in the right order.
- [ ] **OS-agnostic.** No hardcoded platform specifics; everything portable goes through GLFW. The only per-OS knob is the `lwjglNatives` classifier. (Once CI lands -- [[Roadmap]] 1c -- the GPU-free build + tests pass on Windows AND Linux.)
- [ ] **Vault updated.** A dated [[Progress Log]] entry + the relevant concept/design note added or updated. (A pure, no-visual refactor may fold into the next entry instead of getting its own.)
- [ ] **Committed.** Per-beat granularity, branch-first off `main`, GitHub noreply email, the `Co-Authored-By` trailer. `CLAUDE.md` is never committed (gitignored).

## Landed -- a feature / altitude / milestone
Shipped, plus:
- [ ] **Docs match reality.** Every public-facing doc the change touches is current: [[Roadmap]], the relevant design note (e.g. [[L2 Feature Set - Renderer2D]]), `README.md`, and `CLAUDE.md`'s "Current state".
- [ ] **PR opened** against `main` -- what + why + how it was tested.
- [ ] **Understood, not just working.** The *why* is captured (comments and/or a vault note). This is the whole point: correctness + understanding over speed ([[Design North Star]]).

## "Not done" smells (easy to skip -- so they're called out)
- Validation prints anything -> not done.
- A leak (VMA assert on shutdown) -> not done.
- A VUID / raw Vulkan term reaches an L2 user -> not done.
- The vault is stale relative to the code -> not done.
- "It compiles" standing in for hardware verification of a rendering change -> not done.

#process #definition-of-done #project
