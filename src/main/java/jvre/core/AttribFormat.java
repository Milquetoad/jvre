package jvre.core;

import static org.lwjgl.vulkan.VK10.*;

/**
 * The vertex-attribute formats a user-defined {@link VertexLayout} can use -- an
 * L2-clean name for the common float/vector shapes, instead of a raw {@code
 * VK_FORMAT_*} constant. (More formats -- ints, normalized bytes -- can be added
 * as needs appear; these cover the float vectors a typical mesh uses.)
 */
public enum AttribFormat {
    FLOAT(VK_FORMAT_R32_SFLOAT, Float.BYTES),
    VEC2(VK_FORMAT_R32G32_SFLOAT, 2 * Float.BYTES),
    VEC3(VK_FORMAT_R32G32B32_SFLOAT, 3 * Float.BYTES),
    VEC4(VK_FORMAT_R32G32B32A32_SFLOAT, 4 * Float.BYTES);

    /** The underlying VkFormat -- package-private so it never leaks to users. */
    final int vk;
    /** Size in bytes (handy for laying out interleaved vertices). */
    public final int bytes;

    AttribFormat(int vk, int bytes) {
        this.vk = vk;
        this.bytes = bytes;
    }
}
