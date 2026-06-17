package jvre.core;

/**
 * Sampler configuration for a {@link Texture}, bundled so the growing set of knobs
 * (filter, wrap, mipmaps, anisotropy) stays one parameter instead of a thicket of
 * overloads. Built with a builder; every field has a sensible default, so you set
 * only what you care about:
 *
 * <pre>{@code
 * Texture t = renderer.loadImage("/tile.png", TextureOptions.builder()
 *         .wrap(WrapMode.REPEAT)     // tile across a large quad
 *         .filter(Filter.LINEAR)
 *         .build());
 * }</pre>
 *
 * The plain {@code createImage(...)} / {@code loadImage(...)} (no options, or a bare
 * {@link Filter}) keep their intent-based defaults; pass {@code TextureOptions} when
 * you need more than the filter.
 */
public final class TextureOptions {

    final Filter filter;
    final WrapMode wrap;
    final boolean mipmaps;
    final boolean anisotropy;

    private TextureOptions(Builder b) {
        this.filter = b.filter;
        this.wrap = b.wrap;
        this.mipmaps = b.mipmaps;
        this.anisotropy = b.anisotropy;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Filter filter = Filter.LINEAR;
        private WrapMode wrap = WrapMode.CLAMP;
        private boolean mipmaps = false;
        private boolean anisotropy = false;

        /** Magnification/minification filter ({@link Filter#NEAREST} / {@link Filter#LINEAR}). */
        public Builder filter(Filter filter) {
            this.filter = filter;
            return this;
        }

        /** Address mode outside [0,1] UVs ({@link WrapMode}; default {@link WrapMode#CLAMP}). */
        public Builder wrap(WrapMode wrap) {
            this.wrap = wrap;
            return this;
        }

        /**
         * Generate a mipmap chain (downscaled copies) and sample it -- smoother,
         * less shimmery MINIFICATION when the texture is drawn smaller than its
         * texel size (distant 3D surfaces, zoomed-out sprites). Off by default;
         * pairs naturally with {@link Filter#LINEAR}.
         */
        public Builder mipmaps(boolean on) {
            this.mipmaps = on;
            return this;
        }

        /**
         * Enable anisotropic filtering -- sharper textures viewed at a grazing
         * ANGLE (receding floors/walls), where plain mip sampling over-blurs. Uses
         * the device's max level (clamped, and a no-op if the device lacks the
         * feature). Needs {@link #mipmaps} to do anything. Off by default.
         */
        public Builder anisotropy(boolean on) {
            this.anisotropy = on;
            return this;
        }

        public TextureOptions build() {
            return new TextureOptions(this);
        }
    }
}
