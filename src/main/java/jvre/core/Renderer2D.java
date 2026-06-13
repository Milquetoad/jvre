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

    /** Interleaved layout: vec2 position (pixels) + vec4 color (linear). */
    static final int FLOATS_PER_VERTEX = 6;

    private float[] verts = new float[6 * 6 * 64];  // room for ~64 rects before growing
    private int count = 0;                          // floats written this frame
    private boolean inFrame = false;

    // The owning Renderer, for live framebuffer-size queries. Null in the
    // CPU-only unit tests (which never ask for the size). The size is what
    // relative/responsive layout is composed FROM: the engine exposes it; the
    // positioning policy (centered, proportional, anchored) is the user's, by
    // design -- no coordinate modes (the mechanism/policy boundary).
    private final Renderer owner;

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
     * Centre-and-radius is the circle's natural convention (geometry; even
     * Processing defaults ellipseMode to CENTER). A circle is just the
     * {@code rx == ry} ellipse, so this delegates to {@link #fillEllipse}.
     */
    public void fillCircle(float cx, float cy, float r, Color color) {
        requireInFrame("fillCircle");
        if (r < 0f) {
            throw new IllegalArgumentException("fillCircle: negative radius (" + r + ")");
        }
        fillEllipse(cx, cy, r, r, color);
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

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    private void vertex(float px, float py, float[] c) {
        ensureCapacity(FLOATS_PER_VERTEX);
        verts[count++] = px;
        verts[count++] = py;
        verts[count++] = c[0];
        verts[count++] = c[1];
        verts[count++] = c[2];
        verts[count++] = c[3];
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
