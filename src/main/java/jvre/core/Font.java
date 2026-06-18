package jvre.core;

import org.lwjgl.PointerBuffer;
import org.lwjgl.stb.STBTTFontinfo;
import org.lwjgl.stb.STBTTVertex;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.msdfgen.MSDFGenBitmap;
import org.lwjgl.util.msdfgen.MSDFGenTransform;
import org.lwjgl.util.msdfgen.MSDFGenVector2;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.stb.STBTruetype.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memAlloc;
import static org.lwjgl.system.MemoryUtil.memFree;
import static org.lwjgl.util.msdfgen.MSDFGen.*;

/**
 * A font baked into a SIGNED-DISTANCE-FIELD glyph atlas -- the resource behind
 * the L2 {@link Renderer2D#text}. This is jvre's second use of an SDF (after the
 * analytic circle/rounded-rect), and a different KIND: those compute the field
 * in the shader; text SAMPLES a precomputed field from a texture.
 *
 * Why SDF for text? One atlas, baked once at a fixed pixel height, renders crisp
 * at ANY size -- the fragment shader thresholds the sampled distance with a
 * smoothstep whose width tracks the on-screen scale. A plain bitmap atlas would
 * blur when scaled up and alias when scaled down. (Single-channel SDF rounds off
 * very sharp corners at tiny sizes; MSDF fixes that and is the catalogued later
 * refinement -- the same ship-v1-refine-later pattern as miter->bevel.)
 *
 * The actual glyph rasterization is stb_truetype's job (a proven library beats
 * hand-parsing TrueType outlines -- font hinting/rasterization is tangential to
 * the graphics core). jvre's part is the ATLAS: pack every glyph's SDF bitmap
 * into one R8 texture (one texture -> one {@linkplain Renderer2D#runCount draw
 * run}), and keep each glyph's placement metrics for layout.
 */
public final class Font {

    // The ASCII printable range we bake (space .. tilde). Enough for v1; a wider
    // range (or on-demand glyph baking) is a later refinement.
    private static final int FIRST_CHAR = 32;
    private static final int LAST_CHAR = 126;

    // SDF bake parameters. PADDING = how many pixels around the glyph the distance
    // field spreads (the soft-edge headroom). ONEDGE = the byte value at the glyph
    // EDGE (distance 0); inside is larger, outside smaller. PIXEL_DIST_SCALE maps
    // one pixel of distance to byte units so the full PADDING spans the 0..ONEDGE
    // range. The shader thresholds at ONEDGE/255 ~= 0.5.
    private static final int PADDING = 5;
    private static final byte ONEDGE = (byte) 128;
    private static final float PIXEL_DIST_SCALE = 128.0f / PADDING;

    // MSDF bake parameters (the opt-in crisp-corner path). PX_RANGE is the distance
    // field's spread in atlas pixels -- it MUST match the shader's reconstruction
    // constant (shape2d.frag, MSDF_PX_RANGE) or the edge AA mis-scales. MSDF_PADDING
    // gives the field headroom around each glyph, like PADDING for the SDF path.
    private static final float PX_RANGE = 4.0f;
    private static final int MSDF_PADDING = 6;
    // The angle (radians) below which a corner is "sharp" enough to split edge
    // colours -- msdfgen's edgeColoringSimple threshold; 3.0 is its usual default.
    private static final double EDGE_COLOR_ANGLE = 3.0;

    // The atlas is a fixed-width sheet; height is trimmed to what the glyphs use.
    private static final int ATLAS_WIDTH = 512;
    private static final int ATLAS_MAX_HEIGHT = 512;
    private static final int GLYPH_GAP = 1;   // 1px gap so LINEAR sampling can't bleed neighbors

    /** One glyph's atlas location + placement metrics, all in PIXELS at bake size. */
    static final class Glyph {
        float u0, v0, u1, v1;   // atlas UV rect (normalized)
        float xoff, yoff;       // pen-origin -> quad top-left (yoff < 0 = above baseline)
        float w, h;             // quad size (= bitmap size, padding included)
        float advance;          // how far to move the pen after this glyph
    }

    private static final int RANGE = LAST_CHAR - FIRST_CHAR + 1;   // baked codepoint count

