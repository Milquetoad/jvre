package jvre.core;

/**
 * An immutable RGBA color -- the one value type an L2 ("just draw") user handles.
 * Authored in the numbers people actually think in (0-255 channels, or a hex
 * literal); stored as LINEAR floats in [0,1].
 *
 * Why linear storage is a CORRECTNESS step, not cosmetics: jvre's swapchain is an
 * sRGB format ({@code B8G8R8A8_SRGB}), so the hardware sRGB-ENCODES whatever the
 * shader writes. A user's "128 gray" is an sRGB value; to make 128 actually
 * appear as 128, we sRGB-DECODE it to linear here, hand the shader linear, and
 * the framebuffer re-encodes on write -- a correct round trip. (Feed the
 * framebuffer the raw 0.5 instead and it double-encodes, and everything comes out
 * too bright.) Alpha is linear by convention -- it is NOT gamma-encoded, so it is
 * passed straight through.
 *
 * This is the "linear-vs-sRGB handled internally" promise from the
 * {@code L2 Feature Set - Renderer2D} spec: the user never thinks about it.
 */
public final class Color {

    /** Linear, premultiply-free components in [0,1]. */
    public final float r;
    public final float g;
    public final float b;
    public final float a;

    private Color(float r, float g, float b, float a) {
        this.r = r;
        this.g = g;
        this.b = b;
        this.a = a;
    }

    /** Opaque color from 0-255 sRGB channels. */
    public static Color rgb(int r, int g, int b) {
        return rgba(r, g, b, 255);
    }

    /** Color from 0-255 sRGB channels plus a 0-255 (linear) alpha. */
    public static Color rgba(int r, int g, int b, int a) {
        return new Color(srgbToLinear(r), srgbToLinear(g), srgbToLinear(b), clamp01(a / 255f));
    }

    /** Color from a 0xRRGGBB hex literal (opaque). */
    public static Color hex(int rgb) {
        return rgb((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
    }

    // A small set of constants -- the names a beginner reaches for first.
    public static final Color WHITE       = rgb(255, 255, 255);
    public static final Color BLACK       = rgb(0, 0, 0);
    public static final Color RED         = rgb(255, 0, 0);
    public static final Color GREEN       = rgb(0, 255, 0);
    public static final Color BLUE        = rgb(0, 0, 255);
    public static final Color YELLOW      = rgb(255, 255, 0);
    public static final Color CYAN        = rgb(0, 255, 255);
    public static final Color MAGENTA     = rgb(255, 0, 255);
    public static final Color TRANSPARENT = rgba(0, 0, 0, 0);

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    /**
     * The standard sRGB electro-optical transfer function (the same curve the
     * sRGB framebuffer uses in reverse on write): a small linear toe near black,
     * a 2.4-power gamma above it.
     */
    private static float srgbToLinear(int channel255) {
        float c = clamp01(channel255 / 255f);
        return c <= 0.04045f ? c / 12.92f
                : (float) Math.pow((c + 0.055f) / 1.055f, 2.4);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    /**
     * The four linear components, in the order the shape vertex stream wants
     * them. Package-private: this is plumbing for {@link Renderer2D}, not public
     * API (the user thinks in colors, not float arrays).
     */
    float[] linearRGBA() {
        return new float[] { r, g, b, a };
    }
}
