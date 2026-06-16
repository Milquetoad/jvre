# 2D graphics (`Renderer2D`)

`Renderer2D` is jvre's high-level "just draw" surface. You get it from the
renderer once and use it every frame:

```java
Renderer2D g = renderer.renderer2D();
```

It is **immediate mode**: there are no shape objects to keep: each frame you call
the draw methods and they take effect now. Everything between `g.begin()` and
`g.end()` is one frame's drawing; `renderer.drawFrame()` puts it on screen.

```java
while (!window.shouldClose()) {
    window.pollEvents();
    g.begin();
    // ... draw calls ...
    g.end();
    renderer.drawFrame();
}
```

## Coordinates and colors

Drawing is in **pixels, top-left origin**: `(0, 0)` is the top-left corner, `x`
grows right, `y` grows down. The drawable size is `g.width()` × `g.height()`,
which track the window as it resizes (see [Resizing](#resizing)).

Colors are immutable `Color` values:

```java
Color.rgb(80, 160, 240)        // 0-255 channels
Color.rgba(80, 160, 240, 128)  // + alpha (128 = ~50% opaque)
Color.hex(0x50A0F0)            // packed 0xRRGGBB
Color.WHITE, Color.BLACK, Color.RED, Color.GREEN, Color.BLUE,
Color.YELLOW, Color.CYAN, Color.MAGENTA, Color.TRANSPARENT
```

sRGB ↔ linear conversion is handled internally — you think in ordinary colors.
Alpha blends against whatever is already drawn, so **draw order is back-to-front**.

## Fills

Closed shapes come in `fill…` / `stroke…` pairs. The fills:

```java
g.fillRect(x, y, w, h, color);                 // corner (x,y) + size
g.fillRoundedRect(x, y, w, h, radius, color);  // + corner radius (clamped to half the short side)
g.fillCircle(cx, cy, r, color);                // centre + radius
g.fillEllipse(cx, cy, rx, ry, color);          // centre + two radii
g.fillTriangle(x1,y1, x2,y2, x3,y3, color);    // three explicit points
g.fillQuad(x1,y1, x2,y2, x3,y3, x4,y4, color); // four points, in order around the perimeter (convex)
```

Rectangles use a **corner + size** convention (like `java.awt` / canvas); circles
and ellipses use a **centre + radius**. Circles, ellipses, and rounded rects are
drawn as analytic distance fields, so their edges stay crisp at any size or zoom,
independent of MSAA.

## Strokes

The outlines. Every stroke takes a `thickness` in pixels:

```java
g.line(x1, y1, x2, y2, thickness, color);            // a thick line segment
g.strokeRect(x, y, w, h, thickness, color);
g.strokeCircle(cx, cy, r, thickness, color);         // a ring, centred on the radius
g.strokeEllipse(cx, cy, rx, ry, thickness, color);
g.strokeTriangle(x1,y1, x2,y2, x3,y3, thickness, color);  // mitered corners
g.strokeQuad(x1,y1, x2,y2, x3,y3, x4,y4, thickness, color);
```

Strokes are centred on the path (half the thickness falls on each side). Polygon
strokes (rect/triangle/quad) use proper mitered joins; circle/ellipse strokes are
true rings.

## Text

jvre ships a built-in font (DejaVu Sans), baked once as a signed-distance-field
atlas so it stays crisp at any size from a single bake.

```java
g.text("Hello", x, y, color);              // built-in font at its natural size
g.text("Hello", x, y, 24f, color);         // at 24 px
g.text(font, "Hello", x, y, 24f, color);   // an explicit Font (see below)
```

`(x, y)` is the **top-left** of the text box. `'\n'` starts a new line.

To lay text out — centre a label, size a button, wrap lines — measure it without
drawing:

```java
float w = g.textWidth("Hello", 24f);   // on-screen width in pixels (widest line if multi-line)
float lh = g.lineHeight(24f);          // baseline-to-baseline line height
// e.g. horizontally centre in a box of width boxW:
g.text("Hello", boxX + (boxW - w) / 2f, y, 24f, color);
```

The built-in font is `renderer.font()`; you pass a `Font` to the third `text`
overload (and to `textWidth`) when you want a specific one.

## Images

Upload pixels once to get a `Texture`, then draw it as many times as you like.
Pixels are **RGBA bytes**, row-major, 4 bytes per pixel:

```java
// Build (or load) width*height*4 RGBA bytes.
byte[] pixels = /* ... */;
Texture img = renderer.createImage(pixels, width, height);

// Each frame:
g.image(img, x, y);              // draw at native size, top-left at (x, y)
g.image(img, x, y, w, h);        // scaled to fit (w, h)
```

Drawing several different textures in one frame is fine — jvre batches by texture
automatically. A `Texture` is a GPU resource you own: close it when you're done
(before the renderer), e.g. in your cleanup.

> Loading PNGs/JPEGs isn't built in yet; decode to RGBA bytes with your image
> library of choice (e.g. LWJGL's `stb_image`) and hand the bytes to
> `createImage`.

## The transform stack

Every draw call is run through the current transform. You can translate, rotate,
and scale the whole coordinate system, and **save/restore** that state with a
push/pop stack — so a transform applies to a group of shapes and then unwinds:

```java
g.push();                    // save the current transform
g.translate(400, 300);       // move the origin to the screen centre
g.rotate(angleRadians);      // rotate about that new origin
g.scale(2.0f);               // uniform scale (or scale(sx, sy))
g.fillRect(-50, -50, 100, 100, Color.RED);   // a centred, rotated, doubled square
g.pop();                     // restore -- later drawing is unaffected
```

`rotate` is in **radians**. Transforms compose in call order and are applied
CPU-side at draw time. A common pattern is to wrap a whole scene authored in a
fixed reference size in one `scale` so it grows with the window:

```java
float s = Math.min(g.width() / 800f, g.height() / 600f);
g.push();
g.scale(s);
// ... draw the scene in 800x600 reference coordinates ...
g.pop();
```

## Reading input

`window.input()` returns a per-frame **input snapshot**, refreshed automatically by
`pollEvents()`. Mouse position is in the same pixel space you draw in.

```java
Input in = window.input();

float mx = in.mouseX(), my = in.mouseY();          // cursor, in framebuffer pixels

in.mouseDown(MouseButton.LEFT);      // is the button held right now? (level)
in.mousePressed(MouseButton.LEFT);   // did it go down THIS frame? (edge -- a click's start)
in.mouseReleased(MouseButton.LEFT);  // did it go up THIS frame?

in.scrollY();                        // wheel delta this frame (up positive)

in.keyDown(Key.SPACE);               // level + edge, same as the mouse:
in.keyPressed(Key.ESCAPE);
in.keyReleased(Key.ENTER);

in.typedChars();                     // text typed this frame (layout + shift applied) -- for text fields
```

The **level vs. edge** distinction is the key one: `…Down` tells you the current
state, `…Pressed`/`…Released` tell you it *changed* this frame. A click is a
press; "is the player holding W" is a `keyDown`. Use `typedChars()` (not raw key
codes) to accumulate typed text — it already applies the keyboard layout, shift,
and auto-repeat.

## Time and animation

The renderer tracks frame time for you:

```java
float t  = renderer.time();   // seconds since the renderer started
float dt = renderer.dt();     // seconds since the previous frame
```

Drive animation by **`dt`** so motion is frame-rate independent:

```java
x += velocity * dt;                    // moves at the same speed regardless of fps
float pulse = 0.5f + 0.5f * Math.sin(renderer.time() * 3.0);  // or animate from absolute time
```

> If a frame can stall (e.g. on Windows, dragging the window border blocks the
> event loop), clamp `dt` to a sane maximum (`Math.min(renderer.dt(), 0.05f)`) so
> one long frame doesn't make a simulation jump.

## Resizing

The window is resizable; `g.width()` and `g.height()` reflect the current drawable
size each frame, and the renderer recreates its swapchain transparently. Read them
every frame rather than caching, and lay out (or scale) relative to them.

## Putting it together

A small interactive sketch — a circle that follows the mouse and pulses, with a
label:

```java
g.begin();

float r = 30f + 10f * (float) Math.sin(renderer.time() * 4.0);
Color c = window.input().mouseDown(MouseButton.LEFT) ? Color.RED : Color.CYAN;
g.fillCircle(window.input().mouseX(), window.input().mouseY(), r, c);

g.text("click and move the mouse", 20, 20, 18f, Color.WHITE);

g.end();
renderer.drawFrame();
```

## Next steps

- **[Shader effects](shader-effects.md)** — when you want a full-screen procedural
  visual, write one GLSL fragment shader and hand it to `renderer.setEffect(...)`.
- **[Custom pipelines](custom-pipelines.md)** — when you need your own geometry or
  3D, drop to the L1 escape hatch (`renderer.createPipeline(...)` + a scene
  renderer) and the `Camera` helper. You can mix custom 3D *under* a 2D overlay in
  the same frame.