    private final Texture atlas;
    private final boolean msdf;       // true = RGBA8 multi-channel (mode 6); false = R8 single-channel (mode 3)
    private final float bakeHeight;   // the pixel height glyphs were baked at
    private final float ascent;       // baseline offset from the top, px @ bake size
    private final float descent;      // px @ bake size (negative)
    private final float lineGap;      // px @ bake size
    private final Glyph[] glyphs;     // indexed by codepoint - FIRST_CHAR
    // Kerning: per glyph-PAIR spacing adjustment, in px @ bake size, indexed
    // [(left-FIRST)*RANGE + (right-FIRST)]. Precomputed at bake (so the font info +
    // ttf buffer needn't outlive load); usually negative (pull pairs like "AV"
    // together). From stb_truetype's legacy 'kern' table -- fonts that kern only via
    // GPOS yield zeros (a text-shaping engine is out of scope).
    private final float[] kern;

    private Font(Texture atlas, boolean msdf, float bakeHeight, float ascent, float descent,
                 float lineGap, Glyph[] glyphs, float[] kern) {
        this.atlas = atlas;
        this.msdf = msdf;
        this.bakeHeight = bakeHeight;
        this.ascent = ascent;
        this.descent = descent;
        this.lineGap = lineGap;
        this.glyphs = glyphs;
        this.kern = kern;
    }

    /**
     * Load a TTF from the classpath and bake its single-channel SDF atlas at
     * {@code pixelHeight}. The atlas height is one bake size for every render size
     * (SDF scales for free), so a moderate height (~48px) balances sharpness
     * against atlas memory.
     */
    static Font load(Device device, long commandPool, String resourcePath, float pixelHeight) {
        return bake(device, commandPool, resourcePath, pixelHeight, false);
    }

    /**
     * Load a TTF and bake its MULTI-channel (MSDF) atlas -- the opt-in crisp-corner
     * path. Same scale-free sampling as {@link #load}, but each glyph carries three
     * distance fields (via {@code lwjgl-msdfgen}, fed stb_truetype outlines) whose
     * median reconstructs sharp corners single-channel SDF rounds off. Costs ~4x
     * the atlas memory (RGBA8 vs R8) + a slower bake; worth it for large display
     * text. Rendered by shape2d.frag mode 6.
     */
    static Font loadMsdf(Device device, long commandPool, String resourcePath, float pixelHeight) {
        return bake(device, commandPool, resourcePath, pixelHeight, true);
    }

