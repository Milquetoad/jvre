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

    private TextureOptions(Builder b) {
        this.filter = b.filter;
        this.wrap = b.wrap;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Filter filter = Filter.LINEAR;
        private WrapMode wrap = WrapMode.CLAMP;

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

        public TextureOptions build() {
            return new TextureOptions(this);
        }
    }
}
