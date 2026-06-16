# Render to texture (offscreen targets)

A **render target** is an image you render *into* instead of the screen, then
sample back as a texture. It's the foundation for post-processing, minimaps,
pixel-perfect upscaling, and compositing one scene into another.

The idea in one line: a `RenderTarget` *is-a* texture you draw into. You render
content into it each frame, then `target.texture()` is an ordinary `Texture` you
can draw with `g.image(...)` or bind to a custom pipeline.

It works from **both altitudes** — L1 custom geometry and the L2 2D surface — and a
target renders exactly like the screen does (same colour handling, and it inherits
the renderer's MSAA), so everything you already draw works unchanged.

## Create a target

```java
RenderTarget target = renderer.createRenderTarget(512, 512);
// or renderer.createRenderTarget(512, 512, Filter.NEAREST) to choose how it's
// sampled back (LINEAR by default — smooth scaling; NEAREST for pixel-perfect).
```

The size is in pixels. A target is **caller-owned** — `close()` it before the
renderer.

## Drawing into it — L1 (custom geometry)

Use `renderer.drawToTarget(target, frame -> { ... })` each frame. The callback gets
the same `FrameRenderer` as [`setSceneRenderer`](custom-pipelines.md) — bind a
pipeline, bind buffers, draw — but it renders into the target instead of the screen:

```java
// each frame, before renderer.drawFrame():
renderer.drawToTarget(target, frame -> {
    frame.bind(meshPipeline);
    frame.uniform(mvp);
    frame.bindVertexBuffer(verts);
    frame.bindIndexBuffer(indices);
    frame.drawIndexed(36);
});
```

It's immediate mode: call it every frame you want the target refreshed.

## Drawing into it — L2 (the `createGraphics` analog)

`renderer.createCanvas(target)` returns a `Renderer2D` that draws into the target.
You use the *exact same* 2D API as the main surface — `begin()` / shapes / `end()` —
and `gc.width()` / `gc.height()` report the **target's** size (so layout composes
against the canvas, not the window):

```java
RenderTarget canvasTarget = renderer.createRenderTarget(512, 512);
Renderer2D gc = renderer.createCanvas(canvasTarget);   // do this once, after renderer2D()

// each frame:
gc.begin();
gc.fillRoundedRect(0, 0, gc.width(), gc.height(), 36, Color.rgba(28, 32, 46, 235));
gc.fillCircle(gc.width() * 0.5f, gc.height() * 0.4f, 48, Color.rgb(90, 200, 255));
gc.text("drawn offscreen", 40, gc.height() - 60, 28, Color.WHITE);
gc.end();
```

jvre records the canvas into its target automatically each frame it has content —
you don't enqueue anything. (The canvas `Renderer2D` is renderer-owned; only the
`RenderTarget` is yours to close.)

## Using the result

After the target was rendered this frame, `target.texture()` holds the result. It's
a normal `Texture`, so:

```java
// in the main 2D surface:
g.image(target.texture(), x, y, w, h);

// or bind it to a custom pipeline that declares .texture(Stage.FRAGMENT):
frame.texture(target.texture());
```

Targets render in call order within a frame, and each is finished (and sampleable)
before the main screen pass — so the screen pass, or a later target, can sample it.

## Resolution: match the target to its on-screen footprint

A target is a fixed-size image. If you display it **larger** than its pixel size, it
gets magnified and looks soft — that's a *resolution* issue, not anti-aliasing.
Size the target to (at least) the pixel area you'll show it at. The same lever runs
the other way for pixel art: a deliberately small target sampled with
`Filter.NEAREST` gives a crisp integer upscale.

MSAA is handled for you: a target **inherits the renderer's MSAA** (set via
`RendererOptions`), rendering multisampled and resolving into the sampleable image.

## Changing a target's size at runtime

You can't resize an image in place — change the resolution by recreating the target.
Because in-flight frames may still be reading the old images, wait for the GPU first:

```java
renderer.waitIdle();
target.close();
target = renderer.createRenderTarget(newW, newH);
```

(A one-call `resize` that avoids the stall is a planned convenience.)

## One gotcha: the same pipeline, twice in a frame

A custom pipeline created with `.uniformBuffer(...)` has **one** uniform buffer per
frame. If you draw that same pipeline in *two* passes in one frame (e.g. into a
target *and* onto the screen) with **different** uniform values, the second
`frame.uniform(...)` overwrites the first — both draws end up using the second
value.

Fixes, easiest first:

- **Use a push constant** for the per-draw data that differs (e.g. the MVP matrix):
  `.pushConstants(16 * Float.BYTES, Stage.VERTEX)` + `frame.pushConstants(mvp)`.
  Push constants are recorded per draw, so each draw keeps its own value.
- Or use **two pipeline instances** (each has its own uniform buffer).
- Or render the thing **once** into a target and reuse the texture (often what you
  wanted anyway).

A uniform that's constant across the frame (or a pipeline drawn once) has no issue.

## Lifetime

```java
target.close();        // before renderer.close()
canvasTarget.close();  // the canvas Renderer2D is renderer-owned; just close the target
```

## Next steps

- The [custom pipelines](custom-pipelines.md) guide covers the `FrameRenderer` /
  `PipelineSpec` vocabulary `drawToTarget` uses.
- The [2D graphics](2d-graphics.md) guide covers the `Renderer2D` API `createCanvas`
  gives you.
- A complete worked example (an L1 cube into a target *and* an L2 canvas, both
  sampled back as sprites) is in the demo's `Main.java`.