    /**
     * The shared bake skeleton for both glyph techniques. Everything is identical --
     * metrics, the shelf packer, UV normalization, the kern table -- EXCEPT the
     * per-glyph bitmap step and the atlas format, which branch on {@code msdf}:
     * single-channel SDF from stb ({@code stbtt_GetCodepointSDF}, R8) vs multi-
     * channel MSDF from msdfgen (stb outline -> msdfgen Shape, RGBA8).
     */
    private static Font bake(Device device, long commandPool, String resourcePath,
                             float pixelHeight, boolean msdf) {
        ByteBuffer ttf = readResource(resourcePath);   // native buffer -- memFree at the end
        try (MemoryStack stack = stackPush()) {
            STBTTFontinfo info = STBTTFontinfo.malloc(stack);
            if (!stbtt_InitFont(info, ttf)) {
                throw new RuntimeException("stb_truetype could not parse the font: " + resourcePath);
            }

            // scale: font units -> pixels at the requested height.
            float scale = stbtt_ScaleForPixelHeight(info, pixelHeight);
            IntBuffer a = stack.mallocInt(1), d = stack.mallocInt(1), g = stack.mallocInt(1);
            stbtt_GetFontVMetrics(info, a, d, g);
            float ascent = a.get(0) * scale;
            float descent = d.get(0) * scale;
            float lineGap = g.get(0) * scale;

            // The atlas, zero-filled (0 = "fully outside the glyph", so the
            // background reads as empty). Channels: 1 (R8 SDF) or 4 (RGBA8 MSDF).
            int channels = msdf ? 4 : 1;
            byte[] atlasPixels = new byte[ATLAS_WIDTH * ATLAS_MAX_HEIGHT * channels];
            Glyph[] glyphs = new Glyph[LAST_CHAR - FIRST_CHAR + 1];

            IntBuffer pW = stack.mallocInt(1), pH = stack.mallocInt(1);
            IntBuffer pXoff = stack.mallocInt(1), pYoff = stack.mallocInt(1);
            IntBuffer pAdv = stack.mallocInt(1), pLsb = stack.mallocInt(1);

            int penX = 0, penY = 0, rowHeight = 0;
            for (int cp = FIRST_CHAR; cp <= LAST_CHAR; cp++) {
                Glyph glyph = new Glyph();
                glyphs[cp - FIRST_CHAR] = glyph;

                stbtt_GetCodepointHMetrics(info, cp, pAdv, pLsb);
                glyph.advance = pAdv.get(0) * scale;

                // The glyph's bitmap, as channel-interleaved bytes (1 or 4 per
                // texel), plus its size + placement. NULL for whitespace/empty
                // glyphs (space): those carry only an advance, no quad.
                GlyphBitmap bm = msdf
                        ? generateMsdfGlyph(info, cp, scale)
                        : generateSdfGlyph(info, cp, scale, pW, pH, pXoff, pYoff);
                if (bm == null) {
                    continue;
                }
                int gw = bm.w, gh = bm.h;

                // Shelf packing: lay glyphs left-to-right; when a row fills, drop to
                // a new row below the tallest glyph so far.
                if (penX + gw > ATLAS_WIDTH) {
                    penX = 0;
                    penY += rowHeight + GLYPH_GAP;
                    rowHeight = 0;
                }
                if (penY + gh > ATLAS_MAX_HEIGHT) {
                    throw new RuntimeException("Glyph atlas overflow baking " + resourcePath
                            + " at " + pixelHeight + "px (raise ATLAS_MAX_HEIGHT)");
                }

                // Blit the glyph's rows into the atlas at (penX, penY), copying all
                // `channels` bytes per texel.
                for (int row = 0; row < gh; row++) {
                    int dstRow = (penY + row) * ATLAS_WIDTH + penX;
                    int srcRow = row * gw;
                    for (int col = 0; col < gw; col++) {
                        for (int ch = 0; ch < channels; ch++) {
                            atlasPixels[(dstRow + col) * channels + ch] =
                                    bm.pixels[(srcRow + col) * channels + ch];
                        }
                    }
                }

                glyph.xoff = bm.xoff;
                glyph.yoff = bm.yoff;
                glyph.w = gw;
                glyph.h = gh;
                // UVs filled once the final atlas height is known (below) -- store
                // pixel coords for now in the uv slots, convert after the loop.
                glyph.u0 = penX;
                glyph.v0 = penY;
                glyph.u1 = penX + gw;
                glyph.v1 = penY + gh;

                penX += gw + GLYPH_GAP;
                rowHeight = Math.max(rowHeight, gh);
            }

            int usedHeight = penY + rowHeight;   // trim the atlas to the rows we used
            // Convert the stashed pixel rects to normalized UVs against the FINAL size.
            for (Glyph glyph : glyphs) {
                if (glyph.w > 0f) {
                    glyph.u0 /= ATLAS_WIDTH;
                    glyph.u1 /= ATLAS_WIDTH;
                    glyph.v0 /= usedHeight;
                    glyph.v1 /= usedHeight;
                }
            }

            byte[] trimmed = new byte[ATLAS_WIDTH * usedHeight * channels];
            System.arraycopy(atlasPixels, 0, trimmed, 0, trimmed.length);
            Texture atlas = msdf
                    ? Texture.createMsdfAtlas(device, commandPool, trimmed, ATLAS_WIDTH, usedHeight)
                    : Texture.createSdfAtlas(device, commandPool, trimmed, ATLAS_WIDTH, usedHeight);

            // Precompute the kern table (font info is alive here; the ttf is freed
            // below). stb reads the legacy 'kern' table -> font units, scaled to
            // bake-px like glyph.advance. Count non-zero pairs for diagnostics.
            float[] kern = new float[RANGE * RANGE];
            int kernPairs = 0;
            for (int c1 = FIRST_CHAR; c1 <= LAST_CHAR; c1++) {
                for (int c2 = FIRST_CHAR; c2 <= LAST_CHAR; c2++) {
                    int k = stbtt_GetCodepointKernAdvance(info, c1, c2);
                    if (k != 0) {
                        kern[(c1 - FIRST_CHAR) * RANGE + (c2 - FIRST_CHAR)] = k * scale;
                        kernPairs++;
                    }
                }
            }

            System.out.println("Font baked: " + resourcePath + " @ " + (int) pixelHeight
                    + "px -> " + ATLAS_WIDTH + "x" + usedHeight + (msdf ? " MSDF" : " SDF")
                    + " atlas (" + RANGE + " glyphs, " + kernPairs + " kerning pairs).");
            return new Font(atlas, msdf, pixelHeight, ascent, descent, lineGap, glyphs, kern);
        } finally {
            memFree(ttf);
        }
    }

