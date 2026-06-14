package jvre.core;

import java.util.Arrays;

/**
 * The L2 "just draw" surface -- the high-level altitude from
 * {@code API Vision - Layered Altitudes}. The user calls {@code begin()}, a
 * series of stateless drawing calls, then {@code end()} once per frame; jvre
 * turns them into vertices and draws them. No shaders, buffers, or pipelines in
 * sight.
 *
 * Design (from the settled L2 spec): stateless call sites (no modes), one
 * natural convention per shape, pixels with a top-left origin, immediate mode
 * (the user owns the loop), and errors that speak L2 (a plain message, never a
 * Vulkan VUID).
 *
 * This class is the CPU half: it accumulates an interleaved {@code [x y | r g b
 * a]} vertex stream into a growable arena during a frame. The GPU half -- a
 * dynamic per-frame vertex buffer + a shape pipeline + the draw -- lives in the
 * {@link Renderer} (it owns the swapchain formats a pipeline must bake), and
 * consumes {@link #vertexData()} / {@link #floatCount()} during command
 * recording. Constructed by the Renderer, never by the user directly.
 */
public final class Renderer2D {

    // Interleaved layout: vec2 position (pixels) + vec4 color (linear) + vec2
    // local (SDF pixel offset within the shape) + vec2 half (SDF box half-extents)
    // + float cornerRadius (px) + vec2 uv (texture coord) + float mode. The MODE
    // selects what the fragment shader does: 0 = flat (full coverage, flat color),
    // 1 = SDF rounded box (uses local/half/cornerRadius), 2 = textured image
    // (uses uv), 3 = SDF text (uses uv). Flat shapes pass mode 0 and leave the
    // SDF/uv fields zero; SDF shapes (circle, rounded-rect) pass mode 1 + the box
    // fields. One layout, one batch -> draw order is preserved across every kind.
    static final int FLOATS_PER_VERTEX = 14;

    private float[] verts = new float[6 * 6 * 64];  // room for ~64 rects before growing
    private int count = 0;                          // floats written this frame
    private boolean inFrame = false;

    // The owning Renderer, for live framebuffer-size queries. Null in the
    // CPU-only unit tests (which never ask for the size). The size is what
    // relative/responsive layout is composed FROM: the engine exposes it; the
    // positioning policy (centered, proportional, anchored) is the user's, by
    // design -- no coordinate modes (the mechanism/policy boundary).
    private final Renderer owner;

    // Draw RUNS -- the flush-on-texture-switch batching. The vertex arena stays
    // one buffer in paint order; runs slice it into contiguous ranges that each
    // need a different texture bound. runFirst[i] = the first vertex of run i;
    // runTex[i] = the texture for run i (null -> the 1x1 white default). A run
    // ends where the next begins (the last runs to vertexCount()). Flat/SDF
    // shapes join the current run (they ignore the texture); an image() with a
    // DIFFERENT texture opens a new run. So draw order is preserved across any
    // mix of shapes and images -- the Renderer draws the runs in order, binding
    // each run's texture. (Modeled on raylib's rlgl default batch.)
    private int[] runFirst = new int[8];
    private Texture[] runTex = new Texture[8];
    private int runCount = 0;

    Renderer2D() { this.owner = null; }              // CPU-only (tests)
    Renderer2D(Renderer owner) { this.owner = owner; }  // the Renderer vends this one

    /** Current framebuffer width in pixels -- the basis for relative layout. */
    public int width() {
        return owner.framebufferWidth();
    }

    /** Current framebuffer height in pixels. */
    public int height() {
        return owner.framebufferHeight();
    }

    /**
     * Open a frame's worth of drawing. Every {@code begin()} must be matched by
     * an {@code end()}; the accumulated geometry from the previous frame is
     * cleared here.
     */
    public void begin() {
        if (inFrame) {
            throw new IllegalStateException("Renderer2D.begin() called again without an end()");
        }
        inFrame = true;
        count = 0;
        runCount = 0;
    }

    /** Close the frame. The accumulated shapes are now ready to be drawn. */
    public void end() {
        if (!inFrame) {
            throw new IllegalStateException("Renderer2D.end() called without a matching begin()");
        }
        inFrame = false;
    }

