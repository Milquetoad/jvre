package jvre.core;

/**
 * The attachment formats + sample count that PIPELINES and RENDER TARGETS bake:
 * the color format, the depth format, and the MSAA sample count. A pipeline's
 * {@link Pipeline} bake and a {@link RenderTarget}'s images all need to agree on
 * these, and they must match whatever a {@code vkCmdBeginRendering} pass provides.
 *
 * <p>This is the keystone SEAM. Until now those three values were read straight
 * from the {@link Swapchain} at every bake site, hard-wiring "what we render into"
 * to "the window's swapchain." Routing them through this one value instead lets:
 * <ul>
 *   <li>a <b>headless</b> renderer (no swapchain) supply chosen defaults, and</li>
 *   <li>a <b>float/HDR</b> target override the color format,</li>
 * </ul>
 * without touching the bake call sites. Today the renderer still sources it from
 * the swapchain ({@link #fromSwapchain}) -- behavior is unchanged; the seam is the
 * point.
 *
 * <p>Internal: jvre injects these (the "guarded" L1 promise -- a user never passes
 * a raw Vulkan format).
 */
record RenderFormats(int colorFormat, int depthFormat, int sampleCount) {

    /** The window path's formats: the swapchain's negotiated color format, its
     *  chosen depth format, and its (device-clamped) MSAA sample count. */
    static RenderFormats fromSwapchain(Swapchain swapchain) {
        return new RenderFormats(swapchain.imageFormat(), swapchain.depthFormat(),
                swapchain.sampleCount());
    }
}
