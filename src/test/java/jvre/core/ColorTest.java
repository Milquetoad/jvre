package jvre.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Color is pure value-type logic -- the sRGB->linear conversion that makes a
 * user's 0-255 numbers display correctly on jvre's sRGB swapchain. No GPU.
 */
class ColorTest {

    private static final float EPS = 1e-4f;

    @Test
    void endpointsAreExact() {
        // sRGB 0 and 255 map to linear 0 and 1 exactly (the curve pins both ends).
        Color black = Color.rgb(0, 0, 0);
        assertEquals(0f, black.r, EPS);
        Color white = Color.rgb(255, 255, 255);
        assertEquals(1f, white.r, EPS);
        assertEquals(1f, white.g, EPS);
        assertEquals(1f, white.b, EPS);
    }

    @Test
    void midGrayIsDarkenedByDecode() {
        // sRGB 0.5 (128/255) decodes to ~0.214 linear -- the whole point: passing
        // the raw 0.5 to an sRGB framebuffer would come out too bright.
        Color mid = Color.rgb(128, 128, 128);
        assertEquals(0.2159f, mid.r, 1e-3f);
        assertTrue(mid.r < 128f / 255f, "decoded value must be darker than the raw fraction");
    }

    @Test
    void alphaIsLinearNotGammaEncoded() {
        // Alpha is NOT sRGB -- 128 alpha is just 128/255, no curve applied.
        Color c = Color.rgba(255, 255, 255, 128);
        assertEquals(128f / 255f, c.a, EPS);
        assertEquals(1f, Color.rgb(10, 20, 30).a, EPS, "rgb() is opaque");
        assertEquals(0f, Color.TRANSPARENT.a, EPS);
    }

    @Test
    void hexMatchesChannels() {
        Color viaHex = Color.hex(0xFF8000);
        Color viaRgb = Color.rgb(255, 128, 0);
        assertEquals(viaRgb.r, viaHex.r, EPS);
        assertEquals(viaRgb.g, viaHex.g, EPS);
        assertEquals(viaRgb.b, viaHex.b, EPS);
    }

    @Test
    void outOfRangeChannelsClamp() {
        assertEquals(1f, Color.rgba(300, 0, 0, 999).r, EPS);
        assertEquals(0f, Color.rgba(-50, 0, 0, -1).r, EPS);
        assertEquals(0f, Color.rgba(0, 0, 0, -1).a, EPS);
    }
}
