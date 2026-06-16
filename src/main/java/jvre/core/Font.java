package jvre.core;

import org.lwjgl.stb.STBTTFontinfo;
import org.lwjgl.system.MemoryStack;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.stb.STBTruetype.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memAlloc;
import static org.lwjgl.system.MemoryUtil.memFree;

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

    private final Texture atlas;
    private final float bakeHeight;   // the pixel height glyphs were baked at
    private final float ascent;       // baseline offset from the top, px @ bake size
    private final float descent;      // px @ bake size (negative)
    private final float lineGap;      // px @ bake size
    private final Glyph[] glyphs;     // indexed by codepoint - FIRST_CHAR

    private Font(Texture atlas, float bakeHeight, float ascent, float descent,
                 float lineGap, Glyph[] glyphs) {
        this.atlas = atlas;
        this.bakeHeight = bakeHeight;
        this.ascent = ascent;
        this.descent = descent;
        this.lineGap = lineGap;
        this.glyphs = glyphs;
    }

    /**
     * Load a TTF from the classpath and bake its SDF atlas at {@code pixelHeight}.
     * The atlas height is one bake size for every render size (SDF scales for
     * free), so a moderate height (~48px) balances sharpness against atlas memory.
     */
    static Font load(Device device, long commandPool, String resourcePath, float pixelHeight) {
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

            // The atlas, zero-filled (0 = "fully outside the glyph" in SDF terms,
            // so the background reads as empty). Packed with a simple shelf packer.
            byte[] atlasPixels = new byte[ATLAS_WIDTH * ATLAS_MAX_HEIGHT];
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

                // The glyph's SDF bitmap. NULL for whitespace/empty glyphs (space):
                // those carry only an advance, no quad.
                ByteBuffer sdf = stbtt_GetCodepointSDF(info, scale, cp, PADDING, ONEDGE,
                        PIXEL_DIST_SCALE, pW, pH, pXoff, pYoff);
                if (sdf == null) {
                    continue;
                }
                int gw = pW.get(0), gh = pH.get(0);

                // Shelf packing: lay glyphs left-to-right; when a row fills, drop to
                // a new row below the tallest glyph so far.
                if (penX + gw > ATLAS_WIDTH) {
                    penX = 0;
                    penY += rowHeight + GLYPH_GAP;
                    rowHeight = 0;
                }
                if (penY + gh > ATLAS_MAX_HEIGHT) {
                    stbtt_FreeSDF(sdf, 0L);
                    throw new RuntimeException("Glyph atlas overflow baking " + resourcePath
                            + " at " + pixelHeight + "px (raise ATLAS_MAX_HEIGHT)");
                }

                // Blit the glyph's SDF rows into the atlas at (penX, penY).
                for (int row = 0; row < gh; row++) {
                    for (int col = 0; col < gw; col++) {
                        atlasPixels[(penY + row) * ATLAS_WIDTH + (penX + col)] = sdf.get(row * gw + col);
                    }
                }

                glyph.xoff = pXoff.get(0);
                glyph.yoff = pYoff.get(0);
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

                stbtt_FreeSDF(sdf, 0L);
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

            byte[] trimmed = new byte[ATLAS_WIDTH * usedHeight];
            System.arraycopy(atlasPixels, 0, trimmed, 0, trimmed.length);
            Texture atlas = Texture.createSdfAtlas(device, commandPool, trimmed, ATLAS_WIDTH, usedHeight);

            System.out.println("Font baked: " + resourcePath + " @ " + (int) pixelHeight
                    + "px -> " + ATLAS_WIDTH + "x" + usedHeight + " SDF atlas ("
                    + (LAST_CHAR - FIRST_CHAR + 1) + " glyphs).");
            return new Font(atlas, pixelHeight, ascent, descent, lineGap, glyphs);
        } finally {
            memFree(ttf);
        }
    }

    // ------------------------------------------------------------------
    // What Renderer2D.text() reads to lay out + draw a string
    // ------------------------------------------------------------------

    /** The SDF atlas texture (R8, LINEAR) -- bound as the shape pipeline's texture. */
    Texture atlas() {
        return atlas;
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

    /** Glyph for {@code c}, or null if outside the baked range (caller skips it). */
    Glyph glyph(char c) {
        if (c < FIRST_CHAR || c > LAST_CHAR) {
            return null;
        }
        return glyphs[c - FIRST_CHAR];
    }

    /** Free the atlas texture. Package-private: the default font is renderer-owned
     *  (closed by the Renderer); a public custom-font API will come later. */
    void close() {
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
