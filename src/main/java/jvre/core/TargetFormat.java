package jvre.core;

import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32B32A32_SFLOAT;

/**
 * The colour format of a {@link RenderTarget} -- LDR (matching the screen) or a
 * floating-point format that stores values OUTSIDE [0,1].
 *
 * <ul>
 *   <li><b>DEFAULT</b> -- match the renderer's colour format (the swapchain's, or
 *       the headless default). 8-bit, sRGB, clamped to [0,1]: what every built-in
 *       pipeline already bakes, so a DEFAULT target needs no special handling.</li>
 *   <li><b>HDR</b> -- 16-bit float per channel ({@code R16G16B16A16_SFLOAT}). A
 *       shader can write values &gt; 1 (bright highlights, accumulation) and read
 *       them back undimmed -- the intermediate buffer for tone-mapping / bloom /
 *       any post-processing that needs range beyond 0..1. This is the
 *       <i>display-oriented</i> float format: half the bandwidth of 32-bit, plenty
 *       of range + precision for tone-mapping, and the width real HDR swapchains
 *       use. <b>Prefer this</b> unless you specifically need full 32-bit precision.</li>
 *   <li><b>HDR_FLOAT32</b> -- 32-bit float per channel ({@code R32G32B32A32_SFLOAT}).
 *       The <i>data/precision</i> float format. Twice the bandwidth of {@link #HDR},
 *       so it is overkill for display; reach for it when you need exact values:
 *       <b>accumulation</b> buffers (e.g. progressive path tracing summing many
 *       samples), position / normal <b>G-buffers</b>, or any target you
 *       {@code readPixels} back and need the precise numbers from. (Caveat: 32-bit
 *       float is mandatory as a colour attachment + sampled image, but colour
 *       <i>blending</i> into it is not guaranteed across all GPUs -- render opaque,
 *       or check support, if you blend into one.)</li>
 * </ul>
 *
 * <p>Because pipelines BAKE their colour format, geometry rendered INTO a non-default
 * target must use a pipeline baked for it -- {@link Renderer#createPipeline(PipelineSpec,
 * RenderTarget)}. (The built-in L2/effect pipelines are baked for the screen's
 * DEFAULT format.) A float target is not directly {@link Renderer#readPixels
 * readable} (wider texels + a different value range): tone-map it down to an
 * LDR ({@link #DEFAULT}) target first, then read that.
 */
public enum TargetFormat {
    /** Match the renderer's colour format (8-bit LDR). */
    DEFAULT(0),
    /** 16-bit float per channel -- the display-oriented HDR format (the default choice). */
    HDR(VK_FORMAT_R16G16B16A16_SFLOAT),
    /** 32-bit float per channel -- the precision/data format (accumulation, G-buffers, exact readback). */
    HDR_FLOAT32(VK_FORMAT_R32G32B32A32_SFLOAT);

    /** The VkFormat, or 0 for {@link #DEFAULT} (resolved to the renderer's format). */
    final int vk;

    TargetFormat(int vk) {
        this.vk = vk;
    }
}
