# Design North Star

The one-line aim for **jvre**: *smaller than Processing, but powerful, flexible, and approachable* — sitting deliberately **between [Processing](https://processing.org/) and raw [[LWJGL]]/Vulkan**. Less approachable than Processing by definition; far more approachable than "draw a rectangle with nothing but LWJGL and a hope."

This is the same idea as Alan Kay's *"simple things simple, complex things possible"* in [[API Vision - Layered Altitudes]] — this note is the *why it matters* and *how we know if we're hitting it*.

## How the two layers map to the goal
- **More approachable than raw Vulkan** comes from **L2**: `new Window(...)`, `Renderer2D.fillRect`, you-own-the-loop. The hundreds of lines of bootstrap an L2 user never sees *is* the approachability.
- **More flexible than Processing** comes from a **public L1**: when `fillRect` isn't enough, drop to `Pipeline` / `Buffer` / shaders *without leaving the engine*. Processing walls this off; jvre must not.

## What's already serving the goal (power + flexibility)
- **Powerful** — Vulkan + discrete GPU: compute and hardware ray tracing are reachable, not aspirational.
- **Flexible** — the embeddable "you own the loop" choice, the public escape hatch, and clean policy seams ([[Physical Device and Queue Families|rateDevice]], [[Swapchain|chooseSwapSurfaceFormat]]) that keep defaults simple while allowing overrides. Largely won by the architecture already.

## The honest risk: approachability is unbuilt and must be *designed*, not bolted on
Everything built so far is the **un-approachable substrate** (L0/L1 plumbing). Approachability is the one quality **not guaranteed** by any of it — it lives entirely in **L2 + good defaults**, which don't exist yet. The decisions so far *protect the option* to be approachable (no L1 concepts leak upward, seams stay clean) but don't *prove* it.

The specific failure mode: the low-level work is the deep, fun part; it's easy for a solo project to live there forever and treat L2 as a thin afterthought. That would yield a powerful, flexible engine that **isn't approachable** — missing the whole point. **L2 ergonomics deserve as much deliberate design care as the Vulkan internals have gotten.**

### What landing it actually requires
1. **Defaults / zero-config** — how much you can do before being forced to decide. `new Window(...)` must Just Work with zero Vulkan vocabulary.
2. **Escape hatch = ramp, not cliff** — must be able to mix `fillRect` and a custom shader *in the same program* without a rewrite.
3. **Errors that aren't Vulkan VUIDs** — an L2 user should never see a spec-cited validation dump.
4. **The "first five minutes"** — keep a concrete **"hello rectangle"** sketch as the literal benchmark; measure L2 against it.

## Audience (calibrates every L2 decision)
jvre's "approachable" = **approachable for a Java programmer who wants graphics without a Vulkan PhD** — NOT an absolute beginner / artist (Processing's audience, the reason it hides `main`, types, class decls). That beginner-friendliness is a deliberate **non-goal**; chasing it would cost the flexibility that is jvre's actual edge.

## Verdict (2026-06-09)
On the right track: the architecture is correctly shaped for the goal, and the power/flexibility halves are being executed well. The standing commitment: **treat L2 as a first-class design problem when we reach it, not a finishing veneer** — that's where "approachable" is won or lost.

See [[API Vision - Layered Altitudes]], [[Device Selection and Cross-Platform (planned)]].

#design #vision #north-star
