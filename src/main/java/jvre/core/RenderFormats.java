package jvre.core;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkFormatProperties;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

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

    /**
     * The headless path's formats (no swapchain to negotiate against): a chosen
     * RGBA8 sRGB color format (so readback needs no BGRA swizzle), a queried depth
     * format, and single-sample (no MSAA in headless v1 -- offscreen regression
     * rendering doesn't need it; MSAA-headless is additive). The color format is
     * just an offscreen target's format, not a surface's -- any RGBA8 works.
     */
    static RenderFormats headless(Device device) {
        return new RenderFormats(VK_FORMAT_R8G8B8A8_SRGB, findDepthFormat(device),
                VK_SAMPLE_COUNT_1_BIT);
    }

    /** A depth format the device supports as a DEPTH_STENCIL_ATTACHMENT in optimal
     *  tiling -- D32_SFLOAT (universal on desktop), else the stencil-bearing
     *  fallbacks. The headless mirror of the swapchain's depth-format query. */
    private static int findDepthFormat(Device device) {
        int[] candidates = {
                VK_FORMAT_D32_SFLOAT,
                VK_FORMAT_D32_SFLOAT_S8_UINT,
                VK_FORMAT_D24_UNORM_S8_UINT,
        };
        try (MemoryStack stack = stackPush()) {
            VkFormatProperties props = VkFormatProperties.malloc(stack);
            for (int fmt : candidates) {
                vkGetPhysicalDeviceFormatProperties(device.physicalDevice(), fmt, props);
                if ((props.optimalTilingFeatures()
                        & VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT) != 0) {
                    return fmt;
                }
            }
        }
        throw new RuntimeException("No supported depth-stencil attachment format found");
    }
}