    /**
     * Fill an axis-aligned rectangle: corner {@code (x, y)} (top-left) plus size
     * {@code w x h}, in pixels. Corner-and-size is the rect's natural convention
     * (UI, text layout, canvas/awt intuition); a centered form would be a
     * distinct name, never a mode.
     */
    public void fillRect(float x, float y, float w, float h, Color color) {
        requireInFrame("fillRect");
        if (w < 0f || h < 0f) {
            throw new IllegalArgumentException("fillRect: negative size (" + w + " x " + h + ")");
        }
        float[] c = color.linearRGBA();
        // Two triangles covering the rect. Cull is NONE for 2D, so winding is
        // irrelevant -- the corners are listed in a simple, readable order.
        vertex(x,     y,     c);
        vertex(x + w, y,     c);
        vertex(x + w, y + h, c);
        vertex(x + w, y + h, c);
        vertex(x,     y + h, c);
        vertex(x,     y,     c);
    }

    /**
     * Fill a circle: centre {@code (cx, cy)} + radius {@code r}, in pixels.
     *
     * This is jvre's first SDF (signed-distance-field) shape, and the seed of
     * the path rounded-rects and text will reuse. Instead of a tessellated fan,
     * it emits ONE bounding quad; each corner carries its pixel offset from the
     * centre (the SDF "local" coord) and the radius. The fragment shader then
     * computes the exact distance to the rim per pixel and fades alpha across
     * ~1px -- an analytic, resolution-independent edge (no facets at any zoom,
     * crisper than coverage sampling). It rides the SAME batch and pipeline as
     * the flat shapes (which carry mode 0), so draw order is preserved: to the
     * L2 caller this is still just {@code fillCircle}, technique invisible.
     *
     * (An ELLIPSE has no closed-form signed distance, so {@link #fillEllipse}
     * stays a fan for now; that's why the circle no longer delegates to it.)
     */
    public void fillCircle(float cx, float cy, float r, Color color) {
        requireInFrame("fillCircle");
        if (r < 0f) {
            throw new IllegalArgumentException("fillCircle: negative radius (" + r + ")");
        }
        // A circle is the square rounded box with maximal corner radius.
        sdfBox(cx, cy, r, r, r, color.linearRGBA());
    }

    /**
     * Fill a rounded rectangle: corner {@code (x, y)} + size {@code w x h} (like
     * {@link #fillRect}) plus a corner {@code radius}, in pixels. jvre's second
     * SDF shape -- same rounded-box distance field as the circle, just with
     * unequal half-extents and a smaller corner radius. The straight edges and
     * the rounded corners are all analytically anti-aliased.
     *
     * The radius is clamped to half the shorter side (a larger value can't round
     * any harder -- at exactly half the short side a rounded rect IS a stadium /
     * circle). radius = 0 gives a sharp-cornered SDF rect.
     */
    public void fillRoundedRect(float x, float y, float w, float h, float radius, Color color) {
        requireInFrame("fillRoundedRect");
        if (w < 0f || h < 0f) {
            throw new IllegalArgumentException("fillRoundedRect: negative size (" + w + " x " + h + ")");
        }
        if (radius < 0f) {
            throw new IllegalArgumentException("fillRoundedRect: negative radius (" + radius + ")");
        }
        float hx = w * 0.5f;
        float hy = h * 0.5f;
        float cr = Math.min(radius, Math.min(hx, hy));   // can't exceed half the short side
        sdfBox(x + hx, y + hy, hx, hy, cr, color.linearRGBA());
    }

    /**
     * Fill an ellipse: centre {@code (cx, cy)} + radii {@code rx, ry}, in pixels.
     *
     * Tessellated into a triangle FAN -- one slice {@code (centre, rim_i,
     * rim_i+1)} per segment -- emitted as a flat triangle list (the arena has no
     * index buffer). The segment count scales with the LARGER radius so a big
     * ellipse stays smooth and a tiny one stays cheap. This hard-tessellated edge
     * is what the planned SDF edge-AA will later soften (smoothstepping alpha
     * over ~1px of signed distance), which is why curves get their own shader
     * path eventually.
     */
    public void fillEllipse(float cx, float cy, float rx, float ry, Color color) {
        requireInFrame("fillEllipse");
        if (rx < 0f || ry < 0f) {
            throw new IllegalArgumentException("fillEllipse: negative radius (" + rx + ", " + ry + ")");
        }
        float[] c = color.linearRGBA();
        int segments = circleSegments(Math.max(rx, ry));
        float step = (float) (2.0 * Math.PI / segments);

        // Walk the rim once; each step makes a triangle from the centre to the
        // current and next rim points. Start at angle 0 (the +x point).
        float prevX = cx + rx;
        float prevY = cy;
        for (int i = 1; i <= segments; i++) {
            float a = i * step;
            float nextX = cx + rx * (float) Math.cos(a);
            float nextY = cy + ry * (float) Math.sin(a);
            vertex(cx, cy, c);
            vertex(prevX, prevY, c);
            vertex(nextX, nextY, c);
            prevX = nextX;
            prevY = nextY;
        }
    }

