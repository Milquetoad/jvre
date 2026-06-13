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
