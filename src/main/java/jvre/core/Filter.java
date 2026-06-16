package jvre.core;

import static org.lwjgl.vulkan.VK10.*;

/**
 * How a {@link Texture} is sampled when it is drawn at a size other than 1:1 --
 * the magnification/minification filter baked into its sampler.
 *
 * <ul>
 *   <li><b>NEAREST</b> -- snap to the single closest texel. Crisp, hard pixel
 *       edges with no blending: what pixel art and exact hand-authored textures
 *       (e.g. a sharp checker) want. Upscaling shows blocky texels (by design).</li>
 *   <li><b>LINEAR</b> -- blend the four nearest texels (bilinear). Smooth
 *       upscaling: the right default for decoded photographic / illustrated
 *       images.</li>
 * </ul>
 *
 * It is a sampler property (fixed when the texture is created), not a per-draw
 * option. The defaults follow intent: {@link Renderer#createImage} (you built the
 * pixels) defaults to NEAREST; {@link Renderer#loadImage} (a decoded asset)
 * defaults to LINEAR. Pass an explicit value to override.
 *
 * <p>(Mipmapping + anisotropy -- which improve <i>minification</i> quality -- are
 * a later refinement; this knob covers the magnification case.)
 */
public enum Filter {
    NEAREST(VK_FILTER_NEAREST),
    LINEAR(VK_FILTER_LINEAR);

    /** The underlying VkFilter -- package-private (never leaks a raw Vulkan enum). */
    final int vk;

    Filter(int vk) {
        this.vk = vk;
    }
}
