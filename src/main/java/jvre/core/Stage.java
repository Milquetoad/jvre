package jvre.core;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Which shader stage a uniform / push constant is visible to, for a {@link
 * PipelineSpec} -- an L2-clean name instead of a raw {@code VK_SHADER_STAGE_*}
 * bit. (Vertex and fragment cover the graphics escape hatch; more stages can be
 * added if needed.)
 */
public enum Stage {
    VERTEX(VK_SHADER_STAGE_VERTEX_BIT),
    FRAGMENT(VK_SHADER_STAGE_FRAGMENT_BIT);

    /** The underlying VkShaderStageFlags -- package-private. */
    final int vk;

    Stage(int vk) {
        this.vk = vk;
    }
}