    /** Carrier for one glyph's baked bitmap + placement, returned by the per-glyph
     *  generators. {@code pixels} is channel-interleaved (1 byte/texel SDF, 4 MSDF),
     *  {@code w}x{@code h} texels; {@code xoff}/{@code yoff} place the quad relative
     *  to the pen origin (px, y-down; yoff &lt; 0 = above the baseline). */
    private record GlyphBitmap(byte[] pixels, int w, int h, float xoff, float yoff) {}

    /** Single-channel SDF glyph via stb. Returns null for whitespace (no outline). */
    private static GlyphBitmap generateSdfGlyph(STBTTFontinfo info, int cp, float scale,
                                                IntBuffer pW, IntBuffer pH,
                                                IntBuffer pXoff, IntBuffer pYoff) {
        ByteBuffer sdf = stbtt_GetCodepointSDF(info, scale, cp, PADDING, ONEDGE,
                PIXEL_DIST_SCALE, pW, pH, pXoff, pYoff);
        if (sdf == null) {
            return null;
        }
        int gw = pW.get(0), gh = pH.get(0);
        // ABSOLUTE gets (not a bulk get): a bulk get(px) would advance the buffer's
        // position to the end, and stbtt_FreeSDF frees memAddress(sdf) -- computed
        // at the CURRENT position, not the base -- so a moved position frees the
        // wrong pointer (heap corruption). Absolute reads leave the position at 0.
        byte[] px = new byte[gw * gh];
        for (int i = 0; i < px.length; i++) {
            px[i] = sdf.get(i);
        }
        stbtt_FreeSDF(sdf, 0L);
        return new GlyphBitmap(px, gw, gh, pXoff.get(0), pYoff.get(0));
    }

