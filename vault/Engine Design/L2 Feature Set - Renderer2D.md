# L2 Feature Set — Renderer2D (draft)

Status (2026-06-12): **v1 surface settled** (principles, primitives, style, outlines, naming). This formalizes the "just draw" altitude from [[API Vision - Layered Altitudes]]. Modeled on Processing, deliberately smaller ([[Design North Star]]): every omission below is a choice, not an oversight.

> 🚧 **Implementation underway (2026-06-13).** Beat 1: `Color` (sRGB→linear), the `shape2d` shaders (pixels→NDC, no Y-flip), and `Renderer2D`'s CPU half (`begin`/`fillRect`/`end`, unit-tested). **Beat 2 ✅ — first rectangles on screen**: `Pipeline` grew a third `Kind` (`SHAPES_2D`), a per-frame vertex arena (grown on overflow, uploaded like the UBO), a third content seam, `Renderer.renderer2D()`. Verified on the 4090 (solid + translucent fills, blending correct, validation silent). **Resize = shapes stay put in pixels** (the vertex shader divides by the live framebuffer size) — relative layout is user-side arithmetic against `g.width()/g.height()` (next), never a coordinate mode. **Beat 3**: the **v1 FILLS are complete** ✅ — `fillRect`, `fillCircle`, `fillEllipse` (fan, radius-scaled segments; circle delegates to it), `fillTriangle`, `fillQuad` (0-2 diagonal split); plus `g.width()/g.height()`. **Still to do**: strokes (`line`/`strokeRect`/… — CPU-triangulated, no `wideLines`), SDF edge-AA, then `image`/`text`. See [[Progress Log]].

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

## Style and outlines (decided 2026-06-12)