    /**
     * Fill a triangle from three explicit vertices, in pixels. Explicit vertices
     * are the triangle's natural convention (no corner-vs-centre question).
     */
    public void fillTriangle(float x1, float y1, float x2, float y2, float x3, float y3,
                             Color color) {
        requireInFrame("fillTriangle");
        float[] c = color.linearRGBA();
        vertex(x1, y1, c);
        vertex(x2, y2, c);
        vertex(x3, y3, c);
    }

    /**
     * Fill a convex quad from four explicit vertices (in order around the
     * perimeter), in pixels. Triangulated on the 0-2 diagonal: (v0,v1,v2) +
     * (v0,v2,v3) -- the standard convex split, the same diagonal the index
     * buffer used for the quad/cube faces. (Non-convex quads will fold; convex
     * is the documented contract, matching the spec.)
     */
    public void fillQuad(float x1, float y1, float x2, float y2,
                         float x3, float y3, float x4, float y4, Color color) {
        requireInFrame("fillQuad");
        float[] c = color.linearRGBA();
        vertex(x1, y1, c);
        vertex(x2, y2, c);
        vertex(x3, y3, c);
        vertex(x1, y1, c);
        vertex(x3, y3, c);
        vertex(x4, y4, c);
    }

    // ------------------------------------------------------------------
    // Images (the first gated content primitive). A textured quad: same two
    // triangles as fillRect, but each corner also carries a UV, and the vertices
    // are tagged mode 2 so the fragment shader samples the bound texture instead
    // of using a flat color. Rides the SAME batch and pipeline as every other
    // shape -- draw order preserved -- with the texture bound as the shape
    // pipeline's one descriptor.
    // ------------------------------------------------------------------

    /**
     * Draw {@code img} at its natural pixel size, top-left corner at {@code (x,
     * y)}. Convenience for {@link #image(Texture, float, float, float, float)}
     * with {@code w = img.width()}, {@code h = img.height()}.
     */
    public void image(Texture img, float x, float y) {
        image(img, x, y, img.width(), img.height());
    }

    /**
     * Draw {@code img} into the rectangle corner {@code (x, y)} + size {@code w x
     * h}, in pixels (scaled to fit; the sampler decides the filtering). Corner +
     * size matches {@link #fillRect} -- an image is a textured rectangle.
     *
     * Any number of images and textures per frame: an image whose texture differs
     * from the current run opens a new {@linkplain #runCount() draw run}. Drawing
     * the same texture repeatedly (an atlas / sprite sheet) all batches into one
     * run; alternating textures costs one draw per switch -- so group same-texture
     * draws when it matters.
     */
    public void image(Texture img, float x, float y, float w, float h) {
        requireInFrame("image");
        if (img == null) {
            throw new IllegalArgumentException("image: null texture");
        }
        if (w < 0f || h < 0f) {
            throw new IllegalArgumentException("image: negative size (" + w + " x " + h + ")");
        }
        useTexture(img);

        // White tint = the texture passes through unchanged (mode 2 multiplies the
        // sample by this color). UVs map the quad's corners across the whole
        // texture: top-left (0,0) -> bottom-right (1,1). Pixel coords are y-down
        // and textures upload top-to-bottom, so there is no V flip.
        float[] c = Color.WHITE.linearRGBA();
        imageVertex(x,     y,     c, 0f, 0f);
        imageVertex(x + w, y,     c, 1f, 0f);
        imageVertex(x + w, y + h, c, 1f, 1f);
        imageVertex(x + w, y + h, c, 1f, 1f);
        imageVertex(x,     y + h, c, 0f, 1f);
        imageVertex(x,     y,     c, 0f, 0f);
    }