    /**
     * Multi-channel MSDF glyph via msdfgen, fed a stb_truetype outline. Mirrors the
     * validated MsdfProbe path: stb outline -> msdfgen Shape (contours of
     * linear/quadratic/cubic segments) -> normalize + orient + edge-colour ->
     * {@code msdf_generate_msdf} -> a float RGB bitmap, quantized to RGBA8 (A=255).
     * Returns null for whitespace (no box). Placement: the projection maps the
     * glyph box corner (x0,y0) to {@code MSDF_PADDING} px in, so
     * xoff = x0*scale - pad and yoff = -y1*scale - pad (quad top above baseline).
     */
    private static GlyphBitmap generateMsdfGlyph(STBTTFontinfo info, int cp, float scale) {
        try (MemoryStack stack = stackPush()) {
            IntBuffer x0 = stack.mallocInt(1), y0 = stack.mallocInt(1),
                      x1 = stack.mallocInt(1), y1 = stack.mallocInt(1);
            if (!stbtt_GetCodepointBox(info, cp, x0, y0, x1, y1)) {
                return null;   // whitespace / empty glyph: advance only, no quad
            }
            int gw = (int) Math.ceil((x1.get(0) - x0.get(0)) * scale) + 2 * MSDF_PADDING;
            int gh = (int) Math.ceil((y1.get(0) - y0.get(0)) * scale) + 2 * MSDF_PADDING;

            PointerBuffer p = stack.mallocPointer(1);
            checkMsdf(msdf_shape_alloc(p), "shape_alloc");
            long shape = p.get(0);
            try {
                buildShape(info, cp, shape, stack);
                checkMsdf(msdf_shape_normalize(shape), "shape_normalize");
                msdf_shape_orient_contours(shape);   // robust sign regardless of winding
                checkMsdf(msdf_shape_edge_colors_simple(shape, EDGE_COLOR_ANGLE), "edge_colors");

                MSDFGenBitmap bmp = MSDFGenBitmap.malloc(stack);
                checkMsdf(msdf_bitmap_alloc(MSDF_BITMAP_TYPE_MSDF, gw, gh, bmp), "bitmap_alloc");
                try {
                    MSDFGenTransform tf = MSDFGenTransform.calloc(stack);
                    tf.scale(MSDFGenVector2.malloc(stack).set(scale, scale));
                    tf.translation(MSDFGenVector2.malloc(stack)
                            .set(MSDF_PADDING / scale - x0.get(0), MSDF_PADDING / scale - y0.get(0)));
                    double halfRange = (PX_RANGE / scale) / 2.0;
                    tf.distance_mapping(r -> r.set(-halfRange, halfRange));
                    checkMsdf(msdf_generate_msdf(bmp, shape, tf), "generate_msdf");

                    IntBuffer pCh = stack.mallocInt(1);
                    checkMsdf(msdf_bitmap_get_channel_count(bmp, pCh), "channel_count");
                    int ch = pCh.get(0);
                    PointerBuffer pPixels = stack.mallocPointer(1);
                    checkMsdf(msdf_bitmap_get_pixels(bmp, pPixels), "get_pixels");
                    FloatBuffer src = MemoryUtil.memFloatBuffer(pPixels.get(0), gw * gh * ch);

                    // Quantize float RGB -> RGBA8 (A = 255 opaque, unused by mode 6),
                    // FLIPPING vertically: msdfgen's bitmap is y-UP (row 0 = bottom),
                    // but the atlas + glyph metrics (and the SDF path) are y-DOWN
                    // (row 0 = top). Write source row r to destination row gh-1-r.
                    byte[] rgba = new byte[gw * gh * 4];
                    for (int row = 0; row < gh; row++) {
                        int dst = (gh - 1 - row) * gw;
                        int srcR = row * gw;
                        for (int col = 0; col < gw; col++) {
                            int s = (srcR + col) * ch;
                            int d = (dst + col) * 4;
                            rgba[d]     = toByte(src.get(s));
                            rgba[d + 1] = toByte(src.get(s + 1));
                            rgba[d + 2] = toByte(src.get(s + 2));
                            rgba[d + 3] = (byte) 255;
                        }
                    }
                    float xoff = x0.get(0) * scale - MSDF_PADDING;
                    float yoff = -y1.get(0) * scale - MSDF_PADDING;
                    return new GlyphBitmap(rgba, gw, gh, xoff, yoff);
                } finally {
                    msdf_bitmap_free(bmp);
                }
            } finally {
                msdf_shape_free(shape);
            }
        }
    }

    /** Walk the stb outline and add one msdfgen contour per move, one segment per
     *  line/curve. stb coords are font units (y-up); the transform scales them. */
    private static void buildShape(STBTTFontinfo info, int cp, long shape, MemoryStack stack) {
        STBTTVertex.Buffer verts = stbtt_GetCodepointShape(info, cp);
        if (verts == null) {
            return;
        }
        try {
            PointerBuffer p = stack.mallocPointer(1);
            long contour = 0;
            double penX = 0, penY = 0;
            for (int i = 0; i < verts.remaining(); i++) {
                STBTTVertex v = verts.get(i);
                double vx = v.x(), vy = v.y();
                switch (v.type()) {
                    case STBTT_vmove -> {
                        checkMsdf(msdf_shape_add_contour(shape, p), "add_contour");
                        contour = p.get(0);
                    }
                    case STBTT_vline -> addSegment(contour, MSDF_SEGMENT_TYPE_LINEAR, stack,
                            penX, penY, 0, 0, 0, 0, vx, vy);
                    case STBTT_vcurve -> addSegment(contour, MSDF_SEGMENT_TYPE_QUADRATIC, stack,
                            penX, penY, v.cx(), v.cy(), 0, 0, vx, vy);
                    case STBTT_vcubic -> addSegment(contour, MSDF_SEGMENT_TYPE_CUBIC, stack,
                            penX, penY, v.cx(), v.cy(), v.cx1(), v.cy1(), vx, vy);
                    default -> throw new IllegalStateException("bad stb vertex type " + v.type());
                }
                penX = vx; penY = vy;
            }
        } finally {
            stbtt_FreeShape(info, verts);
        }
    }

