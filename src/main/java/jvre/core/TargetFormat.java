package jvre.core;

import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT;

/**
 * The colour format of a {@link RenderTarget} -- LDR (matching the screen) or a
 * floating-point HDR format that stores values OUTSIDE [0,1].
 *
 * <ul>
 *   <li><b>DEFAULT</b> -- match the renderer's colour format (the swapchain's, or
 *       the headless default). 8-bit, sRGB, clamped to [0,1]: what every built-in
 *       pipeline already bakes, so a DEFAULT target needs no special handling.</li>
 *   <li><b>HDR</b> -- 16-bit float per channel ({@code R16G16B16A16_SFLOAT}). A
 *       shader can write values &gt; 1 (bright highlights, accumulation) and read
 *       them back undimmed -- the intermediate buffer for tone-mapping / bloom /
 *       any post-processing that needs range beyond 0..1.</li>
 * </ul>
 *
 * <p>Because pipelines BAKE their colour format, geometry rendered INTO an HDR
 * target must use a pipeline baked for it -- {@link Renderer#createPipeline(PipelineSpec,
 * RenderTarget)}. (The built-in L2/effect pipelines are baked for the screen's
 * DEFAULT format.)
 */
public enum TargetFormat {
    /** Match the renderer's colour format (8-bit LDR). */
    DEFAULT(0),
    /** 16-bit float per channel -- stores HDR values outside [0,1]. */
    HDR(VK_FORMAT_R16G16B16A16_SFLOAT);

    /** The VkFormat, or 0 for {@link #DEFAULT} (resolved to the renderer's format). */
    final int vk;

    TargetFormat(int vk) {
        this.vk = vk;
    }
}