    /** Append one textured-image vertex (mode 2, carrying a UV; SDF fields unused). */
    private void imageVertex(float px, float py, float[] c, float u, float v) {
        emit(px, py, c, 0f, 0f, 0f, 0f, 0f, u, v, MODE_IMAGE);
    }

    // ------------------------------------------------------------------
    // Text (the second gated content primitive). One quad per glyph, sampling
    // the font's SDF atlas (mode 3). The atlas is one texture, so a whole string
    // -- and consecutive text() calls in the same font -- batch into one run.
    // ------------------------------------------------------------------

    /** Draw {@code s} at the built-in font's natural size, top-left at {@code (x, y)}. */
    public void text(String s, float x, float y, Color color) {
        Font font = owner.font();
        text(font, s, x, y, font.naturalSize(), color);
    }

    /** Draw {@code s} at {@code size} pixels (cap height ~ size), top-left at {@code (x, y)},
     *  in the built-in font. */
    public void text(String s, float x, float y, float size, Color color) {
        text(owner.font(), s, x, y, size, color);
    }

    /**
     * Draw {@code s} in {@code font} at {@code size} pixels, the text box's
     * top-left corner at {@code (x, y)} (corner convention, like {@link
     * #fillRect}). Walks the string advancing a pen along the baseline; each glyph
     * is a textured quad sampling the font's SDF atlas (mode 3), placed by its
     * metrics. One bake size renders at any {@code size} -- the SDF scales for
     * free. {@code '\n'} starts a new line; characters outside the baked range are
     * skipped (advanced like a space).
     */
    public void text(Font font, String s, float x, float y, float size, Color color) {
        requireInFrame("text");
        if (font == null) {
            throw new IllegalArgumentException("text: null font");
        }
        if (size < 0f) {
            throw new IllegalArgumentException("text: negative size (" + size + ")");
        }
        if (s.isEmpty()) {
            return;
        }
        useTexture(font.atlas());
        float[] c = color.linearRGBA();
        float scale = font.scaleFor(size);
        float startX = x;
        float penX = x;
        float baseline = y + font.ascent() * scale;   // (x, y) is the TOP-left; drop to the baseline

        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\n') {
                penX = startX;
                baseline += font.lineHeight() * scale;
                continue;
            }
            Font.Glyph g = font.glyph(ch);
            if (g == null) {
                Font.Glyph space = font.glyph(' ');
                penX += (space != null ? space.advance : size * 0.3f) * scale;
                continue;
            }
            if (g.w > 0f) {   // whitespace has no quad, only an advance
                float qx = penX + g.xoff * scale;
                float qy = baseline + g.yoff * scale;
                float qw = g.w * scale;
                float qh = g.h * scale;
                textVertex(qx,      qy,      c, g.u0, g.v0);
                textVertex(qx + qw, qy,      c, g.u1, g.v0);
                textVertex(qx + qw, qy + qh, c, g.u1, g.v1);
                textVertex(qx + qw, qy + qh, c, g.u1, g.v1);
                textVertex(qx,      qy + qh, c, g.u0, g.v1);
                textVertex(qx,      qy,      c, g.u0, g.v0);
            }
            penX += g.advance * scale;
        }
    }

    /** Append one SDF-text vertex (mode 3, carrying an atlas UV; SDF box fields unused). */
    private void textVertex(float px, float py, float[] c, float u, float v) {
        emit(px, py, c, 0f, 0f, 0f, 0f, 0f, u, v, MODE_TEXT);
    }

    /**
     * Note that the next vertices will sample {@code tex}, opening a new draw run
     * if the current run is already committed to a different texture. Three cases:
     * no run yet -> start run 0 with tex; current run has no texture yet (only
     * flat/SDF shapes, which don't sample) -> adopt tex for it; current run
     * already uses a different texture -> split a new run at the current vertex.
     * (Same texture again: nothing -- it just keeps batching.)
     */
    private void useTexture(Texture tex) {
        if (runCount == 0) {
            pushRun(0, tex);
        } else if (runTex[runCount - 1] == null) {
            runTex[runCount - 1] = tex;            // adopt: flat shapes share this run
        } else if (runTex[runCount - 1] != tex) {
            pushRun(vertexCount(), tex);           // switch: new run from here on
        }
    }

    /** Append a run starting at {@code firstVertex} with texture {@code tex}. */
    private void pushRun(int firstVertex, Texture tex) {
        if (runCount == runFirst.length) {
            runFirst = Arrays.copyOf(runFirst, runFirst.length * 2);
            runTex = Arrays.copyOf(runTex, runTex.length * 2);
        }
        runFirst[runCount] = firstVertex;
        runTex[runCount] = tex;
        runCount++;
    }

    // ------------------------------------------------------------------
    // Strokes (the first one; the rest build on it). No GPU line width:
    // Vulkan's lineWidth > 1 is an optional, non-portable feature, so every
    // stroke is TRIANGULATED on the CPU and feeds the same shape batch.
    // ------------------------------------------------------------------

    /**
     * A straight line from {@code (x1, y1)} to {@code (x2, y2)}, {@code
     * thickness} pixels wide. A line is inherently a stroke (no fill/stroke
     * pair), so it carries its own thickness.
     *
     * The geometry IS a quad: offset both endpoints by +/-thickness/2 along the
     * line's NORMAL (the perpendicular), and the four corners form a rectangle
     * the long way down the line. So this just computes the corners and hands
     * them to {@link #fillQuad} -- "a thick line is a quad" made literal. Ends
     * are square (butt caps), flush with the endpoints. Joins between segments
     * are the next strokes' problem; a lone line has none.
     */
    public void line(float x1, float y1, float x2, float y2, float thickness, Color color) {
        requireInFrame("line");
        if (thickness < 0f) {
            throw new IllegalArgumentException("line: negative thickness (" + thickness + ")");
        }
        float dx = x2 - x1;
        float dy = y2 - y1;
        float len = (float) Math.hypot(dx, dy);
        if (len < 1e-6f) {
            return;  // zero-length line: nothing to draw (and no normal to take)
        }
        // Unit normal (perpendicular to the line direction), scaled to half the
        // thickness: rotate the direction 90 degrees -> (-dy, dx), normalize, * h.
        float h = thickness * 0.5f;
        float nx = -dy / len * h;
        float ny = dx / len * h;
        fillQuad(
                x1 + nx, y1 + ny,   // start, +normal
                x2 + nx, y2 + ny,   // end,   +normal
                x2 - nx, y2 - ny,   // end,   -normal
                x1 - nx, y1 - ny,   // start, -normal
                color);
    }

    /**
     * Stroke (outline) a rectangle: same corner+size as {@link #fillRect}, plus
     * a {@code thickness}. The stroke is CENTERED on the boundary (the
     * canvas/SVG convention) -- half the thickness spills outward, half inward.
     *
     * Built as an 8-triangle FRAME, not four {@link #line} calls: four edge
     * bands that tile the border WITHOUT overlapping. The top and bottom bands
     * take the full outer width (corners included); the left and right take only
     * the span between them. The non-overlap matters: a TRANSLUCENT stroke that
     * double-covered its corners would blend to a different color there. (That
     * same reasoning is why a filled+stroked shape will eventually need one
     * combined call rather than two -- the reserved Style form.)
     */
    public void strokeRect(float x, float y, float w, float h, float thickness, Color color) {
        requireInFrame("strokeRect");
        if (w < 0f || h < 0f) {
            throw new IllegalArgumentException("strokeRect: negative size (" + w + " x " + h + ")");
        }
        if (thickness < 0f) {
            throw new IllegalArgumentException("strokeRect: negative thickness (" + thickness + ")");
        }
        // Outer rect = the boundary inflated by half the thickness (centered stroke).
        float ht = thickness * 0.5f;
        float ox = x - ht;
        float oy = y - ht;
        float ow = w + thickness;
        float oh = h + thickness;

        fillRect(ox, oy, ow, thickness, color);                    // top band (full width)
        fillRect(ox, oy + oh - thickness, ow, thickness, color);   // bottom band (full width)
        float midH = oh - 2f * thickness;                          // span between top + bottom
        if (midH > 0f) {
            fillRect(ox, oy + thickness, thickness, midH, color);                  // left band
            fillRect(ox + ow - thickness, oy + thickness, thickness, midH, color); // right band
        }
    }

    /**
     * Stroke a circle: a ring of width {@code thickness} centered on the radius
     * {@code r}. The {@code rx == ry} case of {@link #strokeEllipse}.
     */
    public void strokeCircle(float cx, float cy, float r, float thickness, Color color) {
        requireInFrame("strokeCircle");
        if (r < 0f) {
            throw new IllegalArgumentException("strokeCircle: negative radius (" + r + ")");
        }
        strokeEllipse(cx, cy, r, r, thickness, color);
    }

    /**
     * Stroke an ellipse: a RING between an outer and an inner rim, the stroke
     * centered on the radii (outer = r + thickness/2, inner = r - thickness/2).
     * Unlike the polygon strokes there is no join question -- the curve is
     * smooth, so each tessellation segment is just a quad spanning outer-to-inner
     * across that slice, and consecutive quads share an edge with no corner gap.
     * (The inner rim is clamped at 0, so a thickness wider than the diameter
     * degenerates cleanly to a filled disc rather than inverting.)
     */
    public void strokeEllipse(float cx, float cy, float rx, float ry, float thickness, Color color) {
        requireInFrame("strokeEllipse");
        if (rx < 0f || ry < 0f) {
            throw new IllegalArgumentException("strokeEllipse: negative radius (" + rx + ", " + ry + ")");
        }
        if (thickness < 0f) {
            throw new IllegalArgumentException("strokeEllipse: negative thickness (" + thickness + ")");
        }
        float ht = thickness * 0.5f;
        float orx = rx + ht, ory = ry + ht;                   // outer radii
        float irx = Math.max(0f, rx - ht), iry = Math.max(0f, ry - ht);  // inner radii (clamped)
        float[] c = color.linearRGBA();
        int segments = circleSegments(Math.max(orx, ory));
        float step = (float) (2.0 * Math.PI / segments);

        float poX = cx + orx, poY = cy;   // previous OUTER rim point (angle 0)
        float piX = cx + irx, piY = cy;   // previous INNER rim point
        for (int i = 1; i <= segments; i++) {
            float a = i * step;
            float ca = (float) Math.cos(a);
            float sa = (float) Math.sin(a);
            float noX = cx + orx * ca, noY = cy + ory * sa;   // next outer
            float niX = cx + irx * ca, niY = cy + iry * sa;   // next inner
            // The slice's quad (prevOuter, nextOuter, nextInner, prevInner) as
            // two triangles.
            vertex(poX, poY, c);
            vertex(noX, noY, c);
            vertex(niX, niY, c);
            vertex(poX, poY, c);
            vertex(niX, niY, c);
            vertex(piX, piY, c);
            poX = noX; poY = noY;
            piX = niX; piY = niY;
        }
    }

    /**
     * Stroke the outline of a triangle from three explicit vertices.
     * @see #strokePolygon
     */
    public void strokeTriangle(float x1, float y1, float x2, float y2, float x3, float y3,
                               float thickness, Color color) {
        requireInFrame("strokeTriangle");
        strokePolygon(new float[] { x1, x2, x3 }, new float[] { y1, y2, y3 },
                thickness, color, "strokeTriangle");
    }

    /**
     * Stroke the outline of a convex quad from four explicit vertices (in order
     * around the perimeter).
     * @see #strokePolygon
     */
    public void strokeQuad(float x1, float y1, float x2, float y2,
                           float x3, float y3, float x4, float y4,
                           float thickness, Color color) {
        requireInFrame("strokeQuad");
        strokePolygon(new float[] { x1, x2, x3, x4 }, new float[] { y1, y2, y3, y4 },
                thickness, color, "strokeQuad");
    }

    /**
     * Stroke a closed polygon -- the shared engine behind the polygon outlines,
     * and the first place the JOIN question matters: where two thick edges meet,
     * the corner must be filled cleanly, not gapped (butt caps) or double-covered.
     *
     * The construction is the MITER join. Offset each vertex along its angle
     * BISECTOR -- the average of the two adjacent edge normals -- by {@code
     * halfThickness / cos(half-angle)}. That extra {@code 1/cos} is what makes
     * the offset EDGES land exactly halfThickness from the originals (a plain
     * halfThickness offset along the bisector would pull the corners in). Doing
     * it for +bisector and -bisector gives an outer and inner corner point; the
     * ring between consecutive corners is two triangles. Winding-agnostic: we go
     * symmetrically either way, so the band is centred on the boundary regardless
     * of vertex order.
     *
     * The {@code cos} is clamped (a cheap miter LIMIT): at a very sharp corner a
     * true miter spikes to infinity, so we cap the extension. Fine for the convex
     * triangles/quads this serves; a real bevel/round-join fallback is a later
     * refinement (and SDF strokes will reopen the whole topic).
     */
    private void strokePolygon(float[] xs, float[] ys, float thickness, Color color, String call) {
        if (thickness < 0f) {
            throw new IllegalArgumentException(call + ": negative thickness (" + thickness + ")");
        }
        int n = xs.length;
        float ht = thickness * 0.5f;
        float[] mvx = new float[n];   // miter direction x
        float[] mvy = new float[n];   // miter direction y
        float[] mlen = new float[n];  // miter length

        for (int i = 0; i < n; i++) {
            int prev = (i - 1 + n) % n;
            int next = (i + 1) % n;
            // Unit directions of the incoming (prev->i) and outgoing (i->next) edges.
            float e1x = xs[i] - xs[prev], e1y = ys[i] - ys[prev];
            float l1 = Math.max(1e-6f, (float) Math.hypot(e1x, e1y));
            e1x /= l1; e1y /= l1;
            float e2x = xs[next] - xs[i], e2y = ys[next] - ys[i];
            float l2 = Math.max(1e-6f, (float) Math.hypot(e2x, e2y));
            e2x /= l2; e2y /= l2;
            // Left normals of each edge, and the bisector = their normalized sum.
            float n1x = -e1y, n1y = e1x;
            float n2x = -e2y, n2y = e2x;
            float bx = n1x + n2x, by = n1y + n2y;
            float bl = (float) Math.hypot(bx, by);
            if (bl < 1e-6f) {   // ~180-degree turn: fall back to one edge normal
                bx = n1x; by = n1y; bl = 1f;
            }
            bx /= bl; by /= bl;
            float cos = Math.max(0.1f, bx * n1x + by * n1y);   // clamp = cheap miter limit
            mvx[i] = bx; mvy[i] = by; mlen[i] = ht / cos;
        }

        float[] c = color.linearRGBA();
        for (int i = 0; i < n; i++) {
            int next = (i + 1) % n;
            float oiX = xs[i] + mvx[i] * mlen[i],    oiY = ys[i] + mvy[i] * mlen[i];     // corner i, +bisector
            float iiX = xs[i] - mvx[i] * mlen[i],    iiY = ys[i] - mvy[i] * mlen[i];     // corner i, -bisector
            float onX = xs[next] + mvx[next] * mlen[next], onY = ys[next] + mvy[next] * mlen[next];
            float inX = xs[next] - mvx[next] * mlen[next], inY = ys[next] - mvy[next] * mlen[next];
            // The band for edge i->next: quad (i+, next+, next-, i-) as two triangles.
            vertex(oiX, oiY, c);
            vertex(onX, onY, c);
            vertex(inX, inY, c);
            vertex(oiX, oiY, c);
            vertex(inX, inY, c);
            vertex(iiX, iiY, c);
        }
    }

    /**
     * How many segments to tessellate a circle of radius {@code r} into. Derived
     * from a target chord error (~0.3px): the angle whose chord deviates from the
     * arc by that much is {@code 2*acos(1 - e/r)}, and a full turn divided by it
     * is the count. Clamped to a sane band so tiny circles still look round and
     * huge ones don't explode the vertex count.
     */
    private static int circleSegments(float r) {
        if (r <= 0.5f) {
            return 6;
        }
        double maxError = 0.3;  // pixels
        double theta = 2.0 * Math.acos(Math.max(-1.0, 1.0 - maxError / r));
        int segments = (int) Math.ceil(2.0 * Math.PI / theta);
        return Math.max(8, Math.min(segments, 512));
    }

    // ------------------------------------------------------------------
    // package-private: what the Renderer reads to draw the batch
    // ------------------------------------------------------------------

    /** The interleaved vertex arena (only the first {@link #floatCount()} floats are live). */
    float[] vertexData() {
        return verts;
    }

    /** Live float count this frame (0 means nothing to draw). */
    int floatCount() {
        return count;
    }

    /** Live vertex count this frame (floatCount / FLOATS_PER_VERTEX). */
    int vertexCount() {
        return count / FLOATS_PER_VERTEX;
    }

    /** Number of draw runs this frame (>= 1 once anything is drawn). */
    int runCount() {
        return runCount;
    }

    /** First vertex of run {@code i}. The run ends at the next run's first vertex,
     *  or at {@link #vertexCount()} for the last run. */
    int runFirstVertex(int i) {
        return runFirst[i];
    }

    /** Texture for run {@code i}, or null -- the Renderer binds the white default
     *  for a null (flat/SDF-only) run. */
    Texture runTexture(int i) {
        return runTex[i];
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    // Mode selectors -- mirror the fragment shader's per-vertex switch.
    private static final float MODE_FLAT  = 0f;  // full coverage, flat color
    private static final float MODE_SDF   = 1f;  // SDF rounded box (local/half/cornerRadius)
    private static final float MODE_IMAGE = 2f;  // textured image (samples the bound texture at uv)
    private static final float MODE_TEXT  = 3f;  // SDF text (samples the glyph atlas distance at uv)

    /** Append a FLAT-shape vertex: full coverage, flat color (SDF/uv fields unused). */
    private void vertex(float px, float py, float[] c) {
        emit(px, py, c, 0f, 0f, 0f, 0f, 0f, 0f, 0f, MODE_FLAT);
    }

    /**
     * Append the lowest-level vertex: every field explicit. {@code local} is the
     * SDF pixel offset within the shape, {@code half} the box half-extents,
     * {@code cornerRadius} the corner rounding in px, {@code u/v} the texture
     * coordinate, {@code mode} the fragment-shader selector (see the layout note
     * up top). The one emit every path funnels through.
     */
    private void emit(float px, float py, float[] c, float localX, float localY,
                      float halfX, float halfY, float cornerRadius,
                      float u, float v, float mode) {
        // Every shape lives in some run. The first vertex of the frame opens run 0
        // (texture null -> the white default); image() may later adopt or split it.
        if (runCount == 0) {
            pushRun(0, null);
        }
        ensureCapacity(FLOATS_PER_VERTEX);
        verts[count++] = px;
        verts[count++] = py;
        verts[count++] = c[0];
        verts[count++] = c[1];
        verts[count++] = c[2];
        verts[count++] = c[3];
        verts[count++] = localX;
        verts[count++] = localY;
        verts[count++] = halfX;
        verts[count++] = halfY;
        verts[count++] = cornerRadius;
        verts[count++] = u;
        verts[count++] = v;
        verts[count++] = mode;
    }

    /**
     * Emit a rounded-box SDF shape's bounding quad. Both {@link #fillCircle} and
     * {@link #fillRoundedRect} funnel through here -- a circle is the square box
     * with maximal corner radius. The quad is padded ~1px past the shape so the
     * soft edge has room to ramp; each corner carries its pixel offset from the
     * centre (the SDF local coord), the half-extents, and the corner radius.
     */
    private void sdfBox(float cx, float cy, float halfX, float halfY,
                        float cornerRadius, float[] c) {
        float px = halfX + 1.5f;
        float py = halfY + 1.5f;
        emit(cx - px, cy - py, c, -px, -py, halfX, halfY, cornerRadius, 0f, 0f, MODE_SDF);
        emit(cx + px, cy - py, c,  px, -py, halfX, halfY, cornerRadius, 0f, 0f, MODE_SDF);
        emit(cx + px, cy + py, c,  px,  py, halfX, halfY, cornerRadius, 0f, 0f, MODE_SDF);
        emit(cx - px, cy - py, c, -px, -py, halfX, halfY, cornerRadius, 0f, 0f, MODE_SDF);
        emit(cx + px, cy + py, c,  px,  py, halfX, halfY, cornerRadius, 0f, 0f, MODE_SDF);
        emit(cx - px, cy + py, c, -px,  py, halfX, halfY, cornerRadius, 0f, 0f, MODE_SDF);
    }

    private void ensureCapacity(int more) {
        if (count + more > verts.length) {
            verts = Arrays.copyOf(verts, Math.max(verts.length * 2, count + more));
        }
    }

    private void requireInFrame(String call) {
        if (!inFrame) {
            throw new IllegalStateException("Renderer2D." + call + "() called outside begin()/end()");
        }
    }
}