    /** Alloc a segment of {@code type}, set its 2/3/4 control points, append to
     *  {@code contour} (which takes ownership; freed with the shape). */
    private static void addSegment(long contour, int type, MemoryStack stack,
                                   double sx, double sy, double c0x, double c0y,
                                   double c1x, double c1y, double ex, double ey) {
        PointerBuffer p = stack.mallocPointer(1);
        checkMsdf(msdf_segment_alloc(type, p), "segment_alloc");
        long seg = p.get(0);
        setPoint(seg, 0, sx, sy, stack);
        if (type == MSDF_SEGMENT_TYPE_LINEAR) {
            setPoint(seg, 1, ex, ey, stack);
        } else if (type == MSDF_SEGMENT_TYPE_QUADRATIC) {
            setPoint(seg, 1, c0x, c0y, stack);
            setPoint(seg, 2, ex, ey, stack);
        } else {   // cubic
            setPoint(seg, 1, c0x, c0y, stack);
            setPoint(seg, 2, c1x, c1y, stack);
            setPoint(seg, 3, ex, ey, stack);
        }
        checkMsdf(msdf_contour_add_edge(contour, seg), "contour_add_edge");
    }

    private static void setPoint(long seg, int index, double x, double y, MemoryStack stack) {
        checkMsdf(msdf_segment_set_point(seg, index, MSDFGenVector2.malloc(stack).set(x, y)),
                "segment_set_point");
    }

    private static byte toByte(float v) {
        return (byte) Math.max(0, Math.min(255, Math.round(v * 255f)));
    }

    private static void checkMsdf(int status, String what) {
        if (status != MSDF_SUCCESS) {
            throw new RuntimeException("msdfgen " + what + " failed: status " + status);
        }
    }

    // ------------------------------------------------------------------
    // What Renderer2D.text() reads to lay out + draw a string
    // ------------------------------------------------------------------

    /** The glyph atlas texture (R8 for SDF, RGBA8 for MSDF; both LINEAR) -- bound as
     *  the shape pipeline's texture. */
    Texture atlas() {
        return atlas;
    }

    /** True if this font baked a multi-channel (MSDF) atlas -- so {@link
     *  Renderer2D#text} emits the MSDF text mode (6) instead of the SDF mode (3). */
    boolean isMsdf() {
        return msdf;
    }

    /** The MSDF distance-field range in atlas pixels (the bake constant the shader's
     *  reconstruction must match). Only meaningful when {@link #isMsdf()}. */
    float pxRange() {
        return PX_RANGE;
    }

    /** Bake size -> render size factor, so one atlas serves every text size. */
    float scaleFor(float size) {
        return size / bakeHeight;
    }

    /** Baseline offset from the top of the text, in px at bake size. */
    float ascent() {
        return ascent;
    }

    /** The default text size (the bake height) -- a crisp 1:1 render. */
    float naturalSize() {
        return bakeHeight;
    }

    /** Baseline-to-baseline distance, in px at bake size (ascent - descent + gap;
     *  descent is negative, so this is the full line height). */
    float lineHeight() {
        return ascent - descent + lineGap;
    }

    /** Kerning adjustment between {@code left} and {@code right}, in px @ bake size
     *  (scale it by {@link #scaleFor} like an advance). 0 if either is outside the
     *  baked range or the font carries no kern pair for them. */
    float kerning(char left, char right) {
        if (left < FIRST_CHAR || left > LAST_CHAR || right < FIRST_CHAR || right > LAST_CHAR) {
            return 0f;
        }
        return kern[(left - FIRST_CHAR) * RANGE + (right - FIRST_CHAR)];
    }

    /** Glyph for {@code c}, or null if outside the baked range (caller skips it). */
    Glyph glyph(char c) {
        if (c < FIRST_CHAR || c > LAST_CHAR) {
            return null;
        }
        return glyphs[c - FIRST_CHAR];
    }

    /**
     * Free the atlas texture. Public because a font from {@link
     * Renderer#loadFont} is CALLER-OWNED -- close it before the Renderer (it frees
     * VMA memory the device owns), exactly like a {@link Texture} from {@code
     * loadImage}. Do NOT close the built-in {@link Renderer#font()} -- that one is
     * renderer-owned (closed by the Renderer).
     */
    public void close() {
        atlas.close();
    }

    private static ByteBuffer readResource(String path) {
        try (InputStream in = Font.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new RuntimeException("Font resource not found on the classpath: " + path);
            }
            byte[] bytes = in.readAllBytes();
            ByteBuffer buf = memAlloc(bytes.length);
            buf.put(bytes).flip();
            return buf;
        } catch (IOException e) {
            throw new RuntimeException("Failed to read font resource: " + path, e);
        }
    }
}
