package jvre.core;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Face-culling mode for a user-defined pipeline ({@link PipelineSpec}). NONE draws
 * every triangle (safe default for 2D / unsure winding); BACK/FRONT drop triangles
 * facing away/toward the camera by their on-screen winding (the perf win for a
 * closed opaque mesh). jvre's front face is counter-clockwise (see the cube's
 * two-mirror winding note).
 */
public enum Cull {
    NONE(VK_CULL_MODE_NONE),
    BACK(VK_CULL_MODE_BACK_BIT),
    FRONT(VK_CULL_MODE_FRONT_BIT);

    /** The underlying VkCullModeFlags -- package-private. */
    final int vk;

    Cull(int vk) {
        this.vk = vk;
    }
}
