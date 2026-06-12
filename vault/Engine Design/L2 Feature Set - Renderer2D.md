# L2 Feature Set — Renderer2D (draft)

Status (2026-06-12): **principles decided, primitive set proposed, open questions at the bottom.** This formalizes the "just draw" altitude from [[API Vision - Layered Altitudes]]. Modeled on Processing, deliberately smaller ([[Design North Star]]): every omission below is a choice, not an oversight.

## Principles (decided)

1. **Stateless call sites — no modes.** Every drawing call carries its complete meaning at the call site; nothing a call does may depend on a mode set elsewhere.
   - *The corner-vs-centre question (decided 2026-06-12):* rejected a secondary declaration mode for rect/triangle/quad. Processing has exactly this (`rectMode(CENTER)` etc.) and it is a notorious confusion source: global mutable state, action at a distance, composes badly (helpers must save/restore). The user-side translation is one self-documenting subtraction (`x - w/2`); the engine-side cost would be permanent interface surface + a state machine. Asymmetric trade — omit it. If a centred form ever proves common, the stateless escape is a **distinct name** (`rectCentered`), never a mode.
2. **One natural convention per shape — no switches.** "Natural" = what the shape's common uses want:
   - `rect` = **corner + size** (`x, y, w, h`) — screens, UI, text layout; matches java.awt/SDL/canvas intuition.
   - `circle`/`ellipse` = **centre + radius/radii** — geometry; even Processing defaults ellipseMode to CENTER.
   - `line`/`triangle`/`quad` = **explicit vertices**.
   The consistency that matters is *within* a shape and in argument-order patterns, not one rule across all shapes.
3. **Rotation belongs to the transform stack, not declaration modes.** `push() / translate(dx,dy) / rotate(angle) / scale(s) / pop()` — the one sanctioned "state", legal because it is explicitly scoped (push/pop pairs; `end()` asserts balance). This answers the *actual* demand behind Processing's `rectMode(CENTER)` (rotating about the centre).
4. **Pixels, origin top-left, y down.** L2 never shows NDC, aspect ratios, or any Vulkan coordinate concept. (Framebuffer pixels, consistent with [[Swapchain Recreation|the DPI lesson]].)
5. **Immediate mode; the user owns the loop.** `begin()` / draw calls / `end()` per frame — a perfect fit for the [[Frames in Flight|per-frame recording]] architecture: L2 calls batch into the current frame's recording.
6. **Errors speak L2.** A misuse (`end()` without `begin()`, negative radius) throws a plain message; an L2 user never sees a VUID ([[Design North Star]] rule 3).

## Primitive set, v1 (proposed)

| Call | Convention | Notes |
|---|---|---|
| `line(x1, y1, x2, y2, color)` | endpoints | thickness: open question 2 |
| `rect(x, y, w, h, color)` | corner + size | |
| `triangle(x1, y1, x2, y2, x3, y3, color)` | explicit vertices | |
| `quad(x1, y1, ... x4, y4, color)` | explicit vertices | convex; triangulated on the 0-2 diagonal ([[Index Buffers]]) |
| `circle(cx, cy, r, color)` | centre + radius | tessellated; SDF rendering later |
| `ellipse(cx, cy, rx, ry, color)` | centre + radii | |

**Milestone-gated additions** (specified now, shipped when the L1 machinery exists):

| Call | Gated on |
|---|---|
| `image(img, x, y[, w, h])` | textures (images + samplers + descriptors) |
| `text(s, x, y[, size], color)` | font atlas (textures + batching) — the big one |

`Color`: an immutable value type (`Color.rgb(r,g,b)`, `Color.rgba(...)`, common constants), linear-vs-sRGB handled internally — the user thinks in normal 0-255 / hex colors.

## Deliberately absent (the "smaller than Processing" list)

- `rectMode` / `ellipseMode` / `colorMode` — modes are hidden state (principle 1).
- Ambient `fill()` / `stroke()` / `strokeWeight()` style state — pending open question 1, but the default position is per-call color (the original [[API Vision - Layered Altitudes]] sketch already wrote `g.fillRect(x, 0, w, h, Color.SKYBLUE)`).
- `beginShape()/endShape()` vertex soup — maybe a `polygon(float[] xy, color)` later; not v1.
- A bundled vector-math library (PVector) — out of L2's scope; Java users bring JOML or their own.
- Beginner-hiding magic (no hidden `main`, no implicit loop) — jvre's audience is Java programmers ([[Design North Star]] audience note).
- 2D camera — `translate`/`scale` on the transform stack covers v1 needs.

## Implementation sketch (L1 mapping, for later)

Each call appends vertices into a **per-frame dynamic vertex buffer** (one big host-visible ring; the [[Vertex Buffers and GPU Memory|Buffer]] elementary grows an arena mode — likely the VMA trigger), flushed at `end()` into the current command buffer with one pipeline per primitive class. Transform stack applied CPU-side at append time (v1) — keeps the shader trivial. Batching is its own milestone; this spec is the *surface*, fixed first so the machinery has a target.

## Open questions (to settle conversationally)

1. **Style: per-call color vs a `Style` value object?** Per-call is the default position (stateless, matches the original sketch). If signatures get heavy once stroke width/anti-aliasing options arrive, the stateless upgrade is an immutable `Style` object passed per call — *not* ambient state.
2. **Stroke/outline variants in v1, or fill-only first?** (`rect` + `strokeRect`-style pairs vs shipping fills first and adding outlines with the line-thickness work.)
3. **Naming: `rect()` vs `fillRect()`?** The original sketch said `fillRect`; if outlines become `strokeRect`, the pair is canvas-style and self-explaining. If v1 is fill-only, plain `rect()` is cleaner. Tied to question 2.

#design #api #L2 #draft
