package jvre.core;

import static org.lwjgl.vulkan.VK10.*;

/**
 * How a {@link Texture} is addressed OUTSIDE its [0,1] UV range -- the sampler's
 * address mode, baked when the texture is created. It only matters when UVs leave
 * the unit square (tiling a texture across a large quad, scrolling, mirroring).
 *
 * <ul>
 *   <li><b>CLAMP</b> -- repeat the edge texel (the safe sprite default; UVs are
 *       held to [0,1]). What jvre used before this was configurable.</li>
 *   <li><b>REPEAT</b> -- tile: UV 1.5 wraps to 0.5. For terrain / patterns drawn
 *       across a quad bigger than the texture.</li>
 *   <li><b>MIRROR</b> -- tile, flipping every other copy (no visible seam between
 *       tiles).</li>
 *   <li><b>BORDER</b> -- sample a constant border colour (opaque black) outside
 *       [0,1], rather than stretching or tiling.</li>
 * </ul>
 *
 * A sampler property (fixed at creation), like {@link Filter}. Default {@link
 * #CLAMP}.
 */
public enum WrapMode {
    CLAMP(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE),
    REPEAT(VK_SAMPLER_ADDRESS_MODE_REPEAT),
    MIRROR(VK_SAMPLER_ADDRESS_MODE_MIRRORED_REPEAT),
    BORDER(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_BORDER);

    /** The underlying VkSamplerAddressMode -- package-private (no raw Vulkan leak). */
    final int vk;

    WrapMode(int vk) {
        this.vk = vk;
    }
}
