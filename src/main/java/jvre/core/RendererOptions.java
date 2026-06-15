package jvre.core;

/**
 * Creation-time configuration for the {@link Renderer} -- the capability knobs
 * gathered into one immutable value (so the constructor doesn't grow a long
 * positional parameter list). Everything here is fixed at construction: present
 * mode, MSAA, and GPU choice are all baked into the swapchain/pipelines/device,
 * not runtime-toggleable (see the L2 spec's "AA is a creation-time option" note).
 *
 * <pre>{@code
 * Renderer r = new Renderer(instance, surface, window, RendererOptions.builder()
 *     .clearColor(0.05f, 0.05f, 0.08f)
 *     .vsync(true)
 *     .build());
 * }</pre>
 */
public final class RendererOptions {

    final float clearR;
    final float clearG;
    final float clearB;
    final boolean vsync;
    final int msaa;   // requested sample count: 1 (off), 2, 4, 8 ... (clamped to device max)
    final String preferGpu;   // case-insensitive name substring; null = auto-score

    private RendererOptions(Builder b) {
        this.clearR = b.clearR;
        this.clearG = b.clearG;
        this.clearB = b.clearB;
        this.vsync = b.vsync;
        this.msaa = b.msaa;
        this.preferGpu = b.preferGpu;
    }

    /** Sensible defaults: black clear, vsync on. */
    public static RendererOptions defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private float clearR = 0f;
        private float clearG = 0f;
        private float clearB = 0f;
        private boolean vsync = true;
        private int msaa = 4;
        private String preferGpu = null;

        /** The per-frame clear color (RGB in [0,1]). Default black. */
        public Builder clearColor(float r, float g, float b) {
            this.clearR = r;
            this.clearG = g;
            this.clearB = b;
            return this;
        }

        /**
         * Vsync on (default) caps presentation to the display's refresh -- no
         * tearing, no GPU spinning at thousands of fps (present mode FIFO). Off
         * uncaps it for lowest latency / benchmarking (MAILBOX where available,
         * else FIFO).
         */
        public Builder vsync(boolean on) {
            this.vsync = on;
            return this;
        }

        /**
         * Anti-aliasing sample count: 1 (off), 2, 4 (default), 8, ... -- must be a
         * power of two. Clamped down to the device's max if higher. Baked into the
         * swapchain + every pipeline, so it's creation-time only (never a runtime
         * toggle -- the L2 spec pinned that).
         */
        public Builder msaa(int samples) {
            if (samples < 1 || (samples & (samples - 1)) != 0) {
                throw new IllegalArgumentException("msaa must be a power of two (1, 2, 4, 8, ...): " + samples);
            }
            this.msaa = samples;
            return this;
        }

        /**
         * Override GPU selection: prefer a device whose name CONTAINS this
         * substring (case-insensitive), e.g. "RTX" or "Intel". If no suitable
         * device matches, falls back to the default scoring (discrete &gt;
         * integrated). Null (default) = pure scoring.
         */
        public Builder preferGpu(String nameSubstring) {
            this.preferGpu = nameSubstring;
            return this;
        }

        public RendererOptions build() {
            return new RendererOptions(this);
        }
    }
}