- **Per-call color, no ambient style state** — `fill(red)`-then-`rect(...)` has the same action-at-a-distance disease as `rectMode`. If signatures grow heavy later (anti-aliasing options, dash patterns), the stateless upgrade is an immutable `Style` value object passed per call — never renderer state.
- **Outlined shapes are v1 citizens**, not a later add-on (owner's call: complicated but essential). Consequence for the API: fill and outline want *different signatures* (outlines carry a `thickness`), which is the structural argument for separate methods over a variant parameter.
- **Implementation rule: no `wideLines`.** Vulkan's `lineWidth > 1` is an optional, non-portable feature. Thick lines and outlines are **triangulated CPU-side** (a thick line = a quad; a stroked rect = an 8-triangle frame; the honest work is join/corner geometry). It all feeds the same vertex batch as fills.

## Primitive set, v1 (proposed; naming per the recommendation below)

| Call | Convention | Notes |
|---|---|---|
| `line(x1, y1, x2, y2, thickness, color)` | endpoints | inherently a stroke -- no fill/stroke pair |
| `fillRect` / `strokeRect(x, y, w, h, [thickness], color)` | corner + size | |
| `fillTriangle` / `strokeTriangle(x1..y3, ...)` | explicit vertices | |
| `fillQuad` / `strokeQuad(x1..y4, ...)` | explicit vertices | convex; triangulated on the 0-2 diagonal ([[Index Buffers]]) |
| `fillCircle` / `strokeCircle(cx, cy, r, ...)` | centre + radius | tessellated; SDF rendering later |
| `fillEllipse` / `strokeEllipse(cx, cy, rx, ry, ...)` | centre + radii | |

**Milestone-gated additions** (specified now, shipped when the L1 machinery exists):

| Call | Gated on |
|---|---|
| `image(img, x, y[, w, h])` | textures (images + samplers + descriptors) |
| `text(s, x, y[, size], color)` | font atlas (textures + batching) — the big one |

`Color`: an immutable value type (`Color.rgb(r,g,b)`, `Color.rgba(...)`, common constants), linear-vs-sRGB handled internally — the user thinks in normal 0-255 / hex colors.

## Steelman: would stateful/modes make more sense? (explored 2026-06-12, decision stands)

Pressure-tested before settling. The honest survey:
- **The stateful lineage is real**: Processing/p5, canvas (its *style* is ambient -- we borrowed its naming, not its state model), Cairo, awt, Love2D, OpenGL. Its genuine wins: the brush metaphor (minimal concepts for beginner/artist audiences), DRY bulk drawing, setter-based extensibility, subtree-contextual drawing.
- **The modern direction is the other way**: Skia and Flutter pass an immutable-ish **Paint per call** (= our `Style` general form); **Raylib is per-call color, fully stateless**; ImGui's style state is **scoped push/pop stacks**, never free mutation; declarative UI (React/SwiftUI/Compose) moved the whole industry away from hidden mutable context; Vulkan's pipeline bake is the endpoint of the same arc -- L2 stateless is philosophically continuous with jvre's substrate.
- **Coordinate-interpretation modes (`rectMode`) aren't even close**: a Processing-ism no other major API adopted. Style-state bugs are shallow (wrong color); interpretation-mode bugs are deep (wrong *place* -- looks like broken math). Even state-comfortable APIs never touched them.
- **Each stateful win has a stateless home here**: bulk homogeneity = a local `Color` variable (our audience uses variables -- [[Design North Star]]); extensibility = the `Style` object (Skia's Paint proves it scales); subtree context = scoped stacks (Processing itself had to add `pushStyle/popStyle` -- ambient-without-scoping failed even them).
- **Managed weakness**: per-call args + no named arguments in Java -> pin an **argument-order convention: geometry first, style params after, color always last.** Every call scans the same way; IDE inlay hints do the rest.

Verdict: the choice is *audience-relative* -- stateful is right for Processing's beginners, stateless is right for jvre's documented audience. Same neighborhood as Skia/Flutter/Raylib. Decision stands with confidence.

## Deliberately absent (the "smaller than Processing" list)

- `rectMode` / `ellipseMode` / `colorMode` — modes are hidden state (principle 1).
- Ambient `fill()` / `stroke()` / `strokeWeight()` style state — pending open question 1, but the default position is per-call color (the original [[API Vision - Layered Altitudes]] sketch already wrote `g.fillRect(x, 0, w, h, Color.SKYBLUE)`).
- `beginShape()/endShape()` vertex soup — maybe a `polygon(float[] xy, color)` later; not v1.
- A bundled vector-math library (PVector) — out of L2's scope; Java users bring JOML or their own.
- Beginner-hiding magic (no hidden `main`, no implicit loop) — jvre's audience is Java programmers ([[Design North Star]] audience note).
- 2D camera — `translate`/`scale` on the transform stack covers v1 needs.

## Implementation sketch (L1 mapping, for later)

Each call appends vertices into a **per-frame dynamic vertex buffer** (one big host-visible ring; the [[Vertex Buffers and GPU Memory|Buffer]] elementary grows an arena mode — likely the VMA trigger), flushed at `end()` into the current command buffer with one pipeline per primitive class. Transform stack applied CPU-side at append time (v1) — keeps the shader trivial. Batching is its own milestone; this spec is the *surface*, fixed first so the machinery has a target.

**Antialiasing (slotted 2026-06-12, two tracks):**
- **SDF edge-AA for L2 shapes** -- circles/ellipses/strokes drawn as quads whose fragment shader smoothsteps alpha over ~1px of signed distance to the edge. Better than MSAA for curves, costs nothing extra, independent of MSAA. **Designed into the shape shaders from day one of the batching milestone** (don't tessellate hard edges and retrofit).
- **MSAA for geometry edges** -- offscreen multisampled image + resolve (dynamic rendering has `resolveMode` built into the attachment info). Slot: **after 3D + depth** (needs own-VkImage machinery from textures, and the depth buffer must be multisampled too). `rasterizationSamples` is baked pipeline state -> **AA is a creation-time option at L2** (`msaa: 4` at window/renderer construction), never a runtime toggle.

**Study reference: raylib** (github.com/raysan5/raylib) — the closest existing API to this spec (stateless, per-call color, you-own-the-loop; independent validation of the surface). Its small readable C source solves our exact implementation problems: `rshapes.c` (circle/ring/rounded-rect tessellation, thick lines as triangles — they avoid GPU line width too), `rtext.c` (a complete font-atlas pipeline), and `rlgl`'s default batch (accumulate vertices, flush on texture switch/overflow — our per-frame arena, in C). What jvre does differently is the rest of the thesis: Vulkan substrate, a *modern* public L1 instead of rlgl's pseudo-GL-1.1 escape hatch, native Java.

## Naming (decided 2026-06-12)

**Symmetric verb pairs for closed shapes (`fillRect`/`strokeRect`, ...); bare names for one-form primitives (`line`, `text`, `image`).** The reasoning:
- In a stateless API the fill/outline distinction must live at the call site: ambient state is rejected, and a variant *parameter* fights the signatures (outlines carry `thickness`, fills don't) -- so it goes in the **name**.
- **Symmetry signals status**: `rect()` + `rectOutline()` would brand outlines an afterthought; they're v1 citizens.
- Precedents: HTML canvas (`fillRect`/`strokeRect`, the largest immediate-mode-2D mindshare) vs java.awt (`fillRect`/`drawRect` -- "draw" meaning outline is a famous naming mistake; "draw" is out). **"stroke" over "outline"**: it is the domain's word (canvas, SVG, Skia, Processing, design tools) and L2 naming is "intuitive and domain-facing".
- The pairing is deliberately *ragged*: only closed shapes pair. A line IS a stroke (`strokeLine` is nonsense); text is fill-only; `image` is neither.
- The classic autocomplete objection to verb-first naming (typing `rect` won't find `fillRect`) is obsolete for our audience: IntelliJ's camel-hump matching surfaces both.

## Fill + stroke together (decided 2026-06-12)

`fillRect` and `strokeRect` are **orthogonal** -- a filled rectangle with a border is **two calls** in v1. That keeps primitives single-purpose and composable (canvas precedent; the alternative is a third combined verb per shape -- method explosion with an awkward two-color signature). The honest cost: geometry arguments stated twice.

**But a combined form is planned, and not just as sugar -- it is a *correctness* feature.** A stroke sits centered on the shape boundary (the canvas/SVG convention), so its inner half overlaps the fill. Opaque colors hide this; **translucent colors do not**: fill-then-stroke double-covers the inner band, producing two different blended colors within one stroke. Composing two calls can never fix that. The correct rendering is disjoint geometry in one operation (inset fill + stroke band).

This is exactly the reserved `Style` trigger case. The eventual general form:
```java
g.fillRect(x, y, w, h, color);                          // simple cases stay short
g.strokeRect(x, y, w, h, 2f, color);
g.rect(x, y, w, h, Style.fill(BLUE).stroke(2f, WHITE)); // general form (post-v1)
```
The bare noun returns as the *general* form taking an immutable `Style` (fill and stroke both optional, rendered without overlap); the verb pairs stay as the 90% surface. Stroke alignment: **centered** (the convention), revisit only if demanded.

#design #api #L2 #draft
